package dev.kartograph.export

/** v2의 고정 field 순서와 문자열 사전을 한곳에서 관리한다. 해제 뒤에는 v1과 같은 검증 경로를 쓴다. */
internal class CompactSnapshotGraph(private val positions: Map<String, Int>) {
    private val strings = linkedMapOf<String, Int>()
    val table: List<String> get() = strings.keys.toList()
    fun text(value: String): Int = strings.getOrPut(value) { strings.size }

    fun node(value: Map<String, Any?>): List<Any?> = NODE_FIELDS.map { key -> encode(key, value[key]) }
    fun call(value: Map<String, Any?>): List<Any?> = CALL_FIELDS.map { key -> when (key) {
        "caller" -> positions.getValue(value.getValue(key) as String)
        "resolvedTargets" -> (value.getValue(key) as List<*>).map { positions.getValue(it as String) }
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

    companion object {
        private val NODE_FIELDS = listOf("usr", "name", "kind", "module", "jvmSignature", "location", "accessibility",
            "jvmVisibility", "jvmModifiers", "attributes", "annotations", "supertypes", "extensionReceiverType", "synthesized")
        private val CALL_FIELDS = listOf("caller", "owner", "name", "descriptor", "kind", "ordinal", "location", "resolvedTargets", "resolution", "model")
        private val LIST_FIELDS = setOf("jvmModifiers", "attributes", "annotations", "supertypes")

        fun expand(graph: Map<*, *>): Map<String, Any?> {
            val strings = array(graph["stringTable"])
            require(strings.all { it is String }) { "invalid compact string table" }
            fun string(reference: Any?): String? = reference?.let { strings[index(it, strings.size)] as String }
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
            fun node(reference: Any?): Any? = nodes[index(reference, nodes.size)]["usr"]
            val edges = array(graph["edges"]).map { value ->
                val row = array(value)
                require(row.size == 5) { "invalid compact edge" }
                mapOf("source" to node(row[0]), "target" to node(row[1]), "kind" to string(row[2]),
                    "origin" to string(row[3]), "weight" to row[4])
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
            return mapOf("nodes" to nodes, "edges" to edges, "externalCalls" to calls, "serviceProviders" to graph["serviceProviders"])
        }

        private fun array(value: Any?): List<*> = value as? List<*> ?: throw IllegalArgumentException("invalid compact array")
        private fun index(value: Any?, size: Int): Int {
            require(value is Long && value >= 0 && value < size) { "invalid compact reference" }
            return value.toInt()
        }
    }
}
