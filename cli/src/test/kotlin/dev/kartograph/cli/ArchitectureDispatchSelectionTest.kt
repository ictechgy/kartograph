package dev.kartograph.cli

import dev.kartograph.analysis.ArchitectureGraph
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.cli.fixture.defaultbridge.BridgeFixture
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassIndexCache
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.GraphExportIndexOptions
import dev.kartograph.index.RuntimeLimitationScanner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** dispatch 선택만 바꾸며 기존 명령의 출력·실패 경계와 후속 전체 인덱싱을 보존한다. */
class ArchitectureDispatchSelectionTest {
    @Test
    fun `javac cross package interface and subclass fanout preserves authored dependencies`(@TempDir root: Path) {
        val classes = fanout(root)
        val full = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val selected = selected(classes)
        val call = NodeId("method:client/Entry#call(Lapi/Task;Lapi/Base;)V")
        val targets = setOf(
            NodeId("method:impla/First#work()V"), NodeId("method:implb/Second#work()V"),
            NodeId("method:impla/Child#run()V"), NodeId("method:implb/Child#run()V"),
        )
        assertEquals(targets, full.outgoingEdgesFrom(call).filter { it.origin == EdgeOrigin.DISPATCH_MODEL }.map { it.target }.toSet())
        assertFalse(selected.edges.any { it.origin == EdgeOrigin.DISPATCH_MODEL })
        assertEquals(full.nodes, selected.nodes)
        assertEquals(full.edges.filter { it.origin != EdgeOrigin.DISPATCH_MODEL }, selected.edges)
        val expectedDependencies = setOf(
            "api" to "impla", "api" to "implb", "client" to "api",
            "impla" to "api", "impla" to "body", "implb" to "api", "implb" to "body",
        )
        for (graph in listOf(full, selected)) {
            assertEquals(expectedDependencies, ArchitectureGraph.aggregate(graph).edges.map {
                it.source.value.removePrefix("unit:") to it.target.value.removePrefix("unit:")
            }.toSet())
        }
        val config = layers(root, """
            layers:
              - name: Api
                match: [api]
              - name: Client
                match: [client]
              - name: First
                match: [impla]
              - name: Second
                match: [implb]
              - name: Body
                match: [body]
            rules:
              - name: client may only declare api
                from: Client
                allow: [Api]
        """)
        val cycles = parity("cycles", listOf(classes), extra = listOf("--strict"), status = 1)
        assertContains(cycles.stdout, "api -> impla -> api\n")
        assertContains(cycles.stdout, "cycles: 1 findings\n")
        val rules = parity("rules", listOf(classes), config, listOf("--strict"), 0)
        assertEquals("rules: 0 findings, 0 unassigned\n", rules.stdout)
        val metrics = parity("metrics", listOf(classes), status = 0)
        assertEquals("module\tCa\tCe\tI\tA\tD\n" +
            "api\t3\t2\t0.400\t0.500\t0.100\n" +
            "body\t2\t0\t0.000\t0.000\t1.000\n" +
            "client\t0\t1\t1.000\t0.000\t0.000\n" +
            "impla\t1\t2\t0.667\t0.000\t0.333\n" +
            "implb\t1\t2\t0.667\t0.000\t0.333\n", metrics.stdout)
    }

    @Test
    fun `acyclic javac fanout retains strict success`(@TempDir root: Path) {
        val classes = compile(root, linkedMapOf(
            "api/Task.java" to "package api; public interface Task { void work(); }",
            "api/First.java" to "package api; public class First implements Task { public void work() {} }",
            "api/Second.java" to "package api; public class Second implements Task { public void work() {} }",
            "client/Entry.java" to "package client; public class Entry { public void call(api.Task task) { task.work(); } }",
        ))
        val full = ClassFileIndexer().index(listOf(classes))
        assertEquals(2, full.edges.count { it.origin == EdgeOrigin.DISPATCH_MODEL })
        val config = allLayers(root)
        assertEquals("cycles: 0 findings\n", parity("cycles", listOf(classes), extra = listOf("--strict"), status = 0).stdout)
        assertEquals("rules: 0 findings, 0 unassigned\n", parity("rules", listOf(classes), config, listOf("--strict"), 0).stdout)
        assertEquals("module\tCa\tCe\tI\tA\tD\napi\t1\t0\t0.000\t0.333\t0.667\nclient\t0\t1\t1.000\t0.000\t0.000\n",
            parity("metrics", listOf(classes), status = 0).stdout)
    }

    @Test
    fun `constant reflection reference retains real cycle rule findings and metric rows`(@TempDir root: Path) {
        val classes = runtime(root)
        val full = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val reduced = selected(classes)
        val caller = NodeId("method:alpha/Start#load()V")
        val target = NodeId("class:beta/Target")
        for (graph in listOf(full, reduced)) {
            val edges = graph.outgoingEdgesFrom(caller).filter { it.target == target }
            assertEquals(1, edges.size)
            assertEquals(EdgeKind.REFERENCE, edges.single().kind)
            assertEquals(EdgeOrigin.RUNTIME_MODEL, edges.single().origin)
            assertEquals(1, edges.single().weight)
            assertEquals(setOf("unit:alpha" to "unit:beta", "unit:beta" to "unit:alpha"),
                ArchitectureGraph.aggregate(graph).edges.map { it.source.value to it.target.value }.toSet())
        }
        val config = runtimeLayers(root)
        val cycles = parity("cycles", listOf(classes), extra = listOf("--strict"), status = 1)
        assertEquals("alpha -> beta -> alpha\n  weakest edge: alpha -> beta (reference, weight 1)\ncycles: 1 findings\n", cycles.stdout)
        val rules = parity("rules", listOf(classes), config, listOf("--strict"), 1)
        assertEquals("Start.java:3: method:alpha/Start#load()V (Alpha) -> class:beta/Target (Beta) [no beta]\n" +
            "  evidence: reference, weight 1\nrules: 1 findings, 0 unassigned\n", rules.stdout)
        assertEquals("module\tCa\tCe\tI\tA\tD\nalpha\t1\t1\t0.500\t0.000\t0.500\nbeta\t1\t1\t0.500\t0.000\t0.500\n",
            parity("metrics", listOf(classes), status = 0).stdout)
    }

    @Test
    fun `unresolved reflection observations survive without new architecture warnings`(@TempDir root: Path) {
        val classes = runtime(root)
        val full = ClassFileIndexer(callbackFacts = false).indexWithObservations(listOf(classes))
        val selection = ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(classes), options = noDispatch)
        assertTrue(selection.limitations.isEmpty())
        assertEquals(1, RuntimeLimitationScanner.sourceChannelCounts(full)["Start.java"])
        assertEquals(RuntimeLimitationScanner.sourceChannelCounts(full), RuntimeLimitationScanner.sourceChannelCounts(selection.indexed))
        val limitations = RuntimeLimitationScanner.scan(full, root)
        assertTrue(limitations.any { it.startsWith("reflection-strings: 1") }, limitations.toString())
        assertEquals(limitations, RuntimeLimitationScanner.scan(selection.indexed, root))
        val config = runtimeLayers(root)
        for (command in commands) {
            val result = parity(command, listOf(classes), config, status = 0)
            assertEquals("", result.stderr)
            assertFalse(result.stdout.contains("reflection-strings:"))
        }
    }

    @Test
    fun `rules preserve unassigned allowed denied and exact explanation outcomes`(@TempDir root: Path) {
        val classes = runtime(root)
        val denied = runtimeLayers(root)
        val allowed = layers(root, """
            layers:
              - name: Alpha
                match: [alpha]
              - name: Beta
                match: [beta]
            rules:
              - from: Alpha
                allow: [Beta]
              - from: Beta
                deny: [Beta]
        """, "allowed.yml")
        assertEquals("rules: 0 findings, 0 unassigned\n", parity("rules", listOf(classes), allowed, listOf("--strict"), 0).stdout)
        for (symbol in listOf("method:alpha/Start#load()V", "alpha.Start.load", "load")) {
            val explained = parity("rules", listOf(classes), denied, listOf("--explain", symbol, "--strict"), 1)
            assertContains(explained.stdout, "method:alpha/Start#load()V layer: Alpha\n  matched: alpha against 'alpha'\n")
            assertContains(explained.stdout, "rules: 1 findings, 0 unassigned\n")
        }
        for (symbol in listOf("Missing", "<init>")) {
            val explanation = parity("rules", listOf(classes), denied, listOf("--explain", symbol), 64)
            assertEquals("error: explanation symbol must match exactly one declaration\n", explanation.stderr)
            assertContains(explanation.stdout, "[no beta]\n  evidence: reference, weight 1\n")
            assertFalse(explanation.stdout.contains("rules:"))
        }
        val unassigned = layers(root, "layers:\n  - name: Alpha\n    match: [alpha]\n", "unassigned.yml")
        val result = parity("rules", listOf(classes), unassigned, listOf("--strict", "--explain", "class:beta/Target"), 1)
        assertEquals("info: 3 declarations are not assigned to a layer\nclass:beta/Target layer: unassigned\nrules: 0 findings, 3 unassigned\n", result.stdout)
    }

    @Test
    fun `directory jar and duplicate root precedence retain exact old results`(@TempDir root: Path) {
        val original = runtime(root.resolve("original"))
        val replacement = compile(root.resolve("replacement"), mapOf(
            "alpha/Start.java" to "package alpha; public class Start { public void alternate() {} }",
        ))
        val originalJar = jar(original, root.resolve("original.jar"))
        val replacementJar = jar(replacement, root.resolve("replacement.jar"))
        val config = runtimeLayers(root)
        for (command in commands) {
            val directory = parity(command, listOf(original), config, status = 0)
            val archive = parity(command, listOf(originalJar), config, status = 0)
            sameExecution(directory, archive)
        }
        val cases = listOf(
            listOf(original, replacement) to true,
            listOf(replacement, original) to false,
            listOf(originalJar, replacementJar) to true,
            listOf(replacementJar, originalJar) to false,
            listOf(original, originalJar) to true,
            listOf(originalJar, original) to true,
        )
        for ((roots, hasReflection) in cases) {
            val cycles = parity("cycles", roots, extra = listOf("--strict"), status = if (hasReflection) 1 else 0)
            assertContains(cycles.stdout, "cycles: ${if (hasReflection) 1 else 0} findings\n")
            parity("rules", roots, config, listOf("--strict"), if (hasReflection) 1 else 0)
            parity("metrics", roots, status = 0)
            val full = ClassFileIndexer(callbackFacts = false).indexWithObservations(roots)
            val reduced = ClassFileIndexer(callbackFacts = false).indexForGraphExport(roots, options = noDispatch).indexed
            assertEquals(full.graph.nodes, reduced.graph.nodes)
            assertEquals(full.declarationsByRoot, reduced.declarationsByRoot)
            assertEquals(full.selectedRootByNode, reduced.selectedRootByNode)
            assertEquals(0, reduced.selectedRootByNode[NodeId("class:alpha/Start")])
            assertEquals(hasReflection, reduced.graph.contains(NodeId("method:alpha/Start#load()V")))
            assertEquals(!hasReflection, reduced.graph.contains(NodeId("method:alpha/Start#alternate()V")))
        }
    }

    @Test
    fun `missing empty truncated jar service and yaml failures preserve status and bytes`(@TempDir root: Path) {
        val classes = runtime(root.resolve("valid"))
        val config = allLayers(root)
        val empty = Files.createDirectories(root.resolve("empty"))
        val truncated = Files.createDirectories(root.resolve("truncated"))
        Files.write(truncated.resolve("Broken.class"), byteArrayOf(0, 1, 2))
        val malformedJar = Files.write(root.resolve("malformed.jar"), byteArrayOf(0, 1, 2))
        val truncatedJar = jar(truncated, root.resolve("truncated.jar"))
        val emptyJar = jar(empty, root.resolve("empty.jar"))
        val wrongType = Files.writeString(root.resolve("classes.txt"), "not a class root")
        val cases = listOf(
            root.resolve("missing") to "class root does not exist; build the project first",
            empty to "no compiled declarations found; check class roots and build the project",
            emptyJar to "no compiled declarations found; check class roots and build the project",
            truncated to "invalid class file",
            malformedJar to "class JAR cannot be read",
            truncatedJar to "invalid class file in class JAR",
            wrongType to "class root must be a class directory or JAR",
        )
        for ((input, message) in cases) for (command in commands) {
            val result = parity(command, listOf(input), config, status = 2)
            assertEquals("", result.stdout)
            assertEquals("error: $message\n", result.stderr)
        }
        val registry = classes.resolve("META-INF/services/java.lang.Runnable")
        Files.createDirectories(registry.parent)
        for (bytes in listOf("not a provider!".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28))) {
            Files.write(registry, bytes)
            val badServiceJar = jar(classes, root.resolve("service-${bytes.size}.jar"))
            for (input in listOf(classes, badServiceJar)) for (command in commands) {
                val result = parity(command, listOf(input), config, status = 2)
                assertEquals("", result.stdout)
                assertEquals("error: service provider metadata cannot be read or is invalid; check META-INF/services inputs\n", result.stderr)
            }
        }
        Files.delete(registry)
        val malformed = layers(root, "layers:\n  - name: Alpha\n    match: [alpha]\nrules:\n  - from: Alpha\n    denied: [Alpha]\n", "malformed.yml")
        val badYaml = parity("rules", listOf(classes), malformed, status = 2)
        assertEquals("", badYaml.stdout)
        assertEquals("error: invalid layer configuration: rules[0] has unknown key 'denied'\n", badYaml.stderr)
        val unreadableText = Files.write(root.resolve("invalid-utf8.yml"), byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals("error: unable to read layer configuration\n", parity("rules", listOf(classes), unreadableText, status = 2).stderr)
        assertEquals("error: layer configuration does not exist\n", parity("rules", listOf(classes), root.resolve("missing.yml"), status = 2).stderr)
        assertEquals("error: invalid class file\n", parity("rules", listOf(truncated), malformed, status = 2).stderr)
        assertEquals("error: class root does not exist; build the project first\n",
            parity("rules", listOf(root.resolve("missing")), root.resolve("missing.yml"), status = 2).stderr)
    }

    @Test
    fun `help usage and invalid paths preserve old parser exits`(@TempDir root: Path) {
        val classes = runtime(root)
        for (command in commands) {
            for (arguments in listOf(listOf("--help"), listOf("-h"))) sameExecution(execute(command, arguments, true), execute(command, arguments))
            for (arguments in listOf(emptyList(), listOf("--classes"), listOf("--classes", "bad\u0000path"), listOf("--unknown"))) {
                val previous = execute(command, arguments, true)
                assertEquals(64, previous.status)
                sameExecution(previous, execute(command, arguments))
            }
        }
        val unsupported = execute("metrics", listOf("--classes", classes.toString(), "--strict"), true)
        assertEquals(64, unsupported.status)
        sameExecution(unsupported, execute("metrics", listOf("--classes", classes.toString(), "--strict")))
    }

    @Test
    fun `old indexing catch still encloses action but never swallows unrelated public exceptions`(@TempDir root: Path) {
        val classes = runtime(root)
        val config = allLayers(root)
        for (command in commands) {
            val arguments = arguments(command, listOf(classes), config)
            for (oracle in listOf(true, false)) {
                val errors = ByteArrayOutputStream()
                val outsideBoundary = object : PrintStream(ByteArrayOutputStream()) {
                    override fun println(value: String?) { throw IllegalArgumentException("public action boundary") }
                }
                val failure = assertFailsWith<IllegalArgumentException> {
                    invoke(command, arguments, outsideBoundary, PrintStream(errors), oracle)
                }
                assertEquals(IllegalArgumentException::class.java, failure.javaClass)
                assertEquals("public action boundary", failure.message)
                assertEquals("", errors.toString(Charsets.UTF_8))
                val withinBoundary = object : PrintStream(ByteArrayOutputStream()) {
                    override fun println(value: String?) { throw ClassIndexingException("action inside indexing catch") }
                }
                assertEquals(2, invoke(command, arguments, withinBoundary, PrintStream(errors), oracle))
                assertEquals("error: action inside indexing catch\n", errors.toString(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `architecture leaves later default cold warm graph capture and dead dispatch intact`(@TempDir root: Path) {
        val classes = fanout(root)
        val roots = listOf(classes)
        val baseline = ClassFileIndexer().indexWithObservations(roots)
        val cache = ClassIndexCache(root.resolve("index-cache"))
        val cold = ClassFileIndexer(cache).indexWithObservations(roots)
        assertEquals(baseline.statistics.classFiles, cold.statistics.cacheMisses)
        assertEquals(baseline.statistics.classFiles, cold.statistics.parsedClasses)
        assertEquals(0, cold.statistics.cacheHits)
        sameGraph(baseline.graph, cold.graph)
        val fire = NodeId("method:client/Entry#fire(Ljava/lang/Runnable;)V")
        val external = NodeId("method:impla/External#run()V")
        assertTrue(baseline.graph.edges.any { it.source == fire && it.target == external && it.origin == EdgeOrigin.DISPATCH_MODEL })
        val evidence = listOf(RetentionEvidence(fire, RetentionReason.KEEP_RULE, null))
        assertTrue(external in ReachabilityAnalyzer.analyze(baseline.graph, evidence).reachableNodeIds)
        assertFalse(external in ReachabilityAnalyzer.analyze(selected(classes), evidence).reachableNodeIds)
        Files.writeString(root.resolve("keep.pro"), "-keep class client.Entry { *; }\n")
        Files.writeString(root.resolve("AndroidManifest.xml"), "<manifest />\n")
        Files.createDirectories(root.resolve("res"))
        val graphArgs = listOf("graph", "--classes", classes.toString(), "--format", "json")
        val snapshotArgs = listOf("snapshot", "--classes", classes.toString(), "--project", root.toString(), "--keep-rules", "keep.pro")
        val cachedSnapshotArgs = snapshotArgs + listOf("--index-cache", "snapshot-cache")
        val deadArgs = listOf("dead", "--classes", classes.toString(), "--project", root.toString(),
            "--manifest", "AndroidManifest.xml", "--resources", "res", "--namespace", "client", "--keep-rules", "keep.pro")
        val controls = listOf(graphArgs, snapshotArgs, cachedSnapshotArgs, deadArgs,
            deadArgs + "--include-private-members", deadArgs + listOf("--explain", external.value))
        val config = allLayers(root)
        val before = controls.map(::cli)
        before.forEach { assertEquals(0, it.status, it.stderr) }
        assertContains(before[0].stdout, "\"origin\": \"dispatchModel\"")
        assertContains(before.last().stdout, "reachable\t$external\tvia\t$fire -> $external")
        for (command in commands) parity(command, roots, config, status = 0)
        val later = ClassFileIndexer().indexWithObservations(roots)
        val warm = ClassFileIndexer(cache).indexWithObservations(roots)
        assertEquals(baseline.statistics.classFiles, warm.statistics.cacheHits)
        assertEquals(0, warm.statistics.cacheMisses)
        assertEquals(0, warm.statistics.parsedClasses)
        for (indexed in listOf(later, warm)) {
            sameGraph(baseline.graph, indexed.graph)
            assertEquals(baseline.declarationsByRoot, indexed.declarationsByRoot)
            assertEquals(baseline.selectedRootByNode, indexed.selectedRootByNode)
        }
        controls.zip(before).forEach { (arguments, expected) -> sameExecution(expected, cli(arguments)) }
        val captureBaseline = ClassFileIndexer().indexWithObservations(roots, emptyList()).graph
        for (result in listOf(before[1], before[2])) {
            val captured = QuerySnapshotCodec.parse(result.stdout).graph
            assertTrue(captured.edges.any { it.source == fire && it.target == external && it.origin == EdgeOrigin.DISPATCH_MODEL })
            sameGraph(captureBaseline, captured)
        }
    }

    @Test
    fun `explicit dispatch selection preserves Kotlin metadata and bridge witnesses`(@TempDir root: Path) {
        val owner = "dev/kartograph/cli/fixture/defaultbridge/BridgeFixture"
        val compiled = Path.of(BridgeFixture::class.java.protectionDomain.codeSource.location.toURI()).resolve("$owner.class")
        val file = root.resolve("classes/$owner.class")
        Files.createDirectories(file.parent)
        Files.copy(compiled, file)
        val classes = root.resolve("classes")
        val full = ClassFileIndexer(callbackFacts = false).index(listOf(classes))
        val reduced = selected(classes)
        assertTrue(full.nodes.values.any { it.kotlinDefaultBridge != null })
        assertEquals(full.nodes, reduced.nodes)
        assertEquals(full.edges.filter { it.origin != EdgeOrigin.DISPATCH_MODEL }, reduced.edges)
        val config = allLayers(root)
        for (command in commands) parity(command, listOf(classes), config, status = 0)
    }

    private val commands = listOf("cycles", "rules", "metrics")
    private val noDispatch = GraphExportIndexOptions(excludedOrigins = setOf(EdgeOrigin.DISPATCH_MODEL))

    private fun selected(classes: Path): CodeGraph =
        ClassFileIndexer(callbackFacts = false).indexForGraphExport(listOf(classes), options = noDispatch).indexed.graph

    private fun fanout(root: Path): Path = compile(root, linkedMapOf(
        "api/Task.java" to "package api; public interface Task { void work(); }",
        "api/Base.java" to "package api; public class Base { public void run() {} }",
        "impla/First.java" to "package impla; public class First implements api.Task { public void work() { body.Hit.touch(); } }",
        "implb/Second.java" to "package implb; public class Second implements api.Task { public void work() { body.Hit.touch(); } }",
        "impla/Child.java" to "package impla; public class Child extends api.Base { public void run() { body.Hit.touch(); } }",
        "implb/Child.java" to "package implb; public class Child extends api.Base { public void run() { body.Hit.touch(); } }",
        "impla/External.java" to "package impla; public class External implements Runnable { public void run() { new body.DispatchOnly(); } }",
        "body/Hit.java" to "package body; public class Hit { public static void touch() {} }",
        "body/DispatchOnly.java" to "package body; public class DispatchOnly {}",
        "client/Entry.java" to """
            package client;
            public class Entry {
                public static void call(api.Task task, api.Base base) { task.work(); base.run(); }
                public static void fire(Runnable runnable) { runnable.run(); }
            }
        """.trimIndent(),
    ))

    private fun runtime(root: Path): Path = compile(root, linkedMapOf(
        "alpha/Start.java" to """
            package alpha;
            public class Start {
                public void load() throws Exception { Class.forName("beta.Target"); }
                public void unknown(String name) throws Exception { Class.forName(name); }
            }
        """.trimIndent(),
        "beta/Target.java" to "package beta; public class Target { public alpha.Start start; }",
    ))

    private fun compile(root: Path, sources: Map<String, String>): Path {
        val files = sources.map { (name, text) ->
            root.resolve("src/$name").also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        }
        val classes = Files.createDirectories(root.resolve("classes"))
        val errors = ByteArrayOutputStream()
        assertEquals(0, requireNotNull(ToolProvider.getSystemJavaCompiler()).run(null, null, errors,
            "--release", "17", "-g", "-d", classes.toString(), *files.map(Path::toString).toTypedArray()), errors.toString(Charsets.UTF_8))
        return classes
    }

    private fun jar(directory: Path, target: Path): Path {
        JarOutputStream(Files.newOutputStream(target)).use { output ->
            Files.walk(directory).use { files ->
                files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    output.putNextEntry(JarEntry(directory.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
        return target
    }

    private fun layers(root: Path, text: String, name: String = "layers.yml"): Path =
        Files.writeString(root.resolve(name), text.trimIndent() + "\n")

    private fun allLayers(root: Path): Path = layers(root, "layers:\n  - name: All\n    match: ['*']\n")

    private fun runtimeLayers(root: Path): Path = layers(root, """
        layers:
          - name: Alpha
            match: [alpha]
          - name: Beta
            match: [beta]
        rules:
          - name: no beta
            from: Alpha
            deny: [Beta]
    """)

    private fun arguments(command: String, roots: List<Path>, config: Path?, extra: List<String> = emptyList()): List<String> =
        roots.flatMap { listOf("--classes", it.toString()) } +
            (if (command == "rules") listOf("--config", requireNotNull(config).toString()) else emptyList()) + extra

    private fun parity(command: String, roots: List<Path>, config: Path? = null, extra: List<String> = emptyList(), status: Int): Execution {
        val arguments = arguments(command, roots, config, extra)
        val previous = execute(command, arguments, true)
        assertEquals(status, previous.status, "$command old oracle: ${previous.stderr}")
        val current = execute(command, arguments)
        sameExecution(previous, current)
        return current
    }

    private fun execute(command: String, arguments: List<String>, oracle: Boolean = false): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = invoke(command, arguments, PrintStream(output, true, Charsets.UTF_8), PrintStream(error, true, Charsets.UTF_8), oracle)
        return Execution(status, output.toByteArray(), error.toByteArray())
    }

    private fun invoke(command: String, arguments: List<String>, output: PrintStream, error: PrintStream, oracle: Boolean): Int =
        if (!oracle) KartographCli.run((listOf(command) + arguments).toTypedArray(), output, error) else when (command) {
            "cycles" -> FullIndexArchitectureCommandOracle.cycles(arguments, output, error)
            "rules" -> FullIndexArchitectureCommandOracle.rules(arguments, output, error)
            "metrics" -> FullIndexArchitectureCommandOracle.metrics(arguments, output, error)
            else -> throw IllegalArgumentException("unsupported oracle command: $command")
        }

    private fun cli(arguments: List<String>): Execution = execute(arguments.first(), arguments.drop(1))

    private fun sameExecution(expected: Execution, actual: Execution) {
        assertEquals(expected.status, actual.status, actual.stderr)
        assertContentEquals(expected.output, actual.output, "stdout must be byte-for-byte identical")
        assertContentEquals(expected.error, actual.error, "stderr must be byte-for-byte identical")
    }

    private fun sameGraph(expected: CodeGraph, actual: CodeGraph) {
        assertEquals(expected.nodes, actual.nodes)
        assertEquals(expected.edges, actual.edges)
        assertEquals(expected.externalCalls, actual.externalCalls)
        assertEquals(expected.serviceProviders, actual.serviceProviders)
        assertEquals(expected.enclosures, actual.enclosures)
        assertEquals(expected.callbackArguments, actual.callbackArguments)
        assertEquals(expected.parameterUses, actual.parameterUses)
        assertEquals(expected.lambdaEscapes, actual.lambdaEscapes)
        assertEquals(expected.locatedCompilerReferences, actual.locatedCompilerReferences)
        assertEquals(expected.compilerCallPositionsCaptured, actual.compilerCallPositionsCaptured)
    }

    private data class Execution(val status: Int, val output: ByteArray, val error: ByteArray) {
        val stdout: String get() = output.toString(Charsets.UTF_8)
        val stderr: String get() = error.toString(Charsets.UTF_8)
    }
}
