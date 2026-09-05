package dev.kartograph.cli

import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.SourceLocation
import dev.kartograph.export.BaselineCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KartographCliTest {
    @Test
    fun `private member mode reports only unreferenced private members of reachable owners`(@TempDir root: Path) {
        val source = root.resolve("MemberSample.java")
        source.writeText("""
            public class MemberSample {
                private int usedField;
                private int unusedField;
                private void used() { usedField++; }
                private void unused() {}
                private void reflected() {}
                public void entry() { used(); }
                public void publicApi() {}
                public int externalCallback() { return callbackHelper(); }
                private int callbackHelper() { return 3; }
            }
        """.trimIndent())
        val classes = root.resolve("classes").createDirectories()
        check(requireNotNull(ToolProvider.getSystemJavaCompiler()).run(
            null, null, null, "-d", classes.toString(), source.toString(),
        ) == 0)
        root.resolve("rules.pro").writeText("""
            -keep class MemberSample { public void entry(); }
            -keepclassmembers class MemberSample { private void reflected(); }
        """.trimIndent())
        val args = deadArguments(root, "<manifest />", "--keep-rules", "rules.pro")
        args[2] = classes.toString()
        val default = execute(*args)
        val members = execute(*args, "--include-private-members", "--strict")
        assertEquals(0, default.status)
        assertEquals(emptyList(), default.output.lines().filter { it.startsWith("unreachable\t") })
        assertEquals(1, members.status)
        assertEquals(
            listOf("field:MemberSample#unusedField:I", "method:MemberSample#unused()V"),
            members.output.lines().filter { it.startsWith("unreachable\t") }.map { it.split('\t')[1] },
        )
        val capture = execute("baseline", "--write", "members.json", *args.drop(1).toTypedArray(), "--include-private-members")
        assertEquals(0, capture.status)
        val filtered = execute(*args, "--include-private-members", "--baseline", "members.json", "--strict")
        assertEquals(0, filtered.status)
        assertEquals(emptyList(), filtered.output.lines().filter { it.startsWith("unreachable\t") })
        val query = execute("query", "method:MemberSample#reflected()V", "--classes", classes.toString(),
            "--project", root.toString(), "--keep-rules", "rules.pro", "--include-private-members")
        assertEquals(0, query.status)
        assertContains(query.output, "\"state\": \"retained\"")
    }

    @Test
    fun `version reports the release artifact version`() {
        val execution = execute("--version")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        val releaseVersion = Path.of("../VERSION").readText().trim()
        assertEquals("kartograph $releaseVersion\n", execution.output)
    }

    @Test
    fun `no arguments print help and succeed`() {
        val execution = execute()

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "kartograph")
        assertContains(execution.output, "Exit codes:")
    }

    @Test
    fun `baseline help prints help and succeeds`() {
        val execution = execute("baseline", "--help")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "Find class declarations")
    }

    @Test
    fun `unknown command is a usage error`() {
        val execution = execute("no-such-command")

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "unknown command")
    }

    @Test
    fun `architecture commands expose metrics and fail closed on malformed layer yaml`(@TempDir root: Path) {
        val metrics = execute("metrics", "--classes", classRoot.toString())
        root.resolve("layers.yml").writeText(
            "layers:\n  - name: CLI\n    match: [dev.kartograph.cli]\nrules:\n  - from: CLI\n    denied: [CLI]\n",
        )
        val rules = execute(
            "rules", "--classes", classRoot.toString(), "--config", root.resolve("layers.yml").toString(), "--strict",
        )

        assertEquals(ExitStatus.SUCCESS.code, metrics.status)
        assertContains(metrics.output, "module\tCa\tCe\tI\tA\tD")
        assertEquals(ExitStatus.FAILURE.code, rules.status)
        assertContains(rules.error, "invalid layer configuration")
    }

    @Test
    fun `architecture commands accept JAR class roots`(@TempDir root: Path) {
        val jar = root.resolve("classes.jar")
        writeClassJar(KartographCli::class.java, jar)

        val metrics = execute("metrics", "--classes", jar.toString())

        assertEquals(ExitStatus.SUCCESS.code, metrics.status)
        assertContains(metrics.output, "dev.kartograph.cli")
    }

    @Test
    fun `graph accepts JAR class roots consistently`(@TempDir root: Path) {
        val jar = root.resolve("classes.jar")
        writeClassJar(KartographCli::class.java, jar)

        val graph = execute("graph", "--classes", jar.toString(), "--format", "dot")

        assertEquals(ExitStatus.SUCCESS.code, graph.status)
        assertContains(graph.output, "KartographCli")
    }

    @Test
    fun `architecture strict commands return findings for cycles violations and unassigned nodes`(@TempDir root: Path) {
        val cycleClasses = compileJavaCycle(root)
        val cycles = execute("cycles", "--classes", cycleClasses.toString(), "--strict")
        val config = root.resolve("layers.yml")
        config.writeText(
            """
                layers:
                  - name: First
                    match: [first]
                  - name: Second
                    match: [second]
                rules:
                  - name: no second
                    from: First
                    deny: [Second]
            """.trimIndent(),
        )
        val rules = execute(
            "rules", "--classes", cycleClasses.toString(), "--config", config.toString(), "--strict",
        )
        config.writeText("layers:\n  - name: Missing\n    match: [does-not-match]\n")
        val unassigned = execute(
            "rules", "--classes", cycleClasses.toString(), "--config", config.toString(), "--strict",
        )

        assertEquals(ExitStatus.FINDINGS.code, cycles.status)
        assertContains(cycles.output, "first -> second -> first")
        assertContains(cycles.output, "weakest edge:")
        assertEquals(ExitStatus.FINDINGS.code, rules.status)
        assertContains(rules.output, "[no second]")
        assertContains(rules.output, "evidence:")
        assertEquals(ExitStatus.FINDINGS.code, unassigned.status)
        assertContains(unassigned.output, "declarations are not assigned")
    }

    @Test
    fun `rules explain accepts the same qualified symbol name as query`(@TempDir root: Path) {
        val config = root.resolve("layers.yml")
        config.writeText("layers:\n  - name: CLI\n    match: [dev.kartograph.cli]\n")

        val execution = execute(
            "rules",
            "--classes",
            classRoot.toString(),
            "--config",
            config.toString(),
            "--explain",
            "dev.kartograph.cli.KartographCli.run",
        )

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "layer: CLI")
    }

    @Test
    fun `query notFound returns sibling schema with measured limitations`(@TempDir projectRoot: Path) {
        val execution = execute(
            "query", "NoSuchSymbol",
            "--classes", classRoot.toString(),
            "--project", projectRoot.toString(),
        )

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.output, "\"status\": \"notFound\"")
        assertContains(execution.output, "\"limitations\": [")
        kotlin.test.assertFalse(execution.output.contains("\"result\""))
    }

    @Test
    fun `query uses manifest retention and baseline state from dead inputs`(@TempDir projectRoot: Path) {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application><activity android:name="dev.kartograph.cli.KartographCli" /></application>
            </manifest>
        """.trimIndent()
        projectRoot.resolve("AndroidManifest.xml").writeText(manifest)
        projectRoot.resolve("res").createDirectories()

        val execution = execute(
            "query", "class:dev/kartograph/cli/KartographCli",
            "--classes", classRoot.toString(),
            "--project", projectRoot.toString(),
            "--manifest", "AndroidManifest.xml",
            "--resources", "res",
            "--namespace", "dev.kartograph.cli",
        )

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "\"state\": \"retained\"")
        assertContains(execution.output, "\"reason\": \"manifestComponent\"")
    }

    @Test
    fun `query marks an unreachable baseline finding as suppressed`(@TempDir projectRoot: Path) {
        val isolatedClassRoot = copyClassToRoot(ExternalHierarchyLeaf::class.java, projectRoot.resolve("classes"))
        val nodeId = NodeId("class:dev/kartograph/cli/ExternalHierarchyLeaf")
        projectRoot.resolve("baseline.json").writeText(
            BaselineCodec.render(listOf(Finding(nodeId, SourceLocation("KartographCliTest.kt")))),
        )

        val execution = execute(
            "query", nodeId.value,
            "--classes", isolatedClassRoot.toString(),
            "--project", projectRoot.toString(),
            "--baseline", "baseline.json",
        )

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "\"state\": \"unreachable\"")
        assertContains(execution.output, "\"suppressedByBaseline\": true")
    }

    @Test
    fun `agent commands reject invalid paths as sanitized usage errors`() {
        val query = execute("query", "Subject", "--classes", "invalid\u0000path", "--project", ".")
        val bridges = execute("bridges", "--project", "invalid\u0000path")

        assertEquals(ExitStatus.USAGE.code, query.status)
        assertEquals(ExitStatus.USAGE.code, bridges.status)
        assertContains(query.error, "invalid path")
        assertContains(bridges.error, "invalid path")
        kotlin.test.assertFalse(query.error.contains("invalid\u0000path"))
        kotlin.test.assertFalse(bridges.error.contains("invalid\u0000path"))
    }

    @Test
    fun `agent commands reject invalid query and bridge options`(@TempDir projectRoot: Path) {
        val invalidDepth = execute(
            "query", "Subject",
            "--classes", classRoot.toString(),
            "--project", projectRoot.toString(),
            "--depth", "0",
        )
        val missingNamespace = execute(
            "query", "Subject",
            "--classes", classRoot.toString(),
            "--project", projectRoot.toString(),
            "--manifest", "AndroidManifest.xml",
        )
        val invalidBridgeFormat = execute(
            "bridges", "--project", projectRoot.toString(), "--format", "text",
        )

        assertEquals(ExitStatus.USAGE.code, invalidDepth.status)
        assertContains(invalidDepth.error, "--depth must be a positive integer")
        assertEquals(ExitStatus.USAGE.code, missingNamespace.status)
        assertContains(missingNamespace.error, "requires --namespace")
        assertEquals(ExitStatus.USAGE.code, invalidBridgeFormat.status)
        assertContains(invalidBridgeFormat.error, "invalid bridges format")
    }

    @Test
    fun `bridges emits graph exchange JSON`(@TempDir projectRoot: Path) {
        projectRoot.resolve("Plugin.kt").writeText(
            "val channel = MethodChannel(messenger, \"camera\")\nchannel.setMethodCallHandler(handler)\n",
        )

        val execution = execute("bridges", "--project", projectRoot.toString(), "--format", "json")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "\"format\": \"bridge-facts\"")
        assertContains(execution.output, "\"kind\": \"channel-register\"")
        assertContains(execution.output, "\"project\": \".\"")
        kotlin.test.assertFalse(execution.output.contains(projectRoot.toString()))
        kotlin.test.assertFalse(execution.output.contains(projectRoot.resolve("Plugin.kt").toString()))
    }

    @Test
    fun `skill installs reviewed guidance without overwriting by default`(@TempDir projectRoot: Path) {
        val bundled = Path.of("../Skills/kartograph/SKILL.md").readText()
        val execution = execute("skill", "--project", projectRoot.toString())
        val installed = projectRoot.resolve(".claude/skills/kartograph/SKILL.md")
        val initialContent = installed.readText()
        installed.writeText("User-maintained guidance")
        val repeated = execute("skill", "--project", projectRoot.toString())
        val preservedContent = installed.readText()
        val forced = execute("skill", "--project", projectRoot.toString(), "--force")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertEquals(bundled, initialContent)
        assertEquals(ExitStatus.USAGE.code, repeated.status)
        assertContains(repeated.error, "pass --force to overwrite")
        assertEquals("User-maintained guidance", preservedContent)
        assertEquals(ExitStatus.SUCCESS.code, forced.status)
        assertEquals(bundled, installed.readText())
    }

    @Test
    fun `invalid graph format is a usage error`() {
        val execution = execute("graph", "--format", "yaml")

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "invalid graph format")
    }

    @Test
    fun `invalid graph path is a sanitized usage error`() {
        val execution = execute("graph", "--classes", "invalid\u0000path")

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "invalid path")
        kotlin.test.assertFalse(execution.error.contains("invalid\u0000path"))
    }

    @Test
    fun `missing class root is a tool failure`(@TempDir temporaryDirectory: Path) {
        val missingRoot = temporaryDirectory.resolve("missing")

        val execution = execute("graph", "--classes", missingRoot.toString())

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "class root does not exist")
    }

    @Test
    fun `graph renders real compiled classes as DOT`() {
        val classRoot = Path.of(
            requireNotNull(KartographCli::class.java.protectionDomain.codeSource).location.toURI(),
        )

        val execution = execute("graph", "--classes", classRoot.toString(), "--format", "dot")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "digraph kartograph")
        assertContains(execution.output, "KartographCli")
    }

    @Test
    fun `graph combines repeated class roots instead of replacing the first`(@TempDir emptyRoot: Path) {
        val execution = execute(
            "graph",
            "--classes",
            classRoot.toString(),
            "--classes",
            emptyRoot.toString(),
        )

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KartographCli")
    }

    @Test
    fun `invalid class root is a sanitized tool failure`(@TempDir temporaryDirectory: Path) {
        temporaryDirectory.resolve("Broken.class").writeText("not bytecode")

        val execution = execute("graph", "--classes", temporaryDirectory.toString())

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "invalid class file")
        kotlin.test.assertFalse(execution.error.contains(temporaryDirectory.toString()))
    }

    @Test
    fun `truncated class root is a sanitized tool failure`(@TempDir temporaryDirectory: Path) {
        temporaryDirectory.resolve("Truncated.class").writeBytes(
            byteArrayOf(
                0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
                0x00, 0x00, 0x00, 0x3D, 0x00,
            ),
        )

        val execution = execute("graph", "--classes", temporaryDirectory.toString())

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "invalid class file")
        kotlin.test.assertFalse(execution.error.contains(temporaryDirectory.toString()))
    }

    @Test
    fun `dead reports unreachable classes and strict exits with findings`(@TempDir projectRoot: Path) {
        val arguments = deadArguments(projectRoot, "<manifest />", "--strict")

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FINDINGS.code, execution.status)
        assertContains(execution.output, "unreachable\tclass:dev/kartograph/cli/KartographCli")
        assertContains(execution.output, "limitation\tREFLECTION_STRINGS")
        assertContains(execution.output, "limitation\tDYNAMIC_REGISTRATION")
        kotlin.test.assertFalse(execution.output.contains("deletable"))
    }

    @Test
    fun `baseline write is project relative and deterministic while dead suppresses captured findings`(
        @TempDir projectRoot: Path,
    ) {
        val relativeBaseline = "nested/baseline.json"
        val baseline = projectRoot.resolve(relativeBaseline)
        val arguments = deadArguments(projectRoot, "<manifest />")

        val written = execute("baseline", "--write", relativeBaseline, *arguments.drop(1).toTypedArray())
        val first = baseline.readText()
        val rewritten = execute("baseline", "--write", relativeBaseline, *arguments.drop(1).toTypedArray())
        val filtered = execute(*arguments, "--baseline", baseline.toString(), "--strict", "--report-format", "json")

        assertEquals(ExitStatus.SUCCESS.code, written.status)
        assertTrue(Files.isRegularFile(baseline))
        assertEquals(ExitStatus.SUCCESS.code, rewritten.status)
        assertEquals(first, baseline.readText())
        assertEquals(ExitStatus.SUCCESS.code, filtered.status)
        assertContains(filtered.output, "\"diagnostics\": []")
        assertContains(filtered.output, "\"suppressedCount\": ")
    }

    @Test
    fun `malformed baseline is a sanitized tool failure`(@TempDir projectRoot: Path) {
        val baseline = projectRoot.resolve("broken-baseline.json")
        baseline.writeText("{\"version\": 1}")
        val arguments = deadArguments(projectRoot, "<manifest />")

        val execution = execute(*arguments, "--baseline", baseline.toString())

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "baseline fingerprints are missing")
        kotlin.test.assertFalse(execution.error.contains(projectRoot.toString()))
    }

    @Test
    fun `dead since includes an untracked source basename and excludes other findings`(@TempDir projectRoot: Path) {
        val arguments = deadArguments(projectRoot, "<manifest />")
        git(projectRoot, "init")
        git(projectRoot, "config", "user.email", "test@example.invalid")
        git(projectRoot, "config", "user.name", "Test")
        projectRoot.resolve("seed").writeText("seed")
        git(projectRoot, "add", ".")
        git(projectRoot, "commit", "-m", "base")
        projectRoot.resolve("KartographCli.kt").writeText("untracked")

        val execution = execute(*arguments, "--since", "HEAD", "--report-format", "github-actions")

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "::warning")
        assertContains(execution.output, "KartographCli")
    }

    @Test
    fun `dead combines repeated class roots`(@TempDir projectRoot: Path) {
        val emptyRoot = projectRoot.resolve("empty-classes").createDirectories()
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classes",
            emptyRoot.toString(),
            "--explain",
            "class:dev/kartograph/cli/KartographCli",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "unreachable\tclass:dev/kartograph/cli/KartographCli")
    }

    @Test
    fun `dead rejects another option where a value is required`() {
        val execution = execute("dead", "--keep-rules", "--strict")

        assertEquals(ExitStatus.USAGE.code, execution.status)
        assertContains(execution.error, "missing value for --keep-rules")
    }

    @Test
    fun `dead explain prints manifest retention reason and relative evidence`(@TempDir projectRoot: Path) {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name="dev.kartograph.cli.KartographCli" />
              </application>
            </manifest>
        """.trimIndent()
        val arguments = deadArguments(
            projectRoot,
            manifest,
            "--explain",
            "class:dev/kartograph/cli/KartographCli",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "retained\tclass:dev/kartograph/cli/KartographCli")
        assertContains(execution.output, "MANIFEST_COMPONENT")
        assertContains(execution.output, "AndroidManifest.xml:3")
    }

    @Test
    fun `dead accepts repeated keep rule files and explains their evidence`(@TempDir projectRoot: Path) {
        projectRoot.resolve("first.pro").writeText("-dontwarn dev.fixture.**")
        projectRoot.resolve("second.pro").writeText("-keep class dev.kartograph.cli.KartographCli")
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--keep-rules",
            "first.pro",
            "--keep-rules",
            "second.pro",
            "--explain",
            "class:dev/kartograph/cli/KartographCli",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KEEP_RULE")
        assertContains(execution.output, "second.pro:1")
    }

    @Test
    fun `dead fails safely when a keep rule needs an unavailable external hierarchy`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText(hierarchyKeepRule)
        val isolatedClassRoot = copyClassToRoot(ExternalHierarchyLeaf::class.java, projectRoot.resolve("classes"))
        val arguments = deadArguments(projectRoot, "<manifest />", "--keep-rules", "rules.pro")
        arguments[2] = isolatedClassRoot.toString()

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "dependency hierarchy is incomplete")
        kotlin.test.assertFalse(execution.error.contains(projectRoot.toString()))
    }

    @Test
    fun `query preserves the remediation for an unavailable external hierarchy`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText(hierarchyKeepRule)
        val isolatedClassRoot = copyClassToRoot(ExternalHierarchyLeaf::class.java, projectRoot.resolve("classes"))

        val execution = execute(
            "query",
            externalLeafNodeId,
            "--classes",
            isolatedClassRoot.toString(),
            "--project",
            projectRoot.toString(),
            "--keep-rules",
            "rules.pro",
        )

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "dependency hierarchy is incomplete")
        assertContains(execution.error, "--classpath")
        kotlin.test.assertFalse(execution.error.contains(projectRoot.toString()))
    }

    @Test
    fun `dead resolves external hierarchy from repeated classpath directories`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText(hierarchyKeepRule)
        val isolatedClassRoot = copyClassToRoot(ExternalHierarchyLeaf::class.java, projectRoot.resolve("classes"))
        val emptyClasspath = projectRoot.resolve("empty-classpath").createDirectories()
        val dependencyClasspath = copyClassToRoot(
            ExternalHierarchyIntermediate::class.java,
            projectRoot.resolve("dependency-classes"),
        )
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--keep-rules",
            "rules.pro",
            "--classpath",
            emptyClasspath.toString(),
            "--classpath",
            dependencyClasspath.toString(),
            "--explain",
            externalLeafNodeId,
        )
        arguments[2] = isolatedClassRoot.toString()

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KEEP_RULE")
    }

    @Test
    fun `dead resolves external hierarchy from a jar classpath`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText(hierarchyKeepRule)
        val isolatedClassRoot = copyClassToRoot(ExternalHierarchyLeaf::class.java, projectRoot.resolve("classes"))
        val dependencyJar = projectRoot.resolve("dependency.jar")
        writeClassJar(ExternalHierarchyIntermediate::class.java, dependencyJar)
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--keep-rules",
            "rules.pro",
            "--classpath",
            dependencyJar.toString(),
            "--explain",
            externalLeafNodeId,
        )
        arguments[2] = isolatedClassRoot.toString()

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KEEP_RULE")
    }

    @Test
    fun `dead resolves JDK hierarchy referenced only by app classes`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText("-keep class * extends java.io.OutputStream")
        val isolatedClassRoot = copyClassToRoot(AppRuntimeHierarchyLeaf::class.java, projectRoot.resolve("classes"))
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--keep-rules",
            "rules.pro",
            "--explain",
            "class:${AppRuntimeHierarchyLeaf::class.java.name.replace('.', '/')}",
        )
        arguments[2] = isolatedClassRoot.toString()

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KEEP_RULE")
    }

    @Test
    fun `dead reports missing classpath without exposing its path`(@TempDir projectRoot: Path) {
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--classpath",
            projectRoot.resolve("private-segment/missing.jar").toString(),
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.FAILURE.code, execution.status)
        assertContains(execution.error, "classpath entry must be a class directory or JAR")
        kotlin.test.assertFalse(execution.error.contains(projectRoot.toString()))
    }

    @Test
    fun `dead keeps members selected by a plain keep block`(@TempDir projectRoot: Path) {
        projectRoot.resolve("rules.pro").writeText(
            "-keep class dev.kartograph.cli.KartographCli { *; }",
        )
        val arguments = deadArguments(
            projectRoot,
            "<manifest />",
            "--keep-rules",
            "rules.pro",
            "--explain",
            "method:dev/kartograph/cli/KartographCli#run" +
                "([Ljava/lang/String;Ljava/io/PrintStream;Ljava/io/PrintStream;)I",
        )

        val execution = execute(*arguments)

        assertEquals(ExitStatus.SUCCESS.code, execution.status)
        assertContains(execution.output, "KEEP_RULE")
    }

    @Test
    fun `exit status values remain distinct`() {
        assertEquals(0, ExitStatus.SUCCESS.code)
        assertEquals(1, ExitStatus.FINDINGS.code)
        assertEquals(2, ExitStatus.FAILURE.code)
        assertEquals(64, ExitStatus.USAGE.code)
    }

    private fun execute(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(
            arguments = arguments,
            output = PrintStream(output),
            error = PrintStream(error),
        )
        return Execution(status, output.toString(), error.toString())
    }

    private fun git(root: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { output }
    }

    private fun deadArguments(projectRoot: Path, manifest: String, vararg extra: String): Array<String> {
        projectRoot.resolve("AndroidManifest.xml").writeText(manifest)
        projectRoot.resolve("res").createDirectories()
        return arrayOf(
            "dead",
            "--classes",
            classRoot.toString(),
            "--project",
            projectRoot.toString(),
            "--manifest",
            "AndroidManifest.xml",
            "--resources",
            "res",
            "--namespace",
            "dev.kartograph.cli",
            *extra,
        )
    }

    private val classRoot: Path
        get() = Path.of(requireNotNull(KartographCli::class.java.protectionDomain.codeSource).location.toURI())

    private fun copyClassToRoot(type: Class<*>, root: Path): Path {
        val entryName = type.name.replace('.', '/') + ".class"
        val target = root.resolve(entryName)
        target.parent.createDirectories()
        requireNotNull(type.classLoader.getResourceAsStream(entryName)).use { input ->
            Files.copy(input, target)
        }
        return root
    }

    private fun writeClassJar(type: Class<*>, jar: Path) {
        val entryName = type.name.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(entryName))
            requireNotNull(type.classLoader.getResourceAsStream(entryName)).use { input -> input.copyTo(output) }
            output.closeEntry()
        }
    }

    private fun compileJavaCycle(root: Path): Path {
        val sources = root.resolve("cycle-sources")
        val classes = root.resolve("cycle-classes").createDirectories()
        val first = sources.resolve("first/First.java")
        val second = sources.resolve("second/Second.java")
        first.parent.createDirectories()
        second.parent.createDirectories()
        first.writeText("package first; public class First { public void call() { new second.Second().call(); } }")
        second.writeText("package second; public class Second { public void call() { new first.First().call(); } }")
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        check(compiler.run(null, null, null, "-d", classes.toString(), first.toString(), second.toString()) == 0)
        return classes
    }

    private val hierarchyKeepRule: String
        get() = "-keep class * extends ${ExternalHierarchyTarget::class.java.name}"

    private val externalLeafNodeId: String
        get() = "class:${ExternalHierarchyLeaf::class.java.name.replace('.', '/')}"

    private data class Execution(
        val status: Int,
        val output: String,
        val error: String,
    )
}

private open class ExternalHierarchyTarget

private open class ExternalHierarchyIntermediate : ExternalHierarchyTarget()

private class ExternalHierarchyLeaf : ExternalHierarchyIntermediate()

private class AppRuntimeHierarchyLeaf : java.io.ByteArrayOutputStream()
