package dev.kartograph.export

/** v2의 고정 field 순서와 문자열 사전을 한곳에서 관리한다. 해제 뒤에는 v1과 같은 검증 경로를 쓴다. */
internal class CompactSnapshotGraph(private val positions: Map<String, Int>) {
    private val strings = linkedMapOf<String, Int>()
    private val expansionBudget = ExpansionBudget()
    val table: List<String> get() = strings.keys.toList()
    fun text(value: String): Int {
        expansionBudget.spend(value)
        return strings.getOrPut(value) { strings.size }
    }
    fun reference(value: String): Int {
        expansionBudget.spend(value)
        return positions.getValue(value)
    }

    fun node(value: Map<String, Any?>): List<Any?> = NODE_FIELDS.map { key -> encode(key, value[key]) }
    fun call(value: Map<String, Any?>): List<Any?> = CALL_FIELDS.map { key -> when (key) {
        "caller" -> reference(value.getValue(key) as String)
        "resolvedTargets" -> (value.getValue(key) as List<*>).map { reference(it as String) }
        "ordinal" -> value[key]
        else -> encode(key, value[key])
    } }

    private fun encode(key: String, value: Any?): Any? = when {
        value == null -> null
        key == "synthesized" -> value
        key == "location" -> (value as Map<*, *>).let { listOf(text(it["path"] as String), it["line"], it["column"]) }
        value is List<*> -> value.map { text(it as String) }
        else -> text(value as String)
    }

    /**
     * 펼침이 만든 파생 문자열의 문자 수와 개수를 센다. 문자열 사전 참조와, 정점 위치 참조가 내는 그 정점의 usr가 대상이다.
     *
     * v2는 같은 문자열을 참조 수만큼 돌려준다. 펼친 뒤의 v1 검증·정렬·식별자 연결은 참조마다 그 문자열 전체를 다시 훑거나
     * 복사하므로, 세지 않으면 일이 파일 바이트가 아니라 '참조 수 × 공유 문자열 길이'에 비례한다. 그래서 v1 문서라면
     * 그대로 적혔을 양을 세고, 상한을 넘는 순간 거부해 뒤 단계가 시작되지 않게 한다.
     */
    internal class ExpansionBudget(
        private val maximumCharacters: Long = MAX_EXPANDED_CHARACTERS,
        private val maximumReferences: Long = MAX_EXPANDED_REFERENCES,
    ) {
        /** 지금까지 펼친 문자 수(UTF-16 code unit)다. */
        var characters: Long = 0
            private set

        /** 지금까지 펼친 문자열 수다. */
        var references: Long = 0
            private set

        /** [value]를 한 번 더 펼친 것으로 센다. 상한을 넘으면 원시 값 없이 원인과 해결 방향만 담아 거부한다. */
        fun spend(value: String): String {
            characters += value.length
            references++
            require(characters <= maximumCharacters && references <= maximumReferences) {
                "compact query snapshot string references expand beyond the supported budget " +
                    "($maximumCharacters characters, $maximumReferences strings); recapture it with `kartograph snapshot`"
            }
            return value
        }
    }

    companion object {
        private val NODE_FIELDS = listOf("usr", "name", "kind", "module", "jvmSignature", "location", "accessibility",
            "jvmVisibility", "jvmModifiers", "attributes", "annotations", "supertypes", "extensionReceiverType", "synthesized")
        private val CALL_FIELDS = listOf("caller", "owner", "name", "descriptor", "kind", "ordinal", "location", "resolvedTargets", "resolution", "model")
        private val LIST_FIELDS = setOf("jvmModifiers", "attributes", "annotations", "supertypes")

        // QuerySnapshotCodec의 compact compiler 근거 행 배치다(render와 parseCompilerCallEvidence의 v2 분기).
        // 열 위치를 codec과 따로 알므로 CompactSnapshotTest가 두 쪽이 같은 문자열을 세는지 고정한다.
        private const val COMPILER_EVIDENCE_FIELDS = 12
        private val COMPILER_EVIDENCE_NODE_COLUMNS = 0..1
        private val COMPILER_EVIDENCE_TEXT_COLUMNS = 2..6

        /**
         * compact 문서 하나를 펼칠 때 허용하는 파생 문자열 문자 수(UTF-16 code unit)의 합이다.
         *
         * 근거(2026-10-20, 0.25.0 이후 main 빌드, JDK 21에서 `snapshot --compact --snapshot-max-mib 128`로 캡처):
         * AGP 9.2.0 `gradle-9.2.0.jar` 문서 97,563,954 byte(93.0 MiB)는 371,765,064자(3.81자/byte)로,
         * groovy 3.0.22 jar 문서 81,248,553 byte(77.5 MiB)는 268,558,213자(3.31자/byte)로 펼쳐졌다.
         * 대부분은 간선·외부 호출의 정점 참조(usr)다. 저장 snapshot 절대 상한 128 MiB로 환산하면 약 5.1억 자이고,
         * 평문 모양 절(callSiteEvidence·retention 등) 없이 compact 행(nodes·edges·externalCalls·stringTable)만으로
         * 128 MiB를 채운다고 가정해도 약 7.2억 자다. 2^31은 그 2.97배다.
         * reader가 고른 byte 상한에 비례시키지 않은 것은 호출부(QuerySnapshotCodec.parse)의 시그니처를 유지하기 위해서다.
         */
        const val MAX_EXPANDED_CHARACTERS: Long = 1L shl 31

        /**
         * compact 문서 하나를 펼칠 때 허용하는 파생 문자열 수의 합이다.
         *
         * 근거([MAX_EXPANDED_CHARACTERS]와 같은 측정): AGP 문서는 5,493,258개(0.056개/byte), groovy 문서는 4,705,898개
         * (0.058개/byte)였다. 128 MiB로 환산하면 약 780만 개, compact 행만으로 채운다고 가정하면 약 1,120만 개다.
         * 2^25는 그 3.0배다.
         */
        const val MAX_EXPANDED_REFERENCES: Long = 1L shl 25

        fun expand(graph: Map<*, *>): Map<String, Any?> = expand(graph, ExpansionBudget())

        /** 시험이 같은 펼침 경로의 계수와 경계를 확인할 수 있게 예산을 받는다. */
        internal fun expand(graph: Map<*, *>, budget: ExpansionBudget): Map<String, Any?> {
            val strings = array(graph["stringTable"])
            require(strings.all { it is String }) { "invalid compact string table" }
            fun string(reference: Any?): String? = reference?.let { budget.spend(strings[index(it, strings.size)] as String) }
            fun decode(key: String, value: Any?): Any? = when {
                value == null || key == "synthesized" -> value
                key == "location" -> array(value).also { require(it.size == 3) }.let {
                    mapOf("path" to string(it[0]), "line" to it[1], "column" to it[2]) }
                key in LIST_FIELDS -> array(value).map(::string)
                else -> string(value)
            }
            val nodes = array(graph["nodes"]).map { value ->
                val row = array(value)
                require(row.size == NODE_FIELDS.size) { "invalid compact node" }
                NODE_FIELDS.mapIndexed { i, key -> key to decode(key, row[i]) }.toMap()
            }
            // 정점 위치 참조는 그 정점의 usr를 낸다. v1이라면 참조마다 usr 전체가 적혔을 것이므로 함께 센다.
            fun node(reference: Any?): Any? = nodes[index(reference, nodes.size)]["usr"].also { usr -> if (usr is String) budget.spend(usr) }
            val edges = array(graph["edges"]).map { value ->
                val row = array(value)
                require(row.size == 5) { "invalid compact edge" }
                buildMap<String, Any?> {
                    put("source", node(row[0])); put("target", node(row[1])); put("kind", string(row[2]))
                    put("origin", string(row[3])); put("weight", row[4])
                }
            }
            val calls = array(graph["externalCalls"]).map { value ->
                val row = array(value)
                require(row.size == CALL_FIELDS.size) { "invalid compact external call" }
                CALL_FIELDS.mapIndexed { i, key -> key to when (key) {
                    "caller" -> node(row[i])
                    "resolvedTargets" -> array(row[i]).map(::node)
                    "ordinal" -> row[i]
                    else -> decode(key, row[i])
                } }.toMap()
            }
            spendCompilerEvidence(graph["compilerCallEvidence"], strings, nodes, budget)
            return mapOf("nodes" to nodes, "edges" to edges, "externalCalls" to calls, "serviceProviders" to graph["serviceProviders"],
                "enclosures" to graph["enclosures"],
                "callbackArguments" to graph["callbackArguments"], "parameterUses" to graph["parameterUses"],
                "lambdaEscapes" to graph["lambdaEscapes"], "callSiteEvidence" to graph["callSiteEvidence"])
        }

        /**
         * codec이 raw graph에서 따로 펼치는 compact compiler 근거 행의 정점 열(0·1)과 문자열 열(2..6: path·sha256·collector·
         * compilerVersion·coordinateBasis)을 같은 예산에 미리 더한다. 모양이 틀린 행(목록이 아님, 12열이 아님, 참조가
         * 범위 밖)은 세지 않는다. codec이 그 행을 기존 오류 문구로 거부하게 하기 위해서다.
         */
        private fun spendCompilerEvidence(rows: Any?, strings: List<*>, nodes: List<Map<String, Any?>>, budget: ExpansionBudget) {
            for (raw in rows as? List<*> ?: return) {
                val row = raw as? List<*>
                if (row == null || row.size != COMPILER_EVIDENCE_FIELDS) continue
                val values = COMPILER_EVIDENCE_NODE_COLUMNS.map { column -> evidenceReference(row[column], nodes.size)?.let { nodes[it]["usr"] } } +
                    COMPILER_EVIDENCE_TEXT_COLUMNS.map { column -> evidenceReference(row[column], strings.size)?.let { strings[it] } }
                val texts = values.filterIsInstance<String>()
                if (texts.size == values.size) texts.forEach { budget.spend(it) }
            }
        }

        /** codec의 compact 근거 참조 규칙과 같다. 범위 안의 정수만 참조이고 나머지는 null이다. */
        private fun evidenceReference(value: Any?, size: Int): Int? = (value as? Long)?.takeIf { it >= 0 && it < size }?.toInt()

        private fun array(value: Any?): List<*> = value as? List<*> ?: throw IllegalArgumentException("invalid compact array")
        private fun index(value: Any?, size: Int): Int {
            require(value is Long && value >= 0 && value < size) { "invalid compact reference" }
            return value.toInt()
        }
    }
}
