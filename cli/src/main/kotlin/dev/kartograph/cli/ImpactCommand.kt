package dev.kartograph.cli

import dev.kartograph.analysis.ChangeImpact
import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactInput
import dev.kartograph.analysis.ImpactPathStatus
import dev.kartograph.analysis.ImpactRelation
import dev.kartograph.analysis.ImpactSort
import dev.kartograph.analysis.ImpactTestStatus
import dev.kartograph.core.NodeKind
import dev.kartograph.export.ImpactReportCodec
import java.io.PrintStream

/** snapshot 입력만 조립하고 영향 선택·탐색·출력 정책은 공통 모듈에 위임한다. */
internal object ImpactCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(HELP); return 0 }
        val values = mutableMapOf<String, MutableList<String>>()
        val symbols = mutableListOf<String>()
        var all = false
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index++]
            if (!option.startsWith('-')) { symbols += option; continue }
            if (option == "--all") {
                if (all) return usage(error, "duplicate impact option")
                all = true
                continue
            }
            if (option !in OPTIONS) return usage(error, "unknown impact option")
            val value = arguments.getOrNull(index++)?.takeUnless { it.startsWith('-') }
                ?: return usage(error, "missing impact option value")
            values.getOrPut(option) { mutableListOf() } += value
        }
        if (values["--graph-file"]?.size != 1) return usage(error, "provide exactly one --graph-file")
        val repeatable = setOf("--symbol", "--file", "--module", "--affected-file", "--filter-file", "--kind")
        if (values.any { (key, value) -> key !in repeatable && value.size != 1 }) return usage(error, "duplicate impact option")
        if (all && "--limit" in values) return usage(error, "--all cannot be combined with --limit")
        if (listOf("--revision", "--base-revision").any { key -> values[key]?.single()?.let { !Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(it) } == true }) return usage(error, "revision must be a full commit hash")
        if ("--base-revision" in values && "--base-graph" !in values) return usage(error, "base-revision requires base-graph")
        symbols += values["--symbol"].orEmpty()
        if (symbols.any { it.isBlank() || it.startsWith('/') || it.any(Char::isISOControl) }) return usage(error, "invalid symbol")
        val depth = values["--depth"]?.single()?.toIntOrNull() ?: if ("--depth" in values) 0 else 100
        val limit = values["--limit"]?.single()?.toIntOrNull() ?: if ("--limit" in values) 0 else 500
        val offset = values["--offset"]?.single()?.toIntOrNull() ?: if ("--offset" in values) -1 else 0
        val visitLimit = values["--visit-limit"]?.single()?.toIntOrNull() ?: if ("--visit-limit" in values) 0 else 100_000
        val pathLimit = values["--path-limit"]?.single()?.toIntOrNull() ?: if ("--path-limit" in values) 0 else null
        if (depth !in 1..1000 || (!all && limit !in 1..100_000) || offset < 0 || visitLimit !in 1..5_000_000 || pathLimit != null && pathLimit !in 1..5_000_000) {
            return usage(error, "depth must be 1..1000, limit must be 1..100000, offset must be non-negative, and budgets must be positive")
        }
        if (symbols.isEmpty() && "--file" !in values && "--files-from" !in values) return usage(error, "provide symbols or changed files")
        val sort = values["--sort"]?.single()?.let { parseSort(it) }
            ?: if ("--sort" in values) return usage(error, "invalid impact sort") else ImpactSort.USR
        val testStatus = values["--test-status"]?.single()?.let { parseTestStatus(it) }
            ?: if ("--test-status" in values) return usage(error, "invalid impact test status") else null
        val relation = values["--relation"]?.single()?.let { parseRelation(it) }
            ?: if ("--relation" in values) return usage(error, "invalid impact relation") else null
        val pathStatus = values["--path-status"]?.single()?.let { parsePathStatus(it) }
            ?: if ("--path-status" in values) return usage(error, "invalid impact path status") else null
        val kinds = mutableSetOf<NodeKind>()
        for (kind in values["--kind"].orEmpty()) {
            kinds += NodeKind.entries.firstOrNull { it.name.lowerCamel() == kind }
                ?: return usage(error, "invalid impact kind")
        }
        return try {
            val files = values["--file"].orEmpty() + values["--files-from"]?.single()?.let {
                ImpactReportCodec.parseFiles(SnapshotFiles.readText(it, 1024 * 1024))
            }.orEmpty()
            val affectedFiles = values["--affected-file"].orEmpty() + values["--filter-file"].orEmpty()
            if ((files + affectedFiles).any { !portable(it) }) return usage(error, "impact files must be portable project-relative paths")
            if (values["--module"].orEmpty().any { it.isBlank() || it.any(Char::isISOControl) }) return usage(error, "impact modules must be non-empty")
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
            val effectiveLimit = if (all) Int.MAX_VALUE else limit
            val effectivePathLimit = pathLimit ?: if (all) 500_000 else maxOf(100_000, minOf(500_000, limit * 10))
            val report = ChangeImpact.analyze(
                current = ImpactInput(current.graph, current.retention, current.limitations),
                symbols = symbols,
                files = files,
                base = base?.let { ImpactInput(it.graph, it.retention, it.limitations) },
                depth = depth,
                limit = effectiveLimit,
                visitLimit = visitLimit,
                pathLimit = effectivePathLimit,
                offset = offset,
                filters = ImpactFilter(
                    modules = values["--module"].orEmpty().toSet(),
                    affectedFiles = affectedFiles.toSet(),
                    kinds = kinds,
                    testStatus = testStatus,
                    relation = relation,
                    pathStatus = pathStatus,
                ),
                sort = sort,
            )
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

    private fun parseSort(value: String): ImpactSort? = when (value) {
        "usr" -> ImpactSort.USR
        "module" -> ImpactSort.MODULE
        "file" -> ImpactSort.FILE
        "test", "test-status" -> ImpactSort.TEST_STATUS
        "relation" -> ImpactSort.RELATION
        "path", "path-depth" -> ImpactSort.PATH_DEPTH
        "path-status" -> ImpactSort.PATH_STATUS
        else -> null
    }

    private fun parseTestStatus(value: String): ImpactTestStatus? = ImpactTestStatus.entries.firstOrNull { it.name.lowercase() == value }
    private fun parseRelation(value: String): ImpactRelation? = ImpactRelation.entries.firstOrNull { it.name.lowercase() == value }
    private fun parsePathStatus(value: String): ImpactPathStatus? = ImpactPathStatus.entries.firstOrNull { it.name.lowercase() == value }

    private fun String.lowerCamel(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
    }

    private fun portable(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(path) && '\\' !in path && path.split('/').none { it == ".." || it.isEmpty() } && !path.any(Char::isISOControl)

    private fun usage(error: PrintStream, text: String): Int { error.println("error: $text"); return 64 }
    private val OPTIONS = setOf(
        "--graph-file", "--base-graph", "--symbol", "--file", "--files-from", "--depth", "--limit", "--offset",
        "--module", "--affected-file", "--filter-file", "--kind", "--test-status", "--relation", "--path-status", "--sort",
        "--visit-limit", "--path-limit", "--revision", "--base-revision",
    )
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
          --offset <n>             stable result page offset (default 0)
          --all                    return every observed matching candidate; truncation flags still apply
          --module <name>          filter affected module (repeatable, OR within this field)
          --affected-file <path>  filter affected source path, distinct from changed --file (repeatable)
          --kind <kind>            filter declaration kind (repeatable)
          --test-status <status>   filter test, production or unknown source-root status
          --relation <relation>    filter direct, structural, transitive or unknown path relation
          --path-status <status>   filter complete, partial or unavailable path evidence
          --sort <field>           usr, module, file, test, relation, path or path-status
          --visit-limit <n>        reverse traversal node budget (default 100000)
          --path-limit <n>         path materialization edge budget (default is limit-derived)
          --revision <hash>        require the current snapshot to carry this commit label
          --base-revision <hash>   require the base snapshot to carry this commit label

        Capture matching project/variant inputs with `snapshot --include-paths` after building.
        Paths retain edge kinds, origins and the revision they came from. Summary counts are computed before
        filters and page limits; use navigation.hasNext with --offset for stable pages. Unknown selections return 64;
        invalid snapshots return 2. Partial traversal and path omissions are explicit in the JSON. Baselines never hide impact.
        Candidates do not prove behavior changes, safe deletion or permission to skip tests.
    """.trimIndent() + "\n"
}
