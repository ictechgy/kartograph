package dev.kartograph.export

import dev.kartograph.core.*

/** 기존 compact 행의 길이를 바꾸지 않는 versioned source-only side table이다. */
internal object CompilerAuthoredCallCodec {
    fun value(rows: List<CompilerAuthoredCaller>): Map<String, Any?> = sortedMapOf(
        "version" to 1, "profile" to "ordinary-body-v1", "verification" to "verifiedAtCapture",
        "callers" to rows.map { row -> sortedMapOf(
            "caller" to row.caller.value, "path" to row.file.path, "sourceSha256" to row.file.sha256,
            "start" to row.offsetUtf16, "end" to row.endOffsetUtf16, "basis" to row.coordinateBasis.name,
            "compiler" to row.compilerVersion, "nodes" to row.classifiedNodes, "explicitCalls" to row.explicitCalls,
            "coverage" to row.coverage.name, "reasons" to row.reasons.map { it.name },
            "token" to row.inputToken, "artifact" to row.collectorArtifactSha256, "evidence" to row.evidenceSha256,
            "calls" to row.calls.map { call -> sortedMapOf(
                "target" to call.target.value, "origin" to call.origin.name, "path" to call.targetSourcePath,
                "start" to call.offsetUtf16, "end" to call.endOffsetUtf16,
            ) },
        ) },
    )

    fun parse(raw: Any?, nodes: List<GraphNode>): List<CompilerAuthoredCaller> {
        val value = obj(raw, setOf("version", "profile", "verification", "callers"))
        require(value["version"] == 1L && value["profile"] == "ordinary-body-v1" && value["verification"] == "verifiedAtCapture") {
            "unsupported authored snapshot evidence"
        }
        val ids = nodes.associateBy { it.id }
        val seen = mutableSetOf<NodeId>()
        var calls = 0L
        return list(value["callers"]).map { rawRow ->
            val row = obj(rawRow, setOf("caller", "path", "sourceSha256", "start", "end", "basis", "compiler", "nodes",
                "explicitCalls", "coverage", "reasons", "token", "artifact", "evidence", "calls"))
            val caller = NodeId(str(row["caller"]))
            require(ids[caller]?.let { it.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION) &&
                NodeAttribute.EXTERNAL_STUB !in it.attributes } == true && seen.add(caller)) { "invalid or duplicate authored snapshot caller" }
            val selected = list(row["calls"])
            calls += selected.size
            require(calls <= 200_000) { "authored snapshot exceeds call limit" }
            CompilerAuthoredCaller(caller, CompilerEvidenceSource(str(row["path"]), str(row["sourceSha256"])),
                int(row["start"]), int(row["end"]), enumValueOf<CompilerSourceCoordinateBasis>(str(row["basis"])),
                str(row["compiler"]), int(row["nodes"]), int(row["explicitCalls"]), enumValueOf<AuthoredCallCoverage>(str(row["coverage"])),
                list(row["reasons"]).map { enumValueOf<AuthoredCallReason>(str(it)) }, selected.map { rawCall ->
                    val call = obj(rawCall, setOf("target", "origin", "path", "start", "end"))
                    val target = NodeId(str(call["target"]))
                    val origin = enumValueOf<AuthoredCallOrigin>(str(call["origin"]))
                    require((origin == AuthoredCallOrigin.PROJECT) == (target in ids && NodeAttribute.EXTERNAL_STUB !in ids.getValue(target).attributes)) {
                        "authored target scope contradicts snapshot graph"
                    }
                    CompilerAuthoredCall(target, origin, call["path"]?.let(::str), int(call["start"]), int(call["end"]))
                }, str(row["token"]), str(row["artifact"]), str(row["evidence"]))
        }
    }
    private fun obj(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *>)?.also {
        require(it.keys == fields) { "invalid authored snapshot fields" }
    } ?: throw IllegalArgumentException("invalid authored snapshot object")
    private fun list(value: Any?): List<*> = (value as? List<*>)?.also { require(it.size <= 200_000) { "authored snapshot exceeds row limit" } }
        ?: throw IllegalArgumentException("invalid authored snapshot list")
    private fun str(value: Any?): String = (value as? String)?.also { require(it.length <= 16_384) { "authored snapshot value exceeds limit" } }
        ?: throw IllegalArgumentException("invalid authored snapshot text")
    private fun int(value: Any?): Int = (value as? Long)?.also { require(it in 0..Int.MAX_VALUE.toLong()) { "invalid authored snapshot integer" } }?.toInt()
        ?: throw IllegalArgumentException("invalid authored snapshot integer")
}
