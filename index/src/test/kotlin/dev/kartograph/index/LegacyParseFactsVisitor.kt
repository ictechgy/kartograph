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
import dev.kartograph.core.LexicalEnclosure
import dev.kartograph.core.CallbackArgument
import dev.kartograph.core.ParameterUse
import dev.kartograph.core.LambdaEscape
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

/** b7ab3e9의 raw visitor를 고정한 test oracle다. 최적화 구현을 호출하지 않는다. */
internal class LegacyParseFactsVisitor : ClassVisitor(Opcodes.ASM9) {
    private lateinit var internalName: String
    private var enclosingClass: String? = null
    // EnclosingMethod 속성의 감싼 메서드(초기화 문맥이면 class)다. 지역·익명 class에만 있다.
    private var enclosingDeclaration: NodeId? = null
    private var classAccess: Int = 0
    private var capturesEnumInventory = false
    private val enumConstants = mutableListOf<NodeId>()
    private var innerClassAccess: Int? = null
    private var sourceFile: String? = null
    private var sourceDebugMapper: LegacyParseSourceDebugMapper? = null
    private var sourceDebugPresent = false
    private var metadataValues: MetadataAnnotationValues? = null
    private val nodes = mutableListOf<GraphNode>()
    private val edges = mutableListOf<GraphEdge>()
    private val calls = mutableListOf<ExternalCall>()
    private val fieldWriteMethods = mutableSetOf<NodeId>()
    private val constantStringFields = mutableMapOf<NodeId, String>()
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
        // 상수별 익명 subclass와 Kotlin metadata의 enum-entry kind만으로 빈 enum을 확정하지 않는다.
        capturesEnumInventory = access and Opcodes.ACC_ENUM != 0 && superName == "java/lang/Enum"
        if (superName != null && superName != "java/lang/Object") supertypes += superName
        supertypes += interfaces
        if (superName != null && superName != "java/lang/Object") {
            edges += GraphEdge(JvmNodeId.classId(name), JvmNodeId.classId(superName), EdgeKind.INHERITANCE)
        }
        interfaces.forEach { interfaceName ->
            edges += GraphEdge(JvmNodeId.classId(name), JvmNodeId.classId(interfaceName), EdgeKind.INHERITANCE)
        }
        // 상속 자체는 위 간선에 이미 있으므로 지워진 타입 인자·bound만 선언 참조로 더한다.
        (signatureClassNames(signature) - supertypes - "java/lang/Object").forEach { target ->
            edges += GraphEdge(JvmNodeId.classId(name), JvmNodeId.classId(target), EdgeKind.REFERENCE)
        }
    }

    override fun visitSource(source: String?, debug: String?) {
        sourceFile = source
        sourceDebugPresent = debug?.startsWith("SMAP") == true
        sourceDebugMapper = if (sourceDebugPresent) LegacyParseSourceDebugMapper.parse(debug) else null
    }

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
        if (name == internalName) {
            innerClassAccess = access
            if (outerName != null) enclosingClass = outerName
        }
    }

    override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
        enclosingClass = owner
        enclosingDeclaration = if (name != null && descriptor != null) JvmNodeId.methodId(owner, name, descriptor)
            else JvmNodeId.classId(owner)
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
        val enumConstant = access and Opcodes.ACC_ENUM != 0
        require(!enumConstant || capturesEnumInventory && descriptor == "L$internalName;") {
            "enum constant does not match its enum owner"
        }
        if (enumConstant) {
            require(fieldId !in enumConstants) { "duplicate enum constant field" }
            enumConstants += fieldId
        }
        if (value is String && descriptor == "Ljava/lang/String;" && access and Opcodes.ACC_STATIC != 0) {
            constantStringFields[fieldId] = value
        }
        val annotations = mutableSetOf<String>()
        edges += GraphEdge(JvmNodeId.classId(internalName), fieldId, EdgeKind.MEMBER)
        edges += GraphEdge(fieldId, JvmNodeId.classId(internalName), EdgeKind.REFERENCE)
        (legacyParseDescriptorClassNames(descriptor) + signatureClassNames(signature, typeOnly = true) - internalName).forEach { target ->
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
                    attributes = buildSet {
                        if (value != null) add(NodeAttribute.COMPILE_TIME_CONSTANT)
                        if (enumConstant) add(NodeAttribute.ENUM_CONSTANT)
                    },
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
        (legacyParseDescriptorClassNames(descriptor) + signatureClassNames(signature) + exceptions.orEmpty() - internalName).forEach { target ->
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
                    callSiteLines = callSiteLine(currentLine)?.let(::listOf).orEmpty(),
                )
                edges += GraphEdge(methodId, JvmNodeId.classId(owner), EdgeKind.REFERENCE)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, targetName: String, targetDescriptor: String) {
                if (opcode == Opcodes.PUTSTATIC && targetDescriptor.startsWith("L")) fieldWriteMethods += methodId
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
                edges += GraphEdge(
                    methodId,
                    JvmNodeId.methodId(bootstrapMethodHandle.owner, bootstrapMethodHandle.name, bootstrapMethodHandle.desc),
                    EdgeKind.CALL,
                )
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
                val targetTypes = if (type.startsWith('[')) legacyParseDescriptorClassNames(type) else setOf(type)
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
                legacyParseDescriptorClassNames(descriptor).forEach { target ->
                    edges += GraphEdge(methodId, JvmNodeId.classId(target), EdgeKind.REFERENCE)
                }
            }

            override fun visitLdcInsn(value: Any?) {
                if (value is Type && value.sort in setOf(Type.OBJECT, Type.ARRAY)) {
                    legacyParseDescriptorClassNames(value.descriptor).forEach { target ->
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
            jvmModifiers = effectiveClassAccess.toJvmModifiers().let { modifiers ->
                if (classAccess and Opcodes.ACC_FINAL != 0) modifiers + JvmModifier.FINAL
                else modifiers - JvmModifier.FINAL
            },
            annotations = classAnnotations,
            supertypes = supertypes,
            synthesized = effectiveClassAccess.isSynthetic() || internalName.isAndroidGeneratedClass(),
            enumConstants = if (capturesEnumInventory) enumConstants.sorted() else null,
        )
    }

    fun facts(): ClassFacts {
        val facts = ClassFacts(internalName, nodes, edges, enclosingClass,
            ClassRuntimeObservation(sourceLocation()?.path, FileTime.fromMillis(0),
                nativeMethods, reflectionCalls, dynamicRegistrations), calls, fieldWriteMethods = fieldWriteMethods,
            constantStringFields = constantStringFields, enclosingDeclaration = enclosingDeclaration)
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

    /** 새 호출부 근거만 SMAP으로 확인한다. 같은 basename의 inline body도 caller 위치로 오인하지 않게 identity만 쓴다. */
    private fun callSiteLine(line: Int?): Int? {
        if (line == null || line !in 1..65535) return null
        if (!sourceDebugPresent) return sourceLocation(line)?.line
        val mapped = sourceDebugMapper?.location(line) ?: return null
        val ownPath = sourceLocation()?.path ?: return null
        // primary SMAP의 output/input identity만 확정적인 caller 위치다. KotlinDebug stratum이나 inline 매핑은 추정하지 않는다.
        return mapped.line?.takeIf { mapped.path == ownPath && it == line && it in 1..65535 }
    }
}

/** Kotlin SMAP의 output line을 input source basename과 line으로만 복원한다. */
private class LegacyParseSourceDebugMapper private constructor(private val lines: Map<Int, SourceLocation?>) {
    fun location(outputLine: Int): SourceLocation? = lines[outputLine]

    companion object {
        private const val MAX_DEBUG_BYTES = 1_048_576
        private const val MAX_MAPPED_LINES = 100_000
        private val linePattern = Regex("^(\\d+)#(\\d+)(?:,(\\d+))?:(\\d+)(?:,(\\d+))?$")

        fun parse(debug: String?): LegacyParseSourceDebugMapper? {
            if (debug == null || debug.toByteArray(Charsets.UTF_8).size > MAX_DEBUG_BYTES || !debug.startsWith("SMAP")) return null
            val rows = debug.split(Regex("\\r?\\n"))
            val defaultStratum = rows.getOrNull(2)?.trimEnd('\r')?.takeIf(String::isNotBlank) ?: return null
            val files = mutableMapOf<Int, SmapFile>()
            val basenames = mutableMapOf<String, String>()
            val ambiguousBasenames = mutableSetOf<String>()
            val mapped = mutableMapOf<Int, SourceLocation?>()
            var section = ""
            var hasStratum = false
            var activeStratum = false
            var index = 0
            while (index < rows.size) {
                val row = rows[index].trimEnd('\r')
                when (row) {
                    "*F" -> if (!hasStratum || activeStratum) section = "files"
                    "*L" -> if (!hasStratum || activeStratum) section = "lines"
                    "*E" -> break
                    else -> if (row.startsWith("*S ")) {
                        hasStratum = true
                        activeStratum = row.removePrefix("*S ") == defaultStratum
                        section = ""
                    } else when (section) {
                        "files" -> {
                            val tokens = row.removePrefix("+").trim().split(Regex("\\s+"), limit = 3)
                            val id = tokens.firstOrNull()?.toIntOrNull() ?: return null
                            if (row.trimStart().startsWith("+")) {
                                if (index + 1 >= rows.size) return null
                                val fullPath = rows[++index].trimEnd('\r')
                                val logicalName = tokens.drop(1).joinToString(" ").substringAfterLast('/').substringAfterLast('\\')
                                val basename = logicalName.takeIf(String::isNotBlank) ?: fullPath.substringAfterLast('/').substringAfterLast('\\')
                                if (files.put(id, SmapFile(fullPath, basename)) != null) return null
                                val prior = basenames.putIfAbsent(basename, fullPath)
                                if (prior != null && prior != fullPath) ambiguousBasenames += basename
                            } else {
                                val fullPath = tokens.drop(1).joinToString(" ")
                                val basename = fullPath.substringAfterLast('/').substringAfterLast('\\').takeIf(String::isNotBlank) ?: return null
                                if (files.put(id, SmapFile(fullPath, basename)) != null) return null
                                val prior = basenames.putIfAbsent(basename, fullPath)
                                if (prior != null && prior != fullPath) ambiguousBasenames += basename
                            }
                        }
                        "lines" -> {
                            val match = linePattern.matchEntire(row) ?: return null
                            val inputStart = match.groupValues[1].toLongOrNull() ?: return null
                            val fileId = match.groupValues[2].toIntOrNull() ?: return null
                            val repeat = match.groupValues[3].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 1L
                            val outputStart = match.groupValues[4].toLongOrNull() ?: return null
                            val outputIncrement = match.groupValues[5].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 1L
                            val file = files[fileId] ?: return null
                            if (repeat < 1 || repeat > MAX_MAPPED_LINES || outputIncrement < 1 || mapped.size + repeat > MAX_MAPPED_LINES) return null
                            repeat(repeat.toInt()) { offset ->
                                val output = outputStart + offset.toLong() * outputIncrement
                                val input = inputStart + offset
                                if (output in 1..65535 && input in 1..65535 && file.basename !in ambiguousBasenames) {
                                    val location = SourceLocation(file.basename, input.toInt())
                                    if (mapped.containsKey(output.toInt()) && mapped[output.toInt()] != location) mapped[output.toInt()] = null
                                    else mapped[output.toInt()] = location
                                }
                            }
                        }
                    }
                }
                index++
            }
            return LegacyParseSourceDebugMapper(mapped)
        }

        private data class SmapFile(val fullPath: String, val basename: String)
    }
}

private fun Int.isSynthetic(): Boolean = this and Opcodes.ACC_SYNTHETIC != 0

private fun legacyParseDescriptorClassNames(descriptor: String): Set<String> = buildSet {
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
