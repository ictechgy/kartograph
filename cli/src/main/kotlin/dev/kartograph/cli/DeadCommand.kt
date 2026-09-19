package dev.kartograph.cli

import dev.kartograph.analysis.DeadFindings
import dev.kartograph.analysis.IncompleteKeepRuleHierarchyException
import dev.kartograph.analysis.ReachabilityResult
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.Finding
import dev.kartograph.core.NodeId
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.AdoptionReporter
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.ReportFormat
import dev.kartograph.export.SuppressCodec
import dev.kartograph.export.toPlainTextLocation
import dev.kartograph.index.AndroidResourceScanningException
import dev.kartograph.index.ClassHierarchyIndexingException
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.KeepRuleScanningException
import dev.kartograph.index.RuntimeEvidenceScanner
import dev.kartograph.index.RuntimeEvidenceScanningException
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
        } catch (scanningError: RuntimeEvidenceScanningException) {
            toolFailure(error, scanningError.message ?: "runtime evidence scanning failed")
        } catch (changedFilesError: ChangedFilesException) {
            toolFailure(error, changedFilesError.message ?: "changed files unavailable")
        } catch (fileError: java.io.IOException) {
            toolFailure(error, "unable to read or write adoption file")
        } catch (baselineError: IllegalArgumentException) {
            toolFailure(error, baselineError.message ?: "invalid baseline")
        }
    }

    private fun execute(options: DeadOptions, output: PrintStream, error: PrintStream): Int {
        val analysis = RetentionPipeline.analyze(
            RetentionInputs(
                classRoots = options.classRoots,
                generatedClassRoots = options.generatedClassRoots,
                testClassRoots = options.testClassRoots,
                projectRoot = options.projectRoot,
                manifest = options.manifest,
                resources = options.resources,
                namespace = options.namespace,
                keepRules = options.keepRules,
                classpath = options.classpath,
                serviceResources = options.serviceResources,
                includePrivateMembers = options.includePrivateMembers,
            ),
        )
        val graph = analysis.graph
        val result = analysis.reachability
        if (options.explainNodeId != null) {
            val status = explain(options.explainNodeId, graph, result, output, error)
            if (status == ExitStatus.SUCCESS.code) printLimitations(output)
            return status
        }

        val suppressions = options.suppress?.let { path -> SuppressCodec.parse(Files.readString(path)) }.orEmpty()
        val today = java.time.LocalDate.now()
        val activeSuppressions = suppressions.filter { entry -> entry.expires >= today }
            .mapTo(mutableSetOf()) { entry -> entry.fingerprint }
        val expiredSuppressions = suppressions.count { entry -> entry.expires < today }
        val allFindings = RetentionPipeline.markTestOnly(
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
        val findings = scoped.filterNot { it.fingerprint in fingerprints || it.fingerprint in activeSuppressions }
        val observedClasses = if (options.runtimeClasses.isEmpty() && options.coverageReports.isEmpty()) {
            emptySet()
        } else {
            RuntimeEvidenceScanner().scan(options.runtimeClasses, options.coverageReports)
        }
        val confidence = findings.associate { finding ->
            finding.nodeId to RetentionPipeline.confidenceOf(
                finding, graph, analysis.unresolvedChannelsBySource, observedClasses,
            )
        }
        output.print(
            AdoptionReporter.render(
                options.reportFormat,
                findings,
                AnalysisLimitation.entries,
                scoped.size - findings.size,
                confidence,
                expiredSuppressions,
                analysis.unmatchedKeepRules,
                inputHints = analysis.inputHints.takeIf { findings.isNotEmpty() }.orEmpty(),
            ),
        )
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
        val generatedClassRoots = mutableListOf<Path>()
        val testClassRoots = mutableListOf<Path>()
        var projectRoot: Path? = null
        var manifestPath: String? = null
        var resourcesPath: String? = null
        var namespace: String? = null
        var explainNodeId: NodeId? = null
        val keepRulePaths = mutableListOf<String>()
        val classpathPaths = mutableListOf<String>()
        val servicePaths = mutableListOf<String>()
        val runtimeClassPaths = mutableListOf<String>()
        val coveragePaths = mutableListOf<String>()
        var strict = false
        var includePrivateMembers = false
        var baselineValue: String? = null
        var writeBaselineValue: String? = null
        var suppressValue: String? = null
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
                "--generated-classes" -> generatedClassRoots.add(Path.of(value))
                "--test-classes" -> testClassRoots.add(Path.of(value))
                "--project" -> projectRoot = Path.of(value).toAbsolutePath().normalize()
                "--manifest" -> manifestPath = value
                "--resources" -> resourcesPath = value
                "--namespace" -> namespace = value
                "--explain" -> explainNodeId = NodeId(value)
                "--keep-rules" -> keepRulePaths += value
                "--classpath" -> classpathPaths += value
                "--service-resources" -> servicePaths += value
                "--runtime-classes" -> runtimeClassPaths += value
                "--coverage" -> coveragePaths += value
                "--baseline" -> baselineValue = value
                "--write-baseline" -> writeBaselineValue = value
                "--suppress" -> suppressValue = value
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
            val ignored = setOf(
                "--baseline", "--since", "--strict", "--report-format", "--suppress",
                "--runtime-classes", "--coverage",
            ) + if (explainNodeId != null) setOf("--write-baseline", "--test-classes") else emptySet()
            val conflict = arguments.firstOrNull { it in ignored }
            if (conflict != null) {
                error.println("error: $conflict cannot be combined with $exclusiveMode")
                return null
            }
        }
        val project = projectRoot ?: return missingOption(error, "--project")
        return DeadOptions(
            classRoots = classRoots.takeIf(List<Path>::isNotEmpty) ?: return missingOption(error, "--classes"),
            generatedClassRoots = generatedClassRoots,
            testClassRoots = testClassRoots,
            projectRoot = project,
            manifest = resolveProjectPath(project, manifestPath ?: return missingOption(error, "--manifest")),
            resources = resolveProjectPath(project, resourcesPath ?: return missingOption(error, "--resources")),
            namespace = namespace?.takeIf(String::isNotBlank) ?: return missingOption(error, "--namespace"),
            keepRules = keepRulePaths.map { value -> resolveProjectPath(project, value) },
            classpath = classpathPaths.map { value -> resolveProjectPath(project, value) },
            serviceResources = servicePaths.map { value -> resolveProjectPath(project, value) },
            runtimeClasses = runtimeClassPaths.map { value -> resolveProjectPath(project, value) },
            coverageReports = coveragePaths.map { value -> resolveProjectPath(project, value) },
            strict = strict,
            includePrivateMembers = includePrivateMembers,
            explainNodeId = explainNodeId,
            baseline = baselineValue?.let { value -> resolveProjectPath(project, value) },
            writeBaseline = writeBaselineValue?.let { value -> resolveProjectPath(project, value) },
            suppress = suppressValue?.let { value -> resolveProjectPath(project, value) },
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
        val generatedClassRoots: List<Path>,
        val testClassRoots: List<Path>,
        val projectRoot: Path,
        val manifest: Path,
        val resources: Path,
        val namespace: String,
        val keepRules: List<Path>,
        val classpath: List<Path>,
        val serviceResources: List<Path>,
        val runtimeClasses: List<Path>,
        val coverageReports: List<Path>,
        val strict: Boolean,
        val includePrivateMembers: Boolean,
        val explainNodeId: NodeId?,
        val baseline: Path?,
        val writeBaseline: Path?,
        val suppress: Path?,
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
            [--generated-classes <directory-or-jar>]...
            [--baseline <file>] [--suppress <file>] [--since <git-ref>]
            [--runtime-classes <file>]... [--coverage <file>]...
            [--report-format text|gradle|github-actions|sarif|json|markdown]

        This command reports graph reachability. It does not say that a declaration is safe to delete.
        --test-classes marks findings that only test code reaches as "(used only by tests)"; they remain reported.
        --generated-classes marks a supplied class root as generated-only; its declarations remain in the graph.
        --suppress hides fingerprinted findings until the entry's ISO expires date (inclusive); expired
        entries stop suppressing and machine formats report the count. Fingerprints are the baseline values.
        --runtime-classes (one class name per line; nested classes use "$") and --coverage (JaCoCo/Kover XML) accept
        user-supplied runtime evidence and mark findings whose class was observed as "runtime-observed" confidence.
        Findings, strict results and exit codes are unchanged; coverage is not collected or executed here.
        These options cannot be combined with --explain or --write-baseline.
        markdown renders a human-readable findings table for review descriptions.
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
