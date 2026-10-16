package dev.kartograph.export

import dev.kartograph.core.CompilerEnumDecisionEvidence
import dev.kartograph.core.CompilerEnumDecisionImportCounts
import dev.kartograph.core.CompilerEnumDecisionProducerCounts
import dev.kartograph.core.CompilerEnumDecisionRoot
import dev.kartograph.core.CompilerEnumDecisionSite
import dev.kartograph.core.CompilerEnumDecisionUnit
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.EnumDecisionAttribution
import dev.kartograph.core.EnumDecisionKind
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId

/** compact table이나 graph view와 독립적으로 원래 판단 capture를 보존하는 선택 확장이다. */
internal object CompilerEnumDecisionSnapshotCodec {
    fun value(evidence: CompilerEnumDecisionEvidence): Any = sortedMapOf(
        "version" to 1,
        "roots" to evidence.roots.map { root -> sortedMapOf(
            "id" to root.id, "ordinal" to root.ordinal, "input" to inputValue(root.input),
            "capturedClassOwners" to root.capturedClassOwners.map { it.value },
        ) },
        "units" to evidence.units.map { unit -> sortedMapOf(
            "id" to unit.id, "receipt" to inputValue(unit.receipt), "collector" to unit.collector,
            "compilerVersion" to unit.compilerVersion, "collectorArtifactFingerprint" to unit.collectorArtifactFingerprint,
            "inputToken" to unit.inputToken,
            "claims" to unit.claims.map { claim -> sortedMapOf(
                "scope" to claim.scope, "compiler" to claim.compiler, "task" to claim.task, "outputs" to claim.outputs.map(::inputValue),
            ) },
            "producer" to sortedMapOf("observed" to unit.producer.observed, "emitted" to unit.producer.emitted,
                "unsupported" to unit.producer.unsupported, "ignored" to unit.producer.ignored),
            "import" to sortedMapOf("resolved" to unit.importCounts.resolved, "unresolved" to unit.importCounts.unresolved),
        ) },
        "sites" to evidence.sites.map { site -> sortedMapOf(
            "id" to site.id, "unitId" to site.unitId, "enumOwner" to site.enumOwner.value,
            "constants" to site.constants.map { it.value }, "kind" to site.kind.name.lowerCamel(),
            "lexicalClass" to site.lexicalClass?.value, "lexicalLambda" to site.lexicalLambda,
            "path" to site.file.path, "sourceSha256" to site.file.sha256, "coordinateBasis" to site.coordinateBasis.name.lowerCamel(),
            "offsetUtf16" to site.offsetUtf16, "endOffsetUtf16" to site.endOffsetUtf16,
            "line" to site.line, "column" to site.column, "generated" to site.generated,
            "sourceRootId" to site.sourceRootId, "enumRootId" to site.enumRootId, "attribution" to site.attribution.name.lowerCamel(),
        ) },
    )

    fun parse(value: Any?): CompilerEnumDecisionEvidence {
        val extension = obj(value, setOf("version", "roots", "units", "sites"))
        require(integer(extension["version"]) == 1) { "unsupported compiler enum decision version" }
        val roots = array(extension["roots"], 4_096)
        val units = array(extension["units"], 256)
        val sites = array(extension["sites"], 200_000)
        var owners = 0
        roots.forEach { raw -> owners = boundedTotal(owners, array(obj(raw, ROOT_FIELDS)["capturedClassOwners"], 1_000_000).size, 1_000_000) }
        var claims = 0
        var outputs = 0
        units.forEach { raw ->
            val entries = array(obj(raw, UNIT_FIELDS)["claims"], 4_096)
            claims = boundedTotal(claims, entries.size, 4_096)
            entries.forEach { entry -> outputs = boundedTotal(outputs, array(obj(entry, CLAIM_FIELDS)["outputs"], 16_384).size, 16_384) }
        }
        var constants = 0
        sites.forEach { raw -> constants = boundedTotal(constants, array(obj(raw, SITE_FIELDS)["constants"], 65_536).size, 1_000_000) }
        val parsedRoots = roots.map { raw ->
            val root = obj(raw, ROOT_FIELDS)
            val ids = ids(root["capturedClassOwners"], 1_000_000)
            CompilerEnumDecisionRoot(integer(root["ordinal"]), input(root["input"]), ids).also {
                require(string(root["id"]) == it.id) { "compiler enum decision root identity mismatch" }
            }
        }
        val parsedUnits = units.map { raw ->
            val unit = obj(raw, UNIT_FIELDS)
            val parsedClaims = array(unit["claims"], 4_096).map { entry ->
                val claim = obj(entry, CLAIM_FIELDS)
                CompilerSourceUseClaim(string(claim["scope"]), string(claim["compiler"]), string(claim["task"]),
                    array(claim["outputs"], 16_384).map(::input))
            }
            val producer = obj(unit["producer"], PRODUCER_FIELDS)
            val imported = obj(unit["import"], IMPORT_FIELDS)
            CompilerEnumDecisionUnit(input(unit["receipt"]), string(unit["collector"]), string(unit["compilerVersion"]),
                string(unit["collectorArtifactFingerprint"]), string(unit["inputToken"]), parsedClaims,
                CompilerEnumDecisionProducerCounts(integer(producer["observed"]), integer(producer["emitted"]),
                    integer(producer["unsupported"]), integer(producer["ignored"])),
                CompilerEnumDecisionImportCounts(integer(imported["resolved"]), integer(imported["unresolved"]))).also {
                require(string(unit["id"]) == it.id && parsedClaims == it.claims) { "compiler enum decision unit identity or claim order mismatch" }
            }
        }
        val parsedSites = sites.map { raw ->
            val site = obj(raw, SITE_FIELDS)
            CompilerEnumDecisionSite(string(site["unitId"]), NodeId(string(site["enumOwner"])), ids(site["constants"], 65_536),
                enumValue<EnumDecisionKind>(site["kind"]), site["lexicalClass"]?.let { NodeId(string(it)) },
                site["lexicalLambda"] as? Boolean ?: invalid(), CompilerEvidenceSource(string(site["path"]), string(site["sourceSha256"])),
                enumValue<CompilerSourceCoordinateBasis>(site["coordinateBasis"]), integer(site["offsetUtf16"]), integer(site["endOffsetUtf16"]),
                integer(site["line"]), integer(site["column"]), site["generated"] as? Boolean ?: invalid(),
                site["sourceRootId"]?.let(::string), site["enumRootId"]?.let(::string),
                enumValue<EnumDecisionAttribution>(site["attribution"])).also {
                require(string(site["id"]) == it.id) { "compiler enum decision site identity mismatch" }
            }
        }
        return CompilerEnumDecisionEvidence(parsedRoots, parsedUnits, parsedSites).also {
            require(parsedRoots == it.roots && parsedUnits == it.units && parsedSites == it.sites) { "compiler enum decision rows are not in canonical order" }
        }
    }

    private fun ids(value: Any?, maximum: Int): List<NodeId> = array(value, maximum).map { NodeId(string(it)) }.also { ids ->
        require(ids.zipWithNext().all { (left, right) -> left < right }) { "compiler enum decision IDs must be sorted and unique" }
    }
    private inline fun <reified T : Enum<T>> enumValue(value: Any?): T =
        enumValues<T>().singleOrNull { it.name.lowerCamel() == string(value) } ?: invalid()

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf(
        "role" to input.role, "path" to input.path, "sha256" to input.sha256,
    )

    private fun input(value: Any?): InputFingerprint = obj(value, setOf("role", "path", "sha256")).let {
        InputFingerprint(string(it["role"]), string(it["path"]), string(it["sha256"]))
    }

    private fun obj(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *> ?: invalid()).also {
        require(it.keys == fields) { "compiler enum decisions have invalid or missing fields" }
    }

    private fun array(value: Any?, maximum: Int): List<*> = (value as? List<*> ?: invalid()).also {
        require(it.size <= maximum) { "compiler enum decision collection exceeds its limit" }
    }

    private fun boundedTotal(total: Int, count: Int, maximum: Int): Int {
        require(count <= maximum - total) { "compiler enum decision collection exceeds its aggregate limit" }
        return total + count
    }

    private fun integer(value: Any?): Int = (value as? Long)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: invalid()

    private fun string(value: Any?): String = (value as? String ?: invalid()).also { text ->
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            bytes += when {
                Character.isHighSurrogate(character) -> {
                    require(index < text.length && Character.isLowSurrogate(text[index])) { "compiler enum decision string is not Unicode scalar text" }
                    index++
                    4
                }
                Character.isLowSurrogate(character) -> invalid()
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
            require(bytes <= 16_384) { "compiler enum decision string exceeds its byte limit" }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("compiler enum decisions have invalid or missing fields")

    private val ROOT_FIELDS = setOf("id", "ordinal", "input", "capturedClassOwners")
    private val UNIT_FIELDS = setOf("id", "receipt", "collector", "compilerVersion", "collectorArtifactFingerprint", "inputToken", "claims", "producer", "import")
    private val CLAIM_FIELDS = setOf("scope", "compiler", "task", "outputs")
    private val PRODUCER_FIELDS = setOf("observed", "emitted", "unsupported", "ignored")
    private val IMPORT_FIELDS = setOf("resolved", "unresolved")
    private val SITE_FIELDS = setOf("id", "unitId", "enumOwner", "constants", "kind", "lexicalClass", "lexicalLambda", "path", "sourceSha256",
        "coordinateBasis", "offsetUtf16", "endOffsetUtf16", "line", "column", "generated", "sourceRootId", "enumRootId", "attribution")
}
