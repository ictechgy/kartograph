package dev.kartograph.index

import dev.kartograph.core.CallResolution
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
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

/** 필요한 메서드의 stack/local 값만 제한적으로 전파한다. 문자열 원문은 결과나 오류에 싣지 않는다. */
internal object RuntimeValueAnalyzer {
    fun sensitive(owner: String, name: String): Boolean =
        (owner == "java/lang/Class" && name in CLASS_METHODS) ||
            (name == "loadClass") ||
            (owner == "java/lang/reflect/Constructor" && name == "newInstance") ||
            (owner == "java/util/ServiceLoader" && name in setOf("load", "loadInstalled"))

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
        val interpreter = FlowInterpreter(::loader)
        val derived = mutableListOf<GraphEdge>()
        val models = mutableMapOf<Pair<NodeId, Int>, List<NodeId>>()
        val observations = facts.map { fact ->
            var unknownNames = 0
            var unknownLoaders = 0
            var unknownConstructors = 0
            var unknownServices = 0
            var outsideTargets = 0
            var boundedMethods = 0
            for (method in fact.runtimeMethods) {
                val caller = JvmNodeId.methodId(fact.internalName, method.name, method.desc)
                interpreter.limitReached = false
                val frames = if (method.instructions.size().toLong() * (method.maxLocals + method.maxStack + 1) > MAX_FRAME_SLOTS ||
                    method.instructions.size() > MAX_INSTRUCTIONS) {
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
                    val classLoading = instruction.owner == "java/lang/Class" && instruction.name == "forName"
                    val loaderCall = instruction.name == "loadClass" && loader(instruction.owner)
                    val construction = instruction.name == "newInstance" && instruction.owner in CONSTRUCTION_OWNERS
                    val serviceLoading = instruction.owner == "java/util/ServiceLoader" && instruction.name in setOf("load", "loadInstalled")
                    if (!classLoading && !loaderCall && !construction && !serviceLoading) continue
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
                        if (services == null || targets.isNullOrEmpty()) unknownServices++
                    } else if (classLoading || loaderCall) {
                        val classes = value?.classes
                        targets = classes?.mapNotNull { descriptor -> referencedClass(descriptor)?.let(JvmNodeId::classId) }
                            ?.filter(graph::contains)?.distinct()?.sorted()
                        if (classes == null) {
                            if (loaderCall) unknownLoaders++ else unknownNames++
                        } else if (targets.isNullOrEmpty()) outsideTargets++
                    } else {
                        val members = if (instruction.owner == "java/lang/Class") {
                            arguments.firstOrNull()?.classes?.mapTo(linkedSetOf()) { ConstructorValue(it, 0, false) }
                        } else arguments.firstOrNull()?.constructors
                        targets = members?.flatMap { member ->
                            val owner = objectClass(member.descriptor) ?: return@flatMap emptyList()
                            graph.nodes.values.filter { node ->
                                node.kind == NodeKind.CONSTRUCTOR && node.id.value.startsWith("method:$owner#<init>(") &&
                                    (!member.publicOnly || node.jvmVisibility == Visibility.PUBLIC) &&
                                    (member.arity == null || Type.getArgumentTypes(node.id.value.substringAfter("#<init>")).size == member.arity)
                            }.map { it.id }
                        }?.distinct()?.sorted()
                        if (members == null) unknownConstructors++ else if (targets.isNullOrEmpty()) outsideTargets++
                    }
                    if (targets != null) {
                        models[caller to currentOrdinal] = targets
                        targets.forEach { target -> derived += GraphEdge(caller, target, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) }
                    }
                }
            }
            fact.runtime.copy(reflectionCalls = unknownNames, classLoadingCalls = unknownLoaders,
                reflectiveConstructions = unknownConstructors, outsideRuntimeTargets = outsideTargets,
                valueAnalysisLimits = boundedMethods, serviceLoadingCalls = unknownServices)
        }
        val calls = graph.externalCalls.map { call ->
            models[call.caller to call.ordinal]?.let { targets ->
                call.copy(resolvedTargets = targets, resolution = CallResolution.RUNTIME_MODEL)
            } ?: call
        }
        return CodeGraph(graph.nodes.values, graph.edges + derived, calls, graph.serviceProviders) to observations
    }

    private const val MAX_FRAME_SLOTS = 250_000L
    private const val MAX_INSTRUCTIONS = 20_000
    private val CLASS_METHODS = setOf("forName", "getConstructor", "getDeclaredConstructor", "newInstance")
    private val CLASS_LOADERS = setOf("java/lang/ClassLoader", "java/net/URLClassLoader", "java/security/SecureClassLoader")
    private val CONSTRUCTION_OWNERS = setOf("java/lang/Class", "java/lang/reflect/Constructor")
    private val TYPE_KINDS = setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS)
}

private data class ConstructorValue(val descriptor: String, val arity: Int?, val publicOnly: Boolean)

private data class FlowValue(
    val basic: BasicValue,
    val strings: Set<String>? = null,
    val classes: Set<String>? = null,
    val constructors: Set<ConstructorValue>? = null,
    val instances: Set<String>? = null,
    val integer: Int? = null,
    val arrayLength: Int? = null,
) : Value {
    override fun getSize(): Int = basic.size
    // Analyzer 오류가 값 원문을 출력하지 않도록 한다.
    override fun toString(): String = "flow-value"
}

private class FlowInterpreter(private val isClassLoader: (String) -> Boolean) : Interpreter<FlowValue>(Opcodes.ASM9) {
    private val base = BasicInterpreter()
    var limitReached = false
    private fun limited() { limitReached = true }
    override fun newValue(type: Type?): FlowValue? = base.newValue(type)?.let(::FlowValue)

    override fun newOperation(insn: AbstractInsnNode): FlowValue {
        val value = FlowValue(base.newOperation(insn))
        return when {
            insn is LdcInsnNode && insn.cst is String -> value.copy(strings = bounded(setOf(insn.cst as String), ::limited))
            insn is LdcInsnNode && insn.cst is Type && (insn.cst as Type).sort != Type.METHOD -> value.copy(classes = setOf((insn.cst as Type).descriptor))
            insn is LdcInsnNode && insn.cst is Int -> value.copy(integer = insn.cst as Int)
            insn is IntInsnNode -> value.copy(integer = insn.operand)
            insn.opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> value.copy(integer = insn.opcode - Opcodes.ICONST_0)
            insn is TypeInsnNode && insn.opcode == Opcodes.NEW -> value.copy(instances = setOf(Type.getObjectType(insn.desc).descriptor))
            else -> value
        }
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
        val result = base.naryOperation(insn, values.map { it.basic }) ?: return null
        val unknown = FlowValue(result)
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
        fun value(index: Int): FlowValue? = values.getOrNull(index)
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

    override fun merge(a: FlowValue, b: FlowValue): FlowValue = if (a == b) a else FlowValue(
        base.merge(a.basic, b.basic), mergeSet(a.strings, b.strings, ::limited), mergeSet(a.classes, b.classes, ::limited),
        mergeSet(a.constructors, b.constructors, ::limited), mergeSet(a.instances, b.instances, ::limited),
        a.integer?.takeIf { it == b.integer }, a.arrayLength?.takeIf { it == b.arrayLength },
    )
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
