package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class DependencyUsageScannerTest {
    @Test
    fun `javac signatures and public headers expose types while bodies and private owners do not`(@TempDir root: Path) {
        val source = root.resolve("Api.java")
        source.writeText("""
            public class Api {
              public java.util.List<java.net.URI> exposed;
              private java.util.Set<java.time.Instant> hidden;
              public String body() { return java.util.UUID.randomUUID().toString(); }
              private static class PrivateOwner { public java.io.File notApi; }
              public static class PublicOwner { public java.nio.file.Path visible; }
            }
        """.trimIndent())
        val classes = root.resolve("classes").createDirectories()
        assertTrue(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", classes.toString(), source.toString()) == 0)
        val usage = DependencyUsageScanner().scan(listOf(classes))
        assertContains(usage.apiClasses, "java/net/URI")
        assertContains(usage.apiClasses, "java/nio/file/Path")
        assertFalse("java/time/Instant" in usage.apiClasses)
        assertFalse("java/util/UUID" in usage.apiClasses)
        assertFalse("java/io/File" in usage.apiClasses)
        assertContains(usage.referencedClasses, "java/time/Instant")
        assertContains(usage.referencedClasses, "java/util/UUID")
        assertTrue(usage.apiComplete)
    }

    @Test
    fun `Kotlin metadata separates internal members and preserves inline and typealias API types`(@TempDir root: Path) {
        val prefix = "dev/kartograph/index/fixture/"
        listOf("DependencyAbiPublic", "DependencyAbiInternal", "DependencyAbiFixturesKt").forEach { name ->
            val file = root.resolve(prefix + name + ".class")
            file.parent.createDirectories()
            javaClass.classLoader.getResourceAsStream(prefix + name + ".class")!!.use { file.writeBytes(it.readBytes()) }
        }
        val usage = DependencyUsageScanner().scan(listOf(root))
        assertContains(usage.apiClasses, prefix + "AbiExposedType")
        assertContains(usage.apiClasses, prefix + "AbiInlineType")
        assertContains(usage.apiClasses, prefix + "AbiAliasType")
        for (type in listOf("AbiAliasAnnotation", "AbiAnnotationType", "AbiAnnotationExtra", "AbiNestedAnnotation", "AbiNestedAnnotationType", "AbiAnnotationChoice")) {
            assertContains(usage.apiClasses, prefix + type)
        }
        assertFalse(prefix + "AbiInternalType" in usage.apiClasses)
        assertFalse(prefix + "AbiBodyType" in usage.apiClasses)
        assertFalse(prefix + "AbiPrivateFieldAnnotation" in usage.apiClasses)
        assertFalse(prefix + "AbiPrivateSetterAnnotation" in usage.apiClasses)
        assertContains(usage.referencedClasses, prefix + "AbiBodyType")
        assertContains(usage.referencedClasses, prefix + "AbiInternalType")
        assertTrue(usage.apiComplete)
    }

    @Test
    fun `JAR and directory inputs preserve first class definition and reject damaged input`(@TempDir root: Path) {
        val directory = root.resolve("classes").createDirectories()
        directory.resolve("Api.class").writeBytes(simpleClass("first/Type"))
        val jar = root.resolve("later.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("Api.class")); out.write(simpleClass("later/Type")); out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/versions/17/Api.class")); out.write(byteArrayOf(1)); out.closeEntry()
        }
        val usage = DependencyUsageScanner().scan(listOf(directory, jar))
        assertContains(usage.apiClasses, "first/Type")
        assertFalse("later/Type" in usage.referencedClasses)
        directory.resolve("Broken.class").writeBytes(byteArrayOf(1))
        assertFailsWith<ClassIndexingException> { DependencyUsageScanner().scan(listOf(directory)) }
        assertFailsWith<ClassIndexingException> { DependencyUsageScanner().scan(listOf(root.resolve("absent"))) }
        assertFailsWith<ClassIndexingException> { DependencyUsageScanner().scan(emptyList()) }
    }

    @Test
    fun `missing enclosing class and unknown Kotlin metadata leave an explicit API gap`(@TempDir root: Path) {
        val nested = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Outer\$Nested", null, "java/lang/Object", null)
            visitInnerClass("Outer\$Nested", "Outer", "Nested", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
            visitEnd()
        }
        root.resolve("Nested.class").writeBytes(nested.toByteArray())
        assertFalse(DependencyUsageScanner().scan(listOf(root)).apiComplete)
        val unknown = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Unknown", null, "java/lang/Object", null)
            visitAnnotation("Lkotlin/Metadata;", true).apply { visit("k", 99); visit("mv", intArrayOf(999, 0, 0)); visitEnd() }
            visitEnd()
        }
        root.resolve("Unknown.class").writeBytes(unknown.toByteArray())
        val usage = DependencyUsageScanner().scan(listOf(root))
        assertFalse(usage.apiComplete)
        assertTrue(usage.limitations.any { it.startsWith("dependency-api-incomplete:") })
    }

    @Test
    fun `typealias metadata outside supplied roots withholds absence advice until its owner is supplied`(@TempDir root: Path) {
        val prefix = "dev/kartograph/index/fixture/"
        fun copy(name: String) {
            val target = root.resolve(prefix + name + ".class")
            target.parent.createDirectories()
            javaClass.classLoader.getResourceAsStream(prefix + name + ".class")!!.use { target.writeBytes(it.readBytes()) }
        }
        copy("DependencyAbiAliasUser")
        assertFalse(DependencyUsageScanner().scan(listOf(root)).apiComplete)
        copy("DependencyAbiFixturesKt")
        assertTrue(DependencyUsageScanner().scan(listOf(root)).apiComplete)
    }

    @Test
    fun `excessive signature depth fails as input error instead of overflowing the stack`(@TempDir root: Path) {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Deep", null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "Ljava/lang/Object;", "[".repeat(520) + "Llib/Type;", null).visitEnd()
        writer.visitEnd()
        root.resolve("Deep.class").writeBytes(writer.toByteArray())
        assertFailsWith<ClassIndexingException> { DependencyUsageScanner().scan(listOf(root)) }
    }

    private fun simpleClass(type: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Api", null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PUBLIC, "value", "L$type;", null, null).visitEnd()
        visitEnd()
    }.toByteArray()
}
