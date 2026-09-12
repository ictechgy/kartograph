package dev.kartograph.cli

import dev.kartograph.export.QuerySnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.io.TempDir

class FreshnessCliTest {
    @Test
    fun `raw compiler files need a scope and completed build evidence`(@TempDir root: Path) {
        val source = Files.writeString(root.resolve("Entry.java"), "public class Entry {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.writeString(root.resolve("facts.tsv"), "not a compiler receipt")
        val base = arrayOf("snapshot", "--project", root.toString(), "--classes", classes.toString())
        assertEquals(64, run(*base, "--compiler-evidence", "facts.tsv").first)
        assertEquals(64, run(*base, "--input", "external/classes=classes").first)
        val unverified = run(*base, "--scope", "sample:main", "--compiler-evidence", "facts.tsv")
        assertEquals(2, unverified.first)
        assertContains(unverified.second, "compiler evidence requires matched build inputs")
    }

    @Test
    fun `public certificate resources are fingerprinted without exposing content`(@TempDir root: Path) {
        val source = root.resolve("Example.java")
        Files.writeString(source, "public class Example {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val resources = Files.createDirectories(root.resolve("res/raw"))
        Files.writeString(resources.resolve("pin.pem"), "PUBLIC_CERTIFICATE_FIXTURE")
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest package=\"sample\"><application/></manifest>")
        val captured = run("snapshot", "--project", root.toString(), "--classes", classes.toString(), "--resources", "res",
            "--manifest", "AndroidManifest.xml", "--namespace", "sample")
        assertEquals(0, captured.first, captured.second)
        assertFalse(captured.second.contains("PUBLIC_CERTIFICATE_FIXTURE"))
        val snapshot = root.resolve("snapshot.json")
        Files.writeString(snapshot, captured.second)
        val checked = run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString())
        assertEquals(1, checked.first)
        assertContains(checked.second, "\"status\":\"unverified\"")
        Files.writeString(resources.resolve("pin.pem"), "CHANGED_PUBLIC_CERTIFICATE_FIXTURE")
        val changed = run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString())
        assertEquals(1, changed.first)
        assertContains(changed.second, "changed-resources")
        assertEquals(0, run("query", "Example", "--graph-file", snapshot.toString()).first)
    }

    @Test
    fun `relative generated roots are fingerprinted from the same working directory as indexing`(@TempDir root: Path) {
        val source = root.resolve("Example.java")
        Files.writeString(source, "public class Example {}")
        val classes = Files.createDirectories(root.resolve("compiled"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        // Linux의 짧은 /tmp 경로에서도 '..'가 양쪽을 파일 시스템 루트까지 올려 같아지지 않게 한다.
        val project = Files.createDirectories((0..workingDirectory.nameCount).fold(root.resolve("project")) { path, _ -> path.resolve("nested") })
        val relative = workingDirectory.relativize(classes.toAbsolutePath().normalize()).toString()
        assertFalse(project.resolve(relative).normalize() == classes.toAbsolutePath().normalize())
        val captured = run("snapshot", "--project", project.toString(), "--classes", relative, "--generated-classes", relative)
        assertEquals(0, captured.first, captured.second)
        val parsed = QuerySnapshotCodec.parse(captured.second)
        assertContains(captured.second, "generatedInput")
        assertEquals(parsed.provenance!!.inputs.single { it.role == "classes" }.sha256,
            parsed.provenance!!.inputs.single { it.role == "generated-classes" }.sha256)
        val snapshot = project.resolve("snapshot.json")
        Files.writeString(snapshot, captured.second)
        val unbound = run("verify-snapshot", "--project", project.toString(), "--graph-file", snapshot.toString(), "--classes", relative)
        assertEquals(1, unbound.first, unbound.second)
        assertContains(unbound.second, "\"status\":\"unverified\"")
        Files.writeString(classes.resolve("resource.txt"), "changed output")
        val changed = run("verify-snapshot", "--project", project.toString(), "--graph-file", snapshot.toString(), "--classes", relative)
        assertEquals(1, changed.first, changed.second)
        assertContains(changed.second, "\"status\":\"stale\"")
    }

    @Test
    fun `relabeling real old javac output is unverified and byte changes are stale`(@TempDir root: Path) {
        val sources = Files.createDirectories(root.resolve("src"))
        val source = sources.resolve("Example.java")
        Files.writeString(source, "public class Example {}")
        val classes = Files.createDirectories(root.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.writeString(root.resolve("keep.pro"), "-include extra.pro\n")
        Files.writeString(root.resolve("extra.pro"), "# empty but observed\n")
        val snapshot = root.resolve("snapshot.json")
        val capture = run("snapshot", "--project", root.toString(), "--classes", classes.toString(), "--source-root", "src",
            "--scope", "sample:main", "--revision", "a".repeat(40), "--keep-rules", "keep.pro")
        assertEquals(0, capture.first, capture.second)
        Files.writeString(snapshot, capture.second)
        assertFalse(capture.second.contains(root.toString()))
        val verify = arrayOf("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString())
        assertContains(run(*verify).second, "\"status\":\"unverified\"")
        val stamp = Files.getLastModifiedTime(source)
        Files.writeString(source, "public class Changed {}")
        Files.setLastModifiedTime(source, stamp)
        val changed = run(*verify)
        assertEquals(1, changed.first)
        assertContains(changed.second, "changed-sources")
        Files.writeString(source, "public class Example {}")
        Files.writeString(root.resolve("extra.pro"), "# changed empty include\n")
        assertContains(run(*verify).second, "changed-keepRules")
        Files.delete(classes.resolve("Example.class"))
        assertContains(run(*verify).second, "changed-classes")
        // 내용 비교가 실패해도 저장 질의의 그래프를 변경하지 않는다.
        assertEquals(0, run("query", "Example", "--graph-file", snapshot.toString()).first)
        val legacy = QuerySnapshotCodec.parse(capture.second).copy(provenance = null)
        Files.writeString(snapshot, QuerySnapshotCodec.render(legacy))
        assertContains(run(*verify).second, "legacy-snapshot")
    }

    @Test
    fun `ordered supplied roots and scope are checked independently of offline query`(@TempDir root: Path) {
        val a = Files.createDirectories(root.resolve("a"))
        val b = Files.createDirectories(root.resolve("b"))
        val source = root.resolve("Example.java")
        Files.writeString(source, "public class Example {}")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", a.toString(), source.toString()))
        Files.copy(a.resolve("Example.class"), b.resolve("Example.class"))
        val dependency = Files.createDirectories(root.resolve("dependency"))
        Files.copy(a.resolve("Example.class"), dependency.resolve("Example.class"))
        val config = root.resolve("build.gradle")
        Files.writeString(config, "plugins {}")
        val snapshot = root.resolve("snapshot.json")
        val capture = run("snapshot", "--project", root.toString(), "--classes", a.toString(), "--classes", b.toString(),
            "--classpath", dependency.toString(), "--build-input", "build.gradle", "--scope", "sample:main")
        assertEquals(0, capture.first, capture.second)
        Files.writeString(snapshot, capture.second)
        assertContains(run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString(),
            "--classes", b.toString(), "--classes", a.toString()).second, "changed-classes-order")
        assertContains(run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString(),
            "--scope", "sample:test").second, "snapshot-scope-mismatch")
        assertContains(run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString(),
            "--artifact", ":compileOther").second, "build-artifact-mismatch")
        Files.writeString(config, "plugins { changed }")
        assertContains(run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString()).second, "changed-buildConfig")
        Files.delete(dependency.resolve("Example.class"))
        assertContains(run("verify-snapshot", "--project", root.toString(), "--graph-file", snapshot.toString()).second, "changed-classpath")
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = KartographCli.run(args, PrintStream(out), PrintStream(err))
        return status to (out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8))
    }
}
