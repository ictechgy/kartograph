package dev.kartograph.cli

import dev.kartograph.export.DotGraphRenderer
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassIndexingException
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
        return try {
            output.print(DotGraphRenderer.render(ClassFileIndexer().index(options.classRoots)))
            ExitStatus.SUCCESS.code
        } catch (indexingError: ClassIndexingException) {
            toolFailure(error, indexingError.message ?: "class indexing failed")
        }
    }

    private fun parseGraphOptions(arguments: List<String>, error: PrintStream): GraphOptions? {
        val classRoots = mutableListOf<Path>()
        var format = "dot"
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
                else -> {
                    usageError(error, "unknown graph option: $argument")
                    return null
                }
            }
        }
        if (format != "dot") {
            usageError(error, "invalid graph format: $format")
            return null
        }
        if (classRoots.isEmpty()) {
            usageError(error, "missing required --classes path")
            return null
        }
        return GraphOptions(classRoots)
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

    private data class GraphOptions(val classRoots: List<Path>)

    private val HELP = """
        kartograph — dependency graphs for Kotlin and Android codebases

        Usage:
          kartograph graph --classes <directory-or-jar> [--classes <directory-or-jar>]... [--format dot]
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
          kartograph graph --classes <directory-or-jar> [--classes <directory-or-jar>]... [--format dot]
    """.trimIndent() + "\n"
}
