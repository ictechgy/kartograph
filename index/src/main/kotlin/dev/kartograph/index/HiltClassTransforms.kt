package dev.kartograph.index

import dev.kartograph.core.TransformedClass
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/**
 * compiler 출력과 AGP PROJECT artifact 사이의 제한된 Hilt superclass 재작성만 검증한다.
 * 분석 대상 코드를 실행하지 않으며 transform task의 실행 인증을 대신하지 않는다.
 * ASM stack-map frame과 constant-pool 배치는 의미 비교에서 제외하되 원시 바이트 해시는 보존한다.
 */
public object HiltClassTransforms {
    public const val POLICY: String = "hilt-superclass-v1"
    public const val LIMITATION: String = "hilt-artifact-byte-relation: original compiler outputs and selected AGP artifacts " +
        "match a bounded superclass/invokespecial rewrite; observer task is not proof of Hilt transform execution; " +
        "stack-map frames and constant-pool layout are not compared; runtime behavior and other transforms are not assessed"
    private const val MAX_CLASSES = 100_000
    private const val MAX_ENTRIES = 200_000
    private const val MAX_CLASS_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** 원본과 선택된 class 목록이 같고 허용된 재작성만 있으면 원시 변경 목록을 반환한다. */
    public fun verify(originalRoots: List<Path>, selectedRoots: List<Path>, primarySources: Set<String>): List<TransformedClass> {
        val original = readClasses(originalRoots)
        val selected = readClasses(selectedRoots)
        require(original.isNotEmpty() && original.keys == selected.keys) { "transformed class inventory differs from compiler outputs" }
        return original.keys.sorted().mapNotNull { name ->
            val before = original.getValue(name)
            val after = selected.getValue(name)
            if (before.contentEquals(after)) return@mapNotNull null
            val node = node(before)
            val same = canonical(before).contentEquals(canonical(after))
            if (!same) {
                require(isHiltEntry(node)) { "unsupported class transformation" }
                val parent = generatedParent(node.name)
                val parentNode = original[parent]?.let(::node)
                require(parentNode != null && parentNode.superName == node.superName &&
                    parentNode.sourceFile == parent.substringAfterLast('/') + ".java" &&
                    parentNode.sourceFile !in primarySources) { "Hilt superclass is not a generated compiler output" }
                require(node.methods.none { it.name == "onReceive" &&
                    it.desc == "(Landroid/content/Context;Landroid/content/Intent;)V" }) {
                    "Hilt BroadcastReceiver injection is not supported by this observation policy"
                }
                require(canonical(before, node.superName to parent).contentEquals(canonical(after))) {
                    "class transformation changes more than the supported Hilt superclass rewrite"
                }
            }
            TransformedClass(name, digest(before), digest(after))
        }
    }

    /** witness가 기록한 원본 source 목록은 수동 Hilt_* 선언을 생성물로 오인하지 않기 위한 음성 근거다. */
    public fun primarySourceNames(paths: List<Path>): Set<String> = buildSet {
        var entries = 0
        paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) { "symbolic transformation source input" }
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.walk(path).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    require(++entries <= MAX_ENTRIES) { "transformation source inventory exceeds budget" }
                    val file = iterator.next()
                    require(!Files.isSymbolicLink(file)) { "symbolic transformation source input" }
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS) && isSource(file)) add(file.fileName.toString())
                }
            } else if (isSource(path)) add(path.fileName.toString())
        }
    }

    private fun isSource(path: Path): Boolean = path.fileName.toString().let { it.endsWith(".java") || it.endsWith(".kt") }
    private fun node(bytes: ByteArray): ClassNode = classInput { ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) } }
    private inline fun <T> classInput(read: () -> T): T = try { read() } catch (error: RuntimeException) {
        throw IllegalArgumentException("invalid transformation class input", error)
    }
    private fun isHiltEntry(node: ClassNode): Boolean = (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
        .any { it.desc in setOf("Ldagger/hilt/android/AndroidEntryPoint;", "Ldagger/hilt/android/HiltAndroidApp;") }
    private fun generatedParent(name: String): String = name.substringBeforeLast('/', "").let { pkg ->
        (if (pkg.isEmpty()) "" else "$pkg/") + "Hilt_" + name.substringAfterLast('/').replace('$', '_')
    }

    /** 새 constant pool을 만들고 정확한 Hilt super 호출만 바꿔 두 classfile의 나머지 내용을 대조한다. */
    private fun canonical(bytes: ByteArray, rewrite: Pair<String, String>? = null): ByteArray {
        val writer = ClassWriter(0)
        val visitor = if (rewrite == null) writer else object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                super.visit(version, access, name, signature?.replaceFirst(rewrite.first, rewrite.second), rewrite.second, interfaces)
            }
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    private var superConstructor = false
                    override fun visitMethodInsn(opcode: Int, owner: String, method: String, descriptor: String, isInterface: Boolean) {
                        val replace = opcode == Opcodes.INVOKESPECIAL && owner == rewrite.first &&
                            (method != "<init>" || name == "<init>" && !superConstructor)
                        if (replace && method == "<init>") superConstructor = true
                        super.visitMethodInsn(opcode, if (replace) rewrite.second else owner, method, descriptor, isInterface)
                    }
                }
            }
        }
        classInput { ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES) }
        return writer.toByteArray()
    }

    private fun readClasses(roots: List<Path>): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var bytes = 0L
        var entries = 0
        fun add(name: String, read: () -> ByteArray) {
            if (!name.endsWith(".class")) return
            require(result.size < MAX_CLASSES && name.split('/').none { it == ".." || it.isEmpty() } && !name.startsWith('/')) {
                "invalid or oversized transformation class inventory"
            }
            val content = read()
            bytes += content.size
            require(bytes <= MAX_TOTAL_BYTES) { "transformation class bytes exceed budget" }
            val id = classInput { ClassReader(content).className }
            require(name == "$id.class" && result.putIfAbsent(id, content) == null) { "duplicate or misplaced transformed class" }
        }
        roots.forEach { path ->
            require(Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "unavailable transformation artifact" }
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.walk(path).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext()) {
                    require(++entries <= MAX_ENTRIES) { "transformation artifact inventory exceeds budget" }
                    val file = iterator.next()
                    require(!Files.isSymbolicLink(file)) { "symbolic transformation artifact" }
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS)) add(path.relativize(file).toString().replace('\\', '/')) {
                        Files.newInputStream(file).use { bounded(it.readNBytes(MAX_CLASS_BYTES + 1)) }
                    }
                }
            } else ZipFile(path.toFile()).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    require(++entries <= MAX_ENTRIES) { "transformation artifact inventory exceeds budget" }
                    val entry = iterator.nextElement()
                    if (!entry.isDirectory) add(entry.name) { zip.getInputStream(entry).use { bounded(it.readNBytes(MAX_CLASS_BYTES + 1)) } }
                }
            }
        }
        return result
    }

    private fun bounded(bytes: ByteArray): ByteArray = bytes.also { require(it.size <= MAX_CLASS_BYTES) { "transformed class exceeds byte budget" } }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
