package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.GraphNode
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Interpreter
import org.objectweb.asm.tree.analysis.Value

/** 필요한 stack/local 값과 프로젝트 static 반환값을 제한적으로 전파한다. 문자열 원문은 결과나 오류에 싣지 않는다. */
internal object RuntimeValueAnalyzer {
    // hierarchy 완성 전 후보를 넓게 고르고, 실제 모델 적용은 아래의 loader 관계로 다시 확인한다.
    fun requiresValueAnalysis(call: dev.kartograph.core.ExternalCall): Boolean =
        RuntimeLibraryModels.find(call.owner, call.name, call.descriptor,
            call.kind == dev.kartograph.core.InvocationKind.STATIC) { true }?.sensitive == true

    fun enrich(graph: CodeGraph, facts: List<ClassFacts>, hierarchy: ClassHierarchy): Pair<CodeGraph, List<ClassRuntimeObservation>> {
        val typeSupers = graph.nodes.values.filter { it.jvmSignature != null && it.kind in TYPE_KINDS }
            .associate { requireNotNull(it.jvmSignature) to it.supertypes }
        fun loader(owner: String): Boolean {
            val pending = ArrayDeque(listOf(owner))
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val type = pending.removeFirst()
                if (!seen.add(type)) continue
                if (type in CLASS_LOADERS) return true
                pending.addAll(typeSupers[type] ?: hierarchy.directSupertypesOf(type).orEmpty())
            }
            return false
        }
        val membersByOwner = graph.nodes.values.filter { '#' in it.id.value }
            .groupBy { it.id.value.substringAfter(':').substringBefore('#') }
        val returns = facts.flatMap { fact -> fact.returnMethods.map { method ->
            JvmNodeId.methodId(fact.internalName, method.name, method.desc) to (fact.internalName to method)
        } }.toMap()
        val fieldLookup = FieldLookup(facts, hierarchy)
        val fieldWriters = FieldWriteIndex(facts, fieldLookup, ::loader)
        val derived = mutableListOf<GraphEdge>()
        val models = mutableMapOf<Pair<NodeId, Int>, List<NodeId>>()
        val observations = facts.map { fact ->
            var unknownNames = 0
            var unknownLoaders = 0
            var unknownConstructors = 0
            var unknownServices = 0
            var unknownMethods = 0
            var unknownFields = 0
            var missingMembers = 0
            var outsideTargets = 0
            var boundedMethods = 0
            for (method in fact.runtimeMethods) {
                val caller = JvmNodeId.methodId(fact.internalName, method.name, method.desc)
                val fields = FieldValues(fieldWriters, fieldLookup, ::loader, returns)
                val summaries = ReturnValues(returns, ::loader, fields::read) { fields.cycleEpoch }
                val interpreter = FlowInterpreter(::loader, returnedValue = summaries::evaluate, fieldValue = fields::read)
                val frames = if (exceedsFrameBudget(method)) {
                    boundedMethods++
                    null
                } else try {
                    Analyzer(interpreter).analyze(fact.internalName, method)
                } catch (_: AnalyzerException) {
                    // 기본 명령 사실은 유지하되 해석하지 못한 흐름은 계량 한계로 남긴다.
                    boundedMethods++
                    null
                }
                if (frames != null && interpreter.limitReached) boundedMethods++
                var ordinal = 0
                for ((index, instruction) in method.instructions.toArray().withIndex()) {
                    if (instruction is InvokeDynamicInsnNode) { ordinal++; continue }
                    if (instruction !is MethodInsnNode) continue
                    val currentOrdinal = ordinal++
                    val model = RuntimeLibraryModels.find(instruction.owner, instruction.name, instruction.desc,
                        instruction.opcode == Opcodes.INVOKESTATIC, ::loader) ?: continue
                    val classLoading = model.operation == RuntimeOperation.CLASS_LOADING
                    val loaderCall = model.operation == RuntimeOperation.CLASS_LOADER
                    val construction = model.operation in setOf(RuntimeOperation.CLASS_CONSTRUCTION, RuntimeOperation.CONSTRUCTOR_INVOCATION)
                    val serviceLoading = model.operation == RuntimeOperation.SERVICE_LOADING
                    val methodInvocation = model.operation == RuntimeOperation.METHOD_INVOCATION
                    val fieldAccess = model.operation == RuntimeOperation.FIELD_ACCESS
                    if (!classLoading && !loaderCall && !construction && !serviceLoading && !methodInvocation && !fieldAccess) continue
                    val frame = frames?.get(index)
                    val count = Type.getArgumentTypes(instruction.desc).size + if (instruction.opcode == Opcodes.INVOKESTATIC) 0 else 1
                    val arguments = if (frame != null && frame.stackSize >= count) {
                        (frame.stackSize - count until frame.stackSize).map(frame::getStack)
                    } else emptyList()
                    val value = if (arguments.size == count) interpreter.evaluate(instruction, arguments) else null
                    val targets: List<NodeId>?
                    if (serviceLoading) {
                        val position = Type.getArgumentTypes(instruction.desc).indexOfFirst { it.descriptor == "Ljava/lang/Class;" }
                        val services = arguments.getOrNull(position)?.classes?.mapNotNull(::objectClass)?.toSet()
                        targets = services?.flatMap { service -> graph.serviceProviders.filter { it.service == service }.map { it.provider } }
                            ?.filter(graph::contains)?.distinct()?.sorted()
                        if (services == null || targets.isNullOrEmpty() || arguments.any { it.uncertain }) unknownServices++
                    } else if (classLoading || loaderCall) {
                        val classes = value?.classes
                        targets = classes?.mapNotNull { descriptor -> referencedClass(descriptor)?.let(JvmNodeId::classId) }
                            ?.filter(graph::contains)?.distinct()?.sorted()
                        if (classes == null || value.uncertain) {
                            if (loaderCall) unknownLoaders++ else unknownNames++
                        } else if (targets.isNullOrEmpty()) outsideTargets++
                    } else if (methodInvocation || fieldAccess) {
                        val members = if (methodInvocation) arguments.firstOrNull()?.methods else arguments.firstOrNull()?.fields
                        targets = members?.flatMap { member ->
                            if (fieldAccess) return@flatMap fieldLookup.reflective(member).map { it.id }
                            val start = objectClass(member.descriptor) ?: return@flatMap emptyList()
                            val owners = linkedSetOf<String>()
                            val pending = ArrayDeque(listOf(start))
                            while (pending.isNotEmpty()) {
                                val owner = pending.removeFirst()
                                if (!owners.add(owner)) continue
                                if (!member.declaredOnly) pending.addAll(typeSupers[owner] ?: hierarchy.directSupertypesOf(owner).orEmpty())
                            }
                            owners.flatMap { membersByOwner[it].orEmpty() }.filter { node ->
                                val prefix = if (methodInvocation) "method:" else "field:"
                                val owner = node.id.value.removePrefix(prefix).substringBefore('#')
                                node.id.value.startsWith(prefix) && owner in owners &&
                                    (!methodInvocation || (node.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION) && !member.name.startsWith("<"))) &&
                                    node.id.value.substringAfter('#').substringBefore('(').substringBefore(':') == member.name &&
                                    (member.declaredOnly || node.jvmVisibility == Visibility.PUBLIC) &&
                                    (!methodInvocation || member.arity == null || Type.getArgumentTypes(node.id.value.substring(node.id.value.indexOf('('))).size == member.arity)
                            }.map { it.id }
                        }?.distinct()?.sorted()
                        if (members == null || arguments.any { it.uncertain }) {
                            if (methodInvocation) unknownMethods++ else unknownFields++
                        } else if (targets.isNullOrEmpty()) missingMembers++
                    } else {
                        val members = if (instruction.owner == "java/lang/Class") {
                            arguments.firstOrNull()?.classes?.mapTo(linkedSetOf()) { ConstructorValue(it, 0, false) }
                        } else arguments.firstOrNull()?.constructors
                        targets = members?.flatMap { member ->
                            val owner = objectClass(member.descriptor) ?: return@flatMap emptyList()
                            membersByOwner[owner].orEmpty().filter { node ->
                                node.kind == NodeKind.CONSTRUCTOR && node.id.value.startsWith("method:$owner#<init>(") &&
                                    (!member.publicOnly || node.jvmVisibility == Visibility.PUBLIC) &&
                                    (member.arity == null || Type.getArgumentTypes(node.id.value.substringAfter("#<init>")).size == member.arity)
                            }.map { it.id }
                        }?.distinct()?.sorted()
                        if (members == null || arguments.any { it.uncertain }) unknownConstructors++ else if (targets.isNullOrEmpty()) outsideTargets++
                    }
                    if (targets != null) {
                        models[caller to currentOrdinal] = targets
                        targets.forEach { target -> derived += GraphEdge(caller, target, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) }
                    }
                }
            }
            fact.runtime.copy(reflectionCalls = unknownNames, classLoadingCalls = unknownLoaders,
                reflectiveConstructions = unknownConstructors, outsideRuntimeTargets = outsideTargets,
                valueAnalysisLimits = boundedMethods, serviceLoadingCalls = unknownServices, reflectiveMethods = unknownMethods, reflectiveFields = unknownFields, reflectiveMemberMisses = missingMembers)
        }
        val calls = graph.externalCalls.map { call ->
            val model = RuntimeLibraryModels.find(call.owner, call.name, call.descriptor,
                call.kind == dev.kartograph.core.InvocationKind.STATIC, ::loader)
            models[call.caller to call.ordinal]?.let { targets ->
                call.copy(resolvedTargets = targets, resolution = CallResolution.RUNTIME_MODEL, model = model?.id)
            } ?: call.copy(model = model?.id)
        }
        return CodeGraph(graph.nodes.values, graph.edges + derived, calls, graph.serviceProviders) to observations
    }

    private val CLASS_LOADERS = setOf("java/lang/ClassLoader", "java/net/URLClassLoader", "java/security/SecureClassLoader")
    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
}

private fun frameSlots(method: MethodNode): Long =
    method.instructions.size().toLong() * (method.maxLocals.toLong() + method.maxStack + 1)

private fun exceedsFrameBudget(method: MethodNode): Boolean =
    frameSlots(method) > 250_000L || method.instructions.size() > 20_000

// 호출 사이에 변경 가능한 객체 상태를 전달하지 않는다. 불변 값과 배열 길이만 반환값 분석에 사용한다.
private class ReturnValues(
    private val methods: Map<NodeId, Pair<String, MethodNode>>,
    private val loader: (String) -> Boolean,
    private val fieldValue: (FieldInsnNode?, Set<MemberValue>?, () -> Unit) -> FlowValue? = { _, _, _ -> null },
    private val fieldEpoch: () -> Long = { 0L },
) {
    private data class Key(val method: NodeId, val arguments: List<FlowValue>)
    private data class Summary(val value: FlowValue?, val limited: Boolean)
    private val cache = mutableMapOf<Key, Summary>()
    private val active = mutableSetOf<NodeId>()
    private var analyses = 0
    private var slots = 0L

    fun evaluate(call: MethodInsnNode, values: List<FlowValue>, limited: () -> Unit): FlowValue? {
        if (call.opcode != Opcodes.INVOKESTATIC) return null
        val id = JvmNodeId.methodId(call.owner, call.name, call.desc)
        val (owner, method) = methods[id] ?: return null
        if (method.instructions.size() == 0) return null
        val arguments = values.map { value -> FlowValue(value.basic, strings = value.strings, classes = value.classes,
            integer = value.integer, arrayLength = value.arrayLength, uncertain = value.uncertain, incompleteByLimit = value.incompleteByLimit) }
        val key = Key(id, arguments)
        cache[key]?.let { if (it.limited) limited(); return it.value }
        if (id in active || active.size >= 8 || analyses >= 128 || exceedsFrameBudget(method) ||
            slots + frameSlots(method) > 1_000_000L) {
            limited()
            return null
        }
        analyses++
        slots += frameSlots(method)
        val before = fieldEpoch()
        active += id
        val summary = try { analyze(owner, method, arguments) } finally { active -= id }
        if (before == fieldEpoch()) cache[key] = summary
        if (summary.limited) limited()
        return summary.value
    }

    private fun analyze(owner: String, method: MethodNode, arguments: List<FlowValue>): Summary {
        val parameters = mutableMapOf<Int, FlowValue>()
        var local = 0
        for ((index, type) in Type.getArgumentTypes(method.desc).withIndex()) {
            arguments.getOrNull(index)?.let { parameters[local] = it }
            local += type.size
        }
        val interpreter = FlowInterpreter(loader, parameters, ::evaluate, fieldValue)
        val frames = try { Analyzer(interpreter).analyze(owner, method) } catch (_: AnalyzerException) { return Summary(null, true) }
        var result: FlowValue? = null
        for ((index, instruction) in method.instructions.toArray().withIndex()) {
            if (instruction.opcode != Opcodes.ARETURN) continue
            val frame = frames[index] ?: continue
            if (frame.stackSize == 0) continue
            val value = frame.getStack(frame.stackSize - 1)
            result = result?.let { interpreter.merge(it, value) } ?: value
        }
        return Summary(if (interpreter.limitReached) null else result, interpreter.limitReached)
    }
}

// JVM field 및 public reflection lookup은 일치하는 선언에서 멈춰 숨겨진 부모 field를 섞지 않는다.
private class FieldLookup(facts: List<ClassFacts>, private val hierarchy: ClassHierarchy) {
    private val types = facts.associateBy { it.internalName }
    private val declarations = facts.associate { fact -> fact.internalName to fact.nodes.filter { it.id.value.startsWith("field:") } }
    private val constants = facts.flatMap { it.constantStringFields.entries }.associate { it.key to it.value }

    fun constantString(field: NodeId): String? = constants[field]

    fun direct(owner: String, name: String, descriptor: String): List<GraphNode> =
        lookup(owner, name, descriptor, false, false, mutableSetOf())

    fun reflective(member: MemberValue): List<GraphNode> = objectClass(member.descriptor)?.let {
        lookup(it, member.name, null, !member.declaredOnly, member.declaredOnly, mutableSetOf())
    }.orEmpty()

    private fun lookup(owner: String, name: String, descriptor: String?, publicOnly: Boolean,
        declaredOnly: Boolean, seen: MutableSet<String>): List<GraphNode> {
        if (!seen.add(owner)) return emptyList()
        val own = declarations[owner].orEmpty().filter {
            it.id.value.substringAfter('#').substringBefore(':') == name &&
                (descriptor == null || it.id.value.substringAfter('#').substringAfter(':') == descriptor) &&
                (!publicOnly || it.jvmVisibility == Visibility.PUBLIC)
        }
        if (own.isNotEmpty() || declaredOnly) return own
        val supers = types[owner]?.nodes?.firstOrNull { it.id == JvmNodeId.classId(owner) }?.supertypes
            ?: hierarchy.directSupertypesOf(owner).orEmpty()
        val (interfaces, parents) = supers.partition { parent ->
            types[parent]?.nodes?.any { it.id == JvmNodeId.classId(parent) && it.kind == NodeKind.INTERFACE } == true
        }
        val inherited = interfaces.flatMap { lookup(it, name, descriptor, publicOnly, false, seen) }
        return inherited.ifEmpty { parents.flatMap { lookup(it, name, descriptor, publicOnly, false, seen) } }
    }
}

/** 각 field에 쓰는 method를 한 번만 연결해 무관한 writer를 매 read마다 다시 순회하지 않는다. */
private class FieldWriteIndex(facts: List<ClassFacts>, lookup: FieldLookup, loader: (String) -> Boolean) {
    class Writer(val owner: String, val method: MethodNode, val instructions: Array<AbstractInsnNode>,
        val directTargets: Map<Int, Set<NodeId>>, val reflectiveSites: Set<Int>)

    private val direct = mutableMapOf<NodeId, MutableList<Writer>>()
    private val reflective = mutableListOf<Writer>()

    init {
        for (fact in facts) for (method in fact.fieldMethods) {
            val instructions = method.instructions.toArray()
            val targets = mutableMapOf<Int, Set<NodeId>>()
            val reflectiveSites = mutableSetOf<Int>()
            for ((index, instruction) in instructions.withIndex()) {
                if (instruction is FieldInsnNode && instruction.opcode == Opcodes.PUTSTATIC) {
                    val fields = lookup.direct(instruction.owner, instruction.name, instruction.desc).mapTo(mutableSetOf()) { it.id }
                    if (fields.isNotEmpty()) targets[index] = fields
                } else if (instruction is MethodInsnNode && instruction.name == "set" &&
                    RuntimeLibraryModels.find(instruction.owner, instruction.name, instruction.desc,
                        instruction.opcode == Opcodes.INVOKESTATIC, loader)?.operation == RuntimeOperation.FIELD_ACCESS) {
                    reflectiveSites += index
                }
            }
            if (targets.isEmpty() && reflectiveSites.isEmpty()) continue
            val writer = Writer(fact.internalName, method, instructions, targets, reflectiveSites)
            targets.values.flatten().toSet().forEach { field -> direct.getOrPut(field) { mutableListOf() } += writer }
            if (reflectiveSites.isNotEmpty()) reflective += writer
        }
    }

    fun forField(field: NodeId): List<Writer> = (direct[field].orEmpty() + reflective).distinct()
}

// 실행 순서를 가정하지 않는 may-write 요약이다. 초기화 전 null·재진입·외부 변경은 항상 unknown 가능성으로 남긴다.
// 불변 String/Class 값의 후보만 복원하며 객체 heap 및 실행 코드를 평가하지 않는다.
private class FieldValues(
    private val writers: FieldWriteIndex,
    private val lookup: FieldLookup,
    private val loader: (String) -> Boolean,
    private val returns: Map<NodeId, Pair<String, MethodNode>>,
) {
    private val cache = mutableMapOf<NodeId, FlowValue?>()
    private val active = mutableSetOf<NodeId>()
    private var slots = 0L
    private var analyses = 0
    var cycleEpoch = 0L
        private set
    private val summaries = ReturnValues(returns, loader, ::read) { cycleEpoch }

    fun read(instruction: FieldInsnNode?, members: Set<MemberValue>?, limited: () -> Unit): FlowValue? {
        val fields = if (instruction != null) lookup.direct(instruction.owner, instruction.name, instruction.desc)
            else members?.flatMap(lookup::reflective) ?: return null
        var classes = emptySet<String>()
        var strings = emptySet<String>()
        var exhausted = false
        for (field in fields.distinctBy { it.id }) {
            val value = summarize(field) { exhausted = true; limited() }
            classes = mergeSet(classes, value?.classes.orEmpty(), limited) ?: return null
            strings = mergeSet(strings, value?.strings.orEmpty(), limited) ?: return null
        }
        return if (exhausted || (classes.isEmpty() && strings.isEmpty())) null else FlowValue(BasicValue.REFERENCE_VALUE,
            strings = strings.takeIf { it.isNotEmpty() }, classes = classes.takeIf { it.isNotEmpty() }, uncertain = true)
    }

    private fun summarize(field: GraphNode, limited: () -> Unit): FlowValue? {
        if (JvmModifier.STATIC !in field.jvmModifiers) return null
        if (cache.containsKey(field.id)) return cache[field.id]
        if (field.id in active) { cycleEpoch++; return null }
        if (active.size >= 8) { limited(); return null }
        val before = cycleEpoch
        active += field.id
        var classes = emptySet<String>()
        var strings = emptySet<String>()
        var exhausted = false
        fun limit() { exhausted = true; limited() }
        try {
            lookup.constantString(field.id)?.let { value -> strings = bounded(setOf(value), ::limit).orEmpty() }
            for (writer in writers.forField(field.id)) {
                val method = writer.method
                if (analyses >= 128 || exceedsFrameBudget(method) || slots + frameSlots(method) > 1_000_000L) {
                    limit(); break
                }
                analyses++
                slots += frameSlots(method)
                val interpreter = FlowInterpreter(loader, returnedValue = summaries::evaluate, fieldValue = ::read, retainFieldCandidates = true)
                val frames = try { Analyzer(interpreter).analyze(writer.owner, method) } catch (_: AnalyzerException) { limit(); continue }
                for (index in writer.instructions.indices) {
                    val frame = frames[index] ?: continue
                    val value = when {
                        field.id in writer.directTargets[index].orEmpty() -> frame.getStack(frame.stackSize - 1)
                        index in writer.reflectiveSites && frame.stackSize >= 3 -> {
                            val members = frame.getStack(frame.stackSize - 3).fields
                            if (members?.flatMap(lookup::reflective)?.any { it.id == field.id } != true) null
                            else frame.getStack(frame.stackSize - 1)
                        }
                        else -> null
                    }
                    if (value?.incompleteByLimit == true) limit()
                    classes = mergeSet(classes, value?.classes.orEmpty(), ::limit) ?: emptySet()
                    strings = mergeSet(strings, value?.strings.orEmpty(), ::limit) ?: emptySet()
                }
            }
            val result = if (exhausted || (classes.isEmpty() && strings.isEmpty())) null else FlowValue(BasicValue.REFERENCE_VALUE,
                strings = strings.takeIf { it.isNotEmpty() }, classes = classes.takeIf { it.isNotEmpty() }, uncertain = true)
            // 한도나 순환으로 잘린 결과를 다른 read 문맥에서 완전한 요약으로 재사용하지 않는다.
            if (!exhausted && before == cycleEpoch) cache[field.id] = result
            return result
        } finally { active -= field.id }
    }
}

private data class MemberValue(val descriptor: String, val name: String, val arity: Int?, val declaredOnly: Boolean)

private data class ConstructorValue(val descriptor: String, val arity: Int?, val publicOnly: Boolean)

private data class FlowValue(
    val basic: BasicValue,
    val strings: Set<String>? = null,
    val classes: Set<String>? = null,
    val constructors: Set<ConstructorValue>? = null,
    val instances: Set<String>? = null,
    val integer: Int? = null,
    val arrayLength: Int? = null,
    val methods: Set<MemberValue>? = null,
    val fields: Set<MemberValue>? = null,
    val uncertain: Boolean = false,
    val incompleteByLimit: Boolean = false,
) : Value {
    override fun getSize(): Int = basic.size
    // Analyzer 오류가 값 원문을 출력하지 않도록 한다.
    override fun toString(): String = "flow-value"
}

private class FlowInterpreter(
    private val isClassLoader: (String) -> Boolean,
    private val parameters: Map<Int, FlowValue> = emptyMap(),
    private val returnedValue: (MethodInsnNode, List<FlowValue>, () -> Unit) -> FlowValue? = { _, _, _ -> null },
    private val fieldValue: (FieldInsnNode?, Set<MemberValue>?, () -> Unit) -> FlowValue? = { _, _, _ -> null },
    private val retainFieldCandidates: Boolean = false,
) : Interpreter<FlowValue>(Opcodes.ASM9) {
    private val base = BasicInterpreter()
    var limitReached = false
    private var limitEvents = 0
    private fun limited() { limitReached = true; limitEvents++ }
    override fun newValue(type: Type?): FlowValue? = base.newValue(type)?.let(::FlowValue)
    override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): FlowValue =
        parameters[local] ?: requireNotNull(newValue(type))

    override fun newOperation(insn: AbstractInsnNode): FlowValue {
        val before = limitEvents
        val value = FlowValue(base.newOperation(insn))
        val result = when {
            insn is FieldInsnNode && insn.opcode == Opcodes.GETSTATIC -> {
                var exhausted = false
                val found = fieldValue(insn, null) { exhausted = true; limited() }
                (found ?: value).copy(basic = value.basic, incompleteByLimit = exhausted || found?.incompleteByLimit == true)
            }
            insn is LdcInsnNode && insn.cst is String -> value.copy(strings = bounded(setOf(insn.cst as String), ::limited))
            insn is LdcInsnNode && insn.cst is Type && (insn.cst as Type).sort != Type.METHOD -> value.copy(classes = setOf((insn.cst as Type).descriptor))
            insn is LdcInsnNode && insn.cst is Int -> value.copy(integer = insn.cst as Int)
            insn is IntInsnNode -> value.copy(integer = insn.operand)
            insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> value.copy(integer = insn.opcode - Opcodes.ICONST_0)
            insn is TypeInsnNode && insn.opcode == Opcodes.NEW -> value.copy(instances = setOf(Type.getObjectType(insn.desc).descriptor))
            else -> value
        }
        return if (limitEvents == before) result else result.copy(incompleteByLimit = true)
    }

    override fun copyOperation(insn: AbstractInsnNode, value: FlowValue): FlowValue = value

    override fun unaryOperation(insn: AbstractInsnNode, value: FlowValue): FlowValue? {
        val result = base.unaryOperation(insn, value.basic) ?: return null
        return when (insn.opcode) {
            Opcodes.CHECKCAST -> value.copy(basic = result)
            Opcodes.ANEWARRAY, Opcodes.NEWARRAY -> FlowValue(result, arrayLength = value.integer?.takeIf { it >= 0 })
            Opcodes.ARRAYLENGTH -> FlowValue(result, integer = value.arrayLength)
            Opcodes.INEG -> FlowValue(result, integer = value.integer?.let { -it })
            else -> FlowValue(result)
        }
    }

    override fun binaryOperation(insn: AbstractInsnNode, left: FlowValue, right: FlowValue): FlowValue? {
        val result = base.binaryOperation(insn, left.basic, right.basic) ?: return null
        val a = left.integer
        val b = right.integer
        val integer = if (a != null && b != null) when (insn.opcode) {
            Opcodes.IADD -> a + b
            Opcodes.ISUB -> a - b
            Opcodes.IMUL -> a * b
            else -> null
        } else null
        return FlowValue(result, integer = integer)
    }

    override fun ternaryOperation(insn: AbstractInsnNode, a: FlowValue, b: FlowValue, c: FlowValue): FlowValue? =
        base.ternaryOperation(insn, a.basic, b.basic, c.basic)?.let(::FlowValue)

    override fun naryOperation(insn: AbstractInsnNode, values: MutableList<out FlowValue>): FlowValue? =
        evaluate(insn, values)

    fun evaluate(insn: AbstractInsnNode, values: List<FlowValue>): FlowValue? {
        val before = limitEvents
        val value = evaluateOperation(insn, values)
        return if (limitEvents != before) value?.copy(incompleteByLimit = true) else value
    }

    private fun evaluateOperation(insn: AbstractInsnNode, values: List<FlowValue>): FlowValue? {
        val result = base.naryOperation(insn, values.map { it.basic }) ?: return null
        val unknown = FlowValue(result, uncertain = values.any { it.uncertain }, incompleteByLimit = values.any { it.incompleteByLimit })
        if (insn is InvokeDynamicInsnNode) {
            if (insn.bsm.owner != "java/lang/invoke/StringConcatFactory") return unknown
            val recipe = when (insn.bsm.name) {
                "makeConcat" -> "\u0001".repeat(values.size)
                "makeConcatWithConstants" -> insn.bsmArgs.firstOrNull() as? String ?: return unknown
                else -> return unknown
            }
            val types = Type.getArgumentTypes(insn.desc)
            return unknown.copy(strings = concatRecipe(recipe, values.mapIndexed { index, value -> value.asStrings(types[index]) }, insn.bsmArgs.drop(1), ::limited))
        }
        if (insn !is MethodInsnNode) return unknown
        var returnLimited = false
        returnedValue(insn, values) { returnLimited = true; limited() }?.let { return it.copy(basic = result) }
        if (returnLimited) return unknown.copy(incompleteByLimit = true)
        val model = RuntimeLibraryModels.find(insn.owner, insn.name, insn.desc,
            insn.opcode == Opcodes.INVOKESTATIC, isClassLoader) ?: return unknown
        fun value(index: Int): FlowValue? = values.getOrNull(index)
        if (model.operation == RuntimeOperation.FIELD_ACCESS && insn.name == "get") {
            var exhausted = false
            val found = fieldValue(null, value(0)?.fields) { exhausted = true; limited() }
            return (found ?: unknown).copy(basic = result, uncertain = found?.uncertain == true || unknown.uncertain,
                incompleteByLimit = exhausted || found?.incompleteByLimit == true || unknown.incompleteByLimit)
        }
        if (model.operation in setOf(RuntimeOperation.METHOD_LOOKUP, RuntimeOperation.FIELD_LOOKUP)) {
            val classes = value(0)?.classes ?: return unknown
            val names = value(1)?.strings ?: return unknown
            if (classes.size * names.size > 16) { limited(); return unknown }
            val members = classes.flatMap { descriptor -> names.map { name ->
                MemberValue(descriptor, name, if (model.operation == RuntimeOperation.METHOD_LOOKUP) value(2)?.arrayLength else null,
                    insn.name.startsWith("getDeclared"))
            } }.toSet()
            return if (model.operation == RuntimeOperation.METHOD_LOOKUP) unknown.copy(methods = members) else unknown.copy(fields = members)
        }
        if (insn.owner == "java/lang/Class" && insn.name == "forName") {
            val position = if (insn.desc.startsWith("(Ljava/lang/Module;")) 1 else 0
            return unknown.copy(classes = value(position)?.strings?.mapNotNull(::binaryClass)?.toSet()?.takeIf { it.isNotEmpty() })
        }
        if (insn.name == "loadClass" && isClassLoader(insn.owner)) {
            return unknown.copy(classes = value(1)?.strings?.mapNotNull(::binaryClass)?.toSet()?.takeIf { it.isNotEmpty() })
        }
        if (insn.owner == "java/lang/Class" && insn.name in setOf("getConstructor", "getDeclaredConstructor")) {
            return unknown.copy(constructors = value(0)?.classes?.mapTo(linkedSetOf()) {
                ConstructorValue(it, value(1)?.arrayLength, insn.name == "getConstructor")
            })
        }
        if (insn.owner == "java/lang/Class" && insn.name == "newInstance") return unknown.copy(instances = value(0)?.classes)
        if (insn.owner == "java/lang/reflect/Constructor" && insn.name == "newInstance") {
            return unknown.copy(instances = value(0)?.constructors?.mapTo(linkedSetOf()) { it.descriptor })
        }
        if (insn.name == "getClass" && insn.desc == "()Ljava/lang/Class;") return unknown.copy(classes = value(0)?.instances)
        if (insn.owner == "java/lang/Class" && insn.name == "getName") {
            return unknown.copy(strings = value(0)?.classes?.mapTo(linkedSetOf()) { descriptor ->
                if (descriptor.startsWith('[')) descriptor.replace('/', '.') else Type.getType(descriptor).className
            })
        }
        if (insn.owner == "java/lang/String" && insn.name == "concat") {
            return unknown.copy(strings = concatenate(value(0)?.strings, value(1)?.strings, ::limited))
        }
        if (insn.owner == "java/lang/String" && insn.name == "valueOf") return unknown.copy(strings = value(0)?.asStrings(Type.getArgumentTypes(insn.desc).firstOrNull()))
        return unknown
    }

    override fun returnOperation(insn: AbstractInsnNode, value: FlowValue, expected: FlowValue) = base.returnOperation(insn, value.basic, expected.basic)

    override fun merge(a: FlowValue, b: FlowValue): FlowValue {
        if (a == b) return a
        var exhausted = a.incompleteByLimit || b.incompleteByLimit
        fun limit() { exhausted = true; limited() }
        fun candidates(first: Set<String>?, second: Set<String>?): Set<String>? =
            if (retainFieldCandidates && exhausted) null
            else if (retainFieldCandidates) mergeSet(first.orEmpty(), second.orEmpty(), ::limit)?.takeIf { it.isNotEmpty() }
            else mergeSet(first, second, ::limit)
        val strings = candidates(a.strings, b.strings)
        val classes = candidates(a.classes, b.classes)
        return FlowValue(
            base.merge(a.basic, b.basic),
            strings.takeUnless { retainFieldCandidates && exhausted }, classes.takeUnless { retainFieldCandidates && exhausted },
            mergeSet(a.constructors, b.constructors, ::limit), mergeSet(a.instances, b.instances, ::limit),
            a.integer?.takeIf { it == b.integer }, a.arrayLength?.takeIf { it == b.arrayLength },
            mergeSet(a.methods, b.methods, ::limit), mergeSet(a.fields, b.fields, ::limit),
            a.uncertain || b.uncertain || (retainFieldCandidates &&
                (a.classes == null || b.classes == null || a.strings == null || b.strings == null)), exhausted,
        )
    }
}

private fun FlowValue.asStrings(type: Type? = null): Set<String>? = strings ?: integer?.let {
    when (type?.sort) {
        Type.BOOLEAN -> setOf((it != 0).toString())
        Type.CHAR -> setOf(it.toChar().toString())
        null, Type.INT, Type.BYTE, Type.SHORT, Type.OBJECT -> setOf(it.toString())
        else -> null
    }
}
private fun <T> mergeSet(a: Set<T>?, b: Set<T>?, limited: () -> Unit): Set<T>? {
    if (a == null || b == null) return null
    val values = a + b
    if (values.size > 16) { limited(); return null }
    return values
}
private fun bounded(values: Set<String>, limited: () -> Unit): Set<String>? {
    if (values.size > 16 || values.any { it.length > 4096 }) { limited(); return null }
    return values
}
private fun concatenate(a: Set<String>?, b: Set<String>?, limited: () -> Unit): Set<String>? {
    if (a == null || b == null) return null
    if (a.size * b.size > 16) { limited(); return null }
    return bounded(a.flatMap { first -> b.map { second -> first + second } }.toSet(), limited)
}

private fun concatRecipe(recipe: String, arguments: List<Set<String>?>, constants: List<Any>, limited: () -> Unit): Set<String>? {
    var result: Set<String>? = setOf("")
    var argument = 0
    var constant = 0
    for (character in recipe) {
        val next = when (character) {
            '\u0001' -> arguments.getOrNull(argument++)
            '\u0002' -> constants.getOrNull(constant++)?.takeIf { it is String || it is Number }?.toString()?.let(::setOf)
            else -> setOf(character.toString())
        }
        result = concatenate(result, next, limited) ?: return null
    }
    return result
}

private fun binaryClass(name: String): String? = try {
    if (name.isEmpty() || name.any(Char::isISOControl) || name.length > 4096) null
    else if (name.startsWith('[')) Type.getType(name.replace('.', '/')).descriptor
    else if ('/' in name || ';' in name) null else Type.getObjectType(name.replace('.', '/')).descriptor
} catch (_: IllegalArgumentException) { null }

private fun objectClass(descriptor: String): String? = Type.getType(descriptor).takeIf { it.sort == Type.OBJECT }?.internalName
private fun referencedClass(descriptor: String): String? {
    val type = Type.getType(descriptor)
    return (if (type.sort == Type.ARRAY) type.elementType else type).takeIf { it.sort == Type.OBJECT }?.internalName
}
