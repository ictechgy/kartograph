package dev.kartograph.export

import dev.kartograph.core.CallResolution
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.EdgeOrigin
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.KartographVersion
import dev.kartograph.core.NodeAttribute
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import dev.kartograph.core.ServiceProviderRegistration
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility

/** 컴파일 그래프와 그때의 보존·baseline·관측 한계를 함께 고정한 질의 입력이다. */
public data class QuerySnapshot(
    val graph: CodeGraph,
    val retention: List<RetentionEvidence>,
    val limitations: List<String>,
    val suppressed: Set<NodeId> = emptySet(),
    val includePrivateMembers: Boolean = false,
    val toolVersion: String = KartographVersion.current,
    val revision: String? = null,
    val scope: String? = null,
    val provenance: dev.kartograph.core.SnapshotProvenance? = null,
) {
    init {
        require(revision == null || Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(revision)) { "snapshot revision must be a full commit hash" }
        require(scope == null || (scope.length <= 200 && Regex("[A-Za-z0-9_.:-]+").matches(scope))) { "snapshot scope must be a portable project and variant label" }
    }
}

/** 원본 파일을 다시 읽지 않아도 같은 도달성 의미로 질의할 수 있는 버전 문서 codec이다. */
public object QuerySnapshotCodec {
    /** 일반 code-graph JSON과 보존 문맥 없는 파일을 구분한다. */
    public const val FORMAT: String = "kartograph-query-snapshot"
    /** 한 번에 읽는 질의 문서의 상한이다. */
    public const val MAX_BYTES: Int = 64 * 1024 * 1024

    /** 정렬된 그래프 사실과 보존 근거를 출력하며 로컬 절대경로는 내보내지 않는다. */
    public fun render(snapshot: QuerySnapshot): String = render(snapshot, false)

    /** v2는 간선의 반복 USR을 node 배열 위치로 저장하며 모든 사실과 보존 문맥을 유지한다. */
    public fun render(snapshot: QuerySnapshot, compact: Boolean): String {
        val positions = if (compact) snapshot.graph.nodeIds.withIndex().associate { it.value to it.index } else emptyMap()
        val encoding = if (compact) CompactSnapshotGraph(positions.mapKeys { it.key.value }) else null
        return jsonValue(sortedMapOf(
        "format" to FORMAT,
        "version" to if (compact) 2 else 1,
        "toolVersion" to snapshot.toolVersion,
        "revision" to snapshot.revision, "scope" to snapshot.scope,
        "provenance" to snapshot.provenance?.let(BuildWitnessCodec::provenanceValue),
        "includePrivateMembers" to snapshot.includePrivateMembers,
        "limitations" to snapshot.limitations.distinct().sorted(),
        "suppressed" to snapshot.suppressed.map { it.value }.sorted(),
        "retention" to snapshot.retention.distinct().sortedWith(compareBy({ it.nodeId }, { it.reason.name },
            { it.location?.path.orEmpty() }, { it.location?.line ?: 0 }, { it.location?.column ?: 0 })).map { item ->
            sortedMapOf("nodeId" to item.nodeId.value, "reason" to item.reason.name.lowerCamel(), "location" to locationValue(item.location))
        },
        "graph" to sortedMapOf(
            "nodes" to snapshot.graph.nodeIds.map { snapshot.graph.nodes.getValue(it).toValue().let { value -> encoding?.node(value) ?: value } },
            "edges" to snapshot.graph.edges.map { edge ->
                if (encoding != null) listOf(positions.getValue(edge.source), positions.getValue(edge.target),
                    encoding.text(edge.kind.name.lowerCamel()), encoding.text(edge.origin.name.lowerCamel()), edge.weight)
                else sortedMapOf("source" to edge.source.value, "target" to edge.target.value, "kind" to edge.kind.name.lowerCamel(),
                    "origin" to edge.origin.name.lowerCamel(), "weight" to edge.weight)
            },
            "externalCalls" to snapshot.graph.externalCalls.map { call -> sortedMapOf(
                "caller" to call.caller.value, "owner" to call.owner, "name" to call.name,
                "descriptor" to call.descriptor, "kind" to call.kind.name.lowerCamel(), "ordinal" to call.ordinal,
                "resolution" to call.resolution.name.lowerCamel(), "model" to call.model,
                "resolvedTargets" to call.resolvedTargets.map { it.value }.sorted(), "location" to locationValue(call.location),
            ).let { value -> encoding?.call(value) ?: value } },
            "serviceProviders" to snapshot.graph.serviceProviders.map { item -> sortedMapOf(
                "service" to item.service, "provider" to item.provider.value, "location" to locationValue(item.location),
            ) },
        ).let { graph -> if (encoding == null) graph else graph + ("stringTable" to encoding.table) },
        ).filterValues { it != null }) + "\n"
    }

    /** 불완전한 그래프를 정상 결과로 처리하지 않도록 타입·중복·참조 대상을 조립 전에 검증한다. */
    public fun parse(content: String): QuerySnapshot {
        require(content.length <= MAX_BYTES) { "query snapshot is too large" }
        val document = objectValue(SnapshotJsonParser(content).parse())
        val version = integer(document["version"])
        require(document["format"] == FORMAT && version in 1..2) {
            "unsupported query snapshot; capture it with `kartograph snapshot`"
        }
        val rawGraph = objectValue(document["graph"])
        val graph = if (version == 2) CompactSnapshotGraph.expand(rawGraph) else rawGraph
        val nodes = list(graph["nodes"]).map { raw ->
            val node = objectValue(raw)
            GraphNode(
                id = NodeId(string(node["usr"])), name = string(node["name"]), kind = enumValue(node["kind"]),
                moduleName = optionalString(node["module"]), jvmSignature = optionalString(node["jvmSignature"]),
                location = location(node["location"]), visibility = enumValue(node["accessibility"]),
                jvmVisibility = enumValue(node["jvmVisibility"]),
                jvmModifiers = list(node["jvmModifiers"]).map { enumValue<JvmModifier>(it) }.toSet(),
                attributes = list(node["attributes"]).map { enumValue<NodeAttribute>(it) }.toSet(),
                annotations = strings(node["annotations"]).toSet(), supertypes = strings(node["supertypes"]).toSet(),
                extensionReceiverType = optionalString(node["extensionReceiverType"]), synthesized = boolean(node["synthesized"]),
            )
        }
        val ids = nodes.map { it.id }.toSet()
        require(ids.size == nodes.size) { "query snapshot contains duplicate nodes" }
        val edges = list(graph["edges"]).map { raw ->
            val edge = objectValue(raw)
            GraphEdge(NodeId(string(edge["source"])), NodeId(string(edge["target"])), enumValue(edge["kind"]),
                integer(edge["weight"]), enumValue(edge["origin"]))
        }
        require(edges.all { it.source in ids && it.target in ids }) { "query snapshot contains dangling edges" }
        require(edges.map { listOf(it.source, it.target, it.kind, it.origin) }.distinct().size == edges.size) {
            "query snapshot contains duplicate edges"
        }
        val calls = list(graph["externalCalls"]).map { raw ->
            val call = objectValue(raw)
            ExternalCall(NodeId(string(call["caller"])), string(call["owner"]), string(call["name"]), string(call["descriptor"]),
                enumValue(call["kind"]), location(call["location"]), integer(call["ordinal"]),
                strings(call["resolvedTargets"]).map(::NodeId), enumValue(call["resolution"]), optionalString(call["model"]))
        }
        require(calls.all { it.caller in ids && it.target !in ids && it.ordinal >= 0 && it.resolvedTargets.all(ids::contains) }) {
            "query snapshot contains invalid external calls"
        }
        val providers = list(graph["serviceProviders"]).map { raw ->
            val item = objectValue(raw)
            ServiceProviderRegistration(string(item["service"]), NodeId(string(item["provider"])),
                location(item["location"]) ?: invalid())
        }
        val retention = list(document["retention"]).map { raw ->
            val item = objectValue(raw)
            RetentionEvidence(NodeId(string(item["nodeId"])), enumValue(item["reason"]), location(item["location"]))
        }
        val suppressed = strings(document["suppressed"]).map(::NodeId).toSet()
        require(suppressed.all(ids::contains)) { "query snapshot contains invalid baseline references" }
        return QuerySnapshot(CodeGraph(nodes, edges, calls, providers), retention, strings(document["limitations"]), suppressed,
            boolean(document["includePrivateMembers"]), string(document["toolVersion"]),
            optionalString(document["revision"]), optionalString(document["scope"]),
            document["provenance"]?.let(BuildWitnessCodec::provenance))
    }

    private fun GraphNode.toValue(): Map<String, Any?> = sortedMapOf(
        "usr" to id.value, "name" to name, "kind" to kind.name.lowerCamel(), "module" to moduleName,
        "jvmSignature" to jvmSignature, "location" to locationValue(location), "accessibility" to visibility.name.lowerCamel(),
        "jvmVisibility" to jvmVisibility.name.lowerCamel(), "jvmModifiers" to jvmModifiers.map { it.name.lowerCamel() }.sorted(),
        "attributes" to attributes.map { it.name.lowerCamel() }.sorted(), "annotations" to annotations.sorted(),
        "supertypes" to supertypes.sorted(), "extensionReceiverType" to extensionReceiverType, "synthesized" to synthesized,
    )

    private fun locationValue(location: SourceLocation?): Map<String, Any?>? = location?.let {
        val normalized = it.path.replace('\\', '/')
        val path = if (portable(normalized)) normalized else normalized.substringAfterLast('/').takeIf(::portable)
        require(path != null) { "query snapshot source location cannot be represented as a portable file path" }
        sortedMapOf("path" to path, "line" to it.line, "column" to it.column)
    }

    private fun location(value: Any?): SourceLocation? = value?.let {
        val fields = objectValue(it)
        val path = string(fields["path"])
        require(portable(path)) { "query snapshot source locations must be portable relative paths" }
        SourceLocation(path, fields["line"]?.let(::integer), fields["column"]?.let(::integer))
    }

    private fun portable(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && !path.endsWith('/') && '\\' !in path &&
        !WINDOWS_DRIVE.containsMatchIn(path) && path.split('/').none { it == ".." } && path.none { it.code < 0x20 }

    private fun objectValue(value: Any?): Map<*, *> = value as? Map<*, *> ?: invalid()
    private fun list(value: Any?): List<*> = value as? List<*> ?: invalid()
    private fun string(value: Any?): String = value as? String ?: invalid()
    private fun optionalString(value: Any?): String? = value?.let(::string)
    private fun strings(value: Any?): List<String> = list(value).map(::string)
    private fun boolean(value: Any?): Boolean = value as? Boolean ?: invalid()
    private fun integer(value: Any?): Int = (value as? Long)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: invalid()
    private inline fun <reified T : Enum<T>> enumValue(value: Any?): T =
        enumValueOf<T>(ENUM_NAMES[string(value)] ?: invalid())
    private fun invalid(): Nothing = throw IllegalArgumentException("query snapshot has invalid or missing fields")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")
    private val ENUM_NAMES = listOf(NodeKind.entries, Visibility.entries, JvmModifier.entries, NodeAttribute.entries,
        EdgeKind.entries, EdgeOrigin.entries, InvocationKind.entries, CallResolution.entries, RetentionReason.entries)
        .flatten().associate { it.name.lowerCamel() to it.name }
}

/** 문서 밖의 입력을 오류 문구에 넣지 않는, 깊이와 크기가 제한된 JSON reader다. */
internal class SnapshotJsonParser(private val text: String) {
    private var offset = 0

    fun parse(): Any? {
        val result = value(0)
        whitespace()
        checkInput(offset == text.length)
        return result
    }

    private fun value(depth: Int): Any? {
        checkInput(depth <= 32)
        whitespace()
        return when (text.getOrNull(offset)) {
            '{' -> objectValue(depth + 1)
            '[' -> arrayValue(depth + 1)
            '"' -> stringValue()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> numberValue()
            else -> invalid()
        }
    }

    private fun objectValue(depth: Int): Map<String, Any?> {
        expect('{')
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) return result
        while (true) {
            val key = stringValue()
            checkInput(key !in result)
            expect(':')
            result[key] = value(depth)
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun arrayValue(depth: Int): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        if (consume(']')) return result
        while (true) {
            result += value(depth)
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun stringValue(): String {
        expect('"')
        return buildString {
            while (offset < text.length) {
                when (val character = text[offset++]) {
                    '"' -> return@buildString
                    '\\' -> {
                        checkInput(offset < text.length)
                        append(when (val escape = text[offset++]) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                checkInput(offset + 4 <= text.length)
                                val digits = text.substring(offset, offset + 4)
                                offset += 4
                                checkInput(digits.all { it.digitToIntOrNull(16) != null })
                                digits.toInt(16).toChar()
                            }
                            else -> invalid()
                        })
                    }
                    else -> { checkInput(character.code >= 0x20); append(character) }
                }
            }
            invalid()
        }
    }

    private fun numberValue(): Long {
        val start = offset
        if (text.getOrNull(offset) == '-') offset++
        val digits = offset
        while (text.getOrNull(offset) in '0'..'9') offset++
        checkInput(offset > digits && (text[digits] != '0' || offset == digits + 1))
        return text.substring(start, offset).toLongOrNull() ?: invalid()
    }

    private fun literal(expected: String, result: Any?): Any? {
        checkInput(text.startsWith(expected, offset))
        offset += expected.length
        return result
    }

    private fun consume(character: Char): Boolean {
        whitespace()
        if (text.getOrNull(offset) != character) return false
        offset++
        return true
    }

    private fun expect(character: Char) { checkInput(consume(character)) }
    private fun whitespace() {
        while (offset < text.length) {
            when (text[offset]) { ' ', '\t', '\r', '\n' -> offset++; else -> return }
        }
    }
    private fun checkInput(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw IllegalArgumentException("query snapshot JSON is malformed")
}
