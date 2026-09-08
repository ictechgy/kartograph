package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.IncompleteKeepRuleHierarchyException
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.AdoptionReporter
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.ReportFormat
import dev.kartograph.export.toPlainTextLocation
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidResourceScanningException
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassHierarchyIndexer
import dev.kartograph.index.ClassHierarchyIndexingException
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.KeepRuleScanner
import dev.kartograph.index.KeepRuleScanningException
import dev.kartograph.index.SourcePathIndex
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object DeadCommand {
    fun runBaseline(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(BASELINE_HELP)
            return ExitStatus.SUCCESS.code
        }
        val writeIndex = arguments.indexOf("--write")
        if (writeIndex < 0 || arguments.getOrNull(writeIndex + 1)?.startsWith('-') != false) {
            error.println("error: baseline requires --write <file>")
            return ExitStatus.USAGE.code
        }
        val writePath = arguments[writeIndex + 1]
        val deadArguments = arguments.toMutableList().apply {
            removeAt(writeIndex + 1)
            removeAt(writeIndex)
            add("--write-baseline")
            add(writePath)
        }
        return run(deadArguments, output, error)
    }

    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(HELP)
            return ExitStatus.SUCCESS.code
        }
        val options = try {
            parseOptions(arguments, error)
        } catch (pathError: InvalidPathException) {
            error.println("error: invalid path")
            null
        } ?: return ExitStatus.USAGE.code
        if (!Files.isDirectory(options.projectRoot)) {
            return toolFailure(error, "project root does not exist")
        }

        return try {
            execute(options, output, error)
        } catch (indexingError: ClassIndexingException) {
            toolFailure(error, indexingError.message ?: "class indexing failed")
        } catch (scanningError: AndroidResourceScanningException) {
            toolFailure(error, scanningError.message ?: "Android resource scanning failed")
        } catch (scanningError: KeepRuleScanningException) {
            toolFailure(error, scanningError.message ?: "keep rule scanning failed")
        } catch (hierarchyError: IncompleteKeepRuleHierarchyException) {
            toolFailure(error, hierarchyError.message ?: "dependency hierarchy is incomplete")
        } catch (indexingError: ClassHierarchyIndexingException) {
            toolFailure(error, indexingError.message ?: "dependency hierarchy indexing failed")
        } catch (changedFilesError: ChangedFilesException) {
            toolFailure(error, changedFilesError.message ?: "changed files unavailable")
        } catch (fileError: java.io.IOException) {
            toolFailure(error, "unable to read or write adoption file")
        } catch (baselineError: IllegalArgumentException) {
            toolFailure(error, baselineError.message ?: "invalid baseline")
        }
    }

    private fun execute(options: DeadOptions, output: PrintStream, error: PrintStream): Int {
        val indexed = ClassFileIndexer().indexWithObservations(options.classRoots, options.classpath, options.serviceResources)
        val graph = indexed.graph
        val dependencyHierarchy = indexed.hierarchy
        val inputEvidence = buildList {
            addAll(AndroidManifestScanner(options.projectRoot).scan(options.manifest, options.namespace))
            addAll(AndroidXmlScanner(options.projectRoot).scan(options.resources))
        }
        val evidence = DefaultRetention.find(
            graph,
            inputEvidence,
            KeepRuleScanner(options.projectRoot, options.includePrivateMembers).scan(options.keepRules),
            dependencyHierarchy,
            includePrivateMembers = options.includePrivateMembers,
        )
        val result = ReachabilityAnalyzer.analyze(graph, evidence)
        if (options.explainNodeId != null) {
            val status = explain(options.explainNodeId, graph, result, output, error)
            if (status == ExitStatus.SUCCESS.code) printLimitations(output)
            return status
        }

        val allFindings = markTestOnly(
            DeadFindings.collect(graph, result, options.includePrivateMembers),
            graph,
            options.classRoots,
            options.testClassRoots,
        )
        if (options.writeBaseline != null) {
            val target = options.writeBaseline
            target.parent?.let { parent -> Files.createDirectories(parent) }
            Files.writeString(target, BaselineCodec.render(allFindings))
            output.println("baseline\twritten\t${allFindings.size}")
            return ExitStatus.SUCCESS.code
        }
        val scoped = options.since?.let { reference ->
            val changed = ChangedFiles.since(reference, options.projectRoot)
            val sourceRoot = options.projectRoot.toRealPath()
            val sourcePaths = SourcePathIndex.byFileName(sourceRoot)
            allFindings.filter { finding -> finding.matchesChangedFiles(changed, sourceRoot, sourcePaths) }
        } ?: allFindings
        val fingerprints = options.baseline?.let { path -> BaselineCodec.parse(Files.readString(path)) }.orEmpty()
        val findings = scoped.filterNot { it.fingerprint in fingerprints }
        output.print(AdoptionReporter.render(options.reportFormat, findings, AnalysisLimitation.entries, scoped.size - findings.size))
        return if (options.strict && findings.isNotEmpty()) ExitStatus.FINDINGS.code else ExitStatus.SUCCESS.code
    }

    // production root에서는 도달 불가인 finding 중 test class root에서만 도달되는 것을 test-only로 표시한다.
    private fun markTestOnly(
        findings: List<Finding>,
        graph: CodeGraph,
        classRoots: List<Path>,
        testClassRoots: List<Path>,
    ): List<Finding> {
        if (findings.isEmpty() || testClassRoots.isEmpty()) return findings
        // test→production cross edge를 보존하려면 production과 test root를 함께 index해야 한다.
        // 따로 index하면 combined 조립 시 dangling 제거로 test→production 간선이 유실된다.
        val combined = ClassFileIndexer().index(classRoots + testClassRoots)
        // seed는 combined에만 있고 production graph에는 없는 노드, 즉 test 전용 노드다.
        // classRoots가 먼저 index되므로 production 노드는 항상 graph.nodes에 있어 seed에서 빠진다.
        // 같은 FQN이 production·test 양쪽에 있으면 첫 root(production) 사실이 우선해 test 사본 간선이 가려질 수 있고,
        // 같은 root를 --classes와 --test-classes 양쪽에 넘기면 seed가 비어 표시 없이 성공한다(문서화된 경계).
        val testSideRoots = combined.nodeIds.filter { it !in graph.nodes }
            .map { RetentionEvidence(it, RetentionReason.RUNTIME_ENTRY_POINT, null) }
        val testReachable = ReachabilityAnalyzer.analyze(combined, testSideRoots).reachableNodeIds
        return findings.map { finding -> if (finding.nodeId in testReachable) finding.copy(testOnly = true) else finding }
    }

    private fun explain(
        nodeId: NodeId,
        graph: CodeGraph,
        result: ReachabilityResult,
        output: PrintStream,
        error: PrintStream,
    ): Int {
        if (!graph.contains(nodeId)) {
            error.println("error: no declaration matches the requested node ID")
            return ExitStatus.USAGE.code
        }
        if (nodeId in result.unreachableNodeIds) {
            output.println("unreachable\t$nodeId")
            return ExitStatus.SUCCESS.code
        }
        val directEvidence = result.retentionEvidenceFor(nodeId)
        if (directEvidence.isNotEmpty()) {
            directEvidence.forEach { evidence -> output.println(evidence.explanation()) }
        } else {
            output.println("reachable\t$nodeId\tvia\t${result.pathFromRootTo(nodeId)?.joinToString(" -> ")}")
        }
        return ExitStatus.SUCCESS.code
    }

    private fun parseOptions(arguments: List<String>, error: PrintStream): DeadOptions? {
        val classRoots = mutableListOf<Path>()
        val testClassRoots = mutableListOf<Path>()
        var projectRoot: Path? = null
        var manifestPath: String? = null
        var resourcesPath: String? = null
        var namespace: String? = null
        var explainNodeId: NodeId? = null
        val keepRulePaths = mutableListOf<String>()
        val classpathPaths = mutableListOf<String>()
        val servicePaths = mutableListOf<String>()
        var strict = false
        var includePrivateMembers = false
        var baselineValue: String? = null
        var writeBaselineValue: String? = null
        var since: String? = null
        var reportFormat = ReportFormat.TEXT
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option == "--include-private-members") {
                includePrivateMembers = true
                index++
                continue
            }
            if (option == "--strict") {
                strict = true
                index++
                continue
            }
            val value = valueAfter(arguments, index, option, error) ?: return null
            when (option) {
                "--classes" -> classRoots.add(Path.of(value))
                "--test-classes" -> testClassRoots.add(Path.of(value))
                "--project" -> projectRoot = Path.of(value).toAbsolutePath().normalize()
                "--manifest" -> manifestPath = value
                "--resources" -> resourcesPath = value
                "--namespace" -> namespace = value
                "--explain" -> explainNodeId = NodeId(value)
                "--keep-rules" -> keepRulePaths += value
                "--classpath" -> classpathPaths += value
                "--service-resources" -> servicePaths += value
                "--baseline" -> baselineValue = value
                "--write-baseline" -> writeBaselineValue = value
                "--since" -> since = value
                "--report-format" -> reportFormat = ReportFormat.fromOption(value) ?: run {
                    error.println("error: invalid report format: $value")
                    return null
                }
                else -> {
                    error.println("error: unknown dead option: $option")
                    return null
                }
            }
            index += 2
        }

        val exclusiveMode = when {
            explainNodeId != null -> "--explain"
            writeBaselineValue != null -> "--write-baseline"
            else -> null
        }
        if (exclusiveMode != null) {
            val ignored = setOf("--baseline", "--since", "--strict", "--report-format") +
                if (explainNodeId != null) setOf("--write-baseline", "--test-classes") else emptySet()
            val conflict = arguments.firstOrNull { it in ignored }
            if (conflict != null) {
                error.println("error: $conflict cannot be combined with $exclusiveMode")
                return null
            }
        }
        val project = projectRoot ?: return missingOption(error, "--project")
        return DeadOptions(
            classRoots = classRoots.takeIf(List<Path>::isNotEmpty) ?: return missingOption(error, "--classes"),
            testClassRoots = testClassRoots,
            projectRoot = project,
            manifest = resolveProjectPath(project, manifestPath ?: return missingOption(error, "--manifest")),
            resources = resolveProjectPath(project, resourcesPath ?: return missingOption(error, "--resources")),
            namespace = namespace?.takeIf(String::isNotBlank) ?: return missingOption(error, "--namespace"),
            keepRules = keepRulePaths.map { value -> resolveProjectPath(project, value) },
            classpath = classpathPaths.map { value -> resolveProjectPath(project, value) },
            serviceResources = servicePaths.map { value -> resolveProjectPath(project, value) },
            strict = strict,
            includePrivateMembers = includePrivateMembers,
            explainNodeId = explainNodeId,
            baseline = baselineValue?.let { value -> resolveProjectPath(project, value) },
            writeBaseline = writeBaselineValue?.let { value -> resolveProjectPath(project, value) },
            since = since,
            reportFormat = reportFormat,
        )
    }

    private fun valueAfter(arguments: List<String>, index: Int, option: String, error: PrintStream): String? {
        val value = arguments.getOrNull(index + 1)
        if (value == null || value.startsWith('-')) {
            error.println("error: missing value for $option")
            return null
        }
        return value
    }

    private fun missingOption(error: PrintStream, option: String): Nothing? {
        error.println("error: missing required $option")
        return null
    }

    private fun resolveProjectPath(projectRoot: Path, value: String): Path {
        val path = Path.of(value)
        return if (path.isAbsolute) path.normalize() else projectRoot.resolve(path).normalize()
    }

    private fun toolFailure(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.FAILURE.code
    }

    private fun printLimitations(output: PrintStream) {
        AnalysisLimitation.entries.forEach { limitation ->
            output.println("limitation\t${limitation.name}\t${limitation.description}")
        }
    }

    private fun RetentionEvidence.explanation(): String =
        "retained\t$nodeId\t${reason.name}\t${location.toPlainTextLocation()}\t${reason.description}"

    private data class DeadOptions(
        val classRoots: List<Path>,
        val testClassRoots: List<Path>,
        val projectRoot: Path,
        val manifest: Path,
        val resources: Path,
        val namespace: String,
        val keepRules: List<Path>,
        val classpath: List<Path>,
        val serviceResources: List<Path>,
        val strict: Boolean,
        val includePrivateMembers: Boolean,
        val explainNodeId: NodeId?,
        val baseline: Path?,
        val writeBaseline: Path?,
        val since: String?,
        val reportFormat: ReportFormat,
    )

    private val BASELINE_HELP = """
        Find class declarations that are unreachable from Android retention roots and write the baseline file.

        Usage:
          kartograph baseline --write <file> --classes <directory> --project <directory> \
            --manifest <file> --resources <directory> --namespace <name> [options]

        Takes the same options as kartograph dead. Writes fingerprints of the current findings
        to <file> instead of reporting them.
    """.trimIndent() + "\n"

    private val HELP = """        Find class declarations that are unreachable from Android retention roots.

        Usage:
          kartograph dead --classes <directory> [--classes <directory>]... --project <directory> \
            --manifest <file> --resources <directory> --namespace <name> \
            [--keep-rules <file>]... [--classpath <directory-or-jar>] [--service-resources <directory-or-jar>]... \
            [--test-classes <directory-or-jar>]... \
            [--strict] [--explain <node-id>]
            [--include-private-members]
            [--baseline <file>] [--since <git-ref>]
            [--report-format text|gradle|github-actions|sarif|json]

        This command reports graph reachability. It does not say that a declaration is safe to delete.
        --test-classes marks findings that only test code reaches as "(used only by tests)"; they remain reported.
    """.trimIndent() + "\n"

}

internal fun Finding.matchesChangedFiles(
    changed: Set<Path>,
    projectRoot: Path,
    sourcePaths: Map<String, Set<Path>> = emptyMap(),
): Boolean {
    val sourcePath = location?.path ?: return true
    val relativePath = try {
        Path.of(sourcePath)
    } catch (error: InvalidPathException) {
        return true
    }
    if (projectRoot.resolve(relativePath).normalize() in changed) return true
    if (relativePath.nameCount != 1) return false
    // debug 정보가 basename만 남긴 경우, 프로젝트에 유일한 source면 그 경로로 정확히 판정한다.
    val candidates = sourcePaths[relativePath.fileName.toString()]
    if (candidates != null && candidates.size == 1) return candidates.single() in changed
    // 여러 개거나 인덱스가 없으면 기존 보수적 basename 매칭으로 폴백한다.
    return changed.any { path -> path.fileName == relativePath.fileName }
}
