package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.index.GraphExportIndexOptions
import dev.kartograph.index.IndexedClasses
import dev.kartograph.export.DotGraphRenderer
import dev.kartograph.export.GraphJsonRenderer
import dev.kartograph.export.GraphCsvRenderer
import dev.kartograph.export.GraphCsvPublication
import dev.kartograph.index.ClassFileIndexer
import dev.kartograph.index.ClassHierarchyIndexingException
import dev.kartograph.index.ClassIndexingException
import dev.kartograph.index.SourcePathIndex
import dev.kartograph.index.SourceDeclarationLocations
import dev.kartograph.index.SourcePathResolution
import java.io.PrintStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
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
    fun run(arguments: Array<out String>, output: PrintStream, error: PrintStream): Int =
        runWithInput(arguments, output, error, System.`in`)

    fun runWithInput(
        arguments: Array<out String>,
        output: PrintStream,
        error: PrintStream,
        input: java.io.InputStream,
    ): Int = when (arguments.firstOrNull()) {
        null, "--help", "-h" -> printHelp(output)
        "--version" -> printVersion(output)
        "graph" -> runGraph(arguments.drop(1), output, error)
        "dead" -> DeadCommand.run(arguments.drop(1), output, error)
        "baseline" -> DeadCommand.runBaseline(arguments.drop(1), output, error)
        "why" -> WhyCommand.run(arguments.drop(1), output, error)
        "query" -> AgentCommand.query(arguments.drop(1), output, error)
        "snapshot" -> AgentCommand.snapshot(arguments.drop(1), output, error)
        "verify-snapshot" -> FreshnessCommand.run(arguments.drop(1), output, error)
        "mcp" -> McpCommand.run(arguments.drop(1), input, output, error)
        "impact" -> ImpactCommand.run(arguments.drop(1), output, error)
        "reach" -> TraversalCommand.run(arguments.drop(1), dev.kartograph.analysis.TraversalDirection.DEPENDENCIES, output, error)
        "bridges" -> AgentCommand.bridges(arguments.drop(1), output, error)
        "schema" -> AgentCommand.schema(arguments.drop(1), output, error)
        "routes" -> RoutesCommand.run(arguments.drop(1), output, error)
        "skill" -> AgentCommand.skill(arguments.drop(1), output, error)
        "dependencies" -> DependenciesCommand.run(arguments.drop(1), output, error)
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
            val indexed = ClassFileIndexer(callbackFacts = false).indexForGraphExport(options.classRoots,
                options.classpath.takeIf { it.isNotEmpty() }, options.serviceResources, options.generatedClassRoots,
                GraphExportIndexOptions(options.excludedOrigins, options.maximumDispatchCandidates, options.includeExternalStubs))
            writeGraph(tagModules(indexed.indexed, options), options, output, error, indexed.limitations)
        } catch (indexingError: ClassIndexingException) {
            toolFailure(error, indexingError.message ?: "class indexing failed")
        } catch (hierarchyError: ClassHierarchyIndexingException) {
            toolFailure(error, hierarchyError.message ?: "classpath indexing failed")
        }
    }

    /** 입력 해석과 분리한 출력 경계이며 목적지·문자열 표현 한계만 이곳에서 분류한다. */
    private fun writeGraph(inputGraph: CodeGraph, options: GraphOptions, output: PrintStream, error: PrintStream,
        indexingLimitations: List<String>): Int = try {
            val sourceResolution = sourcePaths(inputGraph, options)
            val graph = options.projectRoot?.let { SourceDeclarationLocations.enrich(inputGraph, it, sourceResolution.byNodeId) } ?: inputGraph
            if (options.format == GraphFormat.NEO4J_CSV) {
                val paths = sourceResolution
                GraphCsvPublication.publish(requireNotNull(options.outputDirectory)) { directory ->
                Files.newBufferedWriter(directory.resolve("nodes.csv"), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { nodes ->
                    Files.newBufferedWriter(directory.resolve("edges.csv"), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { edges ->
                        GraphCsvRenderer.write(graph, nodes, edges, paths.byNodeId)
                    }
                }
                val limitations = exportLimitations(paths.limitations, indexingLimitations, options)
                Files.newBufferedWriter(directory.resolve("facts.ndjson"), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { facts ->
                    GraphJsonRenderer.writeNdjson(graph, KartographVersion.current, facts, paths.byNodeId, limitations, includeGraphRecords = false)
                }
                Files.writeString(directory.resolve("manifest.json"),
                    GraphCsvRenderer.manifest(graph, KartographVersion.current, limitations),
                    java.nio.file.StandardOpenOption.CREATE_NEW)
                }
                output.println("{\"format\":\"code-graph-csv\",\"version\":1,\"status\":\"written\"}")
            } else if (options.format == GraphFormat.DOT) {
                output.print(DotGraphRenderer.render(graph))
                exportLimitations(emptyList(), indexingLimitations, options).forEach { error.println("limitation: $it") }
            } else {
                val paths = sourceResolution
                val limitations = exportLimitations(paths.limitations, indexingLimitations, options)
                val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8), 64 * 1024)
                if (options.format == GraphFormat.NDJSON) {
                    GraphJsonRenderer.writeNdjson(graph, KartographVersion.current, writer, paths.byNodeId, limitations)
                } else {
                    GraphJsonRenderer.write(graph, KartographVersion.current, writer, paths.byNodeId, limitations)
                }
                writer.flush()
            }
            if (output.checkError()) toolFailure(error, "graph output could not be written; check the output destination")
            else ExitStatus.SUCCESS.code
        } catch (existsError: java.nio.file.FileAlreadyExistsException) {
            toolFailure(error, "graph export destination or reservation already exists; use a fresh --output-directory")
        } catch (writeError: IOException) {
            toolFailure(error, "graph output could not be written; check the output destination")
        } catch (sizeError: OutOfMemoryError) {
            if (sizeError.message?.contains("Requested array size exceeds VM limit", ignoreCase = true) == true)
                toolFailure(error, "graph output exceeded the JVM string or array representation limit; use --format ndjson")
            else throw sizeError
        }

    /** 원시 한계와 사용자가 선택한 출처 제외를 모든 export 형식에서 공유한다. */
    private fun exportLimitations(source: List<String>, indexing: List<String>, options: GraphOptions): List<String> =
        (source + indexing + options.excludedOrigins.map { origin ->
            "edge-origin-excluded: ${GRAPH_ORIGINS.entries.single { it.value == origin }.key} was explicitly excluded from graph export"
        }).distinct().sorted()

    /** 원래 class 방문의 첫 입력 소유권으로만 명시된 모듈 태그를 붙인다. */
    private fun tagModules(indexed: IndexedClasses, options: GraphOptions): CodeGraph {
        if (options.moduleNames.all { it == null }) return indexed.graph
        val original = indexed.graph
        val tagged = CodeGraph(original.nodes.values.map { node ->
            indexed.selectedRootByNode[node.id]?.let { options.moduleNames.getOrNull(it) }?.let { node.copy(moduleName = it) } ?: node
        }, original.edges, original.externalCalls, original.serviceProviders, original.enclosures,
            original.callbackArguments, original.parameterUses, original.lambdaEscapes)
        return if (original.compilerCallPositionsCaptured) tagged.withCompilerCallPositions(original.locatedCompilerReferences) else tagged
    }

    /** 위치 해석 요청과 관측한 누락 한계를 JSON·NDJSON에 동일하게 적용한다. */
    private fun sourcePaths(graph: CodeGraph, options: GraphOptions): SourcePathResolution =
        options.projectRoot?.let { root -> SourcePathIndex.resolve(graph, root) }
            ?: SourcePathResolution(limitations = SourcePathIndex.missingSourcePaths(graph))

    private fun parseGraphOptions(arguments: List<String>, error: PrintStream): GraphOptions? {
        val classRoots = mutableListOf<Path>()
        val moduleNames = mutableListOf<String?>()
        val classpath = mutableListOf<Path>()
        val serviceResources = mutableListOf<Path>()
        val generatedClassRoots = mutableListOf<Path>()
        var format = "dot"
        var outputDirectory: Path? = null
        var includePaths = false
        var includeExternalStubs = false
        var projectRoot: Path? = null
        val excludedOrigins = mutableSetOf<EdgeOrigin>()
        var maximumDispatchCandidates = 256
        var dispatchLimitSeen = false
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--classes" -> {
                    classRoots.add(Path.of(valueAfter(arguments, index, argument, error) ?: return null))
                    moduleNames.add(null)
                    index += 2
                }
                "--module-name" -> {
                    val value = valueAfter(arguments, index, argument, error) ?: return null
                    if (classRoots.isEmpty() || moduleNames.last() != null || value.length !in 1..256 ||
                        value.any { it.code < 32 || it.code in 127..159 || it in "\u2028\u2029" }) {
                        usageError(error, "--module-name must follow an untagged --classes root and contain a safe nonempty label"); return null
                    }
                    moduleNames[moduleNames.lastIndex] = value
                    index += 2
                }
                "--generated-classes" -> {
                    generatedClassRoots.add(Path.of(valueAfter(arguments, index, argument, error) ?: return null))
                    index += 2
                }
                "--classpath", "--service-resources" -> {
                    val value = Path.of(valueAfter(arguments, index, argument, error) ?: return null)
                    if (argument == "--classpath") classpath.add(value) else serviceResources.add(value)
                    index += 2
                }
                "--output-directory" -> {
                    if (outputDirectory != null) { usageError(error, "--output-directory cannot be repeated"); return null }
                    outputDirectory = Path.of(valueAfter(arguments, index, argument, error) ?: return null).toAbsolutePath().normalize()
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
                "--exclude-origin" -> {
                    val value = valueAfter(arguments, index, argument, error) ?: return null
                    val origin = GRAPH_ORIGINS[value]
                    if (origin == null) { usageError(error, "unknown edge origin; use bytecode, kotlinMetadata, dispatchModel, runtimeModel or compilerReference"); return null }
                    excludedOrigins += origin
                    index += 2
                }
                "--dispatch-candidate-limit" -> {
                    val value = valueAfter(arguments, index, argument, error) ?: return null
                    val limit = value.toIntOrNull()
                    if (dispatchLimitSeen || !value.matches(Regex("[1-9][0-9]{0,6}")) || limit == null || limit !in 1..1_000_000) {
                        usageError(error, "--dispatch-candidate-limit takes one integer in 1..1000000"); return null
                    }
                    maximumDispatchCandidates = limit
                    dispatchLimitSeen = true
                    index += 2
                }
                "--include-external-stubs" -> {
                    if (includeExternalStubs) { usageError(error, "--include-external-stubs cannot be repeated"); return null }
                    includeExternalStubs = true
                    index++
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
        if (includePaths && resolved == GraphFormat.DOT) {
            usageError(error, "--include-paths requires --format json, ndjson or neo4j-csv")
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
        if ((resolved == GraphFormat.NEO4J_CSV) != (outputDirectory != null)) {
            usageError(error, "--format neo4j-csv requires a new --output-directory; other formats write stdout"); return null
        }
        if (classRoots.isEmpty()) {
            usageError(error, "missing required --classes path")
            return null
        }
        return GraphOptions(classRoots, resolved, projectRoot, classpath, serviceResources, generatedClassRoots,
            excludedOrigins, maximumDispatchCandidates, moduleNames, outputDirectory, includeExternalStubs)
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
        JSON("json"),
        NDJSON("ndjson"),
        NEO4J_CSV("neo4j-csv");

        companion object {
            fun parse(value: String): GraphFormat? = entries.firstOrNull { it.option == value }
        }
    }

    private data class GraphOptions(
        val classRoots: List<Path>,
        val format: GraphFormat,
        val projectRoot: Path?,
        val classpath: List<Path>,
        val serviceResources: List<Path>,
        val generatedClassRoots: List<Path>,
        val excludedOrigins: Set<EdgeOrigin>,
        val maximumDispatchCandidates: Int,
        val moduleNames: List<String?>,
        val outputDirectory: Path?,
        val includeExternalStubs: Boolean,
    )

    /** 외부 형식의 출처 이름은 enum 구현 이름과 분리해 안정적으로 검증한다. */
    private val GRAPH_ORIGINS = mapOf(
        "bytecode" to EdgeOrigin.BYTECODE, "kotlinMetadata" to EdgeOrigin.KOTLIN_METADATA,
        "dispatchModel" to EdgeOrigin.DISPATCH_MODEL, "runtimeModel" to EdgeOrigin.RUNTIME_MODEL,
        "compilerReference" to EdgeOrigin.COMPILER_REFERENCE,
    )

    private val HELP = """
        kartograph — dependency graphs for Kotlin and Android codebases

        Usage:
          kartograph graph --classes <directory-or-jar> [--module-name <label>] [--classes <directory-or-jar> --module-name <label>]... [--format dot|json|ndjson|neo4j-csv] \
            [--include-paths --project <directory>] [--classpath <path>] [--service-resources <path>] \
            [--exclude-origin <origin>] [--dispatch-candidate-limit <1..1000000>] [--include-external-stubs] [--output-directory <new-directory>]
          kartograph dead --classes <directory> --project <directory> [options]
          kartograph baseline --write <file> --classes <directory> --project <directory> [options]
          kartograph why <symbol> --classes <directory> --project <directory> [options]
          kartograph query <symbol> --classes <directory> [--classes <directory>]... --project <directory> [options]
          kartograph snapshot --classes <directory-or-jar> --project <directory> [--snapshot-max-mib <1..128>] [options]
          kartograph snapshot merge --project <root> --module <dir> --graph-file <snapshot.json> [--input-bindings <file>]... [options]
          kartograph verify-snapshot --graph-file <snapshot.json> --project <directory> [--snapshot-max-mib <1..128>] [options]
          kartograph impact <symbol> --graph-file <snapshot.json> [--base-graph <snapshot.json>] [--snapshot-max-mib <1..128>] [options]
          kartograph impact <usr>... --format language-traversal --graph-file <snapshot.json> --project <directory> [--dispatch <mode>] [--class-hops <mode>]
          kartograph reach <usr>... --graph-file <snapshot.json> --project <directory> [--dispatch <mode>] [--class-hops <mode>]
          kartograph query <symbol> --graph-file <snapshot.json> [--depth <n>] [--limit <n>] [--snapshot-max-mib <1..128>]
          kartograph bridges --project <directory> [--format json]
          kartograph schema --project <directory> [--format json] [--graph-file <snapshot>]
          kartograph routes --role client --project <directory> [--wrappers <file>] [--include-tests] [<source-root>...]
          kartograph routes --role server --project <directory> [--graph-file <snapshot>] [--service <name>] [--include-tests] [<source-root>...]
          kartograph mcp --graph-file <snapshot.json> [--snapshot-max-mib <1..128>] [options]
          kartograph skill
          kartograph dependencies --classes <directory> --project <directory> --dependencies <file> [options]
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
          kartograph graph --classes <directory-or-jar> [--module-name <label>] [--classes <directory-or-jar> --module-name <label>]... [--format dot|json|ndjson|neo4j-csv] \
            [--include-paths --project <directory>] [--classpath <path>] [--service-resources <path>] \
            [--exclude-origin <origin>] [--dispatch-candidate-limit <1..1000000>] [--include-external-stubs] [--output-directory <new-directory>]

        dot omits source locations. json carries one node per declaration with its usr, qualifiedName, kind,
        accessibility and, when the class debug attributes recorded it, the source file name.

        --include-paths resolves each source file name against --project and reports the project-relative path
        when exactly one source file matches. Every location states its origin in pathKind, and the counts that
        stayed unresolved are reported as unresolved-source-paths and missing-source-paths limitations.
        Absolute local paths are never emitted.

        --generated-classes <path> marks an existing --classes root as generated-only (repeatable).
        Nodes remain in the graph with synthesized=true and the generatedInput attribute.
    """.trimIndent() + "\n"
}
