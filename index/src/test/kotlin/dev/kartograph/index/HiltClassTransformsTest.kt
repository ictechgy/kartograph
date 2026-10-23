package dev.kartograph.index

import dev.kartograph.core.BuildWitness
import dev.kartograph.core.ClassTransformObservation
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.SnapshotProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes

/** ASM 단위 fixture는 실제 Hilt processor 소비 통합 검증을 대체하지 않는다. */
class HiltClassTransformsTest {
    @Test
    fun `observation bridges only matching original and selected class bytes`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        val changes = HiltClassTransforms.verify(listOf(raw), listOf(selected), setOf("App.java"))
        assertEquals(listOf("p/App"), changes.map { it.name })
        assertTrue(changes.single().beforeSha256 != changes.single().afterSha256)
        Files.writeString(root.resolve("App.java"), "class App {}")
        Files.writeString(root.resolve("build.gradle"), "configuration")
        Files.writeString(root.resolve("compiler.jar"), "compiler")
        Files.writeString(root.resolve("witness.json"), "receipt")
        fun fp(role: String, path: String) = ContentFingerprint.capture(root, root.resolve(path), role, role)
        val witness = BuildWitness(":app:debug", "javac", ":compileDebugJavaWithJavac",
            listOf(fp("sources", "App.java"), fp("buildConfig", "build.gradle"), fp("compiler", "compiler.jar"),
                InputFingerprint("options", "javac-options", "0".repeat(64))), listOf(fp("classes", "raw")))
        val original = SnapshotProvenance(listOf(fp("classes", "selected"), fp("witness", "witness.json")), listOf(witness))
        assertEquals(listOf("unwitnessed-class-root"), ProvenanceVerifier.verify(original, root, ":app:debug").reasons)
        val observation = ClassTransformObservation(":app:debug", ":kartographSnapshotDebug", HiltClassTransforms.POLICY,
            witness.outputs, original.inputs.filter { it.role == "classes" }, changes)
        val captured = original.copy(transformations = listOf(observation))
        fun verify(value: SnapshotProvenance = captured) = ProvenanceVerifier.verify(value, root, ":app:debug")
        assertEquals("matched", verify().status)
        assertEquals(witness, captured.witnesses.single(), "transformed bytes never replace original compiler outputs")
        assertEquals("unverified", verify(captured.copy(transformations = listOf(observation.copy(scope = ":app:release")))).status)
        assertEquals("unverified", verify(captured.copy(transformations = listOf(observation.copy(changedClasses =
            changes.map { it.copy(afterSha256 = "0".repeat(64)) })))).status)
        assertEquals("matched", ProvenanceVerifier.verify(captured.copy(memberScopes = listOf(":app:debug")), root, "aggregate:debug").status)
        for (path in listOf("App.java", "build.gradle", "raw/p/App.class", "selected/p/App.class", "witness.json")) {
            val file = root.resolve(path)
            val bytes = Files.readAllBytes(file)
            Files.write(file, bytes + 0)
            assertEquals("stale", verify().status, path)
            Files.write(file, bytes)
        }
        assertEquals("matched", verify().status)
        val external = observation.copy(originalOutputs = observation.originalOutputs.map { it.copy(path = "external/raw") },
            selectedOutputs = observation.selectedOutputs.map { it.copy(path = "external/selected") })
        val externalCapture = captured.copy(inputs = listOf(external.selectedOutputs.single(), fp("witness", "witness.json")),
            witnesses = listOf(witness.copy(outputs = external.originalOutputs)), transformations = listOf(external))
        assertEquals("unverified", verify(externalCapture).status)
        assertEquals("matched", ProvenanceVerifier.verify(externalCapture, root, ":app:debug",
            mapOf("external/raw" to raw, "external/selected" to selected)).status)
    }

    @Test
    fun `method changes manual superclass and absent generated outputs cannot be certified`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        fun reject() = assertFailsWith<IllegalArgumentException> {
            HiltClassTransforms.verify(listOf(raw), listOf(selected), setOf("App.java"))
        }
        Files.write(selected.resolve("p/App.class"), entry("p/Hilt_App", value = 2))
        reject()
        Files.write(selected.resolve("p/App.class"), entry("p/Hilt_App"))
        assertFailsWith<IllegalArgumentException> {
            HiltClassTransforms.verify(listOf(raw), listOf(selected), setOf("App.java", "Hilt_App.java"))
        }
        val parent = Files.readAllBytes(raw.resolve("p/Hilt_App.class"))
        Files.delete(raw.resolve("p/Hilt_App.class"))
        reject()
        Files.write(raw.resolve("p/Hilt_App.class"), parent)
        Files.write(selected.resolve("p/Extra.class"), type("p/Extra", "java/lang/Object", "Extra.java"))
        reject()
        Files.delete(selected.resolve("p/Extra.class"))
        assertFailsWith<IllegalArgumentException> { HiltClassTransforms.verify(listOf(raw, raw), listOf(selected), emptySet()) }
        Files.write(raw.resolve("p/App.class"), entry("android/app/Application", annotation = false))
        reject()
    }

    @Test
    fun `jar roots and changed debug metadata preserve bounded comparison`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        val jar = root.resolve("selected.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for (name in listOf("App", "Hilt_App")) {
                zip.putNextEntry(ZipEntry("p/$name.class"))
                zip.write(Files.readAllBytes(selected.resolve("p/$name.class")))
                zip.closeEntry()
            }
        }
        assertEquals(listOf("p/App"), HiltClassTransforms.verify(listOf(raw), listOf(jar), setOf("App.java")).map { it.name })
        Files.write(selected.resolve("p/App.class"), entry("p/Hilt_App", source = "Other.java"))
        assertFailsWith<IllegalArgumentException> { HiltClassTransforms.verify(listOf(raw), listOf(selected), emptySet()) }
        Files.write(selected.resolve("p/App.class"), byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> { HiltClassTransforms.verify(listOf(raw), listOf(selected), emptySet()) }
    }

    @Test
    fun `receiver injection and source inventory cannot be guessed from class names`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        Files.write(raw.resolve("p/App.class"), entry("android/app/Application", receiver = true))
        Files.write(selected.resolve("p/App.class"), entry("p/Hilt_App", receiver = true))
        val error = assertFailsWith<IllegalArgumentException> { HiltClassTransforms.verify(listOf(raw), listOf(selected), emptySet()) }
        assertTrue(error.message!!.contains("BroadcastReceiver"))
        val sources = Files.createDirectories(root.resolve("src/p"))
        Files.writeString(sources.resolve("App.java"), "class App {}")
        Files.writeString(sources.resolve("Other.kt"), "class Other")
        Files.writeString(sources.resolve("metadata.txt"), "ignored")
        assertEquals(setOf("App.java", "Other.kt"), HiltClassTransforms.primarySourceNames(listOf(root.resolve("src"))))
    }

    @Test
    fun `compressed oversized classes truncated jars and misplaced definitions reject`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        val jar = root.resolve("large.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("p/App.class"))
            zip.write(ByteArray(8 * 1024 * 1024 + 1))
            zip.closeEntry()
        }
        assertTrue(Files.size(jar) < 100_000, "compressed size does not substitute for the expanded class budget")
        val oversized = assertFailsWith<IllegalArgumentException> {
            HiltClassTransforms.verify(listOf(raw), listOf(jar), emptySet())
        }
        assertTrue(oversized.message!!.contains("byte budget"))
        Files.write(jar, byteArrayOf(0x50, 0x4b, 3, 4))
        assertFailsWith<java.io.IOException> { HiltClassTransforms.verify(listOf(raw), listOf(jar), emptySet()) }
        Files.write(selected.resolve("p/Wrong.class"), Files.readAllBytes(selected.resolve("p/App.class")))
        assertFailsWith<IllegalArgumentException> { HiltClassTransforms.verify(listOf(raw), listOf(selected), emptySet()) }
    }

    @Test
    fun `unused constant pool layout changes retain raw hashes without claiming semantic changes`(@TempDir root: Path) {
        val (raw, selected) = fixture(root)
        val source = Files.readAllBytes(raw.resolve("p/Hilt_App.class"))
        val rewritten = ClassWriter(0).apply { newConst("unused entry"); ClassReader(source).accept(this, 0) }.toByteArray()
        Files.write(selected.resolve("p/Hilt_App.class"), rewritten)
        val observed = HiltClassTransforms.verify(listOf(raw), listOf(selected), setOf("App.java"))
        assertEquals(listOf("p/App", "p/Hilt_App"), observed.map { it.name })
        assertTrue(observed.all { it.beforeSha256 != it.afterSha256 })
    }

    private fun fixture(root: Path): Pair<Path, Path> {
        val raw = Files.createDirectories(root.resolve("raw/p")).parent
        val selected = Files.createDirectories(root.resolve("selected/p")).parent
        Files.write(raw.resolve("p/App.class"), entry("android/app/Application"))
        Files.write(selected.resolve("p/App.class"), entry("p/Hilt_App"))
        for (dir in listOf(raw, selected)) Files.write(dir.resolve("p/Hilt_App.class"),
            type("p/Hilt_App", "android/app/Application", "Hilt_App.java"))
        return raw to selected
    }

    private fun entry(parent: String, value: Int = 1, annotation: Boolean = true,
        source: String = "App.java", receiver: Boolean = false): ByteArray = type("p/App", parent, source) { writer ->
        if (annotation) writer.visitAnnotation("Ldagger/hilt/android/HiltAndroidApp;", false).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            visitCode(); visitLdcInsn(value); visitInsn(Opcodes.IRETURN); visitMaxs(1, 1); visitEnd()
        }
        if (receiver) writer.visitMethod(Opcodes.ACC_PUBLIC, "onReceive", "(Landroid/content/Context;Landroid/content/Intent;)V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 3); visitEnd()
        }
    }

    private fun type(name: String, parent: String, source: String, extra: (ClassWriter) -> Unit = {}): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null)
        visitSource(source, null)
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        extra(this); visitEnd()
    }.toByteArray()
}
