package dev.kartograph.gradle

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.InputHints
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.InputHint
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.ProvenanceVerifier
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/** 실제 생성기/컴파일러의 출처 공백 재현이다. generated를 runtime root로 가정하지 않는다. */
class AndroidSafeArgsIntegrationTest {
    private val profile = SafeArgsNativeProfile.select(System.getenv("KARTOGRAPH_SAFEARGS_NATIVE_PROFILE"))
    private fun runner(root: Path) = profile.runner(root, System.getenv("KARTOGRAPH_SAFEARGS_GRADLE_HOME"), Runtime.version().feature())
    @Test
    fun `SafeArgs 실제 생성물과 수기 대조군의 출처 및 bytecode 호출을 구분한다`(@TempDir root: Path) {
        SafeArgsBuildEvidence.withFinalCapture({ preserveEvidence(root) }) { observe(root) }
    }

    private fun observe(root: Path) {
        fixture(root)
        val result = SafeArgsBuildEvidence.build(root.resolve("build/safeargs-observations")) {
            runner(root).withArguments(
                "observeSafeArgsDebug", "observeSafeArgsRelease", "kartographSnapshotDebug", "kartographSnapshotRelease",
                "--stacktrace", "--configuration-cache", "--no-build-cache",
            ).build()
        }
        for (variant in listOf("Debug", "Release")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":generateSafeArgs$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":compile${variant}JavaWithJavac")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":observeSafeArgs$variant")?.outcome)
            assertEquals(null, result.task(":test${variant}UnitTest"))
            val snap = snapshot(root, variant.lowercase())
            val generated = listOf("p/StartFragmentArgs", "p/StartFragmentDirections",
                "p/StartFragmentDirections\$Go", "p/StartFragmentDirections\$Idle")
            val manual = listOf("p/ManualArgs", "p/ManualDirections", "other/StartFragmentArgs")
            for (name in generated + manual) {
                val node = snap.graph.nodes.getValue(NodeId("class:$name"))
                // 9b9082의 재현 기대값이다. 구현 후 생성물만 증명하는 기대값으로 바꾼다.
                assertFalse(node.synthesized, name)
                assertFalse(NodeAttribute.GENERATED_INPUT in node.attributes, name)
            }
            assertTrue(InputHint.ANDROID_GENERATED_PROVENANCE_UNVERIFIED in InputHints.detect(1, 1, 1, snap.graph))
            val entry = NodeId("method:p/MainActivity#onCreate(Landroid/os/Bundle;)V")
            val usedFactory = NodeId("method:p/StartFragmentDirections#go(I)Lp/StartFragmentDirections\$Go;")
            val unusedFactory = NodeId("method:p/StartFragmentDirections#idle(I)Lp/StartFragmentDirections\$Idle;")
            assertTrue(snap.graph.outgoingEdgesFrom(entry).any {
                it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE && it.target == usedFactory
            })
            assertFalse(snap.graph.incomingEdgesTo(unusedFactory).any {
                it.kind == EdgeKind.CALL && it.origin == EdgeOrigin.BYTECODE
            })
            val reached = ReachabilityAnalyzer.analyze(snap.graph, snap.retention)
            assertTrue(usedFactory in reached.reachableNodeIds)
            val dead = DeadFindings.collect(snap.graph, reached)
            for (name in manual) {
                val id = NodeId("class:$name")
                assertFalse(id in reached.reachableNodeIds, name)
                assertTrue(dead.any { it.nodeId == id }, name)
            }
            // 미사용 생성 action은 독립 대조군이다. 구조적 과잉 보존을 false-dead로 단정하지 않는다.
            for (name in generated + manual) {
                val id = NodeId("class:$name")
                println("safe-args-native variant=$variant id=$id synthesized=${snap.graph.nodes.getValue(id).synthesized}" +
                    " reachable=${id in reached.reachableNodeIds} path=${reached.pathFromRootTo(id)}" +
                    " dead=${dead.any { it.nodeId == id }}")
            }
            val observation = Files.readString(root.resolve("build/safeargs-observations/$variant.json"))
            println("safe-args-native observation=$variant bytes=${observation.toByteArray(Charsets.UTF_8).size}")
            assertTrue(observation.contains("StartFragmentArgs.java"))
            assertTrue(observation.contains("ManualArgs.class"))
            assertTrue(observation.contains("p/StartFragmentArgs.class"))
            assertTrue(observation.contains("other/StartFragmentArgs.class"))
            assertTrue(observation.contains("StartFragmentDirections\$Idle.class"))
            val expectedDefault = if (variant == "Debug") "11" else "22"
            assertTrue(observation.contains("\\\"seed\\\", $expectedDefault"), observation)
            println("safe-args-native complete=$variant freshness=matched controls=checked")
        }
        observeColdProduct(root.resolve("cold-product"), root)
    }

    private fun observeColdProduct(root: Path, baselineRoot: Path) {
        fixture(root)
        val results = root.resolve("build/safeargs-product"); Files.createDirectories(results)
        val result = SafeArgsBuildEvidence.build(results) {
            runner(root).withArguments("observeSafeArgsDebug", "observeSafeArgsRelease", "kartographGraphDebug", "kartographDeadDebug", "kartographGraphRelease", "kartographDeadRelease",
                "--stacktrace", "--no-configuration-cache", "--no-build-cache").build()
        }
        val rows = mutableListOf<Map<String, Any?>>()
        for (variant in listOf("Debug", "Release")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":observeSafeArgs$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":generateSafeArgs$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":kartographVerifySafeArgs$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":kartographGraph$variant")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, result.task(":kartographDead$variant")?.outcome)
            val snap = snapshot(root, variant.lowercase())
            val old = snapshot(baselineRoot, variant.lowercase())
            val evidence = requireNotNull(snap.generationEvidence) { "cold generation evidence missing: ${snap.limitations}" }
            assertEquals(1, evidence.receipts.size)
            assertEquals(3, evidence.receipts.single().sources.size)
            assertEquals("current-cold-success", evidence.observations.single().originBasis)
            assertEquals("SUCCEEDED", evidence.observations.single().currentGenerationStatus)
            val generated = listOf("p/StartFragmentArgs", "p/StartFragmentDirections", "p/StartFragmentDirections\$Go", "p/StartFragmentDirections\$Idle")
            val manual = listOf("p/ManualArgs", "p/ManualDirections", "other/StartFragmentArgs")
            for (name in generated) {
                val node = snap.graph.nodes.getValue(NodeId("class:$name"))
                assertTrue(node.synthesized, name)
                assertTrue(NodeAttribute.GENERATED_SOURCE_EVIDENCE in node.attributes, name)
                assertTrue(evidence.declarations.any { it.owner == node.id }, name)
            }
            for (name in manual) assertFalse(snap.graph.nodes.getValue(NodeId("class:$name")).synthesized, name)
            assertEquals(old.graph.edges, snap.graph.edges, "generation classification must not add calls or dispatch")
            assertEquals(old.retention, snap.retention, "generation classification must not create roots")
            assertLiveReports(root, variant, evidence, "current-cold-success/SUCCEEDED", results.resolve("live-$variant"))
            val classes = root.resolve(evidence.declarations.first().originalRoot)
            val publicHome = System.getenv("KARTOGRAPH_PUBLIC_025_HOME")
            if (publicHome != null) {
            val public = runCli(publicHome, listOf("graph", "--classes", classes.toString(), "--format", "json"), results.resolve("public-$variant"))
            val publicNodes = (public["nodes"] as List<*>).map { it as Map<*, *> }.associateBy { it["usr"] }
            for (name in generated + manual) assertEquals(false, publicNodes["class:$name"]?.get("synthesized"), name)
            val publicEdges = (public["edges"] as List<*>).map { it as Map<*, *> }
            assertTrue(publicEdges.any { it["source"] == "method:p/MainActivity#onCreate(Landroid/os/Bundle;)V" &&
                it["target"] == "method:p/StartFragmentDirections#go(I)Lp/StartFragmentDirections\$Go;" && it["kind"] == "call" })
            assertFalse(publicEdges.any { it["target"] == "method:p/StartFragmentDirections#idle(I)Lp/StartFragmentDirections\$Idle;" && it["kind"] == "call" })
            }
            val snapshotPath = root.resolve("build/reports/kartograph/${variant.lowercase()}-snapshot.json")
            Files.copy(snapshotPath, results.resolve("$variant-cold-snapshot.json"))
            val currentHome = System.getenv("KARTOGRAPH_CURRENT_CLI_HOME")
            if (currentHome != null) {
            val query = runCli(currentHome,
                listOf("query", "class:p/StartFragmentArgs", "--graph-file", snapshotPath.toString()), results.resolve("query-$variant"))
            assertEquals("found", query["status"])
            assertEquals("class:p/StartFragmentArgs", ((query["result"] as Map<*, *>)["subject"] as Map<*, *>)["usr"])
            assertGenerationSummary(query, evidence, "current-cold-success", "SUCCEEDED")
            }
            val observedProfile = (dev.kartograph.export.McpJsonCodec.parse(Files.readString(
                root.resolve("build/safeargs-observations/$variant.json"))) as Map<*, *>)["profile"]
            val actionHashes = evidence.receipts.single().inputs.filter { it.role == "generatorActionArtifact" }.map { it.sha256 }
            val row = mapOf("profile" to observedProfile, "profileObservationTaskOutcome" to "SUCCESS",
                "actionHashes" to actionHashes, "variant" to variant, "snapshotSha256" to sha(snapshotPath), "sources" to evidence.receipts.single().sources.size,
                "declarations" to evidence.declarations.map { it.owner.value }, "publicVersion" to if (publicHome != null) "0.25.0" else null,
                "publicGenerated" to if (publicHome != null) false else null,
                "coldGenerated" to true, "manualUnchanged" to true, "callsUnchanged" to true, "retentionUnchanged" to true,
                "queryExit" to if (currentHome != null) 0 else null, "liveGraph" to true, "liveDead" to true,
                "scope" to "actual cold Java SafeArgs 2.10.2; current task roots independently attributed")
            rows += row
            Files.writeString(results.resolve("receipt.json"), dev.kartograph.export.McpJsonCodec.render(mapOf("variants" to rows)))
            println("safe-args-product complete=$variant source-class=matched public025=${publicHome != null} query=${currentHome != null}")
        }
        // 실제 UP-TO-DATE에서 과거 출처와 새 소비 invocation을 각각 검증한다.
        val warm = SafeArgsBuildEvidence.build(results.resolve("warm")) {
            runner(root).withArguments("observeSafeArgsDebug", "kartographGraphDebug", "kartographDeadDebug", "--stacktrace",
                "--no-configuration-cache", "--no-build-cache").build()
        }
        assertEquals(TaskOutcome.UP_TO_DATE, warm.task(":generateSafeArgsDebug")?.outcome)
        val profileObservationOutcome = requireNotNull(warm.task(":observeSafeArgsDebug")).outcome
        assertTrue(profileObservationOutcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE))
        val warmSnapshot = snapshot(root, "debug")
        val coldSnapshot = QuerySnapshotCodec.parse(Files.readString(results.resolve("Debug-cold-snapshot.json")))
        val coldEvidence = requireNotNull(coldSnapshot.generationEvidence)
        val warmEvidence = requireNotNull(warmSnapshot.generationEvidence)
        val observed = warmEvidence.observations.single()
        assertEquals("historical-byte-equivalence", observed.originBasis)
        assertEquals("UP_TO_DATE", observed.currentGenerationStatus)
        assertEquals(null, observed.currentAttempt)
        assertEquals(coldEvidence.receipts, warmEvidence.receipts)
        assertEquals(coldEvidence.observations.single().originContext, observed.originContext)
        assertTrue(coldEvidence.observations.single().invocation != observed.invocation)
        assertEquals(coldSnapshot.graph.nodes, warmSnapshot.graph.nodes)
        assertEquals(coldSnapshot.graph.edges, warmSnapshot.graph.edges)
        assertLiveReports(root, "Debug", warmEvidence, "historical-byte-equivalence/UP_TO_DATE", results.resolve("live-warm"))
        val warmPath = root.resolve("build/reports/kartograph/debug-snapshot.json")
        Files.copy(warmPath, results.resolve("Debug-warm-snapshot.json"))
        val currentHome = System.getenv("KARTOGRAPH_CURRENT_CLI_HOME")
        if (currentHome != null) {
            val query = runCli(currentHome, listOf("query", "class:p/StartFragmentArgs", "--graph-file", warmPath.toString()), results.resolve("query-warm"))
            assertEquals("found", query["status"])
            assertEquals("class:p/StartFragmentArgs", ((query["result"] as Map<*, *>)["subject"] as Map<*, *>)["usr"])
            assertGenerationSummary(query, warmEvidence, "historical-byte-equivalence", "UP_TO_DATE")
        }
        Files.writeString(results.resolve("warm.json"), dev.kartograph.export.McpJsonCodec.render(mapOf(
            "profileObservationTaskOutcome" to profileObservationOutcome.name,
            "profile" to (dev.kartograph.export.McpJsonCodec.parse(Files.readString(
                root.resolve("build/safeargs-observations/Debug.json"))) as Map<*, *>)["profile"],
            "actionHashes" to warmEvidence.receipts.single().inputs.filter { it.role == "generatorActionArtifact" }.map { it.sha256 },
            "originBasis" to observed.originBasis, "currentGenerationStatus" to observed.currentGenerationStatus,
            "currentAttempt" to observed.currentAttempt, "sameOrigin" to true, "newInvocation" to true,
            "snapshotSha256" to sha(warmPath), "queryExit" to if (currentHome != null) 0 else null, "liveGraph" to true, "liveDead" to true)))
        // 전체 출력에 수기 파일이 추가되면 원래 receipt가 이 파일까지 소유한다고 확대하지 않는다.
        Files.writeString(root.resolve(warmEvidence.receipts.single().outputRoot).resolve("p/HandwrittenExtra.java"),
            "package p; public class HandwrittenExtra {}")
        SafeArgsBuildEvidence.build(results.resolve("warm-extra")) {
            runner(root).withArguments("kartographGraphDebug", "kartographDeadDebug", "--stacktrace",
                "--no-configuration-cache", "--no-build-cache").build()
        }
        val extraSnapshot = snapshot(root, "debug")
        assertEquals(null, extraSnapshot.generationEvidence)
        assertTrue(extraSnapshot.limitations.any { it.startsWith("generation-unverified:") })
        val extraGraphPath = root.resolve("build/reports/kartograph/debug-graph.json")
        val extraGraph = dev.kartograph.export.McpJsonCodec.parse(Files.readString(extraGraphPath)) as Map<*, *>
        val extraNodes = (extraGraph["nodes"] as List<*>).map { it as Map<*, *> }.associateBy { it["usr"] }
        assertEquals(false, extraNodes.getValue("class:p/StartFragmentArgs")["synthesized"])
        assertTrue(Files.readString(extraGraphPath).contains("generation-live-unverified:"))
        Files.copy(extraGraphPath, results.resolve("live-extra-graph.json"))
        Files.writeString(results.resolve("warm-extra.txt"), "whole inventory changed; generation-unverified\n")
        val disabled = SafeArgsBuildEvidence.build(results.resolve("disabled-dry-run")) {
            runner(root).withArguments("kartographGraphDebug", "--dry-run", "-PdisableSnapshots=true").build()
        }
        assertTrue(disabled.tasks.none { it.path.startsWith(":kartographSnapshot") || it.path.contains("DebugUnitTest") || it.path.startsWith(":kartographVerifySafeArgs") })
        Files.writeString(results.resolve("live-disabled.txt"), "snapshots disabled: no snapshot, generation verification or unit-test task dependency\n")
    }

    private fun assertLiveReports(root: Path, variant: String, evidence: dev.kartograph.core.GenerationEvidence, state: String, prefix: Path) {
        val graphPath = root.resolve("build/reports/kartograph/${variant.lowercase()}-graph.json")
        val deadPath = root.resolve("build/reports/kartograph/${variant.lowercase()}.txt")
        val graph = dev.kartograph.export.McpJsonCodec.parse(Files.readString(graphPath)) as Map<*, *>
        val nodes = (graph["nodes"] as List<*>).map { it as Map<*, *> }.associateBy { it["usr"] }
        val dead = dev.kartograph.export.McpJsonCodec.parse(Files.readString(deadPath)) as Map<*, *>
        val findings = (dead["diagnostics"] as List<*>).map { it as Map<*, *> }.map { it["nodeId"] }.toSet()
        evidence.declarations.forEach { declaration ->
            assertEquals(true, nodes.getValue(declaration.owner.value)["synthesized"])
            assertFalse(declaration.owner.value in findings)
        }
        for (manual in listOf("class:p/ManualArgs", "class:p/ManualDirections", "class:other/StartFragmentArgs")) {
            assertEquals(false, nodes.getValue(manual)["synthesized"])
            assertTrue(manual in findings)
        }
        val edges = (graph["edges"] as List<*>).map { it as Map<*, *> }
        assertFalse(edges.any { it["origin"] == "dispatchModel" })
        assertTrue(edges.any { it["source"] == "method:p/MainActivity#onCreate(Landroid/os/Bundle;)V" &&
            it["target"] == "method:p/StartFragmentDirections#go(I)Lp/StartFragmentDirections\$Go;" && it["kind"] == "call" })
        assertFalse(edges.any { it["target"] == "method:p/StartFragmentDirections#idle(I)Lp/StartFragmentDirections\$Idle;" && it["kind"] == "call" })
        for (path in listOf(graphPath, deadPath)) {
            val text = Files.readString(path)
            assertTrue(text.contains("generation-live-observation: $state"))
            assertTrue(text.contains("liveRevalidated=true"))
        }
        Files.copy(graphPath, prefix.resolveSibling(prefix.fileName.toString() + "-graph.json"))
        Files.copy(deadPath, prefix.resolveSibling(prefix.fileName.toString() + "-dead.json"))
    }

    private fun assertGenerationSummary(query: Map<*, *>, evidence: dev.kartograph.core.GenerationEvidence, basis: String, status: String) {
        val summary = query["generationSummary"] as Map<*, *>
        assertEquals(false, summary["liveRevalidated"])
        assertEquals("snapshotCapture", summary["observationTime"])
        assertEquals("entireLoadedSnapshot", summary["summaryScope"])
        assertEquals(evidence.receipts.size.toLong(), summary["totalReceipts"])
        assertEquals(evidence.declarations.size.toLong(), summary["totalDeclarations"])
        assertEquals(0L, summary["omittedReceipts"])
        val row = (summary["receipts"] as List<*>).single() as Map<*, *>
        assertEquals(basis, row["originBasis"])
        assertEquals(status, row["currentGenerationStatus"])
        assertEquals(evidence.observations.single().currentAttempt, row["currentAttempt"])
    }

    private fun runCli(home: String, arguments: List<String>, prefix: Path): Map<*, *> {
        val directory = Path.of(home)
        val executable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val jars = Files.list(directory.resolve("lib")).use { it.filter { p -> p.fileName.toString().endsWith(".jar") }.sorted().toList() }
        require(jars.isNotEmpty()) { "actual CLI distribution is missing" }
        val argv = listOf(executable.toString(), "-cp", jars.joinToString(java.io.File.pathSeparator), "dev.kartograph.cli.MainKt") + arguments
        Files.createDirectories(requireNotNull(prefix.parent))
        val out = Path.of("$prefix.json"); val err = Path.of("$prefix.stderr")
        val start = System.nanoTime()
        val process = ProcessBuilder(argv).redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        val exited = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) { process.destroyForcibly(); process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) }
        Files.writeString(Path.of("$prefix-command.json"), dev.kartograph.export.McpJsonCodec.render(mapOf("argv" to argv,
            "exit" to if (exited) process.exitValue() else null, "timeout" to !exited, "elapsedNanos" to System.nanoTime() - start,
            "jars" to jars.associate { it.fileName.toString() to sha(it) })))
        assertTrue(exited, "CLI command timed out")
        assertEquals(0, process.exitValue(), Files.readString(err).take(2048))
        require(Files.size(out) <= 10 * 1024 * 1024 && Files.size(err) <= 1024 * 1024) { "CLI fixture evidence exceeds budget" }
        return dev.kartograph.export.McpJsonCodec.parse(Files.readString(out)) as Map<*, *>
    }

    private fun sha(file: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    /** 이 테스트가 만든 작은 입력/관측만 보존한다. 실패 시 원래 실패도 유지한다. */
    private fun preserveEvidence(root: Path) {
        val requested = System.getenv("KARTOGRAPH_SAFEARGS_EVIDENCE_DIR") ?: return
        val destination = Path.of(requested).toAbsolutePath().normalize()
        require(!Files.exists(destination)) { "SafeArgs evidence destination must be new" }
        Files.createDirectories(destination)
        val rows = mutableListOf<String>()
        var total = 0L
        for ((relative, prefix) in listOf("src" to "fixture/src", "build.gradle" to "fixture/build.gradle",
            "settings.gradle" to "fixture/settings.gradle", "gradle.properties" to "fixture/gradle.properties",
            "build/safeargs-observations" to "observations", "build/reports/kartograph" to "snapshots",
            "cold-product/build/safeargs-product" to "product", "cold-product/build/reports/kartograph" to "product-snapshots",
            "cold-product/build/safeargs-observations" to "product-observations")) {
            val source = root.resolve(relative)
            if (!Files.exists(source)) continue
            Files.walk(source).use { paths -> paths.sorted().forEach { file ->
                require(!Files.isSymbolicLink(file)) { "unexpected symlink in SafeArgs fixture evidence" }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@forEach
                val suffix = source.relativize(file).toString().replace('\\', '/')
                val name = if (suffix.isEmpty()) prefix else "$prefix/$suffix"
                require(!name.contains('\t') && !name.contains('\n'))
                val size = Files.size(file)
                require(size <= 10 * 1024 * 1024 && total + size <= 50 * 1024 * 1024 && rows.size < 200) {
                    "SafeArgs fixture evidence budget exceeded"
                }
                val bytes = Files.readAllBytes(file)
                require(bytes.size.toLong() == size) { "SafeArgs evidence changed during capture" }
                total += bytes.size
                val target = destination.resolve(name)
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                rows += "$hash\t${bytes.size}\t$name"
            } }
        }
        Files.writeString(destination.resolve("capture.tsv"), rows.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun snapshot(root: Path, variant: String): QuerySnapshot {
        val snap = QuerySnapshotCodec.parse(Files.readString(root.resolve("build/reports/kartograph/$variant-snapshot.json")))
        val bindings = ExternalInputBindingsCodec.parse(Files.readString(root.resolve("build/kartograph/$variant-input-bindings.json")))
            .mapValues { Path.of(it.value) }
        assertEquals("matched", ProvenanceVerifier.verify(snap.provenance, root, snap.scope, bindings).status)
        return snap
    }

    private fun fixture(root: Path) {
        val pluginJar = requireNotNull(System.getProperty("kartograph.pluginJar")).replace("\\", "\\\\").replace("'", "\\'")
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString()
        require(Files.isDirectory(Path.of(sdk))) { "SafeArgs integration requires an installed Android SDK; do not substitute generated stubs" }
        write(root, "local.properties", "sdk.dir=${sdk.replace("\\", "\\\\")}\n")
        write(root, "settings.gradle", "rootProject.name='android-safeargs-provenance'\n")
        write(root, "gradle.properties", "android.useAndroidX=true\n")
        write(root, "build.gradle", """
            import androidx.navigation.safeargs.gradle.ArgumentsGenerationTask
            import androidx.navigation.safe.args.generator.NavSafeArgsGenerator
            import groovy.json.JsonOutput
            import java.security.MessageDigest
            import org.objectweb.asm.ClassReader
            import org.objectweb.asm.ClassVisitor
            import org.objectweb.asm.Opcodes
            class SafeArgsClassHeader extends ClassVisitor {
                final Map value = [owner:null, sourceFile:null, enclosingOwner:null, innerOwner:null]
                SafeArgsClassHeader() { super(Opcodes.ASM9) }
                void visit(int version, int access, String name, String signature, String parent, String[] interfaces) { value.owner = name }
                void visitSource(String source, String debug) { value.sourceFile = source }
                void visitOuterClass(String owner, String name, String descriptor) { value.enclosingOwner = owner }
                void visitInnerClass(String name, String outerName, String innerName, int access) {
                    if (name == value.owner) value.innerOwner = outerName
                }
            }
            abstract class SafeArgsObservation extends DefaultTask {
                @Input abstract Property<String> getNativeProfile()
                @Input abstract Property<String> getObservedGradle()
                @Input abstract Property<String> getObservedAgp()
                @Input abstract Property<Integer> getObservedJava()
                @Input abstract Property<String> getObservedJavaRuntime()
                @Input abstract Property<String> getObservedJavaVendor()
                @Input abstract Property<String> getApplicationId()
                @Input abstract Property<String> getRFilePackage()
                @Input abstract Property<Boolean> getUseAndroidX()
                @Input abstract Property<Boolean> getGenerateKotlin()
                @Input abstract Property<String> getGeneratorTask()
                @Input abstract Property<String> getCompilerTask()
                @Internal abstract DirectoryProperty getFixtureRoot()
                @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getNavigationFiles()
                @InputFiles @PathSensitive(PathSensitivity.RELATIVE) abstract ConfigurableFileCollection getCompilerSources()
                @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getGeneratedSources()
                @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) abstract DirectoryProperty getCompiledClasses()
                @InputFile @PathSensitive(PathSensitivity.RELATIVE) abstract RegularFileProperty getMappingFile()
                @InputFiles @PathSensitive(PathSensitivity.NONE) abstract ConfigurableFileCollection getGeneratorArtifacts()
                @Input abstract ListProperty<String> getGeneratorActionTypes()
                @InputFiles @PathSensitive(PathSensitivity.NONE) abstract ConfigurableFileCollection getGeneratorActionArtifacts()
                @OutputFile abstract RegularFileProperty getObservationFile()
                static String sha(File file) { MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString() }
                static Map inventory(File root) {
                    def result = new TreeMap()
                    root.eachFileRecurse { file -> if (file.isFile()) result[root.toPath().relativize(file.toPath()).toString().replace('\\','/')] = sha(file) }
                    result
                }
                @TaskAction void observe() {
                    def base = fixtureRoot.get().asFile.toPath().toAbsolutePath().normalize()
                    def relative = { File file ->
                        def path = file.toPath().toAbsolutePath().normalize()
                        if (!path.startsWith(base)) throw new GradleException('fixture input outside project')
                        base.relativize(path).toString().replace('\\','/')
                    }
                    def descriptor = JsonOutput.toJson([applicationId:applicationId.get(), rFilePackage:getRFilePackage().get(),
                        useAndroidX:useAndroidX.get(), generateKotlin:generateKotlin.get(),
                        generatorTask:generatorTask.get(), compilerTask:compilerTask.get(),
                        generatedSourceRoot:relative(generatedSources.get().asFile), compiledClassRoot:relative(compiledClasses.get().asFile)])
                    def value = [profile:[id:nativeProfile.get(), gradle:observedGradle.get(), agp:observedAgp.get(),
                         java:observedJava.get(), javaRuntime:observedJavaRuntime.get(), javaVendor:observedJavaVendor.get()],
                         providerDescriptor:descriptor,
                        providerSha256:MessageDigest.getInstance('SHA-256').digest(descriptor.getBytes('UTF-8')).encodeHex().toString(),
                        navigation:navigationFiles.files.collectEntries { [(relative(it)):sha(it)] },
                        compilerSources:compilerSources.files.collectEntries { [(relative(it)):sha(it)] },
                        generated:inventory(generatedSources.get().asFile), compiled:inventory(compiledClasses.get().asFile)]
                    def mapping = mappingFile.get().asFile
                    if (mapping.length() > 1024 * 1024) throw new GradleException('fixture mapping exceeds evidence budget')
                    value.mapping = [path:relative(mapping), sha256:sha(mapping), text:mapping.getText('UTF-8')]
                    value.generatorArtifacts = generatorArtifacts.files.sort { it.name }.collectEntries { [(it.name):sha(it)] }
                    value.generatorActionTypes = generatorActionTypes.get()
                    value.generatorActionArtifacts = generatorActionArtifacts.files.sort { it.name }.collectEntries { [(it.name):sha(it)] }
                    def headers = new TreeMap()
                    compiledClasses.get().asFile.eachFileRecurse { file ->
                        if (file.isFile() && file.name.endsWith('.class')) {
                            if (headers.size() >= 1000 || file.length() > 4 * 1024 * 1024) throw new GradleException('fixture class evidence exceeds budget')
                            def visitor = new SafeArgsClassHeader()
                            new ClassReader(file.bytes).accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES)
                            def name = compiledClasses.get().asFile.toPath().relativize(file.toPath()).toString().replace('\\','/')
                            headers[name] = visitor.value + [sha256:sha(file)]
                        }
                    }
                    value.classHeaders = headers
                    def sourceTexts = new TreeMap()
                    generatedSources.get().asFile.eachFileRecurse { file -> if (file.isFile() && file.name.endsWith('.java')) sourceTexts[file.name] = file.getText('UTF-8') }
                    value.generatedSourceTexts = sourceTexts
                    def out = observationFile.get().asFile; out.parentFile.mkdirs(); out.text = JsonOutput.toJson(value)
                }
            }
            buildscript {
                repositories { google(); mavenCentral() }
                dependencies {
                    classpath 'com.android.tools.build:gradle:${profile.agpVersion}'
                    classpath 'androidx.navigation:navigation-safe-args-gradle-plugin:2.10.2'
                    classpath files('$pluginJar')
                }
            }
            apply plugin: 'com.android.application'
            apply plugin: 'androidx.navigation.safeargs'
            apply plugin: 'io.github.ictechgy.kartograph'
            repositories { google(); mavenCentral() }
            dependencies { implementation 'androidx.navigation:navigation-runtime:2.10.2' }
            android {
                namespace 'p'; compileSdk 36
                defaultConfig { applicationId 'p.safeargs'; minSdk 24; targetSdk 36; versionCode 1; versionName '1' }
                compileOptions { sourceCompatibility JavaVersion.VERSION_17; targetCompatibility JavaVersion.VERSION_17 }
            }
            ${profile.kotlinDsl(Runtime.version().feature())}
            kartograph {
                snapshotsEnabled = !project.hasProperty('disableSnapshots'); includeSourcePaths = true
                reportFormat = 'json'; excludeOrigins = ['dispatchModel']
                ${profile.kotlinSnapshotDsl(Runtime.version().feature())}
            }
            androidComponents.onVariants(androidComponents.selector().all()) { selected ->
                def variant = selected.name.capitalize()
                def generation = tasks.named('generateSafeArgs' + variant, ArgumentsGenerationTask)
                def observation = tasks.register('observeSafeArgs' + variant, SafeArgsObservation) {
                    nativeProfile.set('${profile.id}')
                    observedGradle.set(gradle.gradleVersion)
                    observedAgp.set(androidComponents.pluginVersion.toString())
                    observedJava.set(Runtime.version().feature())
                    observedJavaRuntime.set(System.getProperty('java.runtime.version'))
                    observedJavaVendor.set(System.getProperty('java.vendor'))
                    fixtureRoot.set(layout.projectDirectory)
                    generatorTask.set(generation.map { it.path })
                    applicationId.set(generation.flatMap { it.applicationId })
                    getRFilePackage().set(generation.flatMap { it.getRFilePackage() })
                    useAndroidX.set(generation.flatMap { it.useAndroidX })
                    generateKotlin.set(generation.flatMap { it.generateKotlin })
                    navigationFiles.from(generation.map { it.navigationFiles })
                    generatedSources.set(generation.flatMap { it.outputDir })
                    mappingFile.set(generation.flatMap { it.getIncrementalFolder().file('file_mappings.json') })
                    generatorArtifacts.from(ArgumentsGenerationTask.class.protectionDomain.codeSource.location,
                        NavSafeArgsGenerator.class.protectionDomain.codeSource.location)
                    generatorActionTypes.set(generation.map { task -> task.actions.collect { it.class.name } })
                    generatorActionArtifacts.from(generation.map { task -> task.actions.collect {
                        it.class.protectionDomain.codeSource?.location
                    }.findAll { it != null } })
                    observationFile.set(layout.buildDirectory.file('safeargs-observations/' + variant + '.json'))
                }
                def compilerName = 'compile' + variant + 'JavaWithJavac'
                def actualCompilerSources = objects.fileCollection()
                def actualCompilerClasses = objects.directoryProperty()
                def actualCompilerPath = objects.property(String)
                selected.configureJavaCompileTask { compiler ->
                    if (compiler.name != compilerName) throw new GradleException('unexpected fixture JavaCompile task')
                    actualCompilerPath.set(compiler.path)
                    actualCompilerSources.from(compiler.source); actualCompilerSources.builtBy(compiler)
                    actualCompilerClasses.set(compiler.destinationDirectory)
                }
                observation.configure {
                    compilerTask.set(actualCompilerPath); compilerSources.from(actualCompilerSources)
                    compiledClasses.set(actualCompilerClasses)
                    // 고정 AGP fixture의 실행 선택이다. 증거 경로는 위 공개 callback에서만 받는다.
                    dependsOn(compilerName)
                }
            }
            tasks.withType(Test).configureEach { doFirst { throw new GradleException('snapshot must not run tests') } }
        """.trimIndent())
        write(root, "src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
                <activity android:name="p.MainActivity" android:exported="false" />
            </application></manifest>
        """.trimIndent())
        write(root, "src/main/java/p/MainActivity.java", """
            package p;
            public class MainActivity extends android.app.Activity {
                @Override protected void onCreate(android.os.Bundle state) {
                    super.onCreate(state);
                    StartFragmentArgs args = StartFragmentArgs.fromBundle(new android.os.Bundle());
                    android.os.Bundle direct = StartFragmentDirections.go(args.getSeed()).getArguments();
                    setTitle(Integer.toString(direct.getInt("value")));
                }
            }
        """.trimIndent())
        write(root, "src/main/java/p/ManualArgs.java", "package p; public final class ManualArgs implements androidx.navigation.NavArgs {}")
        write(root, "src/main/java/p/ManualDirections.java", """
            package p; public final class ManualDirections implements androidx.navigation.NavDirections {
                public int getActionId() { return 0; }
                public android.os.Bundle getArguments() { return new android.os.Bundle(); }
            }
        """.trimIndent())
        write(root, "src/main/java/other/StartFragmentArgs.java", "package other; public final class StartFragmentArgs {}")
        write(root, "src/main/java/p/StartFragment.java", "package p; public class StartFragment extends android.app.Fragment {}")
        write(root, "src/main/java/p/EndFragment.java", "package p; public class EndFragment extends android.app.Fragment {}")
        write(root, "src/main/res/navigation/graph.xml", navigation(22))
        write(root, "src/debug/res/navigation/graph.xml", navigation(11))
    }

    private fun navigation(defaultSeed: Int) = """
        <navigation xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@+id/graph" app:startDestination="@id/start">
            <fragment android:id="@+id/start" android:name="p.StartFragment">
                <argument android:name="seed" app:argType="integer" android:defaultValue="$defaultSeed" />
                <action android:id="@+id/go" app:destination="@id/end" />
                <action android:id="@+id/idle" app:destination="@id/end" />
            </fragment>
            <fragment android:id="@+id/end" android:name="p.EndFragment">
                <argument android:name="value" app:argType="integer" />
            </fragment>
        </navigation>
    """.trimIndent()

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, text)
    }
}
