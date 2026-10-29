package dev.kartograph.export

import dev.kartograph.core.*

/** plain/compact 공통의 별도 versioned source-universe assertion이다. */
internal object CompilerCallerInventoryCodec {
    fun value(rows: List<CompilerCallerInventory>): Map<String, Any?> = sortedMapOf(
        "version" to 1, "profile" to "source-backend-accounting-v1", "verification" to "verifiedAtCapture",
        "inventories" to rows.map { row -> sortedMapOf(
            "scope" to row.scope, "artifact" to row.artifact, "outputRoot" to row.outputRoot,
            "sources" to row.sources.map { sortedMapOf("path" to it.path, "sha256" to it.sha256) },
            "bodies" to row.bodies.map { body -> sortedMapOf("key" to body.key, "path" to body.file.path,
                "start" to body.offsetUtf16, "end" to body.endOffsetUtf16, "kind" to body.kind.name,
                "present" to body.bodyPresent, "origin" to body.compilerOrigin, "methods" to body.methods.map { it.value }) },
            "outputs" to row.outputs.map { sortedMapOf("path" to it.path, "sha256" to it.sha256, "owner" to it.owner) },
            "methods" to row.methods.map { method -> sortedMapOf("id" to method.id.value, "access" to method.access,
                "code" to method.hasCode, "origin" to method.origin.name, "body" to method.sourceBody, "compilerOrigin" to method.compilerOrigin) },
            "basis" to row.coordinateBasis.name, "compiler" to row.compilerVersion, "token" to row.inputToken,
            "collectorArtifact" to row.collectorArtifactSha256, "evidence" to row.evidenceSha256,
            "authoredEvidence" to row.authoredEvidenceSha256, "bodyCounts" to row.sourceBodyCounts.toSortedMap(),
            "projectionComplete" to row.projectionComplete,
        ) },
    )
    fun parse(raw: Any?): List<CompilerCallerInventory> {
        val value = obj(raw, setOf("version", "profile", "verification", "inventories"))
        require(value["version"] == 1L && value["profile"] == "source-backend-accounting-v1" && value["verification"] == "verifiedAtCapture") { "unsupported caller inventory snapshot" }
        val rows = list(value["inventories"])
        require(rows.size <= 256) { "caller inventory snapshot exceeds task limit" }
        var total = 0L
        fun bounded(value: Any?): List<*> = list(value).also { total += it.size; require(total <= 200_000) { "caller inventory snapshot exceeds total row limit" } }
        return rows.map { rawRow ->
            val row = obj(rawRow, setOf("scope", "artifact", "outputRoot", "sources", "bodies", "outputs", "methods", "basis", "compiler", "token",
                "collectorArtifact", "evidence", "authoredEvidence", "bodyCounts", "projectionComplete"))
            val sources = bounded(row["sources"]).map { obj(it, setOf("path", "sha256")).let { source -> CompilerEvidenceSource(str(source["path"]), str(source["sha256"])) } }
            val byPath = sources.associateBy { it.path }
            val bodies = bounded(row["bodies"]).map { obj(it, setOf("key", "path", "start", "end", "kind", "present", "origin", "methods")).let { body ->
                CompilerSourceBody(str(body["key"]), requireNotNull(byPath[str(body["path"])]) { "inventory snapshot body source is missing" },
                    body["start"]?.let(::int), body["end"]?.let(::int), enumValueOf<CompilerSourceBodyKind>(str(body["kind"])),
                    bool(body["present"]), str(body["origin"]), bounded(body["methods"]).map { id -> NodeId(str(id)) })
            } }
            val outputs = bounded(row["outputs"]).map { obj(it, setOf("path", "sha256", "owner")).let { output ->
                CompilerInventoryOutput(str(output["path"]), str(output["sha256"]), output["owner"]?.let(::str))
            } }
            val methods = bounded(row["methods"]).map { obj(it, setOf("id", "access", "code", "origin", "body", "compilerOrigin")).let { method ->
                CompilerInventoryMethod(NodeId(str(method["id"])), int(method["access"]), bool(method["code"]), enumValueOf<CompilerBackendOrigin>(str(method["origin"])),
                    method["body"]?.let(::str), str(method["compilerOrigin"]))
            } }
            val counts = row["bodyCounts"] as? Map<*, *> ?: throw IllegalArgumentException("invalid inventory body counts")
            require(counts.size == sources.size) { "inventory body counts differ from sources" }
            CompilerCallerInventory(str(row["scope"]), str(row["artifact"]), str(row["outputRoot"]), sources, bodies, outputs, methods,
                enumValueOf<CompilerSourceCoordinateBasis>(str(row["basis"])), str(row["compiler"]), str(row["token"]), str(row["collectorArtifact"]),
                str(row["evidence"]), str(row["authoredEvidence"]), counts.entries.associate { str(it.key) to int(it.value) }, bool(row["projectionComplete"]))
        }
    }
    private fun obj(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *>)?.also { require(it.keys == fields) { "invalid inventory snapshot fields" } }
        ?: throw IllegalArgumentException("invalid inventory snapshot object")
    private fun list(value: Any?): List<*> = (value as? List<*>)?.also { require(it.size <= 200_000) { "inventory snapshot exceeds row limit" } }
        ?: throw IllegalArgumentException("invalid inventory snapshot list")
    private fun str(value: Any?): String = (value as? String)?.also { require(it.length <= 16_384) { "inventory snapshot value exceeds limit" } }
        ?: throw IllegalArgumentException("invalid inventory snapshot text")
    private fun int(value: Any?): Int = (value as? Long)?.also { require(it in 0..Int.MAX_VALUE.toLong()) { "invalid inventory snapshot integer" } }?.toInt()
        ?: throw IllegalArgumentException("invalid inventory snapshot integer")
    private fun bool(value: Any?): Boolean = value as? Boolean ?: throw IllegalArgumentException("invalid inventory snapshot boolean")
}
