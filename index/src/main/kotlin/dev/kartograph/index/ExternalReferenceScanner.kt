package dev.kartograph.index

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
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.ModuleVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath

/**
 * class root의 classfile에서 참조하는 모든 class internal 이름을 모은다.
 * 그래프를 만들지 않고 descriptor·annotation(type-use 포함)·invokedynamic·LDC·지역 변수까지 읽으며,
 * generic signature에만 있는 타입은 bytecode에 남지 않아 포함하지 않는다.
 */
public class ExternalReferenceScanner {
    /** 입력 root를 순서대로 읽고 중복 없는 참조 class 이름을 정렬해 반환한다. */
    public fun scan(classRoots: Iterable<Path>): Set<String> {
        val referenced = sortedSetOf<String>()
        classRoots.forEach { root ->
            when {
                root.isDirectory() -> referenced += scanDirectory(root)
                root.isRegularFile() && root.fileName.toString().endsWith(".jar", ignoreCase = true) -> referenced += scanJar(root)
                !root.exists() -> throw ClassIndexingException("class root does not exist; build the project before indexing")
                else -> throw ClassIndexingException("class root must be a class directory or JAR")
            }
        }
        return referenced
    }

    private fun scanDirectory(root: Path): Set<String> = try {
        val files = Files.walk(root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".class") }
                .sorted()
                .toList()
        }
        files.flatMapTo(sortedSetOf()) { file -> scanBytes(Files.readAllBytes(file)) }
    } catch (error: IOException) {
        throw ClassIndexingException("class root cannot be read", error)
    } catch (error: java.io.UncheckedIOException) {
        // Files.walk는 읽을 수 없는 하위 디렉터리에서 원시 경로를 담은 unchecked 예외를 낸다.
        throw ClassIndexingException("class root cannot be read", error)
    }

    private fun scanJar(jar: Path): Set<String> = try {
        JarFile(jar.toFile(), false).use { archive ->
            archive.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && entry.name.endsWith(".class") &&
                        !entry.name.startsWith("META-INF/versions/")
                }
                .sortedBy { entry -> entry.name }
                .flatMap { entry ->
                    archive.getInputStream(entry).use { input -> scanBytes(input.readBytes()).asSequence() }
                }
                .toSortedSet()
        }
    } catch (error: IOException) {
        throw ClassIndexingException("class JAR cannot be read", error)
    }

    private fun scanBytes(bytes: ByteArray): Set<String> = try {
        val visitor = ReferenceVisitor()
        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        visitor.referenced
    } catch (error: RuntimeException) {
        throw ClassIndexingException("invalid class file", error)
    }
}

internal class ReferenceVisitor : ClassVisitor(Opcodes.ASM9) {
    val referenced: MutableSet<String> = sortedSetOf()

    private fun add(owner: String?) {
        if (owner != null) referenced += owner
    }

    private fun addDescriptor(descriptor: String?) {
        descriptor?.let { referenced += descriptorClassNames(it) }
    }

    private fun addAnnotation(descriptor: String?): AnnotationVisitor {
        descriptor?.let { referenced += Type.getType(it).internalName }
        return ReferenceAnnotationVisitor(referenced)
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>,
    ) {
        add(superName)
        interfaces.forEach(::add)
    }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = addAnnotation(descriptor)

    override fun visitTypeAnnotation(
        typeRef: Int,
        typePath: TypePath?,
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor = addAnnotation(descriptor)

    override fun visitNestHost(nestHost: String) {
        add(nestHost)
    }

    override fun visitNestMember(nestMember: String) {
        add(nestMember)
    }

    override fun visitPermittedSubclass(permittedSubclass: String) {
        add(permittedSubclass)
    }

    override fun visitModule(name: String, access: Int, version: String?): ModuleVisitor =
        object : ModuleVisitor(Opcodes.ASM9) {
            override fun visitMainClass(mainClass: String?) {
                add(mainClass)
            }

            override fun visitUse(service: String) {
                add(service)
            }

            override fun visitProvide(service: String, providers: Array<out String>) {
                add(service)
                providers.forEach(::add)
            }
        }

    override fun visitRecordComponent(name: String, descriptor: String, signature: String?): RecordComponentVisitor {
        addDescriptor(descriptor)
        return object : RecordComponentVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                addAnnotation(annotationDescriptor)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                annotationDescriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(annotationDescriptor)
        }
    }

    override fun visitField(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        value: Any?,
    ): FieldVisitor {
        addDescriptor(descriptor)
        return object : FieldVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                addAnnotation(annotationDescriptor)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                annotationDescriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(annotationDescriptor)
        }
    }

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        addDescriptor(descriptor)
        exceptions.orEmpty().forEach(::add)
        return object : MethodVisitor(Opcodes.ASM9) {
            override fun visitAnnotationDefault(): AnnotationVisitor = ReferenceAnnotationVisitor(referenced)

            override fun visitAnnotation(annotationDescriptor: String, visible: Boolean): AnnotationVisitor =
                addAnnotation(annotationDescriptor)

            override fun visitTypeAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                annotationDescriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(annotationDescriptor)

            override fun visitParameterAnnotation(
                parameter: Int,
                annotationDescriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(annotationDescriptor)

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
            ) {
                add(owner)
                addDescriptor(descriptor)
            }

            override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                add(owner)
                addDescriptor(descriptor)
            }

            override fun visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bootstrapMethodHandle: Handle,
                vararg bootstrapMethodArguments: Any,
            ) {
                addDescriptor(descriptor)
                addHandle(bootstrapMethodHandle)
                bootstrapMethodArguments.forEach(::addConstant)
            }

            override fun visitTypeInsn(opcode: Int, type: String) {
                referenced += if (type.startsWith('[')) descriptorClassNames(type) else setOf(type)
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                add(type)
            }

            override fun visitTryCatchAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(descriptor)

            override fun visitInsnAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(descriptor)

            override fun visitLocalVariable(
                name: String,
                descriptor: String,
                signature: String?,
                start: Label,
                end: Label,
                index: Int,
            ) {
                addDescriptor(descriptor)
            }

            override fun visitLocalVariableAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                start: Array<out Label>,
                end: Array<out Label>,
                index: IntArray,
                descriptor: String,
                visible: Boolean,
            ): AnnotationVisitor = addAnnotation(descriptor)

            override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
                addDescriptor(descriptor)
            }

            override fun visitLdcInsn(value: Any?) {
                addConstant(value)
            }

            private fun addConstant(value: Any?) {
                when (value) {
                    is Type -> referenced += descriptorClassNames(value.descriptor)
                    is Handle -> addHandle(value)
                    is ConstantDynamic -> addDynamic(value)
                    else -> Unit
                }
            }

            private fun addHandle(handle: Handle) {
                add(handle.owner)
                addDescriptor(handle.desc)
            }

            private fun addDynamic(dynamic: ConstantDynamic) {
                referenced += descriptorClassNames(dynamic.descriptor)
                addHandle(dynamic.bootstrapMethod)
                (0 until dynamic.bootstrapMethodArgumentCount).forEach { index ->
                    addConstant(dynamic.getBootstrapMethodArgument(index))
                }
            }
        }
    }
}

internal class ReferenceAnnotationVisitor(private val referenced: MutableSet<String>) : AnnotationVisitor(Opcodes.ASM9) {
    override fun visit(name: String?, value: Any?) {
        when (value) {
            is Type -> referenced += descriptorClassNames(value.descriptor)
            is ConstantDynamic -> referenced += descriptorClassNames(value.descriptor)
            else -> Unit
        }
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String?) {
        descriptor?.let { referenced += Type.getType(it).internalName }
    }

    override fun visitAnnotation(name: String?, descriptor: String?): AnnotationVisitor {
        descriptor?.let { referenced += Type.getType(it).internalName }
        return this
    }

    override fun visitArray(name: String?): AnnotationVisitor = this
}
