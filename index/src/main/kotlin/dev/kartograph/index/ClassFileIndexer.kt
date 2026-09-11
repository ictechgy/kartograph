package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.ClassHierarchy
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.GeneratedSiblingNaming
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.jar.JarFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodNode

/** class root의 JVM 산출물을 읽어 구조와 instruction 관계로 된 코드 그래프를 만든다. */
public class ClassFileIndexer {
    /**
     * 각 root를 재귀 탐색하고 JVM class name이 같은 중복 산출물은 첫 번째 것만 사용한다.
     * class 하나라도 깨졌으면 불완전한 그래프를 반환하지 않는다.
     */
    public fun index(classRoots: Iterable<Path>): CodeGraph = indexWithObservations(classRoots).graph

    /** 그래프와 runtime 관측값을 같은 class 방문에서 모아 후속 질의의 재파싱을 없앤다. */
    public fun indexWithObservations(classRoots: Iterable<Path>): IndexedClasses = indexWithObservations(classRoots, null)

    /** 명시된 dependency 입력을 함께 읽어 외부 상속과 runtime 모델의 문맥으로 사용한다. */
    public fun indexWithObservations(classRoots: Iterable<Path>, classpath: Iterable<Path>?): IndexedClasses =
        indexWithObservations(classRoots, classpath, emptyList())

    /** Java resource 입력의 ServiceLoader 등록도 같은 variant의 사실에 포함한다. */
    public fun indexWithObservations(classRoots: Iterable<Path>, classpath: Iterable<Path>?, serviceResources: Iterable<Path>): IndexedClasses =
        indexWithObservations(classRoots, classpath, serviceResources, emptyList())

    /** 생성 전용 컴파일 root의 명시적 출처를 사용하며, 중복 class는 선택된 첫 입력의 출처를 유지한다. */
    public fun indexWithObservations(
        classRoots: Iterable<Path>,
        classpath: Iterable<Path>?,
        serviceResources: Iterable<Path>,
        generatedClassRoots: Iterable<Path>,
    ): IndexedClasses {
        val roots = classRoots.toList()
        val generated = generatedClassRoots.toList()
        val markedRoots = if (generated.isEmpty()) emptySet() else try {
            val selected = roots.associateWith { it.toRealPath() }
            val marked = generated.mapTo(mutableSetOf()) { it.toRealPath() }
            if (!selected.values.containsAll(marked)) throw ClassIndexingException("generated class roots must also be supplied as class roots")
            selected.filterValues(marked::contains).keys
        } catch (error: IOException) {
            throw ClassIndexingException("class roots cannot be resolved; check the compiled inputs", error)
        }
        val factsByClass = linkedMapOf<String, ClassFacts>()
        roots.forEach { root ->
            val generatedInput = root in markedRoots
            readRoot(root).forEach { facts ->
                val selected = if (generatedInput) facts.copy(nodes = facts.nodes.map { node ->
                    node.copy(synthesized = true, attributes = node.attributes + NodeAttribute.GENERATED_INPUT)
                }) else facts
                factsByClass.putIfAbsent(facts.internalName, selected)
            }
        }
        if (factsByClass.isEmpty()) throw ClassIndexingException("no compiled declarations found; check class roots and build the project")
        val generatedSiblingNames = factsByClass.values.flatMapTo(mutableSetOf(), ClassFacts::generatedSiblingNames)
        factsByClass.values.filter { facts ->
            facts.nodes.any { node ->
                node.id == JvmNodeId.classId(facts.internalName) && node.annotations.any(GENERATED_MARKERS::contains)
            }
        }.forEach { facts -> generatedSiblingNames.add(facts.internalName) }
        // 이름의 '$'가 아니라 classfile의 실제 enclosing 관계만 전파한다.
        val enclosedClasses = factsByClass.values.groupBy(ClassFacts::enclosingClass)
        val pendingGenerated = ArrayDeque(generatedSiblingNames)
        while (pendingGenerated.isNotEmpty()) {
            enclosedClasses[pendingGenerated.removeFirst()].orEmpty().forEach { enclosed ->
                if (generatedSiblingNames.add(enclosed.internalName)) pendingGenerated.addLast(enclosed.internalName)
            }
        }
        val classFacts = factsByClass.values.map { facts ->
            if (facts.internalName in generatedSiblingNames) facts.asSynthesized() else facts
        }
        val graph = CodeGraph(
            nodes = classFacts.flatMap(ClassFacts::nodes),
            edges = classFacts.flatMap(ClassFacts::edges) +
                projectOverrideEdges(classFacts) +
                frameworkCallbackEdges(classFacts) +
                enclosingContainerEdges(classFacts),
            externalCalls = classFacts.flatMap(ClassFacts::calls),
            serviceProviders = ServiceProviderScanner.scan(roots + serviceResources),
        )
        val hierarchy = classpath?.let { ClassHierarchyIndexer().index(it, graph.nodes.values.flatMap(GraphNode::supertypes)) } ?: ClassHierarchy.EMPTY
        val (modeled, observations) = RuntimeValueAnalyzer.enrich(graph, classFacts, hierarchy)
        return IndexedClasses(modeled, observations, hierarchy).withHierarchy(hierarchy)
    }

    private fun readRoot(root: Path): List<ClassFacts> = when {
        root.isDirectory() -> discoverClassFiles(root).map(::readClass)
        root.isRegularFile() && root.fileName.toString().endsWith(".jar", ignoreCase = true) -> readJar(root)
        !root.exists() -> throw ClassIndexingException("class root does not exist; build the project before indexing")
        else -> throw ClassIndexingException("class root must be a class directory or JAR")
    }.filterNot { facts -> facts.internalName == "module-info" || facts.internalName.endsWith("/package-info") }

    private fun discoverClassFiles(root: Path): List<Path> {
        if (!root.isDirectory()) {
            throw ClassIndexingException("class root does not exist; build the project before indexing")
        }
        return try {
            Files.walk(root).use { paths ->
                paths.filter(::isClassFile).sorted().toList()
            }
        } catch (error: IOException) {
            throw ClassIndexingException("class root cannot be read", error)
        }
    }

    private fun readClass(classFile: Path): ClassFacts = try {
        readFacts(ClassReader(Files.readAllBytes(classFile))).let {
            it.copy(runtime = it.runtime.copy(modified = Files.getLastModifiedTime(classFile)))
        }
    } catch (error: IOException) {
        throw ClassIndexingException("class file cannot be read", error)
    } catch (error: RuntimeException) {
        throw ClassIndexingException("invalid class file", error)
    }

    private fun readJar(jar: Path): List<ClassFacts> = try {
        JarFile(jar.toFile(), false).use { archive ->
            archive.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.endsWith(".class") &&
                        !entry.name.startsWith("META-INF/versions/")
                }
                .sortedBy { entry -> entry.name }
                .map { entry ->
                    archive.getInputStream(entry).use { input ->
                        val observed = readFacts(ClassReader(input))
                        val modified = entry.time.takeIf { it >= 0 }?.let(FileTime::fromMillis)
                            ?: Files.getLastModifiedTime(jar)
                        // 일반 ZIP의 DOS timestamp는 2초 단위다. 더 정밀한 extra가 있어도 이 상한을 보수적으로 쓴다.
                        val facts = observed.copy(runtime = observed.runtime.copy(modified = modified, modifiedPrecisionMillis = 2_000))
                        if (jar.fileName.toString() == "R.jar") facts.asSynthesized() else facts
                    }
                }
                .toList()
        }
    } catch (error: IOException) {
        throw ClassIndexingException("class JAR cannot be read", error)
    } catch (error: RuntimeException) {
        throw ClassIndexingException("invalid class file in class JAR", error)
    }

    // runtime 호출과 반환값 후보의 본문만 보관한다. 같은 reader를 재사용해 파일을 다시 읽지 않는다.
    private fun readFacts(reader: ClassReader): ClassFacts {
        val visitor = FactsVisitor()
        reader.accept(visitor, 0)
        val facts = visitor.facts()
        val methods = facts.calls.filter { RuntimeValueAnalyzer.requiresValueAnalysis(it) }.map { it.caller }.toSet()
        val returns = facts.nodes.filter { node ->
            JvmModifier.STATIC in node.jvmModifiers &&
                (node.id.value.endsWith(")Ljava/lang/String;") || node.id.value.endsWith(")Ljava/lang/Class;"))
        }.mapTo(mutableSetOf(), GraphNode::id)
        if (methods.isEmpty() && returns.isEmpty()) return facts
        val bodies = mutableMapOf<NodeId, MethodNode>()
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                val id = JvmNodeId.methodId(facts.internalName, name, descriptor)
                return if (id in methods || id in returns) MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions)
                    .also { bodies[id] = it } else null
            }
        }, ClassReader.SKIP_FRAMES)
        return facts.copy(runtimeMethods = bodies.filterKeys(methods::contains).values.toList(),
            returnMethods = bodies.filterKeys(returns::contains).values.toList())
    }

    private fun isClassFile(path: Path): Boolean =
        Files.isRegularFile(path) && path.fileName.toString().endsWith(".class")
}

private class FactsVisitor : ClassVisitor(Opcodes.ASM9) {
    private lateinit var internalName: String
    private var enclosingClass: String? = null
    private var classAccess: Int = 0
    private var innerClassAccess: Int? = null
    private var sourceFile: String? = null
    private var metadataValues: MetadataAnnotationValues? = null
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val calls = mutableListOf<ExternalCall>()
    private val classAnnotations = mutableSetOf<String>()
    private val supertypes = mutableSetOf<String>()
    private var nativeMethods = 0
    private var reflectionCalls = 0
    private var dynamicRegistrations = 0

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>,
    ) {
        internalName = name
        classAccess = access
        if (superName != null && superName != "java/lang/Object") supertypes += superName
        supertypes += interfaces
        if (superName != null && superName != "java/lang/Object") {
            edges += GraphEdge(JvmNodeId.classId(name), JvmNodeId.classId(superName), EdgeKind.INHERITANCE)
        }
        interfaces.forEach { interfaceName ->
            edges += GraphEdge(JvmNodeId.classId(name), JvmNodeId.classId(interfaceName), EdgeKind.INHERITANCE)
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFile = source
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        if (name == internalName) {
            innerClassAccess = access
            if (outerName != null) enclosingClass = outerName
        }
    }

    override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
        enclosingClass = owner
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        classAnnotations += annotationInternalName(descriptor)
        edges += annotationEdge(JvmNodeId.classId(internalName), descriptor)
        if (descriptor == "Lkotlin/Metadata;") {
            return MetadataAnnotationValues().also { metadataValues = it }
        }
        return AnnotationValueVisitor(JvmNodeId.classId(internalName))
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        val fieldId = JvmNodeId.fieldId(internalName, name, descriptor)
        val annotations = mutableSetOf<String>()
        edges += GraphEdge(JvmNodeId.classId(internalName), fieldId, EdgeKind.MEMBER)
        edges += GraphEdge(fieldId, JvmNodeId.classId(internalName), EdgeKind.REFERENCE)
        descriptorClassNames(descriptor).forEach { target ->
            edges += GraphEdge(fieldId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
        }
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? {
                annotations += annotationInternalName(annotationDescriptor)
                edges += annotationEdge(fieldId, annotationDescriptor)
                return AnnotationValueVisitor(fieldId)
            }

            override fun visitEnd() {
                nodes += GraphNode(
                    id = fieldId,
                    name = name,
                    kind = NodeKind.FIELD,
                    jvmSignature = fieldId.value.removePrefix("field:"),
                    location = sourceLocation(),
                    visibility = access.toVisibility(),
                    jvmVisibility = access.toVisibility(),
                    jvmModifiers = access.toJvmModifiers(),
                    attributes = if (value != null) setOf(NodeAttribute.COMPILE_TIME_CONSTANT) else emptySet(),
                    annotations = annotations,
                    synthesized = access.isSynthetic() || internalName.isAndroidGeneratedClass(),
                )
            }
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        if (access and Opcodes.ACC_NATIVE != 0) nativeMethods++
        val methodId = JvmNodeId.methodId(internalName, name, descriptor)
        val annotations = mutableSetOf<String>()
        edges += GraphEdge(JvmNodeId.classId(internalName), methodId, EdgeKind.MEMBER)
        edges += GraphEdge(methodId, JvmNodeId.classId(internalName), EdgeKind.REFERENCE)
        if (name == "<clinit>") {
            edges += GraphEdge(JvmNodeId.classId(internalName), methodId, EdgeKind.REFERENCE)
        }
        descriptorClassNames(descriptor).forEach { target ->
            edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
        }
        exceptions.orEmpty().forEach { target ->
            edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
        }
        return object : MethodVisitor(Opcodes.ASM9) {
            private var firstLine: Int? = null
            private var currentLine: Int? = null
            private var callOrdinal = 0

            override fun visitAnnotationDefault(): AnnotationVisitor = AnnotationValueVisitor(methodId, JvmNodeId.classId(internalName))

            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? {
                annotations += annotationInternalName(annotationDescriptor)
                edges += annotationEdge(methodId, annotationDescriptor)
                return AnnotationValueVisitor(methodId)
            }

            override fun visitParameterAnnotation(
                parameter: Int,
                annotationDescriptor: String,
                visible: Boolean,
            ): AnnotationVisitor {
                // parameter annotation 타입 자체도 연결해 값 없는 어노테이션이 referenced class를 잃지 않게 한다.
                edges += annotationEdge(methodId, annotationDescriptor)
                return AnnotationValueVisitor(methodId)
            }

            override fun visitLineNumber(line: Int, start: Label) {
                if (firstLine == null) firstLine = line
                currentLine = line
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                targetName: String,
                targetDescriptor: String,
                isInterface: Boolean,
            ) {
                val invocationKind = when (opcode) {
                    Opcodes.INVOKESTATIC -> InvocationKind.STATIC
                    Opcodes.INVOKESPECIAL -> InvocationKind.SPECIAL
                    Opcodes.INVOKEINTERFACE -> InvocationKind.INTERFACE
                    else -> InvocationKind.VIRTUAL
                }
                calls += ExternalCall(methodId, owner, targetName, targetDescriptor, invocationKind, sourceLocation(currentLine), callOrdinal++)
                if (owner == "java/lang/Class" && targetName == "forName") reflectionCalls++
                if (RuntimeLimitationScanner.isDynamicRegistration(owner, targetName)) dynamicRegistrations++
                edges += GraphEdge(
                    methodId,
                    JvmNodeId.methodId(owner, targetName, targetDescriptor),
                    EdgeKind.CALL,
                )
                edges += GraphEdge(methodId, JvmNodeId.classId(owner), EdgeKind.REFERENCE)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, targetName: String, targetDescriptor: String) {
                edges += GraphEdge(
                    methodId,
                    JvmNodeId.fieldId(owner, targetName, targetDescriptor),
                    EdgeKind.FIELD_ACCESS,
                )
                edges += GraphEdge(methodId, JvmNodeId.classId(owner), EdgeKind.REFERENCE)
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any,
            ) {
                val ordinal = callOrdinal++
                edges += GraphEdge(methodId, JvmNodeId.methodId(bootstrapMethodHandle.owner, bootstrapMethodHandle.name, bootstrapMethodHandle.desc), EdgeKind.CALL)
                edges += GraphEdge(methodId, JvmNodeId.classId(bootstrapMethodHandle.owner), EdgeKind.REFERENCE)
                calls += ExternalCall(methodId, bootstrapMethodHandle.owner, bootstrapMethodHandle.name,
                    bootstrapMethodHandle.desc, InvocationKind.BOOTSTRAP, sourceLocation(currentLine), ordinal)
                bootstrapMethodArguments.filterIsInstance<Handle>().forEach { handle ->
                    when (handle.tag) {
                        Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC, Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC -> {
                            edges += GraphEdge(
                                methodId,
                                JvmNodeId.fieldId(handle.owner, handle.name, handle.desc),
                                EdgeKind.FIELD_ACCESS,
                            )
                            edges += GraphEdge(methodId, JvmNodeId.classId(handle.owner), EdgeKind.REFERENCE)
                        }
                        Opcodes.H_INVOKEVIRTUAL,
                        Opcodes.H_INVOKESTATIC,
                        Opcodes.H_INVOKESPECIAL,
                        Opcodes.H_NEWINVOKESPECIAL,
                        Opcodes.H_INVOKEINTERFACE,
                        -> {
                            val kind = when (handle.tag) {
                                Opcodes.H_INVOKESTATIC -> InvocationKind.STATIC
                                Opcodes.H_INVOKEINTERFACE -> InvocationKind.INTERFACE
                                Opcodes.H_INVOKEVIRTUAL -> InvocationKind.VIRTUAL
                                else -> InvocationKind.SPECIAL
                            }
                            calls += ExternalCall(methodId, handle.owner, handle.name, handle.desc, kind, sourceLocation(currentLine), ordinal)
                            edges += GraphEdge(
                                methodId,
                                JvmNodeId.methodId(handle.owner, handle.name, handle.desc),
                                EdgeKind.CALL,
                            )
                            edges += GraphEdge(methodId, JvmNodeId.classId(handle.owner), EdgeKind.REFERENCE)
                        }
                    }
                }
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                val targetTypes = if (type.startsWith('[')) descriptorClassNames(type) else setOf(type)
                targetTypes.forEach { target ->
                    edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
                }
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                type?.let { target ->
                    edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
                }
            }

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                descriptorClassNames(descriptor).forEach { target ->
                    edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
                }
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is Type && value.sort in setOf(Type.OBJECT, Type.ARRAY)) {
                    descriptorClassNames(value.descriptor).forEach { target ->
                        edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
                    }
                }
            }

            override fun visitEnd() {
                nodes += GraphNode(
                    id = methodId,
                    name = name,
                    kind = if (name == "<init>") NodeKind.CONSTRUCTOR else NodeKind.METHOD,
                    jvmSignature = methodId.value.removePrefix("method:"),
                    location = sourceLocation(firstLine),
                    visibility = access.toVisibility(),
                    jvmVisibility = access.toVisibility(),
                    jvmModifiers = access.toJvmModifiers(),
                    annotations = annotations,
                    synthesized = access.isSynthetic() || name == "<clinit>" || internalName.isAndroidGeneratedClass(),
                )
            }
        }
    }

    override fun visitEnd() {
        val classId = JvmNodeId.classId(internalName)
        val effectiveClassAccess = innerClassAccess ?: classAccess
        nodes += GraphNode(
            id = classId,
            name = internalName.substringAfterLast('/'),
            kind = effectiveClassAccess.toNodeKind(),
            jvmSignature = internalName,
            location = sourceLocation(),
            visibility = effectiveClassAccess.toVisibility(),
            jvmVisibility = effectiveClassAccess.toVisibility(),
            jvmModifiers = effectiveClassAccess.toJvmModifiers(),
            annotations = classAnnotations,
            supertypes = supertypes,
            synthesized = effectiveClassAccess.isSynthetic() || internalName.isAndroidGeneratedClass(),
        )
    }

    fun facts(): ClassFacts {
        val facts = ClassFacts(internalName, nodes, edges, enclosingClass,
            ClassRuntimeObservation(sourceLocation()?.path, FileTime.fromMillis(0),
                nativeMethods, reflectionCalls, dynamicRegistrations), calls)
        val metadata = metadataValues?.toMetadata() ?: return facts
        return KotlinMetadataEnricher.enrich(facts, metadata)
    }

    private fun annotationEdge(source: NodeId, descriptor: String): GraphEdge = GraphEdge(
        source,
        JvmNodeId.classId(Type.getType(descriptor).internalName),
        EdgeKind.ANNOTATION,
    )

    private fun annotationInternalName(descriptor: String): String = Type.getType(descriptor).internalName

    /**
     * 어노테이션 member 값으로 참조되는 class를 선언과 REFERENCE 간선으로 연결한다.
     * 중첩 어노테이션과 배열 값을 재귀적으로 따라가지만 값이 없는 primitive·문자열은 무시한다.
     */
    private inner class AnnotationValueVisitor(private val source: NodeId, private val defaultOwner: NodeId? = null) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String?, value: Any?) {
            if (value is Type) addTypeReference(value)
        }

        override fun visitEnum(name: String?, descriptor: String?, value: String?) {
            descriptor?.let { addReference(Type.getType(it).internalName) }
        }

        override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
            descriptor?.let { addReference(Type.getType(it).internalName) }
            return this
        }

        override fun visitArray(name: String?): AnnotationVisitor = this

        private fun addTypeReference(type: Type) {
            val elementType = if (type.sort == Type.ARRAY) type.elementType else type
            if (elementType.sort == Type.OBJECT) addReference(elementType.internalName)
        }

        private fun addReference(internalName: String) {
            edges += GraphEdge(source, JvmNodeId.classId(internalName), EdgeKind.REFERENCE)
            defaultOwner?.let { owner -> edges += GraphEdge(owner, JvmNodeId.classId(internalName), EdgeKind.REFERENCE) }
        }
    }

    /**
     * JVM `SourceFile` attribute는 임의 문자열이라 컴파일러나 후처리 도구가 절대경로를 남길 수 있다.
     * 그래프에 들어가기 전에 파일 이름 성분만 남겨, 보고서·교환 문서·baseline 지문 어디로도 빌드 기계의
     * 로컬 경로가 흘러가지 않게 한다. 남는 이름이 없으면 위치를 만들지 않는다.
     */
    private fun sourceLocation(line: Int? = null): SourceLocation? =
        sourceFile?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf(String::isNotBlank)
            ?.let { SourceLocation(it, line?.takeIf { value -> value > 0 }) }
}

internal data class ClassFacts(
    val internalName: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val enclosingClass: String? = null,
    val runtime: ClassRuntimeObservation = ClassRuntimeObservation(),
    val calls: List<ExternalCall> = emptyList(),
    val runtimeMethods: List<MethodNode> = emptyList(),
    val returnMethods: List<MethodNode> = emptyList(),
)

// CLASS-retention 생성 marker는 이름만 닮은 사용자 선언을 숨기지 않는다.
private val GENERATED_MARKERS = setOf(
    "dagger/internal/DaggerGenerated",
    "dagger/hilt/codegen/OriginatingElement",
    "dagger/hilt/processor/internal/aggregateddeps/AggregatedDeps",
    "dagger/hilt/internal/componenttreedeps/ComponentTreeDeps",
    "dagger/hilt/internal/aggregatedroot/AggregatedRoot",
    "dagger/hilt/internal/processedrootsentinel/ProcessedRootSentinel",
)

private fun ClassFacts.asSynthesized(): ClassFacts = copy(
    nodes = nodes.map { node -> node.copy(synthesized = true) },
)

private fun ClassFacts.generatedSiblingNames(): Set<String> {
    val annotations = nodes.firstOrNull { node -> node.id == JvmNodeId.classId(internalName) }?.annotations.orEmpty()
    return GeneratedSiblingNaming.candidatesFor(internalName, annotations)
}

private fun projectOverrideEdges(classFacts: List<ClassFacts>): List<GraphEdge> {
    val factsByName = classFacts.associateBy(ClassFacts::internalName)
    val methodsByOwner = classFacts.associate { facts ->
        facts.internalName to facts.nodes.filter(GraphNode::isOverrideCandidate)
            .mapNotNull { node -> node.memberSignature?.let { signature -> signature to node } }
            .toMap()
    }
    return classFacts.flatMap { implementation ->
        val implementationMethods = methodsByOwner.getValue(implementation.internalName)
        implementation.projectSupertypes(factsByName).flatMap { supertype ->
            val superMethods = methodsByOwner[supertype].orEmpty()
            implementationMethods.mapNotNull { (signature, method) ->
                val superMethod = superMethods[signature]?.takeIf { candidate ->
                    candidate.isVisibleToImplementation(supertype, implementation.internalName)
                } ?: return@mapNotNull null
                GraphEdge(superMethod.id, method.id, EdgeKind.OVERRIDE)
            }
        }
    }
}

private fun frameworkCallbackEdges(classFacts: List<ClassFacts>): List<GraphEdge> {
    val factsByName = classFacts.associateBy(ClassFacts::internalName)
    return classFacts.flatMap { facts ->
        if (facts.projectSupertypes(factsByName).none(RUNTIME_CALLBACK_TYPES::contains)) return@flatMap emptyList()
        val classId = JvmNodeId.classId(facts.internalName)
        facts.nodes.filter(GraphNode::isRuntimeCallbackMember)
            .map { member -> GraphEdge(classId, member.id, EdgeKind.REFERENCE, origin = EdgeOrigin.RUNTIME_MODEL) }
    }
}

// 중첩 class의 사용 사실만으로는 바깥 container가 죽어 보이지 않게 실제 enclosing 관계를 참조로 연결한다.
private fun enclosingContainerEdges(classFacts: List<ClassFacts>): List<GraphEdge> = classFacts.mapNotNull { facts ->
    val enclosingClass = facts.enclosingClass
    if (enclosingClass == null || enclosingClass == facts.internalName) return@mapNotNull null
    GraphEdge(JvmNodeId.classId(facts.internalName), JvmNodeId.classId(enclosingClass), EdgeKind.REFERENCE)
}

private fun ClassFacts.projectSupertypes(factsByName: Map<String, ClassFacts>): Set<String> {
    val directSupertypes = nodes.firstOrNull { node -> node.id == JvmNodeId.classId(internalName) }
        ?.supertypes.orEmpty()
    val pending = ArrayDeque(directSupertypes)
    return buildSet {
        while (pending.isNotEmpty()) {
            val supertype = pending.removeFirst()
            if (!add(supertype)) continue
            factsByName[supertype]?.nodes
                ?.firstOrNull { node -> node.id == JvmNodeId.classId(supertype) }
                ?.supertypes
                ?.let(pending::addAll)
        }
    }
}

private val GraphNode.memberSignature: String?
    get() = jvmSignature?.substringAfter('#', missingDelimiterValue = "")?.takeIf(String::isNotEmpty)

private fun GraphNode.isOverrideCandidate(): Boolean =
    kind == NodeKind.METHOD && jvmVisibility != Visibility.PRIVATE && JvmModifier.STATIC !in jvmModifiers

private fun GraphNode.isVisibleToImplementation(owner: String, implementation: String): Boolean =
    jvmVisibility != Visibility.PACKAGE_PRIVATE || owner.substringBeforeLast('/') == implementation.substringBeforeLast('/')

private fun GraphNode.isRuntimeCallbackMember(): Boolean =
    kind in RUNTIME_CALLBACK_MEMBER_KINDS &&
        jvmVisibility != Visibility.PRIVATE &&
        JvmModifier.STATIC !in jvmModifiers

private val RUNTIME_CALLBACK_TYPES = setOf(
    "android/hardware/camera2/CameraCaptureSession\$CaptureCallback",
    "android/hardware/camera2/CameraCaptureSession\$StateCallback",
    "android/hardware/camera2/CameraDevice\$StateCallback",
    "android/hardware/camera2/CameraManager\$AvailabilityCallback",
    "android/webkit/WebViewClient",
    "androidx/lifecycle/ViewModel",
    "androidx/lifecycle/ViewModelProvider\$Factory",
    "org/objectweb/asm/tree/analysis/Interpreter",
    "org/objectweb/asm/AnnotationVisitor",
    "org/objectweb/asm/ClassVisitor",
    "org/objectweb/asm/FieldVisitor",
    "org/objectweb/asm/MethodVisitor",
)

private val RUNTIME_CALLBACK_MEMBER_KINDS = setOf(NodeKind.CONSTRUCTOR, NodeKind.METHOD)

private fun Int.isSynthetic(): Boolean = this and Opcodes.ACC_SYNTHETIC != 0

private fun descriptorClassNames(descriptor: String): Set<String> = buildSet {
    if (descriptor.startsWith('(')) {
        Type.getArgumentTypes(descriptor).forEach { type -> addDescriptorType(type) }
        addDescriptorType(Type.getReturnType(descriptor))
    } else {
        addDescriptorType(Type.getType(descriptor))
    }
}

private fun MutableSet<String>.addDescriptorType(type: Type) {
    val elementType = if (type.sort == Type.ARRAY) type.elementType else type
    if (elementType.sort == Type.OBJECT) add(elementType.internalName)
}

// Class.forName 인자 binary name이나 배열 descriptor를 internal name으로 바꾼다. class 이름이 아니면 무시한다.
private fun String.isAndroidGeneratedClass(): Boolean {
    val simpleName = substringAfterLast('/')
    return simpleName == "BuildConfig" || simpleName == "BR" || simpleName == "R" ||
        simpleName.startsWith("R$") || simpleName == "Manifest" || simpleName.startsWith("Manifest$")
}

private fun Int.toJvmModifiers(): Set<JvmModifier> = buildSet {
    if (this@toJvmModifiers and Opcodes.ACC_FINAL != 0) add(JvmModifier.FINAL)
    if (this@toJvmModifiers and Opcodes.ACC_ABSTRACT != 0) add(JvmModifier.ABSTRACT)
    if (this@toJvmModifiers and Opcodes.ACC_SYNTHETIC != 0) add(JvmModifier.SYNTHETIC)
    if (this@toJvmModifiers and Opcodes.ACC_NATIVE != 0) add(JvmModifier.NATIVE)
    if (this@toJvmModifiers and Opcodes.ACC_STATIC != 0) add(JvmModifier.STATIC)
}

private fun Int.toNodeKind(): NodeKind = when {
    this and Opcodes.ACC_ANNOTATION != 0 -> NodeKind.ANNOTATION_CLASS
    this and Opcodes.ACC_ENUM != 0 -> NodeKind.ENUM
    this and Opcodes.ACC_INTERFACE != 0 -> NodeKind.INTERFACE
    else -> NodeKind.CLASS
}

private fun Int.toVisibility(): Visibility = when {
    this and Opcodes.ACC_PUBLIC != 0 -> Visibility.PUBLIC
    this and Opcodes.ACC_PROTECTED != 0 -> Visibility.PROTECTED
    this and Opcodes.ACC_PRIVATE != 0 -> Visibility.PRIVATE
    else -> Visibility.PACKAGE_PRIVATE
}
