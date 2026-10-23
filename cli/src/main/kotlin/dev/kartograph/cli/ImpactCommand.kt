package dev.kartograph.cli

import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactPathStatus
import dev.kartograph.analysis.ImpactRelation
import dev.kartograph.analysis.ImpactSort
import dev.kartograph.analysis.ImpactTestStatus
import dev.kartograph.analysis.TraversalDirection
import dev.kartograph.core.NodeKind
import dev.kartograph.export.ImpactReportCodec
import dev.kartograph.export.McpJsonCodec
import java.io.PrintStream
import java.nio.file.Path

/** snapshot 입력만 조립하고 영향 선택·탐색·출력 정책은 공통 모듈에 위임한다. */
internal object ImpactCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(HELP); return 0 }
        if (arguments.count { it == "--format" } > 1) return usage(error, "duplicate impact option")
        if ("--enum-expansion" in arguments) {
            if (arguments.count { it == "--enum-expansion" } != 1 ||
                arguments.any { it in setOf("--source-edits", "--source-limit", "--source-offset") } ||
                arguments.windowed(2).any { it == listOf("--format", "language-traversal") })
                return usage(error, "enum expansion cannot be combined with source edits or language traversal")
            return EnumImpactCommand.run(arguments, output, error)
        }
        // 새 교환 형식은 별도 생산자가 맡는다. 기본 kartograph-impact v1 경로와 출력은 그대로 둔다.
        val formatIndex = arguments.indexOf("--format")
        val format = if (formatIndex >= 0) arguments.getOrNull(formatIndex + 1) else null
        val sourceOptions = setOf("--source-edits", "--source-limit", "--source-offset")
        if (format == "language-traversal" && arguments.any { it in sourceOptions })
            return usage(error, "source edits require json format")
        if (format == "language-traversal") return TraversalCommand.run(arguments, TraversalDirection.DEPENDENTS, output, error)
        if (formatIndex >= 0 && format != "json") return usage(error, "invalid impact format: use json or language-traversal")
        if (formatIndex >= 0) return run(arguments.filterIndexed { index, _ -> index != formatIndex && index != formatIndex + 1 }, output, error)
        val values = mutableMapOf<String, MutableList<String>>()
        val symbols = mutableListOf<String>()
        var all = false
        var sourceEdits = false
        var workingTree = false
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index++]
            if (!option.startsWith('-')) { symbols += option; continue }
            if (option == "--working-tree") {
                if (workingTree) return usage(error, "duplicate --working-tree")
                workingTree = true
                continue
            }
            if (option == "--source-edits") {
                if (sourceEdits) return usage(error, "duplicate --source-edits")
                sourceEdits = true
                continue
            }
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
        val gitMode = "--git-base" in values
        if (gitMode && ("--base-graph" !in values || "--project" !in values || sourceEdits))
            return usage(error, "Git impact requires --base-graph and --project, and does not support --source-edits")
        if (gitMode && (symbols.isNotEmpty() || values.keys.any { it in setOf("--symbol", "--file", "--files-from") }))
            return usage(error, "Git impact selects changed paths; use ordinary impact for explicit symbols or files")
        if (!gitMode && (workingTree || values.keys.any { it in GIT_OPTIONS }))
            return usage(error, "Git input and freshness options require --git-base")
        if (workingTree && "--git-head" in values) return usage(error, "--working-tree cannot be combined with --git-head")
        if ("--base-input" in values && "--base-project" !in values) return usage(error, "--base-input requires --base-project")
        if (!sourceEdits && ("--source-limit" in values || "--source-offset" in values))
            return usage(error, "source pagination requires --source-edits")
        if (sourceEdits && (all || values.keys.any { it in setOf("--sort", "--module", "--affected-file", "--filter-file",
                "--kind", "--test-status", "--relation", "--path-status") }))
            return usage(error, "source edits do not support all, sort or affected-result filters")
        val repeatable = setOf("--symbol", "--file", "--module", "--affected-file", "--filter-file", "--kind", "--input", "--base-input")
        if (values.any { (key, value) -> key !in repeatable && value.size != 1 }) return usage(error, "duplicate impact option")
        val sourceLimit = values["--source-limit"]?.single()?.toIntOrNull() ?: if ("--source-limit" in values) 0 else 100
        val sourceOffset = values["--source-offset"]?.single()?.toIntOrNull() ?: if ("--source-offset" in values) -1 else 0
        if (sourceLimit !in 1..1000 || sourceOffset !in 0..200000)
            return usage(error, "source-limit must be 1..1000 and source-offset must be 0..200000")
        val snapshotLimit = SnapshotFiles.limit(values["--snapshot-max-mib"].orEmpty())
            ?: return usage(error, "--snapshot-max-mib must be one integer from 1 to 1024")
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
        val summaryLimit = values["--summary-limit"]?.single()?.toIntOrNull() ?: if ("--summary-limit" in values) 0 else null
        if (depth !in 1..1000 || (!all && limit !in 1..100_000) || offset < 0 || visitLimit !in 1..5_000_000 || pathLimit != null && pathLimit !in 1..5_000_000) {
            return usage(error, "depth must be 1..1000, limit must be 1..100000, offset must be non-negative, and budgets must be positive")
        }
        if (summaryLimit != null && summaryLimit !in 1..100_000) return usage(error, "summary-limit must be 1..100000")
        if (!gitMode && symbols.isEmpty() && "--file" !in values && "--files-from" !in values) return usage(error, "provide symbols or changed files")
        val sort = values["--sort"]?.single()?.let { parseSort(it) }
            ?: if ("--sort" in values) return usage(error, "invalid impact sort") else ImpactSort.REVIEW
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
            var files = values["--file"].orEmpty() + values["--files-from"]?.single()?.let {
                ImpactReportCodec.parseFiles(SnapshotFiles.readText(it, 1024 * 1024))
            }.orEmpty()
            val affectedFiles = values["--affected-file"].orEmpty() + values["--filter-file"].orEmpty()
            if ((files + affectedFiles).any { !portable(it) }) return usage(error, "impact files must be portable project-relative paths")
            if (values["--module"].orEmpty().any { it.isBlank() || it.any(Char::isISOControl) }) return usage(error, "impact modules must be non-empty")
            val currentLoaded = if (sourceEdits || gitMode) SnapshotFiles.readWithIdentity(values.getValue("--graph-file").single(), snapshotLimit.maximumBytes) else null
            val baseLoaded = if (sourceEdits || gitMode) values["--base-graph"]?.single()?.let { SnapshotFiles.readWithIdentity(it, snapshotLimit.maximumBytes) } else null
            val current = currentLoaded?.snapshot ?: SnapshotFiles.read(values.getValue("--graph-file").single(), snapshotLimit.maximumBytes)
            val base = if (sourceEdits || gitMode) baseLoaded?.snapshot else values["--base-graph"]?.single()?.let { SnapshotFiles.read(it, snapshotLimit.maximumBytes) }
            try { SavedSnapshotOperations.compatible(current, base) } catch (invalid: IllegalArgumentException) {
                error.println("error: ${invalid.message}")
                return 2
            }
            if (("--revision" in values && current.revision != values.getValue("--revision").single()) ||
                ("--base-revision" in values && (base == null || base.revision != values.getValue("--base-revision").single()))) {
                error.println("error: snapshot revision does not match the requested commit; rebuild and recapture")
                return 2
            }
            val project = values["--project"]?.single()?.let(Path::of)
            val change = if (gitMode) GitImpactContext.inspect(requireNotNull(project), values.getValue("--git-base").single(),
                values["--git-head"]?.single(), workingTree).also {
                GitImpactContext.validate(it, current, base)
                files = (files + it.files).distinct().sorted()
            } else null
            val effectiveLimit = if (all) Int.MAX_VALUE else limit
            val effectivePathLimit = pathLimit ?: if (all) 500_000 else maxOf(100_000, minOf(500_000, limit * 10))
            val report = if (sourceEdits) SavedSnapshotOperations.sourceImpact(
                current = requireNotNull(currentLoaded), base = baseLoaded, symbols = symbols, files = files,
                depth = depth, limit = effectiveLimit, visitLimit = visitLimit, pathLimit = effectivePathLimit, offset = offset,
                summaryLimit = summaryLimit, request = dev.kartograph.analysis.SourceUsePageRequest(sourceLimit, sourceOffset),
            ) else SavedSnapshotOperations.impact(
                current = current,
                symbols = symbols,
                files = files,
                base = base,
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
                summaryLimit = summaryLimit,
            )
            if (change == null) output.print(report.json) else {
                val freshness = GitImpactContext.freshness(current, requireNotNull(base), requireNotNull(project),
                    values["--base-project"]?.single()?.let(Path::of), FreshnessCommand.inputBindings(values["--input"].orEmpty()),
                    FreshnessCommand.inputBindings(values["--base-input"].orEmpty()), workingTree)
                val identities = mapOf("current" to mapOf("sha256" to requireNotNull(currentLoaded).sha256,
                    "revision" to current.revision, "scope" to current.scope, "toolVersion" to current.toolVersion),
                    "base" to mapOf("sha256" to requireNotNull(baseLoaded).sha256,
                        "revision" to base.revision, "scope" to base.scope, "toolVersion" to base.toolVersion))
                output.println(McpJsonCodec.render(GitImpactContext.document(McpJsonCodec.parse(report.json), change, identities, freshness), 16 * 1024 * 1024))
            }
            report.status
        } catch (_: dev.kartograph.export.SourceUseDocumentSizeException) {
            error.println("error: source report exceeds 16 MiB; reduce graph/source pages or use a narrower snapshot")
            2
        } catch (_: Exception) {
            error.println("error: unable to read impact inputs; use valid UTF-8 snapshots (${snapshotLimit.maximumMiB} MiB max), a JSON file list (1 MiB max), and matching scoped Git revision labels; Git selection requires local history and portable paths")
            2
        }
    }

    internal fun parseSort(value: String): ImpactSort? = when (value) {
        "review" -> ImpactSort.REVIEW
        "usr" -> ImpactSort.USR
        "module" -> ImpactSort.MODULE
        "file" -> ImpactSort.FILE
        "test", "test-status" -> ImpactSort.TEST_STATUS
        "relation" -> ImpactSort.RELATION
        "path", "path-depth" -> ImpactSort.PATH_DEPTH
        "path-status" -> ImpactSort.PATH_STATUS
        else -> null
    }

    internal fun parseTestStatus(value: String): ImpactTestStatus? = ImpactTestStatus.entries.firstOrNull { it.name.lowercase() == value }
    internal fun parseRelation(value: String): ImpactRelation? = ImpactRelation.entries.firstOrNull { it.name.lowercase() == value }
    internal fun parsePathStatus(value: String): ImpactPathStatus? = ImpactPathStatus.entries.firstOrNull { it.name.lowercase() == value }

    private fun String.lowerCamel(): String = lowercase().split('_').let { words ->
        words.first() + words.drop(1).joinToString("") { it.replaceFirstChar { character -> character.titlecase() } }
    }

    internal fun portable(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(path) && '\\' !in path && path.split('/').none { it == ".." || it.isEmpty() } && !path.any(Char::isISOControl)

    private fun usage(error: PrintStream, text: String): Int { error.println("error: $text"); return 64 }
    private val GIT_OPTIONS = setOf("--git-base", "--git-head", "--project", "--base-project", "--input", "--base-input")
    private val OPTIONS = GIT_OPTIONS + setOf(
        "--graph-file", "--base-graph", "--symbol", "--file", "--files-from", "--depth", "--limit", "--offset",
        "--module", "--affected-file", "--filter-file", "--kind", "--test-status", "--relation", "--path-status", "--sort",
        "--visit-limit", "--path-limit", "--revision", "--base-revision",
        "--snapshot-max-mib", "--summary-limit", "--source-limit", "--source-offset",
    )
    private val HELP = """
        Inspect potential change impact using captured graphs.

        Usage:
          kartograph impact <symbol> [<symbol>...] --graph-file <snapshot.json> [options]
          kartograph impact --file <project-relative-path> --graph-file <current.json> --base-graph <base.json>

        Options:
          --enum-expansion         compare enum additions and current source decision candidates (json|text)
          --source-edits           separate direct compiler-selection observations (JSON only)
          --source-limit <n>       per-revision source page size, 1..1000 (default 100)
          --source-offset <n>      per-revision source offset, 0..200000 (default 0)
          --symbol <symbol>        additional symbol or exact JVM USR, repeatable
          --file <path>            changed source or retention file, repeatable
          --files-from <file>      UTF-8 JSON array of project-relative changed files (may be empty)
          --base-graph <file>      include old declarations and paths, including deletions
          --git-base <ref>         select actual Git changes; emits kartograph-change-impact v1 wrapper
          --git-head <ref>         compare committed endpoints (default HEAD)
          --working-tree          compare base to staged/unstaged/untracked files; excludes --git-head
          --project <directory>   Git repository root and current freshness inputs (Git mode only)
          --base-project <dir>    before checkout for separate historical freshness verification
          --input <slot=path>      current external freshness binding, repeatable
          --base-input <slot=path> before external freshness binding, repeatable
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
          --sort <field>           review (default), usr, module, file, test, relation, path or path-status
          --visit-limit <n>        reverse traversal node budget (default 100000)
          --path-limit <n>         path materialization edge budget (default is limit-derived)
          --summary-limit <n>      buckets retained per summary axis, 1..100000 (default unbounded)
          --revision <hash>        require the current snapshot to carry this commit label
          --base-revision <hash>   require the base snapshot to carry this commit label
          --snapshot-max-mib <n>  current/base snapshot read maximum in MiB, 1..1024 (default 64)
          --format <format>        json (default, kartograph-impact v1) or language-traversal (isthmus trace input;
                                   see `kartograph impact --format language-traversal --help`)

        Capture matching project/variant inputs with `snapshot --include-paths` after building.
        Paths retain edge kinds, origins and the revision they came from. Summary counts are computed before
        filters and page limits; use navigation.hasNext with --offset for stable pages. Unknown selections return 64;
        invalid snapshots return 2. Partial traversal and path omissions are explicit in the JSON. Baselines never hide impact.
        Review order places direct and transitive paths before structural and unknown paths, then uses path depth and USR.
        This changes presentation only; structural and unknown candidates remain part of the complete observed set.
        Source edits reject --all, explicit --sort and affected-result filters. Graph budgets remain graph-only.
        Saved source observations are historical; captured coverage is unknown or partial, never complete.
        Candidates do not prove behavior changes, safe deletion or permission to skip tests.
    """.trimIndent() + "\n"
}
