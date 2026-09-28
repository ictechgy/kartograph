package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * compiled class root(디렉터리·JAR)에서 Spring 매핑에 필요한 선언을 읽는다.
 *
 * 바이트코드 어노테이션 값은 컴파일러가 상수를 이미 접어 넣은 값이다 — Java `static final String`과 Kotlin
 * `const val`(최상위·object·companion, 문자열 템플릿·연결 포함)이 리터럴로 들어 있음을 javap로 확인했다
 * (스파이크 S5, `docs/SPRING-ROUTES.md`). 그래서 값 원천으로는 소스보다 이 reader를 우선한다.
 */
internal class SpringBytecodeReader(private val classRoots: List<Path>) {
    /** 모든 class root의 타입을 읽는다. 같은 이름이 여러 root에 있으면 앞 root가 이긴다. */
    fun read(): List<SpringType> {
        val types = linkedMapOf<String, SpringType>()
        classRoots.forEach { root -> classBytes(root).forEach { bytes -> parse(bytes)?.let { types.putIfAbsent(it.name, it) } } }
        return types.values.toList()
    }

    private fun classBytes(root: Path): Sequence<ByteArray> = when {
        Files.isDirectory(root) -> Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }.sorted().toList()
        }.asSequence().map(Files::readAllBytes)
        Files.isRegularFile(root) && root.fileName.toString().endsWith(".jar", ignoreCase = true) -> jarBytes(root).asSequence()
        else -> emptySequence()
    }

    private fun jarBytes(jar: Path): List<ByteArray> = JarFile(jar.toFile(), false).use { archive ->
        archive.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
            .sortedBy { it.name }.map { entry -> archive.getInputStream(entry).use { it.readBytes() } }.toList()
    }

    private fun parse(bytes: ByteArray): SpringType? {
        val visitor = SpringClassVisitor()
        ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES)
        return visitor.type()
    }
}

/** class 하나를 [SpringType]으로 모은다. 모듈·패키지 정보 class와 지역·익명 class는 버린다. */
private class SpringClassVisitor : ClassVisitor(Opcodes.ASM9) {
    private var internalName = ""
    private var access = 0
    private var superName: String? = null
    private var interfaces: List<String> = emptyList()
    private var sourceFile: String? = null
    private var local = false
    private val annotations = mutableListOf<SpringAnnotation>()
    private val methods = mutableListOf<SpringMethod>()

    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
        this.internalName = name
        this.access = access
        this.superName = superName
        this.interfaces = interfaces.orEmpty().toList()
    }

    override fun visitSource(source: String?, debug: String?) { sourceFile = source }

    override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) { local = true }

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
        if (!visible) null else SpringAnnotationCollector(descriptor) { annotations += it.annotation }

    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
        if (name == "<clinit>" || name == "<init>") return null
        return SpringMethodCollector(access, name, descriptor) { methods += it }
    }

    fun type(): SpringType? {
        if (local || internalName == "module-info" || internalName.endsWith("/package-info")) return null
        val kind = when {
            access and Opcodes.ACC_ANNOTATION != 0 -> SpringTypeKind.ANNOTATION
            access and Opcodes.ACC_INTERFACE != 0 -> SpringTypeKind.INTERFACE
            access and Opcodes.ACC_ENUM != 0 -> SpringTypeKind.ENUM
            else -> SpringTypeKind.CLASS
        }
        return SpringType(dotted(internalName), internalName, kind, access and Opcodes.ACC_ABSTRACT != 0,
            superName?.let(::dotted), interfaces.map(::dotted), annotations.toList(), methods.toList(), sourceFileName = sourceFile)
    }
}

/** 메서드 하나의 어노테이션·`@AliasFor`·기본값·첫 줄을 모은다. */
private class SpringMethodCollector(
    private val access: Int,
    private val name: String,
    private val descriptor: String,
    private val done: (SpringMethod) -> Unit,
) : MethodVisitor(Opcodes.ASM9) {
    private val annotations = mutableListOf<SpringAnnotation>()
    private val aliases = mutableListOf<SpringAliasTarget>()
    private var defaultValue: SpringValue? = null
    private var firstLine: Int? = null

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
        if (!visible) return null
        return SpringAnnotationCollector(descriptor) { collected ->
            if (collected.annotation.type == SpringAnnotations.ALIAS_FOR) aliases += collected.alias() else annotations += collected.annotation
        }
    }

    override fun visitAnnotationDefault(): AnnotationVisitor = SpringValueCollector { defaultValue = it }

    override fun visitLineNumber(line: Int, start: Label) {
        if (firstLine == null || line < firstLine!!) firstLine = line
    }

    override fun visitEnd() {
        val arguments = Type.getArgumentTypes(descriptor)
        val continuation = arguments.lastOrNull()?.internalName == "kotlin/coroutines/Continuation"
        done(SpringMethod(name, descriptor, arguments.size - if (continuation) 1 else 0, annotations.toList(),
            isAbstract = access and Opcodes.ACC_ABSTRACT != 0,
            isSynthetic = access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) != 0,
            firstLine = firstLine, aliases = aliases.toList(), defaultValue = defaultValue))
    }
}

/** 어노테이션 하나의 수집 결과다. `@AliasFor`의 `annotation` 속성(class 값)도 함께 둔다. */
private class CollectedAnnotation(val annotation: SpringAnnotation, private val classValues: Map<String, String>) {
    /** `@AliasFor`면 대상 어노테이션·속성이다. `value`·`attribute`는 서로 별칭이다. */
    fun alias(): SpringAliasTarget {
        val attribute = listOf("attribute", "value").firstNotNullOfOrNull { key ->
            (annotation.attributes[key] as? SpringValue.Strings)?.values?.singleOrNull()?.takeIf(String::isNotEmpty)
        }.orEmpty()
        return SpringAliasTarget(classValues["annotation"]?.takeUnless { it == "java.lang.annotation.Annotation" }, attribute)
    }
}

/** 어노테이션 속성 값을 모은다. 중첩 어노테이션 값은 매핑에 쓰지 않아 버린다. */
private class SpringAnnotationCollector(
    descriptor: String,
    private val done: (CollectedAnnotation) -> Unit,
) : AnnotationVisitor(Opcodes.ASM9) {
    private val type = dotted(Type.getType(descriptor).internalName)
    private val attributes = linkedMapOf<String, SpringValue>()
    private val classValues = linkedMapOf<String, String>()

    override fun visit(name: String?, value: Any?) {
        when (value) {
            is String -> attributes[name ?: "value"] = SpringValue.Strings(listOf(value))
            is Type -> classValues[name ?: "value"] = dotted(value.internalName)
        }
    }

    override fun visitEnum(name: String?, descriptor: String?, value: String) {
        attributes[name ?: "value"] = SpringValue.Enums(listOf(value))
    }

    override fun visitArray(name: String?): AnnotationVisitor = SpringValueCollector { attributes[name ?: "value"] = it }

    override fun visitEnd() = done(CollectedAnnotation(SpringAnnotation(type, attributes.toMap()), classValues.toMap()))
}

/** 배열·기본값 하나를 [SpringValue]로 모은다. 문자열과 enum 외의 원소가 섞이면 버린다. */
private class SpringValueCollector(private val done: (SpringValue) -> Unit) : AnnotationVisitor(Opcodes.ASM9) {
    private val strings = mutableListOf<String>()
    private val enums = mutableListOf<String>()

    override fun visit(name: String?, value: Any?) { if (value is String) strings += value }

    override fun visitEnum(name: String?, descriptor: String?, value: String) { enums += value }

    override fun visitArray(name: String?): AnnotationVisitor = this

    override fun visitEnd() = done(if (enums.isNotEmpty()) SpringValue.Enums(enums.toList()) else SpringValue.Strings(strings.toList()))
}

/** JVM internal name을 점 이름으로 바꾼다. 중첩 타입의 `$`도 점으로 바꿔 소스 이름과 맞춘다. */
internal fun dotted(internalName: String): String = internalName.replace('/', '.').replace('$', '.')
