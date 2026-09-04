package dev.kartograph.cli

import dev.kartograph.analysis.CycleAnalyzer
import dev.kartograph.analysis.ArchitectureGraph
import dev.kartograph.analysis.LayerConfigurationException
import dev.kartograph.analysis.LayerRuleEvaluator
import dev.kartograph.analysis.LayerRuleYaml
import dev.kartograph.analysis.MartinMetrics
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.qualifiedName
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassIndexingException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

internal object ArchitectureCommand {
    fun cycles(arguments: List<String>, output: PrintStream, error: PrintStream): Int = withGraph(arguments, error) { graph, options ->
        val architectureGraph = ArchitectureGraph.aggregate(graph)
        val cycles = CycleAnalyzer.analyze(architectureGraph)
        cycles.forEach { cycle ->
            val names = cycle.path.map { architectureGraph.node(it)?.name ?: it.value }
            output.println((names + names.first()).joinToString(" -> "))
            val edge = cycle.weakestEdge
            val source = architectureGraph.node(edge.source)?.name ?: edge.source.value
            val target = architectureGraph.node(edge.target)?.name ?: edge.target.value
            output.println("  weakest edge: $source -> $target (${edge.kind.name.lowercase(Locale.ROOT)}, weight ${edge.weight})")
        }
        output.println("cycles: ${cycles.size} findings")
        if (options.strict && cycles.isNotEmpty()) ExitStatus.FINDINGS.code else ExitStatus.SUCCESS.code
    }

    fun metrics(arguments: List<String>, output: PrintStream, error: PrintStream): Int = withGraph(arguments, error) { graph, _ ->
        output.println("module\tCa\tCe\tI\tA\tD")
        MartinMetrics.calculate(graph).forEach { metric ->
            output.println("${metric.module}\t${metric.afferentCoupling}\t${metric.efferentCoupling}\t${format(metric.instability)}\t${format(metric.abstractness)}\t${format(metric.distance)}")
        }
        ExitStatus.SUCCESS.code
    }

    fun rules(arguments: List<String>, output: PrintStream, error: PrintStream): Int = withGraph(arguments, error, requireConfig = true) { graph, options ->
        val configuration = try {
            LayerRuleYaml.parse(Files.readString(options.config!!))
        } catch (problem: LayerConfigurationException) {
            error.println("error: invalid layer configuration: ${problem.message}")
            return@withGraph ExitStatus.FAILURE.code
        } catch (_: Exception) {
            error.println("error: unable to read layer configuration")
            return@withGraph ExitStatus.FAILURE.code
        }
        val evaluator = LayerRuleEvaluator(configuration.layers, configuration.rules)
        val violations = evaluator.evaluate(graph)
        val unassigned = evaluator.unassignedNodes(graph)
        violations.forEach { violation ->
            val location = violation.location?.let { "${it.path}${it.line?.let { line -> ":$line" }.orEmpty()}: " }.orEmpty()
            output.println("$location${violation.edge.source} (${violation.sourceLayer}) -> ${violation.edge.target} (${violation.targetLayer}) [${violation.rule.name}]")
            output.println("  evidence: ${violation.edge.kind.name.lowercase(Locale.ROOT)}, weight ${violation.edge.weight}")
        }
        if (unassigned.isNotEmpty()) output.println("info: ${unassigned.size} declarations are not assigned to a layer")
        options.explain?.let { query ->
            val matches = graph.nodes.values.filter { node ->
                node.id.value == query || node.name == query || node.qualifiedName == query
            }.sortedBy { it.id }
            if (matches.size != 1) {
                error.println("error: explanation symbol must match exactly one declaration")
                return@withGraph ExitStatus.USAGE.code
            }
            val assignment = evaluator.assignment(matches.single())
            output.println("${matches.single().id} layer: ${assignment.layer ?: "unassigned"}")
            if (assignment.pattern != null) output.println("  matched: ${assignment.candidate} against '${assignment.pattern}'")
        }
        output.println("rules: ${violations.size} findings, ${unassigned.size} unassigned")
        if (options.strict && (violations.isNotEmpty() || unassigned.isNotEmpty())) {
            ExitStatus.FINDINGS.code
        } else {
            ExitStatus.SUCCESS.code
        }
    }

    private inline fun withGraph(
        arguments: List<String>,
        error: PrintStream,
        requireConfig: Boolean = false,
        action: (CodeGraph, Options) -> Int,
    ): Int {
        val options = try { parse(arguments, requireConfig) } catch (_: InvalidPathException) {
            error.println("error: invalid path")
            return ExitStatus.USAGE.code
        } catch (problem: IllegalArgumentException) {
            error.println("error: ${problem.message}")
            return ExitStatus.USAGE.code
        }
        if (options.roots.any { root -> !Files.isDirectory(root) && !Files.isRegularFile(root) }) {
            error.println("error: class root does not exist; build the project first")
            return ExitStatus.FAILURE.code
        }
        if (requireConfig && !Files.isRegularFile(options.config)) {
            error.println("error: layer configuration does not exist")
            return ExitStatus.FAILURE.code
        }
        return try { action(ClassFileIndexer().index(options.roots), options) } catch (_: ClassIndexingException) {
            error.println("error: unable to index compiled declarations")
            ExitStatus.FAILURE.code
        }
    }

    private fun parse(arguments: List<String>, requireConfig: Boolean): Options {
        val roots = mutableListOf<Path>()
        var config: Path? = null
        var strict = false
        var explain: String? = null
        var index = 0
        while (index < arguments.size) when (val option = arguments[index]) {
            "--classes" -> { roots.add(Path.of(value(arguments, ++index, option))); index++ }
            "--config" -> { config = Path.of(value(arguments, ++index, option)); index++ }
            "--strict" -> { strict = true; index++ }
            "--explain" -> { explain = value(arguments, ++index, option); index++ }
            else -> throw IllegalArgumentException("unknown architecture option: $option")
        }
        require(roots.isNotEmpty()) { "missing required --classes path" }
        if (requireConfig) require(config != null) { "missing required --config path" }
        return Options(roots, config, strict, explain)
    }

    private fun value(arguments: List<String>, index: Int, option: String): String =
        arguments.getOrNull(index)?.takeUnless { it.startsWith("--") }
            ?: throw IllegalArgumentException("missing value for $option")

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    private data class Options(val roots: List<Path>, val config: Path?, val strict: Boolean, val explain: String?)
}
