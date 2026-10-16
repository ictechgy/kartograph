package dev.kartograph.cli

import dev.kartograph.analysis.EnumDecisionImpact
import dev.kartograph.core.EnumConstantIds
import dev.kartograph.core.NodeId
import dev.kartograph.export.EnumImpactReportRenderer
import java.io.PrintStream

/** 완전한 enum 상수 목록과 현재 소스 위치가 있는 분기 증거를 비교한다. */
internal object EnumImpactCommand {
    fun run(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        if (arguments == listOf("--enum-expansion", "--help") || arguments == listOf("--enum-expansion", "-h")) {
            output.print(HELP)
            return 0
        }
        val values = LinkedHashMap<String, MutableList<String>>()
        var seenMode = false
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index++]
            if (option == "--enum-expansion") {
                if (seenMode) return usage(error, "duplicate --enum-expansion")
                seenMode = true
                continue
            }
            if (option !in OPTIONS) return usage(error, "unknown enum expansion option")
            val value = arguments.getOrNull(index++)?.takeUnless { it.startsWith('-') }
                ?: return usage(error, "missing enum expansion option value")
            values.getOrPut(option) { ArrayList() }.add(value)
        }
        if (!seenMode || values["--graph-file"]?.size != 1 || values["--base-graph"]?.size != 1)
            return usage(error, "enum expansion requires exactly one --graph-file and --base-graph")
        if (values.any { (key, list) -> key != "--symbol" && list.size != 1 })
            return usage(error, "duplicate enum expansion option")
        val format = values["--format"]?.single() ?: "json"
        if (format !in setOf("json", "text")) return usage(error, "enum expansion format must be json or text")
        val limit = values["--limit"]?.single()?.toIntOrNull() ?: if ("--limit" in values) 0 else 100
        val offset = values["--offset"]?.single()?.toIntOrNull() ?: if ("--offset" in values) -1 else 0
        if (limit !in 1..1_000 || offset !in 0..200_000)
            return usage(error, "enum expansion limit must be 1..1000 and offset 0..200000")
        val maximum = SnapshotFiles.limit(values["--snapshot-max-mib"].orEmpty())
            ?: return usage(error, "--snapshot-max-mib must be one integer from 1 to 128")
        val owners = try {
            values["--symbol"].orEmpty().map(::NodeId).onEach {
                require(EnumConstantIds.isOwner(it)) { "enum expansion requires exact class: JVM identities" }
            }.toSet()
        } catch (_: IllegalArgumentException) { return usage(error, "enum expansion requires exact class: JVM identities") }
        return try {
            val current = SnapshotFiles.readWithIdentity(values.getValue("--graph-file").single(), maximum.maximumBytes)
            val base = SnapshotFiles.readWithIdentity(values.getValue("--base-graph").single(), maximum.maximumBytes)
            SavedSnapshotOperations.compatible(current.snapshot, base.snapshot)
            val report = EnumDecisionImpact.analyze(base.snapshot.graph, current.snapshot.graph,
                current.snapshot.enumDecisionEvidence, owners, limit, offset)
            val rendered = if (format == "text") EnumImpactReportRenderer.text(report, current.snapshot, base.snapshot)
                else EnumImpactReportRenderer.json(report, current.snapshot, base.snapshot, current.sha256, base.sha256)
            output.print(rendered)
            0
        } catch (_: dev.kartograph.export.QuerySnapshotSizeException) {
            error.println("error: enum expansion report exceeds 16 MiB; select an enum or a smaller page")
            2
        } catch (invalid: IllegalArgumentException) {
            error.println("error: ${invalid.message ?: "invalid enum expansion inputs"}")
            2
        } catch (_: Exception) {
            error.println("error: unable to read enum expansion snapshots")
            2
        }
    }

    private fun usage(error: PrintStream, message: String): Int {
        error.println("error: $message")
        error.print(HELP)
        return 64
    }

    private val OPTIONS = setOf("--graph-file", "--base-graph", "--symbol", "--limit", "--offset", "--format", "--snapshot-max-mib")
    private const val HELP = """Usage: kartograph impact --enum-expansion --graph-file CURRENT --base-graph BASE
  [--symbol class:package/Enum] [--limit 1..1000] [--offset 0..200000] [--format json|text]
Reports candidates for added enum constants at current authored fallback and finite membership sites.
Capture with snapshot --include-enum-decisions and compiler enumDecisions=true.
Missing inventories or evidence are reported as unavailable. Captured inputs are not rechecked.
"""
}
