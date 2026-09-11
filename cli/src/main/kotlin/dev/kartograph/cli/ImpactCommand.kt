package dev.kartograph.cli

import dev.kartograph.analysis.ChangeImpact
import dev.kartograph.analysis.ImpactInput
import dev.kartograph.export.ImpactReportCodec
import java.io.PrintStream

/** snapshot 입력만 조립하고 영향 선택·탐색·출력 정책은 공통 모듈에 위임한다. */
internal object ImpactCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(HELP); return 0 }
        val values = mutableMapOf<String, MutableList<String>>()
        val symbols = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index++]
            if (!option.startsWith('-')) { symbols += option; continue }
            if (option !in OPTIONS) return usage(error, "unknown impact option")
            val value = arguments.getOrNull(index++)?.takeUnless { it.startsWith('-') }
                ?: return usage(error, "missing impact option value")
            values.getOrPut(option) { mutableListOf() } += value
        }
        if (values["--graph-file"]?.size != 1) return usage(error, "provide exactly one --graph-file")
        if (values.any { (key, value) -> key !in setOf("--symbol", "--file") && value.size != 1 }) return usage(error, "duplicate impact option")
        if (listOf("--revision", "--base-revision").any { key -> values[key]?.single()?.let { !Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(it) } == true }) return usage(error, "revision must be a full commit hash")
        if ("--base-revision" in values && "--base-graph" !in values) return usage(error, "base-revision requires base-graph")
        symbols += values["--symbol"].orEmpty()
        if (symbols.any { it.isBlank() || it.startsWith('/') || it.any(Char::isISOControl) }) return usage(error, "invalid symbol")
        val depth = values["--depth"]?.single()?.toIntOrNull() ?: if ("--depth" in values) 0 else 100
        val limit = values["--limit"]?.single()?.toIntOrNull() ?: if ("--limit" in values) 0 else 500
        if (depth !in 1..1000 || limit !in 1..100_000) return usage(error, "depth must be 1..1000 and limit must be 1..100000")
        if (symbols.isEmpty() && "--file" !in values && "--files-from" !in values) return usage(error, "provide symbols or changed files")
        return try {
            val files = values["--file"].orEmpty() + values["--files-from"]?.single()?.let {
                ImpactReportCodec.parseFiles(SnapshotFiles.readText(it, 1024 * 1024))
            }.orEmpty()
            if (files.any { !portable(it) }) return usage(error, "changed files must be portable project-relative paths")
            val current = SnapshotFiles.read(values.getValue("--graph-file").single())
            val base = values["--base-graph"]?.single()?.let(SnapshotFiles::read)
            if (base != null && current.toolVersion != base.toolVersion) {
                error.println("error: snapshot analyzer versions differ; recapture both with the same version")
                return 2
            }
            if (base != null && base.scope != current.scope) {
                error.println("error: snapshot scopes differ; capture the same project and variant")
                return 2
            }
            if (("--revision" in values && current.revision != values.getValue("--revision").single()) ||
                ("--base-revision" in values && (base == null || base.revision != values.getValue("--base-revision").single()))) {
                error.println("error: snapshot revision does not match the requested commit; rebuild and recapture")
                return 2
            }
            val report = ChangeImpact.analyze(ImpactInput(current.graph, current.retention, current.limitations), symbols, files,
                base?.let { ImpactInput(it.graph, it.retention, it.limitations) }, depth, limit, pathLimit = maxOf(100_000, minOf(500_000, limit * 10)))
            val limitations = report.limitations + "saved-graph: impact uses captured inputs; revision and scope labels do not prove build freshness" +
                if (current.scope == null) listOf("snapshot-scope: project and variant labels were not provided") else emptyList()
            output.print(ImpactReportCodec.render(report.copy(limitations = limitations.sorted()),
                buildMap { put("current", current); base?.let { put("base", it) } }))
            if (report.unresolved.isEmpty()) 0 else 64
        } catch (_: Exception) {
            error.println("error: unable to read impact inputs; use valid UTF-8 snapshots (64 MiB max) and a JSON file list (1 MiB max)")
            2
        }
    }

    private fun portable(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(path) && '\\' !in path && path.split('/').none { it == ".." || it.isEmpty() } && !path.any(Char::isISOControl)

    private fun usage(error: PrintStream, text: String): Int { error.println("error: $text"); return 64 }
    private val OPTIONS = setOf("--graph-file", "--base-graph", "--symbol", "--file", "--files-from", "--depth", "--limit", "--revision", "--base-revision")
    private val HELP = """
        Inspect potential change impact using captured graphs.

        Usage:
          kartograph impact <symbol> [<symbol>...] --graph-file <snapshot.json> [options]
          kartograph impact --file <project-relative-path> --graph-file <current.json> --base-graph <base.json>

        Options:
          --symbol <symbol>        additional symbol or exact JVM USR, repeatable
          --file <path>            changed source or retention file, repeatable
          --files-from <file>      UTF-8 JSON array of project-relative changed files (may be empty)
          --base-graph <file>      include old declarations and paths, including deletions
          --depth <n>              reverse dependency depth, 1..1000 (default 100)
          --limit <n>              affected result cap, 1..100000 (default 500)
          --revision <hash>        require the current snapshot to carry this commit label
          --base-revision <hash>   require the base snapshot to carry this commit label

        Capture matching project/variant inputs with `snapshot --include-paths` after building.
        Paths retain edge kinds, origins and the revision they came from. Unknown selections return 64;
        invalid snapshots return 2. Partial traversal is explicit in the JSON. Baselines never hide impact.
        Candidates do not prove behavior changes, safe deletion or permission to skip tests.
    """.trimIndent() + "\n"
}
