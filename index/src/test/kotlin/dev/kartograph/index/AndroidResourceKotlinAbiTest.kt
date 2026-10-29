package dev.kartograph.index

import dev.kartograph.core.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class AndroidResourceKotlinAbiTest {
    @Test
    fun `resource identity bytecode avoids the Kotlin 2_2 single element sequence overload`() {
        for (type in listOf(AndroidResourceRoot::class.java, AndroidLayoutDefinition::class.java,
            AndroidResourceBindingIdentity::class.java)) {
            val path = "/" + type.name.replace('.', '/') + ".class"
            val bytes = requireNotNull(type.getResourceAsStream(path)).use { it.readBytes() }
            assertEquals(emptyList(), incompatibleCalls(bytes), type.name)
        }
        // 실제 bytecode oracle이 금지된 overload를 탐지하는지 음성 대조군으로 확인한다.
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "probe", "()V", null, null)
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/sequences/SequencesKt", "sequenceOf",
            "(Ljava/lang/Object;)Lkotlin/sequences/Sequence;", false)
        method.visitEnd()
        writer.visitEnd()
        assertEquals(listOf("probe"), incompatibleCalls(writer.toByteArray()))
    }

    @Test
    fun `compatible sequence construction preserves full root layout and namespace hash inputs`() {
        val root = AndroidResourceRoot(InputFingerprint("android-resources", "src/main/res", "1".repeat(64)))
        assertEquals(hash(listOf("kartograph-android-resource-root-v1", "android-resources", "src/main/res", "1".repeat(64))), root.id)
        val definition = AndroidLayoutDefinition(AndroidResourceKey("dev.example", "layout", "screen"), root.id,
            CompilerEvidenceSource("src/main/res/layout/screen.xml", "2".repeat(64)), "layout/screen.xml")
        assertEquals(hash(listOf("kartograph-android-layout-definition-v1", "dev.example", "layout", "screen",
            root.id, "src/main/res/layout/screen.xml", "2".repeat(64), "layout/screen.xml", "")), definition.id)
        assertEquals(hash(listOf("namespace=dev.example")), AndroidResourceBindingIdentity.namespaceOptions("dev.example"))
    }

    private fun incompatibleCalls(bytes: ByteArray): List<String> {
        val found = mutableListOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                exceptions: Array<out String>?): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(opcode: Int, owner: String, called: String, descriptor: String, isInterface: Boolean) {
                    if (owner.startsWith("kotlin/sequences/SequencesKt") && called == "sequenceOf" &&
                        descriptor == "(Ljava/lang/Object;)Lkotlin/sequences/Sequence;") found += name
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return found
    }

    private fun hash(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
