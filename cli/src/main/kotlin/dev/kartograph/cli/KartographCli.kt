package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeId
import dev.kartograph.export.DotGraphRenderer
import dev.kartograph.export.GraphJsonRenderer
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassIndexingException
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import dev.kartograph.core.KartographVersion

internal enum class ExitStatus(val code: Int) {
    SUCCESS(0),
    FINDINGS(1),
    FAILURE(2),
    USAGE(64),
}

internal object KartographCli {
    fun run(
        arguments: Array<out String>,
        output: PrintStream,
        error: PrintStream,
    ): Int = when (arguments.firstOrNull()) {
        null, "--help", "-h" -> printHelp(output)
        "--version" -> printVersion(output)
        "graph" -> runGraph(arguments.drop(1), output, error)
        "dead" -> DeadCommand.run(arguments.drop(1), output, error)
        "baseline" -> DeadCommand.runBaseline(arguments.drop(1), output, error)
        "query" -> AgentCommand.query(arguments.drop(1), output, error)
        "bridges" -> AgentCommand.bridges(arguments.drop(1), output, error)
        "skill" -> AgentCommand.skill(arguments.drop(1), output, error)
        "cycles" -> ArchitectureCommand.cycles(arguments.drop(1), output, error)
        "rules" -> ArchitectureCommand.rules(arguments.drop(1), output, error)
        "metrics" -> ArchitectureCommand.metrics(arguments.drop(1), output, error)
        else -> usageError(error, "unknown command or option: ${arguments.first()}")
    }

    private fun printHelp(output: PrintStream): Int {
        output.print(HELP)
        return ExitStatus.SUCCESS.code
    }

    private fun printVersion(output: PrintStream): Int {
        output.println("kartograph ${KartographVersion.current}")
        return ExitStatus.SUCCESS.code
    }

    private fun runGraph(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(GRAPH_HELP)
            return ExitStatus.SUCCESS.code
        }
        val options = try {
            parseGraphOptions(arguments, error)
        } catch (pathError: InvalidPathException) {
            usageError(error, "invalid path")
            null
        } ?: return ExitStatus.USAGE.code
        if (options.classRoots.any { classRoot -> !Files.isDirectory(classRoot) && !Files.isRegularFile(classRoot) }) {
            return toolFailure(error, "class root does not exist; build the project or pass a class directory or JAR")
        }
        if (options.projectRoot != null && !Files.isDirectory(options.projectRoot)) {
            return toolFailure(error, "project root does not exist; pass the directory that holds the source files")
        }
        return try {
            output.print(renderGraph(ClassFileIndexer().index(options.classRoots), options))
            ExitStatus.SUCCESS.code
        } catch (indexingError: ClassIndexingException) {
            toolFailure(error, indexingError.message ?: "class indexing failed")
        }
    }

    private fun renderGraph(graph: CodeGraph, options: GraphOptions): String =
        if (options.format == GraphFormat.JSON) {
            val paths = options.projectRoot?.let { root -> SourcePathResolution.resolve(graph, root) }
                ?: SourcePathResolution()
            GraphJsonRenderer.render(graph, KartographVersion.current, paths.byNodeId, paths.limitations)
        } else {
            DotGraphRenderer.render(graph)
        }

    private fun parseGraphOptions(arguments: List<String>, error: PrintStream): GraphOptions? {
        val classRoots = mutableListOf<Path>()
        var format = "dot"
        var includePaths = false
        var projectRoot: Path? = null
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--classes" -> {
                    classRoots.add(Path.of(valueAfter(arguments, index, argument, error) ?: return null))
                    index += 2
                }
                "--format" -> {
                    format = valueAfter(arguments, index, argument, error) ?: return null
                    index += 2
                }
                "--project" -> {
                    projectRoot = Path.of(valueAfter(arguments, index, argument, error) ?: return null)
                        .toAbsolutePath()
                        .normalize()
                    index += 2
                }
                "--include-paths" -> {
                    includePaths = true
                    index++
                }
                else -> {
                    usageError(error, "unknown graph option: $argument")
                    return null
                }
            }
        }
        val resolved = GraphFormat.parse(format)
        if (resolved == null) {
            usageError(error, "invalid graph format: $format")
            return null
        }
        // 요청한 경로가 조용히 무시되지 않도록 형식과 입력이 맞지 않으면 사용 오류로 알린다.
        if (includePaths && resolved != GraphFormat.JSON) {
            usageError(error, "--include-paths requires --format json")
            return null
        }
        if (includePaths && projectRoot == null) {
            usageError(error, "--include-paths requires --project")
            return null
        }
        if (!includePaths && projectRoot != null) {
            usageError(error, "--project requires --include-paths")
            return null
        }
        if (classRoots.isEmpty()) {
            usageError(error, "missing required --classes path")
            return null
        }
        return GraphOptions(classRoots, resolved, projectRoot)
    }

    private fun valueAfter(
        arguments: List<String>,
        optionIndex: Int,
        option: String,
        error: PrintStream,
    ): String? {
        val value = arguments.getOrNull(optionIndex + 1)
        if (value == null || value.startsWith('-')) {
            usageError(error, "missing value for $option")
            return null
        }
        return value
    }

    private fun usageError(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.USAGE.code
    }

    private fun toolFailure(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.FAILURE.code
    }

    private enum class GraphFormat(val option: String) {
        DOT("dot"),
        JSON("json");

        companion object {
            fun parse(value: String): GraphFormat? = entries.firstOrNull { it.option == value }
        }
    }

    private data class GraphOptions(
        val classRoots: List<Path>,
        val format: GraphFormat,
        val projectRoot: Path?,
    )

    private val HELP = """
        kartograph — dependency graphs for Kotlin and Android codebases

        Usage:
          kartograph graph --classes <directory-or-jar> [--classes <directory-or-jar>]... [--format dot|json] \
            [--include-paths --project <directory>]
          kartograph dead --classes <directory> --project <directory> [options]
          kartograph baseline --write <file> --classes <directory> --project <directory> [options]
          kartograph query <symbol> --classes <directory> [--classes <directory>]... --project <directory> [options]
          kartograph bridges --project <directory> [--format json]
          kartograph skill
          kartograph cycles --classes <directory-or-jar> [--classes <directory-or-jar>]... [--strict]
          kartograph rules --classes <directory-or-jar> --config <file> [--strict] [--explain <symbol>]
          kartograph metrics --classes <directory-or-jar> [--classes <directory-or-jar>]...

        Exit codes:
          0   success
          1   findings with --strict, or a configured threshold exceeded
          2   tool failure
          64  usage error, ambiguous query, or query symbol not found
    """.trimIndent() + "\n"

    private val GRAPH_HELP = """
        Render the dependency graph.

        Usage:
          kartograph graph --classes <directory-or-jar> [--classes <directory-or-jar>]... [--format dot|json] \
            [--include-paths --project <directory>]

        dot omits source locations. json carries one node per declaration with its usr, qualifiedName, kind,
        accessibility and, when the class debug attributes recorded it, the source file name.

        --include-paths resolves each source file name against --project and reports the project-relative path
        when exactly one source file matches. Every location states its origin in pathKind, and the counts that
        stayed unresolved are reported as unresolved-source-paths and missing-source-paths limitations.
        Absolute local paths are never emitted.
    """.trimIndent() + "\n"
}

/**
 * bytecode가 남긴 source file 이름을 project 기준 상대경로로 해석한 결과다.
 * 유일하게 확정된 경로만 담고, 확정하지 못한 정점 수는 측정된 한계로 알린다.
 */
internal data class SourcePathResolution(
    val byNodeId: Map<NodeId, String> = emptyMap(),
    val limitations: List<String> = emptyList(),
) {
    companion object {
        fun resolve(graph: CodeGraph, projectRoot: Path): SourcePathResolution {
            // SourcePaths는 절대 정규화 경로를 모으므로 symlink를 지나는 project root도 같은 기준으로 맞춘다.
            val root = try {
                projectRoot.toRealPath()
            } catch (error: IOException) {
                projectRoot
            } catch (error: SecurityException) {
                projectRoot
            }
            val pathsByFileName = SourcePaths.byFileName(root)
            val byNodeId = mutableMapOf<NodeId, String>()
            var located = 0
            var unresolved = 0
            graph.nodeIds.forEach { nodeId ->
                val sourceFileName = graph.nodes.getValue(nodeId).location?.path ?: return@forEach
                located++
                // 이름이 여러 파일과 맞거나(모호) project 밖에서 컴파일된 class(무일치)는 확정하지 않는다.
                val match = pathsByFileName[sourceFileName]?.singleOrNull()
                if (match == null) {
                    unresolved++
                    return@forEach
                }
                // 교환 문서가 플랫폼과 무관하게 같아지도록 항상 '/'로 잇는다.
                byNodeId[nodeId] = root.relativize(match).joinToString("/")
            }
            val missing = graph.nodeCount - located
            return SourcePathResolution(
                byNodeId = byNodeId,
                limitations = buildList {
                    if (unresolved > 0) add(
                        "unresolved-source-paths: $unresolved of $located located node(s) did not match exactly one project source file",
                    )
                    if (missing > 0) add(
                        "missing-source-paths: $missing of ${graph.nodeCount} node(s) have no source file in the class debug attributes",
                    )
                },
            )
        }
    }
}
