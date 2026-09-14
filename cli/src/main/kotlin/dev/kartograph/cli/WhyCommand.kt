package dev.kartograph.cli

import dev.kartograph.analysis.IncompleteKeepRuleHierarchyException
import dev.kartograph.core.AnalysisLimitation
import dev.kartograph.core.RetentionReason
import dev.kartograph.export.toPlainTextLocation
import dev.kartograph.index.AndroidResourceScanningException
import dev.kartograph.index.ClassHierarchyIndexingException
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.KeepRuleScanningException
import java.io.PrintStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * 한 선언이 보존되거나 도달 가능하거나 도달 불가능한 이유를 근거와 함께 답한다.
 * R8의 -whyareyoukeeping과 달리 release 빌드가 아니라 전달된 입력 그래프 위에서 답하며,
 * 답은 삭제 승인이 아니다.
 */
internal object WhyCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(HELP)
            return ExitStatus.SUCCESS.code
        }
        val requested = arguments.firstOrNull()?.takeUnless { it.startsWith('-') }
            ?: run {
                error.println("error: why requires a symbol")
                return ExitStatus.USAGE.code
            }
        val inputs = try {
            parseInputs(arguments.drop(1), error)
        } catch (pathError: InvalidPathException) {
            error.println("error: invalid path")
            null
        } ?: return ExitStatus.USAGE.code
        if (!Files.isDirectory(inputs.projectRoot)) {
            return toolFailure(error, "project root does not exist")
        }

        return try {
            execute(requested, inputs, output)
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
        } catch (fileError: IOException) {
            toolFailure(error, "unable to read analysis inputs")
        }
    }

    private fun execute(requested: String, inputs: RetentionInputs, output: PrintStream): Int {
        val analysis = RetentionPipeline.analyze(inputs)
        val graph = analysis.graph
        val matches = graph.nodes.values.filter { node ->
            node.id.value == requested || node.name == requested || node.qualifiedName == requested
        }.sortedBy { it.id }
        if (matches.isEmpty()) {
            output.println("status\tnotFound")
            output.println("requested\t$requested")
            return ExitStatus.USAGE.code
        }
        if (matches.size > 1) {
            output.println("status\tambiguous")
            output.println("requested\t$requested")
            matches.forEach { candidate -> output.println("candidate\t${candidate.qualifiedName}\t${candidate.id}") }
            return ExitStatus.USAGE.code
        }
        val node = matches.single()
        val evidence = analysis.reachability.retentionEvidenceFor(node.id)
        val state = when {
            evidence.firstOrNull()?.reason == RetentionReason.KEEP_ANNOTATED_MEMBER -> "retainedByMember"
            evidence.isNotEmpty() -> "retained"
            node.id in analysis.reachability.reachableNodeIds -> "reachable"
            else -> "unreachable"
        }
        output.println("subject\t${node.id}\t${node.qualifiedName}\t${node.kind.name.lowerCamel()}\t${node.location.toPlainTextLocation()}")
        output.println("state\t$state")
        evidence.forEach { item ->
            output.println("reason\t${item.reason.name}\t${item.location.toPlainTextLocation()}\t${item.reason.description}")
        }
        if (evidence.isEmpty() && state == "reachable") {
            analysis.reachability.pathFromRootTo(node.id)?.let { path ->
                output.println("path\t" + path.mapNotNull(graph::node).joinToString(" -> ") { it.qualifiedName })
            }
        }
        val callers = graph.incomingEdgesTo(node.id).filter { it.kind.impliesUsage }
            .groupBy { it.source }
            .toSortedMap()
        output.println("usedBy\t${callers.size}")
        val shown = callers.entries.take(CALLER_LIMIT)
        shown.forEach { (callerId, edges) ->
            graph.node(callerId)?.let { caller ->
                val kinds = edges.map { edge -> edge.kind.name.lowerCamel() }.distinct().sorted().joinToString(",")
                output.println("caller\t${caller.qualifiedName}\t$kinds")
            }
        }
        if (callers.size > shown.size) output.println("callers-truncated\ttrue")
        if (RetentionPipeline.isTestOnly(graph, inputs, node.id)) {
            output.println("used-by-tests\t${node.id}")
        }
        if (state == "unreachable") {
            val confidence = RetentionPipeline.confidenceOf(node.location, analysis.unresolvedChannelsBySource)
            output.println("confidence\t${confidence.label}")
        }
        AnalysisLimitation.entries.sortedBy { it.name }.forEach { limitation ->
            output.println("limitation\t${limitation.name}\t${limitation.description}")
        }
        return ExitStatus.SUCCESS.code
    }

    private fun parseInputs(arguments: List<String>, error: PrintStream): RetentionInputs? {
        val classRoots = mutableListOf<Path>()
        val generatedClassRoots = mutableListOf<Path>()
        val testClassRoots = mutableListOf<Path>()
        var projectRoot: Path? = null
        var manifestPath: String? = null
        var resourcesPath: String? = null
        var namespace: String? = null
        val keepRulePaths = mutableListOf<String>()
        val classpathPaths = mutableListOf<String>()
        val servicePaths = mutableListOf<String>()
        var includePrivateMembers = false
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option == "--include-private-members") {
                includePrivateMembers = true
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
                "--keep-rules" -> keepRulePaths += value
                "--classpath" -> classpathPaths += value
                "--service-resources" -> servicePaths += value
                else -> {
                    error.println("error: unknown why option: $option")
                    return null
                }
            }
            index += 2
        }
        val project = projectRoot ?: return missingOption(error, "--project")
        return RetentionInputs(
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
            includePrivateMembers = includePrivateMembers,
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

    private fun String.lowerCamel(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
    }

    private const val CALLER_LIMIT = 100

    private val HELP = """
        Explain why one declaration is retained, reachable, or unreachable.

        Usage:
          kartograph why <symbol> --classes <directory> [--classes <directory>]... --project <directory> \
            --manifest <file> --resources <directory> --namespace <name> \
            [--keep-rules <file>]... [--classpath <directory-or-jar>] [--service-resources <directory-or-jar>]... \
            [--test-classes <directory-or-jar>]... [--generated-classes <directory-or-jar>]... \
            [--include-private-members]

        Prints the declaration's state (retained, retainedByMember, reachable, unreachable), every retention
        evidence with its file:line provenance, the representative path from a retention root, direct callers,
        a measured confidence tier for unreachable declarations, and the analysis limitations. The answer is a
        reachability fact about the supplied inputs; it never approves a deletion.
        A symbol that matches nothing or several declarations is a usage error, like query.
    """.trimIndent() + "\n"
}
