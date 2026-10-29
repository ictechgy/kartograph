package dev.kartograph.export

import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerSourceCoordinateBasis
import dev.kartograph.core.CompilerSourceUse
import dev.kartograph.core.CompilerSourceUseClaim
import dev.kartograph.core.CompilerSourceUseEvidence
import dev.kartograph.core.CompilerSourceUseImportCounts
import dev.kartograph.core.CompilerSourceUseKind
import dev.kartograph.core.CompilerSourceUseProducerCounts
import dev.kartograph.core.CompilerSourceUseRoot
import dev.kartograph.core.CompilerSourceUseUnit
import dev.kartograph.core.InputFingerprint
import dev.kartograph.core.NodeId

/** v1·compact v2가 공유하는 비간선 compiler 선택 관측의 엄격한 선택 확장이다. */
internal object CompilerSourceUseSnapshotCodec {
    fun value(evidence: CompilerSourceUseEvidence): Any = sortedMapOf(
        "version" to 1,
        "roots" to evidence.roots.map { root -> sortedMapOf(
            "id" to root.id, "ordinal" to root.ordinal, "input" to inputValue(root.input),
            "capturedCallableOwners" to root.capturedCallableOwners.map { it.value },
        ) },
        "units" to evidence.units.map { unit -> sortedMapOf(
            "id" to unit.id, "receipt" to inputValue(unit.receipt), "collector" to unit.collector,
            "compilerVersion" to unit.compilerVersion, "collectorArtifactFingerprint" to unit.collectorArtifactFingerprint,
            "inputToken" to unit.inputToken,
            "claims" to unit.claims.map { claim -> sortedMapOf(
                "scope" to claim.scope, "compiler" to claim.compiler, "task" to claim.task,
                "outputs" to claim.outputs.map(::inputValue),
            ) },
            "producer" to sortedMapOf("observed" to unit.producer.observed, "emitted" to unit.producer.emitted,
                "unmapped" to unit.producer.unmapped, "ambiguous" to unit.producer.ambiguous),
            "import" to sortedMapOf("admitted" to unit.importCounts.admitted, "outsideGraph" to unit.importCounts.outsideGraph,
                "shadowedCaller" to unit.importCounts.shadowedCaller, "shadowedTarget" to unit.importCounts.shadowedTarget,
                "outsideClaimedOutputs" to unit.importCounts.outsideClaimedOutputs,
                "sourceAssociationUnverified" to unit.importCounts.sourceAssociationUnverified),
            "viewOmitted" to unit.viewOmitted,
        ) },
        "uses" to evidence.uses.map { use -> sortedMapOf(
            "id" to use.id, "unitId" to use.unitId, "source" to use.source.value, "target" to use.target.value,
            "sourceRootId" to use.sourceRootId, "targetRootId" to use.targetRootId,
            "kind" to use.kind.name.lowerCamel(), "path" to use.file.path, "sourceSha256" to use.file.sha256,
            "collector" to use.collector, "compilerVersion" to use.compilerVersion,
            "coordinateBasis" to use.coordinateBasis.name.lowerCamel(), "offsetUtf16" to use.offsetUtf16,
            "endOffsetUtf16" to use.endOffsetUtf16, "line" to use.line, "column" to use.column, "generated" to use.generated,
        ) },
    )

    fun parse(value: Any?): CompilerSourceUseEvidence {
        val extension = obj(value, setOf("version", "roots", "units", "uses"))
        require(integer(extension["version"]) == 1) { "unsupported compiler source use version" }
        val roots = array(extension["roots"], 4_096)
        val units = array(extension["units"], 256)
        val uses = array(extension["uses"], 200_000)
        // JSON tree는 전체 snapshot 상한 안에 있다. 새 retained collection은 합산 상한을 확인한 뒤 만든다.
        var owners = 0
        roots.forEach { raw ->
            val root = obj(raw, ROOT_FIELDS)
            owners = boundedTotal(owners, array(root["capturedCallableOwners"], 1_000_000).size, 1_000_000)
        }
        var claims = 0
        var outputs = 0
        units.forEach { raw ->
            val unit = obj(raw, UNIT_FIELDS)
            val entries = array(unit["claims"], 4_096)
            claims = boundedTotal(claims, entries.size, 4_096)
            entries.forEach { entry ->
                val claim = obj(entry, CLAIM_FIELDS)
                outputs = boundedTotal(outputs, array(claim["outputs"], 16_384).size, 16_384)
            }
        }
        val parsedRoots = roots.map { raw ->
            val root = obj(raw, ROOT_FIELDS)
            val ids = array(root["capturedCallableOwners"], 1_000_000).map { NodeId(string(it)) }
            require(ids.distinct().size == ids.size) { "duplicate compiler source use root owner" }
            CompilerSourceUseRoot(integer(root["ordinal"]), input(root["input"]), ids).also {
                require(string(root["id"]) == it.id) { "compiler source use root identity mismatch" }
            }
        }
        require(parsedRoots.map { it.id }.distinct().size == parsedRoots.size) { "duplicate compiler source use root" }
        val parsedUnits = units.map { raw ->
            val unit = obj(raw, UNIT_FIELDS)
            val parsedClaims = array(unit["claims"], 4_096).map { entry ->
                val claim = obj(entry, CLAIM_FIELDS)
                CompilerSourceUseClaim(string(claim["scope"]), string(claim["compiler"]), string(claim["task"]),
                    array(claim["outputs"], 16_384).map(::input))
            }
            require(parsedClaims.distinct().size == parsedClaims.size) { "duplicate compiler source use claim" }
            val producer = obj(unit["producer"], PRODUCER_FIELDS)
            val imported = obj(unit["import"], IMPORT_FIELDS)
            CompilerSourceUseUnit(input(unit["receipt"]), string(unit["collector"]), string(unit["compilerVersion"]),
                string(unit["collectorArtifactFingerprint"]), string(unit["inputToken"]), parsedClaims,
                CompilerSourceUseProducerCounts(integer(producer["observed"]), integer(producer["emitted"]),
                    integer(producer["unmapped"]), integer(producer["ambiguous"])),
                CompilerSourceUseImportCounts(integer(imported["admitted"]), integer(imported["outsideGraph"]),
                    integer(imported["shadowedCaller"]), integer(imported["shadowedTarget"]),
                    integer(imported["outsideClaimedOutputs"]), integer(imported["sourceAssociationUnverified"])),
                integer(unit["viewOmitted"])).also {
                require(string(unit["id"]) == it.id) { "compiler source use unit identity mismatch" }
            }
        }
        require(parsedUnits.map { it.id }.distinct().size == parsedUnits.size) { "duplicate compiler source use unit" }
        val parsedUses = uses.map { raw ->
            val use = obj(raw, USE_FIELDS)
            require(string(use["kind"]) == "invocationSelection") { "unsupported compiler source use kind" }
            val basis = when (string(use["coordinateBasis"])) {
                "kotlinUtf16NormalizedSource" -> CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE
                "javacUtf16CharSequence" -> CompilerSourceCoordinateBasis.JAVAC_UTF16_CHAR_SEQUENCE
                else -> invalid()
            }
            CompilerSourceUse(string(use["unitId"]), NodeId(string(use["source"])), NodeId(string(use["target"])),
                string(use["sourceRootId"]), string(use["targetRootId"]), CompilerSourceUseKind.INVOCATION_SELECTION,
                CompilerEvidenceSource(string(use["path"]), string(use["sourceSha256"])),
                string(use["collector"]), string(use["compilerVersion"]), basis,
                integer(use["offsetUtf16"]), integer(use["endOffsetUtf16"]), integer(use["line"]), integer(use["column"]),
                use["generated"] as? Boolean ?: invalid()).also {
                require(string(use["id"]) == it.id) { "compiler source use identity mismatch" }
            }
        }
        require(parsedUses.map { it.id }.distinct().size == parsedUses.size) { "duplicate compiler source use" }
        return CompilerSourceUseEvidence(parsedRoots, parsedUnits, parsedUses)
    }

    private fun inputValue(input: InputFingerprint): Any = sortedMapOf(
        "role" to input.role, "path" to input.path, "sha256" to input.sha256,
    )

    private fun input(value: Any?): InputFingerprint = obj(value, setOf("role", "path", "sha256")).let {
        InputFingerprint(string(it["role"]), string(it["path"]), string(it["sha256"]))
    }

    private fun obj(value: Any?, fields: Set<String>): Map<*, *> = (value as? Map<*, *> ?: invalid()).also {
        require(it.keys == fields) { "compiler source uses have invalid or missing fields" }
    }

    private fun array(value: Any?, maximum: Int): List<*> = (value as? List<*> ?: invalid()).also {
        require(it.size <= maximum) { "compiler source use collection exceeds its limit" }
    }

    private fun boundedTotal(total: Int, count: Int, maximum: Int): Int {
        require(count <= maximum - total) { "compiler source use collection exceeds its aggregate limit" }
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
                    require(index < text.length && Character.isLowSurrogate(text[index])) { "compiler source use string is not Unicode scalar text" }
                    index++
                    4
                }
                Character.isLowSurrogate(character) -> invalid()
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
            require(bytes <= 16_384) { "compiler source use string exceeds its byte limit" }
        }
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("compiler source uses have invalid or missing fields")
    private val ROOT_FIELDS = setOf("id", "ordinal", "input", "capturedCallableOwners")
    private val UNIT_FIELDS = setOf("id", "receipt", "collector", "compilerVersion", "collectorArtifactFingerprint",
        "inputToken", "claims", "producer", "import", "viewOmitted")
    private val CLAIM_FIELDS = setOf("scope", "compiler", "task", "outputs")
    private val PRODUCER_FIELDS = setOf("observed", "emitted", "unmapped", "ambiguous")
    private val IMPORT_FIELDS = setOf("admitted", "outsideGraph", "shadowedCaller", "shadowedTarget",
        "outsideClaimedOutputs", "sourceAssociationUnverified")
    private val USE_FIELDS = setOf("id", "unitId", "source", "target", "sourceRootId", "targetRootId", "kind", "path",
        "sourceSha256", "collector", "compilerVersion", "coordinateBasis", "offsetUtf16", "endOffsetUtf16", "line", "column", "generated")
}
