package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.core.Finding
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.BaselineCodec
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.BridgeFactScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassHierarchyIndexer
import dev.kartograph.index.KeepRuleScanner
import dev.kartograph.index.RuntimeLimitationScanner
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant

internal object AgentCommand {
    fun skill(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        var projectValue = "."
        var force = false
        var index = 0
        while (index < arguments.size) {
            when (val option = arguments[index]) {
                "--project" -> {
                    val value = arguments.getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
                        ?: return usage(error, "missing value for --project")
                    projectValue = value
                    index += 2
                }
                "--force" -> {
                    force = true
                    index++
                }
                else -> return usage(error, "unknown skill option: $option")
            }
        }
        val project = try {
            Path.of(projectValue).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return usage(error, "invalid project path")
        }
        if (!Files.isDirectory(project)) {
            error.println("error: project root does not exist")
            return ExitStatus.FAILURE.code
        }
        val target = project.resolve(".claude/skills/kartograph/SKILL.md")
        if (Files.exists(target) && !force) return usage(error, "skill already exists; pass --force to overwrite")
        return try {
            val text = AgentCommand::class.java.getResourceAsStream("/kartograph/SKILL.md")
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("bundled skill is missing")
            Files.createDirectories(requireNotNull(target.parent))
            Files.writeString(target, text + if (text.endsWith('\n')) "" else "\n")
            output.println("Wrote .claude/skills/kartograph/SKILL.md")
            ExitStatus.SUCCESS.code
        } catch (_: Exception) {
            error.println("error: unable to install the bundled skill; check project permissions")
            ExitStatus.FAILURE.code
        }
    }

    fun query(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        val requested = arguments.firstOrNull()?.takeUnless { it.startsWith('-') }
            ?: return usage(error, "query requires a symbol")
        val options = parsePaths(
            arguments.drop(1),
            setOf(
                "--classes", "--project", "--depth", "--limit", "--manifest", "--resources", "--namespace",
                "--keep-rules", "--classpath", "--baseline",
            ),
            error,
        )
            ?: return ExitStatus.USAGE.code
        val classRoots: List<Path>
        val project: Path
        try {
            classRoots = options.values("--classes").map { Path.of(it) }
            project = options.single("--project")?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: return usage(error, "missing required --project path")
        } catch (_: InvalidPathException) {
            return usage(error, "invalid path")
        }
        if (classRoots.isEmpty()) return usage(error, "missing required --classes path")
        if (classRoots.any { root -> !Files.isDirectory(root) && !Files.isRegularFile(root) } ||
            !Files.isDirectory(project)
        ) {
            error.println("error: query input does not exist")
            return ExitStatus.FAILURE.code
        }
        val depth = options.positiveInt("--depth", 1, error) ?: return ExitStatus.USAGE.code
        val limit = options.positiveInt("--limit", 50, error) ?: return ExitStatus.USAGE.code
        return try {
            val graph = ClassFileIndexer().index(classRoots)
            val classpath = options.values("--classpath").map { resolveProjectPath(project, it) }
            val hierarchy = ClassHierarchyIndexer().index(classpath, graph.nodes.values.flatMap { it.supertypes })
            val inputEvidence = buildList {
                options.single("--manifest")?.let { manifest ->
                    val namespace = options.single("--namespace")
                        ?: return usage(error, "query with --manifest requires --namespace")
                    addAll(AndroidManifestScanner(project).scan(resolveProjectPath(project, manifest), namespace))
                }
                options.values("--resources").forEach { resources ->
                    addAll(AndroidXmlScanner(project).scan(resolveProjectPath(project, resources)))
                }
            }
            val keepRules = KeepRuleScanner(project).scan(
                options.values("--keep-rules").map { resolveProjectPath(project, it) },
            )
            val evidence = DefaultRetention.find(graph, inputEvidence, keepRules, hierarchy)
            val reachability = ReachabilityAnalyzer.analyze(graph, evidence)
            val baseline = options.single("--baseline")?.let { path ->
                BaselineCodec.parse(Files.readString(resolveProjectPath(project, path)))
            }.orEmpty()
            val suppressed = graph.nodes.values
                .filter { node -> Finding(node.id, node.location).fingerprint in baseline }
                .mapTo(mutableSetOf()) { node -> node.id }
            val document = SymbolQuery.query(
                graph,
                reachability,
                requested,
                RuntimeLimitationScanner.scan(classRoots, project),
                depth,
                limit,
                suppressed,
            )
            output.print(AgentDocumentRenderer.query(document))
            if (document.status == "found") ExitStatus.SUCCESS.code else ExitStatus.USAGE.code
        } catch (_: InvalidPathException) {
            usage(error, "invalid path")
        } catch (_: Exception) {
            error.println("error: unable to query compiled declarations; check the inputs")
            ExitStatus.FAILURE.code
        }
    }

    fun bridges(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        val options = parsePaths(arguments, setOf("--project", "--format"), error) ?: return ExitStatus.USAGE.code
        val project = try {
            options.single("--project")?.let(Path::of)?.toAbsolutePath()?.normalize()
                ?: return usage(error, "missing required --project path")
        } catch (_: InvalidPathException) {
            return usage(error, "invalid path")
        }
        if (options.single("--format")?.let { it != "json" } == true) return usage(error, "invalid bridges format")
        if (!Files.isDirectory(project)) {
            error.println("error: project root does not exist")
            return ExitStatus.FAILURE.code
        }
        return try {
            val document = BridgeFactScanner(project).scan(Instant.now().toString())
            output.print(AgentDocumentRenderer.bridges(document))
            ExitStatus.SUCCESS.code
        } catch (_: Exception) {
            error.println("error: unable to scan bridge facts; check the project inputs")
            ExitStatus.FAILURE.code
        }
    }

    private fun parsePaths(arguments: List<String>, allowed: Set<String>, error: PrintStream): ParsedOptions? {
        val values = mutableMapOf<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (option !in allowed) {
                error.println("error: unknown option: $option")
                return null
            }
            val value = arguments.getOrNull(index + 1)?.takeUnless { it.startsWith('-') }
            if (value == null) {
                error.println("error: missing value for $option")
                return null
            }
            values.getOrPut(option) { mutableListOf() } += value
            index += 2
        }
        return ParsedOptions(values)
    }

    private fun usage(error: PrintStream, message: String): Int {
        error.println("error: $message")
        return ExitStatus.USAGE.code
    }

    private fun resolveProjectPath(projectRoot: Path, value: String): Path {
        val path = Path.of(value)
        return if (path.isAbsolute) path.normalize() else projectRoot.resolve(path).normalize()
    }

    private data class ParsedOptions(val options: Map<String, List<String>>) {
        fun values(name: String): List<String> = options[name].orEmpty()
        fun single(name: String): String? = values(name).lastOrNull()
        fun positiveInt(name: String, default: Int, error: PrintStream): Int? {
            val raw = single(name) ?: return default
            val value = raw.toIntOrNull()
            if (value == null || value < 1) error.println("error: $name must be a positive integer")
            return value?.takeIf { it > 0 }
        }
    }
}
