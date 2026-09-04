package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.IncompleteKeepRuleHierarchyException
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.GraphNode
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
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
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object DeadCommand {
    fun runBaseline(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
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
        val graph = ClassFileIndexer().index(options.classRoots)
        val dependencyHierarchy = ClassHierarchyIndexer().index(
            options.classpath,
            graph.nodes.values.flatMap(GraphNode::supertypes),
        )
        val inputEvidence = buildList {
            addAll(AndroidManifestScanner(options.projectRoot).scan(options.manifest, options.namespace))
            addAll(AndroidXmlScanner(options.projectRoot).scan(options.resources))
        }
        val evidence = DefaultRetention.find(
            graph,
            inputEvidence,
            KeepRuleScanner(options.projectRoot).scan(options.keepRules),
            dependencyHierarchy,
        )
        val result = ReachabilityAnalyzer.analyze(graph, evidence)
        if (options.explainNodeId != null) {
            val status = explain(options.explainNodeId, graph, result, output, error)
            if (status == ExitStatus.SUCCESS.code) printLimitations(output)
            return status
        }

        val allFindings = result.unreachableNodeIds
            .mapNotNull(graph::node)
            .filter { node -> node.isReportableType }
            .map { node -> Finding(node.id, node.location) }
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
            allFindings.filter { finding -> finding.matchesChangedFiles(changed, sourceRoot) }
        } ?: allFindings
        val fingerprints = options.baseline?.let { path -> BaselineCodec.parse(Files.readString(path)) }.orEmpty()
        val findings = scoped.filterNot { it.fingerprint in fingerprints }
        output.print(AdoptionReporter.render(options.reportFormat, findings, AnalysisLimitation.entries, scoped.size - findings.size))
        return if (options.strict && findings.isNotEmpty()) ExitStatus.FINDINGS.code else ExitStatus.SUCCESS.code
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
        var projectRoot: Path? = null
        var manifestPath: String? = null
        var resourcesPath: String? = null
        var namespace: String? = null
        var explainNodeId: NodeId? = null
        val keepRulePaths = mutableListOf<String>()
        val classpathPaths = mutableListOf<String>()
        var strict = false
        var baselineValue: String? = null
        var writeBaselineValue: String? = null
        var since: String? = null
        var reportFormat = ReportFormat.TEXT
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option == "--strict") {
                strict = true
                index++
                continue
            }
            val value = valueAfter(arguments, index, option, error) ?: return null
            when (option) {
                "--classes" -> classRoots.add(Path.of(value))
                "--project" -> projectRoot = Path.of(value).toAbsolutePath().normalize()
                "--manifest" -> manifestPath = value
                "--resources" -> resourcesPath = value
                "--namespace" -> namespace = value
                "--explain" -> explainNodeId = NodeId(value)
                "--keep-rules" -> keepRulePaths += value
                "--classpath" -> classpathPaths += value
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

        val project = projectRoot ?: return missingOption(error, "--project")
        return DeadOptions(
            classRoots = classRoots.takeIf(List<Path>::isNotEmpty) ?: return missingOption(error, "--classes"),
            projectRoot = project,
            manifest = resolveProjectPath(project, manifestPath ?: return missingOption(error, "--manifest")),
            resources = resolveProjectPath(project, resourcesPath ?: return missingOption(error, "--resources")),
            namespace = namespace?.takeIf(String::isNotBlank) ?: return missingOption(error, "--namespace"),
            keepRules = keepRulePaths.map { value -> resolveProjectPath(project, value) },
            classpath = classpathPaths.map { value -> resolveProjectPath(project, value) },
            strict = strict,
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

    private val GraphNode.isReportableType: Boolean
        get() = !synthesized && kind in REPORTABLE_TYPE_KINDS

    private data class DeadOptions(
        val classRoots: List<Path>,
        val projectRoot: Path,
        val manifest: Path,
        val resources: Path,
        val namespace: String,
        val keepRules: List<Path>,
        val classpath: List<Path>,
        val strict: Boolean,
        val explainNodeId: NodeId?,
        val baseline: Path?,
        val writeBaseline: Path?,
        val since: String?,
        val reportFormat: ReportFormat,
    )

    private val REPORTABLE_TYPE_KINDS = setOf(
        NodeKind.CLASS,
        NodeKind.INTERFACE,
        NodeKind.OBJECT,
        NodeKind.ENUM,
        NodeKind.ANNOTATION_CLASS,
    )

    private val HELP = """
        Find class declarations that are unreachable from Android retention roots.

        Usage:
          kartograph dead --classes <directory> [--classes <directory>]... --project <directory> \
            --manifest <file> --resources <directory> --namespace <name> \
            [--keep-rules <file>]... [--classpath <directory-or-jar>]... \
            [--strict] [--explain <node-id>]
            [--baseline <file>] [--since <git-ref>]
            [--report-format text|gradle|github-actions|sarif|json]

        This command reports graph reachability. It does not say that a declaration is safe to delete.
    """.trimIndent() + "\n"

}

internal fun Finding.matchesChangedFiles(changed: Set<Path>, projectRoot: Path): Boolean {
    val sourcePath = location?.path ?: return true
    val relativePath = try {
        Path.of(sourcePath)
    } catch (error: InvalidPathException) {
        return true
    }
    if (projectRoot.resolve(relativePath).normalize() in changed) return true
    if (relativePath.nameCount != 1) return false
    return changed.any { path -> path.fileName == relativePath.fileName }
}
