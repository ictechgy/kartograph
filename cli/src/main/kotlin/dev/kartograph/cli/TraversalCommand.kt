package dev.kartograph.cli

import dev.kartograph.analysis.LanguageTraversal
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.analysis.TraversalDispatch
import dev.kartograph.export.LanguageTraversalCodec
import dev.kartograph.export.LanguageTraversalMetadata
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException

/**
 * `impact --format language-traversal`(역방향)과 `reach`(정방향)가 공유하는 `language-traversal` v1 생산자다.
 *
 * 기존 `impact` JSON(`kartograph-impact` v1)은 바꾸지 않고, 다중 root 귀속·근거 등급·잇지 못한 호출을 싣는
 * 새 형식만 여기서 낸다. 선택·탐색 정책은 analysis의 [LanguageTraversal]에 있다.
 */
internal object TraversalCommand {
    private val VALUE_OPTIONS = setOf(
        "--graph-file", "--symbol", "--roots-from", "--project", "--depth", "--dispatch", "--generated-at",
        "--revision", "--snapshot-max-mib", "--max-reached", "--format",
    )
    private val REPEATABLE = setOf("--symbol")
    private val timestampFormat = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun run(arguments: List<String>, direction: TraversalDirection, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(help(direction)); return 0 }
        val parsed = try { parse(arguments) } catch (invalid: TraversalUsageException) { return usage(error, invalid.text) }
        val values = parsed.first
        val format = values["--format"]?.single()
        if (format != null && format != "language-traversal") return usage(error, "${command(direction)} supports only --format language-traversal")
        val options = options(values, parsed.second, error) ?: return 64
        return execute(options, direction, output, error)
    }

    /** 검증을 마친 입력이다. */
    private data class Options(
        val graphFile: String, val roots: List<String>, val project: String, val depth: Int, val dispatch: TraversalDispatch,
        val generatedAt: String, val revision: String?, val maximumBytes: Int, val maximumMiB: Int, val maxReached: Int,
    )

    /** 사용 오류 문구를 파서 밖으로 전달한다. 입력 값을 문구에 넣지 않는다. */
    private class TraversalUsageException(val text: String) : Exception(text)

    private fun parse(arguments: List<String>): Pair<Map<String, List<String>>, List<String>> {
        val values = mutableMapOf<String, MutableList<String>>()
        val positional = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index++]
            if (!argument.startsWith('-')) { positional += argument; continue }
            if (argument !in VALUE_OPTIONS) throw TraversalUsageException(unsupported(argument))
            val value = arguments.getOrNull(index++)?.takeUnless { it.startsWith('-') }
                ?: throw TraversalUsageException("missing value for $argument")
            values.getOrPut(argument) { mutableListOf() } += value
        }
        if (values.any { (key, list) -> key !in REPEATABLE && list.size != 1 }) throw TraversalUsageException("duplicate traversal option")
        return values to positional
    }

    /** 기존 impact 전용 옵션은 새 형식에서 의미가 없으므로 조용히 무시하지 않고 거부한다. */
    private fun unsupported(option: String): String =
        if (option in IMPACT_ONLY) "$option is not supported with --format language-traversal" else "unknown traversal option: $option"

    private fun options(values: Map<String, List<String>>, positional: List<String>, error: PrintStream): Options? {
        val graphFile = values["--graph-file"]?.single() ?: return null.also { usage(error, "provide exactly one --graph-file") }
        val project = projectPath(values["--project"]?.single(), error) ?: return null
        val limit = SnapshotFiles.limit(values["--snapshot-max-mib"].orEmpty())
            ?: return null.also { usage(error, "--snapshot-max-mib must be one integer from 1 to 128") }
        val depth = values["--depth"]?.single()?.let { it.toIntOrNull() ?: 0 } ?: LanguageTraversal.MAX_DEPTH
        val maxReached = values["--max-reached"]?.single()?.let { it.toIntOrNull() ?: 0 } ?: LanguageTraversal.MAX_REACHED
        if (depth !in 1..LanguageTraversal.MAX_DEPTH || maxReached !in 1..LanguageTraversal.MAX_REACHED) {
            return null.also { usage(error, "depth must be 1..128 and max-reached must be 1..100000") }
        }
        val dispatch = values["--dispatch"]?.single()?.let { label -> TraversalDispatch.entries.firstOrNull { it.label == label } }
            ?: if ("--dispatch" in values) return null.also { usage(error, "dispatch must be direct, bound, candidates or all") } else TraversalDispatch.CANDIDATES
        val generatedAt = timestamp(values["--generated-at"]?.single()) ?: return null.also { usage(error, "--generated-at must be an ISO-8601 instant") }
        val revision = values["--revision"]?.single()
        if (revision != null && !LanguageTraversalCodec.isExchangeText(revision)) {
            return null.also { usage(error, "--revision must be non-empty without control characters (C0, DEL, C1, U+2028, U+2029); isthmus rejects such revisions") }
        }
        val roots = rootRequests(positional + values["--symbol"].orEmpty(), values["--roots-from"]?.single(), error) ?: return null
        return Options(graphFile, roots, project, depth, dispatch, generatedAt, revision, limit.maximumBytes, limit.maximumMiB, maxReached)
    }

    /** isthmus는 모든 문서의 project가 같은 realpath 문자열이어야 조인한다. routes와 같은 규칙으로 만든다. */
    private fun projectPath(value: String?, error: PrintStream): String? {
        if (value == null) return null.also { usage(error, "missing required --project path (the same root passed to routes)") }
        return try {
            val path = Path.of(value)
            if (!Files.isDirectory(path)) return null.also { usage(error, "project root does not exist") }
            path.toRealPath().toString().replace('\\', '/')
        } catch (_: InvalidPathException) {
            null.also { usage(error, "invalid path") }
        }
    }

    private fun timestamp(value: String?): String? = try {
        timestampFormat.format(value?.let(Instant::parse) ?: Instant.now())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun rootRequests(direct: List<String>, file: String?, error: PrintStream): List<String>? {
        val fromFile = try {
            file?.let { LanguageTraversalCodec.parseRoots(SnapshotFiles.readText(it, 16 * 1024 * 1024)) }.orEmpty()
        } catch (_: Exception) {
            return null.also { usage(error, "--roots-from must be a UTF-8 JSON string array or bridge-facts document (16 MiB max)") }
        }
        val roots = (direct + fromFile).distinct()
        if (roots.isEmpty()) return null.also { usage(error, "provide root symbols, --symbol or --roots-from") }
        if (!roots.all(LanguageTraversalCodec::isExchangeText)) {
            return null.also { usage(error, "invalid symbol: roots must be non-empty without control characters (C0, DEL, C1, U+2028, U+2029); pass the symbol.usr from routes or bridge facts") }
        }
        if (roots.size > LanguageTraversal.MAX_ROOTS) return null.also { usage(error, "at most 10000 roots are supported") }
        return roots
    }

    private fun execute(options: Options, direction: TraversalDirection, output: PrintStream, error: PrintStream): Int {
        val snapshot = try {
            SnapshotFiles.read(options.graphFile, options.maximumBytes)
        } catch (_: Exception) {
            error.println("error: unable to read the traversal snapshot; use a valid UTF-8 snapshot (${options.maximumMiB} MiB max)")
            return 2
        }
        if (options.revision != null && snapshot.revision != null && snapshot.revision != options.revision) {
            error.println("error: --revision does not match the snapshot's revision label; pass the captured commit or recapture the snapshot")
            return 2
        }
        val traversal = LanguageTraversal.traverse(snapshot.graph, options.roots, direction, options.dispatch, options.depth,
            options.maxReached, snapshot.enclosuresCaptured, snapshot.callbackFactsCaptured)
        val limitations = traversal.limitations + snapshot.limitations +
            "saved-graph: traversal uses captured inputs; revision and scope labels do not prove build freshness"
        val revision = options.revision ?: snapshot.revision ?: GitRevision.cleanHead(Path.of(options.project))
        val metadata = LanguageTraversalMetadata(options.generatedAt, options.project, revision,
            LanguageTraversalCodec.graphRevision(snapshot.graph, snapshot.enclosuresCaptured, snapshot.callbackFactsCaptured))
        output.print(LanguageTraversalCodec.render(traversal.copy(limitations = limitations), metadata))
        return if (traversal.rootNotFound) 64 else 0
    }

    private fun command(direction: TraversalDirection): String = if (direction == TraversalDirection.DEPENDENTS) "impact" else "reach"

    private fun usage(error: PrintStream, text: String): Int { error.println("error: $text"); return 64 }

    private val IMPACT_ONLY = setOf(
        "--base-graph", "--file", "--files-from", "--limit", "--offset", "--all", "--module", "--affected-file", "--filter-file",
        "--kind", "--test-status", "--relation", "--path-status", "--sort", "--visit-limit", "--path-limit", "--base-revision",
        "--summary-limit",
    )

    private fun help(direction: TraversalDirection): String = """
        Emit a language-traversal v1 document (isthmus docs/LANGUAGE-TRAVERSAL.md) for isthmus trace.

        Usage:
          kartograph ${if (direction == TraversalDirection.DEPENDENTS) "impact --format language-traversal" else "reach"} <usr> [<usr>...] --graph-file <snapshot.json> --project <directory> [options]

        Direction: ${if (direction == TraversalDirection.DEPENDENTS) "dependents (callers and referrers of each root)" else "dependencies (what each root calls or references)"}.

        Options:
          --symbol <usr>           additional root, repeatable; exact JVM USRs match route/bridge fact symbol.usr
          --roots-from <file>      JSON string array, or a bridge-facts document whose facts' symbol.usr become roots
          --project <directory>    project root written as "project"; pass the same root given to routes/schema
          --dispatch <mode>        direct, bound, candidates (default) or all
          --depth <n>              listed depth, 1..128 (default 128)
          --max-reached <n>        reached cap, 1..100000 (default 100000)
          --generated-at <instant> fixed ISO-8601 generatedAt for byte-stable output
          --revision <rev>         source revision to record; must equal the snapshot's revision label when it has one.
                                   Default: the snapshot's label, else the git HEAD when the project directory has no
                                   uncommitted or untracked changes, else omitted
          --snapshot-max-mib <n>   snapshot read maximum in MiB, 1..128 (default 64)

        Dispatch modes (each includes the previous):
          direct      compiler-resolved calls, references, field accesses and lexical containment of lambda bodies
          bound       + dispatch whose project receiver type resolves to a single project implementation, runtime models
          candidates  + class-hierarchy override candidates (evidence "candidate")
          all         + FunctionN/SAM invoke fan-out into every lambda or anonymous-class body (evidence "candidate")
        Every reached declaration carries roots (all roots that reach it), a via witness, per-root lower-bound evidence
        and unresolvedCalls. Roots reached from other roots are listed without their own index.
        Unknown roots stay listed without a symbol with root-not-found and exit 64. Recapture snapshots with this version
        for lexical containment facts; older snapshots fall back to following lambda candidates.
    """.trimIndent() + "\n"
}
