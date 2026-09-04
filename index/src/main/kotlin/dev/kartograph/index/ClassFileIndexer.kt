package dev.kartograph.index

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
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

/** class root의 JVM 산출물을 읽어 구조와 instruction 관계로 된 코드 그래프를 만든다. */
public class ClassFileIndexer {
    /**
     * 각 root를 재귀 탐색하고 JVM class name이 같은 중복 산출물은 첫 번째 것만 사용한다.
     * class 하나라도 깨졌으면 불완전한 그래프를 반환하지 않는다.
     */
    public fun index(classRoots: Iterable<Path>): CodeGraph {
        val factsByClass = linkedMapOf<String, ClassFacts>()
        classRoots.forEach { root ->
            readRoot(root).forEach { facts ->
                factsByClass.putIfAbsent(facts.internalName, facts)
            }
        }
        val generatedSiblingNames = factsByClass.values.flatMapTo(mutableSetOf(), ClassFacts::generatedSiblingNames)
        val classFacts = factsByClass.values.map { facts ->
            if (facts.internalName in generatedSiblingNames) facts.asSynthesized() else facts
        }
        return CodeGraph(
            nodes = classFacts.flatMap(ClassFacts::nodes),
            edges = classFacts.flatMap(ClassFacts::edges) +
                projectOverrideEdges(classFacts) +
                frameworkCallbackEdges(classFacts),
        )
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
        val visitor = FactsVisitor()
        ClassReader(Files.readAllBytes(classFile)).accept(visitor, 0)
        visitor.facts()
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
                        val visitor = FactsVisitor()
                        ClassReader(input).accept(visitor, 0)
                        val facts = visitor.facts()
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

    private fun isClassFile(path: Path): Boolean =
        Files.isRegularFile(path) && path.fileName.toString().endsWith(".class")
}

private class FactsVisitor : ClassVisitor(Opcodes.ASM9) {
    private lateinit var internalName: String
    private var classAccess: Int = 0
    private var innerClassAccess: Int? = null
    private var sourceFile: String? = null
    private var metadataValues: MetadataAnnotationValues? = null
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val classAnnotations = mutableSetOf<String>()
    private val supertypes = mutableSetOf<String>()

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
        if (name == internalName) innerClassAccess = access
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        classAnnotations += annotationInternalName(descriptor)
        edges += annotationEdge(JvmNodeId.classId(internalName), descriptor)
        if (descriptor == "Lkotlin/Metadata;") {
            return MetadataAnnotationValues().also { metadataValues = it }
        }
        return null
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
        descriptorClassNames(descriptor).forEach { target ->
            edges += GraphEdge(fieldId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
        }
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? {
                annotations += annotationInternalName(annotationDescriptor)
                edges += annotationEdge(fieldId, annotationDescriptor)
                return null
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
        val methodId = JvmNodeId.methodId(internalName, name, descriptor)
        val annotations = mutableSetOf<String>()
        edges += GraphEdge(JvmNodeId.classId(internalName), methodId, EdgeKind.MEMBER)
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

            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor? {
                annotations += annotationInternalName(annotationDescriptor)
                edges += annotationEdge(methodId, annotationDescriptor)
                return null
            }

            override fun visitLineNumber(line: Int, start: Label) {
                if (firstLine == null) firstLine = line
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                targetName: String,
                targetDescriptor: String,
                isInterface: Boolean,
            ) {
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
        val facts = ClassFacts(internalName, nodes, edges)
        val metadata = metadataValues?.toMetadata() ?: return facts
        return KotlinMetadataEnricher.enrich(facts, metadata)
    }

    private fun annotationEdge(source: NodeId, descriptor: String): GraphEdge = GraphEdge(
        source,
        JvmNodeId.classId(Type.getType(descriptor).internalName),
        EdgeKind.ANNOTATION,
    )

    private fun annotationInternalName(descriptor: String): String = Type.getType(descriptor).internalName

    private fun sourceLocation(line: Int? = null): SourceLocation? =
        sourceFile?.takeIf(String::isNotBlank)?.let { SourceLocation(it, line?.takeIf { value -> value > 0 }) }
}

internal data class ClassFacts(
    val internalName: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
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
            .map { member -> GraphEdge(classId, member.id, EdgeKind.REFERENCE) }
    }
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
