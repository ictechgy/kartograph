package dev.kartograph.cli

import dev.kartograph.export.ExternalInputBindingsCodec
import dev.kartograph.export.QuerySnapshotCodec
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 고정 snapshot으로만 동작하는 legacy MCP stdio 진입점이다. */
internal object McpCommand {
    fun run(arguments: List<String>, input: InputStream, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--help") || arguments == listOf("-h")) { output.print(HELP); return 0 }
        val allowed = setOf("--graph-file", "--base-graph", "--project", "--scope", "--input-bindings", "--input", "--snapshot-max-mib")
        if (arguments.size % 2 != 0 || arguments.chunked(2).any { it[0] !in allowed || it[1].isBlank() || it[1].startsWith("--") })
            return usage(error)
        val options = arguments.chunked(2).groupBy({ it[0] }, { it[1] })
        if (options["--graph-file"]?.size != 1 || options.any { it.key != "--input" && it.value.size != 1 }) return usage(error)
        if (options["--scope"]?.single()?.let { it.length > 200 || !Regex("[A-Za-z0-9_.:-]+").matches(it) } == true) return usage(error)
        val snapshotLimit = SnapshotFiles.limit(options["--snapshot-max-mib"].orEmpty()) ?: return usage(error)
        return try {
            val project = options["--project"]?.single()?.let { Path.of(it).toAbsolutePath().normalize().also { path -> require(Files.isDirectory(path)) } }
            val explicit = FreshnessCommand.inputBindings(options["--input"].orEmpty())
            val local = options["--input-bindings"]?.single()?.let {
                ExternalInputBindingsCodec.parse(SnapshotFiles.readText(it, 1024 * 1024)).mapValues { binding -> Path.of(binding.value) }
            }.orEmpty()
            require(explicit.keys.none(local::containsKey))
            // 같은 binding codec 검증을 명시적 슬롯에도 적용한다.
            ExternalInputBindingsCodec.render(explicit.mapValues { it.value.toString() })
            val external = (local + explicit).mapValues { it.value.toAbsolutePath().normalize() }
            val identities = linkedMapOf<String, Any?>()
            fun load(role: String, path: String): dev.kartograph.export.QuerySnapshot {
                val text = SnapshotFiles.readText(path, snapshotLimit.maximumBytes)
                val snapshot = QuerySnapshotCodec.parse(text, snapshotLimit.maximumBytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                identities[role] = mapOf("sha256" to hash, "format" to QuerySnapshotCodec.FORMAT,
                    "toolVersion" to snapshot.toolVersion, "scope" to snapshot.scope, "revision" to snapshot.revision)
                return snapshot
            }
            val current = load("current", options.getValue("--graph-file").single())
            val base = options["--base-graph"]?.single()?.let { load("base", it) }
            SavedSnapshotOperations.compatible(current, base)
            McpServer(McpTools(current, base, identities.toMap(), project, options["--scope"]?.single(), external)::call)
                .run(input, output, error)
        } catch (_: Exception) {
            error.println("error: MCP startup failed; check fixed UTF-8 snapshots (${snapshotLimit.maximumMiB} MiB), compatible base scope/version, project and local bindings (1 MiB)")
            2
        }
    }

    private fun usage(error: PrintStream): Int {
        error.println("error: mcp requires one --graph-file and valid startup options; use mcp --help")
        return 64
    }
    private val HELP = """
        Serve legacy MCP 2025-11-25 over stdio.
        Usage: kartograph mcp --graph-file <snapshot.json> [--base-graph <snapshot.json>]
          [--project <directory>] [--scope <project:variant>] [--input-bindings <local.json>]
          [--input <external/slot=path>]... [--snapshot-max-mib <1..128>]
        Snapshots are loaded once at the selected bound (default 64 MiB, maximum 128 MiB);
        restart to use a new generation.
        No project default. freshness without --project reports unverified.
        Tools: query_symbol, impact, freshness. Requests cannot select filesystem inputs or run builds.
        UTF-8 newline frames: 256 KiB max; responses: 1 MiB max. Narrow selectors or page if oversized.
        Tool text content: 16 KiB max. Check response.effective for adapted page/path limits.
        Missing source-style selectors may include suggestions with exact USRs; overloads are not guessed.
        One tool call at a time; concurrent calls are rejected. Cancellation suppresses the response;
        work may finish internally. EOF exits promptly and abandons pending responses.
        No HTTP, tasks, progress, or MCP 2026-07-28 discovery support.
    """.trimIndent() + "\n"
}
