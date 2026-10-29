package dev.kartograph.export

import dev.kartograph.core.GeneratedDeclaration
import dev.kartograph.core.GenerationEvidence
import dev.kartograph.core.NodeId

/** 필수 버전의 생성 관측을 복원한다. 파일 내용 대조는 저장 후 별도 freshness 절차다. */
internal object GenerationEvidenceSnapshotCodec {
    fun value(evidence: GenerationEvidence): Any {
        evidence.requireValid()
        val value = sortedMapOf(
            "version" to 2, "receipts" to evidence.receipts.map(GenerationReceiptCodec::value),
            "observations" to evidence.observations.map(GenerationObservationCodec::value),
            "declarations" to evidence.declarations.map { row -> sortedMapOf(
                "scope" to row.scope, "generatorTask" to row.generatorTask, "attempt" to row.attempt,
                "compilerTask" to row.compilerTask, "source" to row.source, "sourceSha256" to row.sourceSha256,
                "owner" to row.owner.value, "symbols" to row.symbols.map { it.value },
                "originalRoot" to row.originalRoot, "selectedRoot" to row.selectedRoot,
                "classPath" to row.classPath, "classSha256" to row.classSha256,
            ) },
        )
        // 큰 그래프의 상한과 별도로 작은 필수 근거 문서의 출력 상한을 고정한다.
        boundedJsonValue(value, GenerationReceiptCodec.MAX_BYTES, "")
        return value
    }

    fun parse(value: Any?): GenerationEvidence {
        val row = generationObject(value, setOf("version", "receipts", "declarations", "observations"))
        require(row["version"] == 2L) { "unsupported generation evidence version" }
        return GenerationEvidence(generationArray(row["receipts"], 256).map(GenerationReceiptCodec::receipt),
            generationArray(row["declarations"]).map { raw ->
                val item = generationObject(raw, setOf("scope", "generatorTask", "attempt", "compilerTask", "source", "sourceSha256",
                    "owner", "symbols", "originalRoot", "selectedRoot", "classPath", "classSha256"))
                GeneratedDeclaration(generationString(item["scope"]), generationString(item["generatorTask"]),
                    generationString(item["attempt"]), generationString(item["compilerTask"]), generationString(item["source"]),
                    generationString(item["sourceSha256"]), NodeId(generationString(item["owner"])),
                    generationArray(item["symbols"]).map { NodeId(generationString(it)) },
                    generationString(item["originalRoot"]), generationString(item["selectedRoot"]),
                    generationString(item["classPath"]), generationString(item["classSha256"]))
            }, generationArray(row["observations"], 256).map(GenerationObservationCodec::parse))
    }
}
