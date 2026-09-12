package dev.kartograph.cli

import dev.kartograph.analysis.DefaultRetention
import dev.kartograph.analysis.IncompleteKeepRuleHierarchyException
import dev.kartograph.analysis.ReachabilityAnalyzer
import dev.kartograph.analysis.SymbolQuery
import dev.kartograph.core.Finding
import dev.kartograph.export.AgentDocumentRenderer
import dev.kartograph.export.BaselineCodec
import dev.kartograph.export.QuerySnapshot
import dev.kartograph.export.QuerySnapshotCodec
import dev.kartograph.index.AndroidManifestScanner
import dev.kartograph.index.AndroidXmlScanner
import dev.kartograph.index.BridgeFactScanner
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassHierarchyIndexer
import dev.kartograph.index.KeepRuleScanner
import dev.kartograph.index.KeepRuleScanningException
import dev.kartograph.index.RuntimeLimitationScanner
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.io.IOException

internal object AgentCommand {
    fun skill(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(SKILL_HELP)
            return ExitStatus.SUCCESS.code
        }
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
        return try {
            val target = skillTarget(project)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !force) {
                return usage(error, "skill already exists; pass --force to overwrite")
            }
            val text = AgentCommand::class.java.getResourceAsStream("/kartograph/SKILL.md")
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("bundled skill is missing")
            val content = text + if (text.endsWith('\n')) "" else "\n"
            if (force) {
                // 기존 파일을 truncate하면 프로젝트 밖 hard link도 함께 바뀌므로 디렉터리 항목만 교체한다.
                val temporary = Files.createTempFile(target.parent, ".kartograph-skill-", ".tmp")
                try {
                    Files.writeString(temporary, content, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            } else {
                Files.writeString(target, content, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
            }
            output.println("Wrote .claude/skills/kartograph/SKILL.md")
            ExitStatus.SUCCESS.code
        } catch (_: Exception) {
            error.println("error: unable to install the bundled skill; check project permissions")
            ExitStatus.FAILURE.code
        }
    }

    // 프로젝트 루트 alias는 허용하지만 설치 경로 안의 링크는 따라가지 않는다.
    private fun skillTarget(project: Path): Path {
        val root = project.toRealPath()
        var parent = root
        for (name in listOf(".claude", "skills", "kartograph")) {
            val directory = parent.resolve(name)
            if (Files.isSymbolicLink(directory)) throw IOException("skill directory must not be a symbolic link")
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory)
            parent = directory.toRealPath()
            if (!parent.startsWith(root) || !Files.isDirectory(parent)) throw IOException("invalid skill directory")
        }
        val target = parent.resolve("SKILL.md")
        if (Files.isSymbolicLink(target)) throw IOException("skill file must not be a symbolic link")
        return target
    }

    fun query(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(QUERY_HELP)
            return ExitStatus.SUCCESS.code
        }
        val requested = arguments.firstOrNull()?.takeUnless { it.startsWith('-') }
            ?: return usage(error, "query requires a symbol")
        val options = arguments.drop(1)
        return if ("--graph-file" in options) savedQuery(requested, options, output, error)
        else compiledQuery(options, requested, output, error)
    }

    fun snapshot(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(SNAPSHOT_HELP)
            return ExitStatus.SUCCESS.code
        }
        return compiledQuery(arguments, null, output, error)
    }

    private fun compiledQuery(arguments: List<String>, requested: String?, output: PrintStream, error: PrintStream): Int {
        val options = parsePaths(
            arguments,
            setOf(
                "--classes", "--project", "--manifest", "--resources", "--namespace",
                "--keep-rules", "--classpath", "--service-resources", "--baseline", "--include-private-members", "--generated-classes",
            ) + if (requested != null) setOf("--depth", "--limit") else setOf("--include-paths", "--revision", "--scope", "--compact", "--build-witness", "--source-root", "--build-input", "--timings"),
            error,
        )
            ?: return ExitStatus.USAGE.code
        if (requested == null) {
            if (options.values("--revision").size > 1 || options.values("--scope").size > 1) return usage(error, "duplicate snapshot context option")
            if (options.single("--revision")?.let { !Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(it) } == true) return usage(error, "snapshot revision must be a full commit hash")
            if (options.single("--scope")?.let { it.length > 200 || !Regex("[A-Za-z0-9_.:-]+").matches(it) } == true) return usage(error, "scope must be a portable project:variant label")
        }
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
            val classpath = options.values("--classpath").map { resolveProjectPath(project, it) }
            val keepScanner = KeepRuleScanner(project, options.values("--include-private-members").isNotEmpty())
            val keepRules = keepScanner.scan(options.values("--keep-rules").map { resolveProjectPath(project, it) })
            val generatedRoots = options.values("--generated-classes").map(Path::of)
            val fingerprintFiles = classRoots.map { "classes" to it } + classpath.map { "classpath" to it } +
                generatedRoots.map { "generated-classes" to it } +
                listOf("--manifest", "--resources", "--service-resources", "--baseline", "--source-root", "--build-input").flatMap { option ->
                    val role = when (option) { "--source-root" -> "sources"; "--build-input" -> "buildConfig"; else -> option.removePrefix("--") }
                    options.values(option).map { role to resolveProjectPath(project, it) }
                } + keepScanner.inputFiles.map { "keepRules" to it }
            val context = listOf("--namespace", "--include-private-members", "--include-paths", "--scope").flatMap { listOf(it) + options.values(it) }
            val witnessPaths = options.values("--build-witness").map { resolveProjectPath(project, it) }
            val hashStarted = System.nanoTime()
            val provenance = if (requested == null) FreshnessCommand.capture(project, fingerprintFiles, context, witnessPaths) else null
            var captureHashNanos = System.nanoTime() - hashStarted
            val indexed = ClassFileIndexer().indexWithObservations(classRoots, classpath,
                options.values("--service-resources").map { resolveProjectPath(project, it) },
                generatedRoots)
            val graph = indexed.graph
            val hierarchy = indexed.hierarchy
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
            val evidence = DefaultRetention.find(graph, inputEvidence, keepRules, hierarchy,
                includePrivateMembers = options.values("--include-private-members").isNotEmpty())
            val baseline = options.single("--baseline")?.let { path ->
                BaselineCodec.parse(Files.readString(resolveProjectPath(project, path)))
            }.orEmpty()
            val suppressed = graph.nodes.values
                .filter { node -> Finding(node.id, node.location).fingerprint in baseline }
                .mapTo(mutableSetOf()) { node -> node.id }
            val limitations = RuntimeLimitationScanner.scan(indexed, project)
            if (requested == null) {
                val paths = if (options.values("--include-paths").isNotEmpty()) dev.kartograph.index.SourcePathIndex.resolve(graph, project) else null
                val capturedGraph = if (paths == null) graph else dev.kartograph.core.CodeGraph(graph.nodes.values.map { node ->
                    paths.byNodeId[node.id]?.let { path -> node.copy(location = node.location?.copy(path = path)) } ?: node
                }, graph.edges, graph.externalCalls, graph.serviceProviders)
                val rehashStarted = System.nanoTime()
                require(provenance == FreshnessCommand.capture(project, fingerprintFiles, context, witnessPaths)) { "snapshot inputs changed during capture" }
                captureHashNanos += System.nanoTime() - rehashStarted
                if (options.values("--timings").isNotEmpty()) error.println("captureHashNanos=$captureHashNanos")
                val captured = QuerySnapshotCodec.render(QuerySnapshot(capturedGraph, evidence, limitations + paths?.limitations.orEmpty(), suppressed,
                    options.values("--include-private-members").isNotEmpty(),
                    revision = options.single("--revision"), scope = options.single("--scope"), provenance = provenance), compact = options.values("--compact").isNotEmpty())
                // JSON writer는 ASCII escape를 사용하므로 문자 수가 UTF-8 바이트 수와 같다.
                if (captured.length > QuerySnapshotCodec.MAX_BYTES) {
                    error.println("error: query snapshot (${captured.length} bytes) exceeds 64 MiB; use --compact or capture a narrower input scope")
                    ExitStatus.FAILURE.code
                } else {
                    output.print(captured)
                    ExitStatus.SUCCESS.code
                }
            } else {
                val document = SymbolQuery.query(graph, ReachabilityAnalyzer.analyze(graph, evidence), requested,
                    limitations, depth, limit, suppressed)
                output.print(AgentDocumentRenderer.query(document))
                if (document.status == "found") ExitStatus.SUCCESS.code else ExitStatus.USAGE.code
            }
        } catch (_: InvalidPathException) {
            usage(error, "invalid path")
        } catch (hierarchyError: IncompleteKeepRuleHierarchyException) {
            error.println("error: ${hierarchyError.message ?: "dependency hierarchy is incomplete"}")
            ExitStatus.FAILURE.code
        } catch (indexError: dev.kartograph.index.ClassIndexingException) {
            error.println("error: ${indexError.message}")
            ExitStatus.FAILURE.code
        } catch (hierarchyError: dev.kartograph.index.ClassHierarchyIndexingException) {
            error.println("error: ${hierarchyError.message}")
            ExitStatus.FAILURE.code
        } catch (ruleError: KeepRuleScanningException) {
            error.println("error: ${ruleError.message}")
            ExitStatus.FAILURE.code
        } catch (_: Exception) {
            error.println("error: unable to query compiled declarations; check the inputs")
            ExitStatus.FAILURE.code
        }
    }

    private fun savedQuery(requested: String, arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        val options = parsePaths(arguments, setOf("--graph-file", "--depth", "--limit"), error)
            ?: return ExitStatus.USAGE.code
        if (options.values("--graph-file").size != 1) return usage(error, "provide exactly one --graph-file")
        val depth = options.positiveInt("--depth", 1, error) ?: return ExitStatus.USAGE.code
        val limit = options.positiveInt("--limit", 50, error) ?: return ExitStatus.USAGE.code
        return try {
            val snapshot = SnapshotFiles.read(options.single("--graph-file")!!)
            val provenanceLimitation = if (snapshot.provenance?.witnesses.isNullOrEmpty())
                "build-provenance-unverified: no compiler-task evidence was captured"
                else "build-provenance: captured compiler-task evidence has not been rechecked"
            val document = SymbolQuery.query(snapshot.graph, ReachabilityAnalyzer.analyze(snapshot.graph, snapshot.retention),
                requested, (snapshot.limitations + SAVED_GRAPH_LIMITATION + provenanceLimitation).distinct().sorted(), depth, limit, snapshot.suppressed)
            output.print(AgentDocumentRenderer.query(document))
            if (document.status == "found") ExitStatus.SUCCESS.code else ExitStatus.USAGE.code
        } catch (_: InvalidPathException) {
            usage(error, "invalid graph file path")
        } catch (_: Exception) {
            error.println("error: unable to read query snapshot (maximum 64 MiB); capture a valid file with `kartograph snapshot`")
            ExitStatus.FAILURE.code
        }
    }

    private const val SAVED_GRAPH_LIMITATION = "saved-graph: using captured graph and retention evidence; live inputs and freshness are not rechecked"

    fun bridges(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) {
            output.print(BRIDGES_HELP)
            return ExitStatus.SUCCESS.code
        }
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
            val document = BridgeFactScanner(project).scan()
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
            if (option in setOf("--include-private-members", "--include-paths", "--compact", "--timings")) {
                values.getOrPut(option) { mutableListOf() } += "true"
                index++
                continue
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

    private val QUERY_HELP = """
        Query one symbol against the compiled graph.

        Usage:
          kartograph query <symbol> --classes <directory> [--classes <directory>]... --project <directory> [options]
          kartograph query <symbol> --graph-file <snapshot.json> [--depth <n>] [--limit <n>]

        Options:
          --depth <n>               neighbor depth, a positive integer (default 1)
          --limit <n>               neighbor cap per section, a positive integer (default 50)
          --manifest <file>         read entry points from the manifest (requires --namespace)
          --resources <directory>   read entry points from resources
          --namespace <name>        manifest package name
          --keep-rules <file>       retention rule file, repeatable
          --classpath <path>        dependency hierarchy entry, repeatable
          --service-resources <path> Java resource directory or JAR, repeatable
          --baseline <file>         suppress fingerprinted findings
          --include-private-members include private members in the graph
          --generated-classes <path> mark a supplied class root as generated, repeatable
          --graph-file <file>       query a saved snapshot without reading live inputs

        --classes and --generated-classes resolve from the working directory. Other live-input paths resolve
        from --project. Saved queries accept no live-input overrides.
    """.trimIndent() + "\n"

    private val SNAPSHOT_HELP = """
        Capture a compiled graph with its retention evidence, baseline state and measured limitations.

        Usage:
          kartograph snapshot --classes <directory-or-jar> [--classes <path>]... --project <directory> [options]

        Accepts the live-input options of query except --depth and --limit. Writes a deterministic JSON
        snapshot to stdout. Query it with `kartograph query <symbol> --graph-file <snapshot.json>`.
        --include-paths resolves source locations for `impact --file` and records unresolved path counts.
        --compact writes lossless v2 indexed graph rows and a string table; v1 remains the default.
        --revision <full-commit-hash> and --scope <project:variant> label CI artifacts for input matching.
        --build-witness <file> attaches a successful supported Gradle compiler-task record (repeatable).
        --source-root <directory> and --build-input <file> capture additional explicit source/config inputs.
        --timings reports capture-only fingerprint time to stderr. verify-snapshot reports comparison time.
        These labels are caller assertions; the source freshness checks still apply.
        A snapshot records its input state; it does not prove that current sources or runtime behavior match.
        Ordinary `graph --format json` output does not contain the required retention context.
    """.trimIndent() + "\n"

    private val BRIDGES_HELP = """
        Scan project sources for Flutter MethodChannel and React Native module registrations.

        Usage:
          kartograph bridges --project <directory> [--format json]

        Static literals only; dynamic channel names and unattributed handlers are reported as limitations.
        generatedAt is the newest scanned source modification time (Unix epoch for an empty source tree).
    """.trimIndent() + "\n"

    private val SKILL_HELP = """
        Install the bundled kartograph skill file.

        Usage:
          kartograph skill [--project <directory>] [--force]

        Writes .claude/skills/kartograph/SKILL.md under the project. Refuses to overwrite without --force.
    """.trimIndent() + "\n"

    private fun resolveProjectPath(projectRoot: Path, value: String): Path {
        val path = Path.of(value)
        return if (path.isAbsolute) path.normalize() else projectRoot.resolve(path).normalize()
    }

    private data class ParsedOptions(val options: Map<String, List<String>>) {        fun values(name: String): List<String> = options[name].orEmpty()
        fun single(name: String): String? = values(name).lastOrNull()
        fun positiveInt(name: String, default: Int, error: PrintStream): Int? {
            val raw = single(name) ?: return default
            val value = raw.toIntOrNull()
            if (value == null || value < 1) error.println("error: $name must be a positive integer")
            return value?.takeIf { it > 0 }
        }
    }
}
