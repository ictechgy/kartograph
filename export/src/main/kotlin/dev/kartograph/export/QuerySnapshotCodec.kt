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
    val processorGenerations: List<dev.kartograph.core.ProcessorGeneration> = emptyList(),
    val processorOutputs: List<dev.kartograph.core.ProcessorOutputs> = emptyList(),
) {
    init {
        require(revision == null || Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}").matches(revision)) { "snapshot revision must be a full commit hash" }
        require(scope == null || (scope.length <= 200 && Regex("[A-Za-z0-9_.:-]+").matches(scope))) { "snapshot scope must be a portable project and variant label" }
        require(processorOutputs.size <= 256 && processorOutputs.all { it.scope == scope }) { "processor outputs require the matching snapshot scope" }
    }
}

/** 원본 파일을 다시 읽지 않아도 같은 도달성 의미로 질의할 수 있는 버전 문서 codec이다. */
public object QuerySnapshotCodec {
    /** 일반 code-graph JSON과 보존 문맥 없는 파일을 구분한다. */
    public const val FORMAT: String = "kartograph-query-snapshot"
    /** 저장 snapshot의 호환 기본 상한이며 opt-in하지 않은 호출에 적용한다. */
    public const val DEFAULT_MAX_MIB: Int = 64
    /** 명시적 저장 snapshot 상한이 넘을 수 없는 절대 경계다. */
    public const val MAX_MIB: Int = 128
    /** 한 번에 읽는 질의 문서의 상한이다. */
    public const val MAX_BYTES: Int = DEFAULT_MAX_MIB * 1024 * 1024

    /** CLI/adapter의 MiB 설정을 overflow 없는 byte 상한으로 검증한다. */
    public fun maximumBytes(maximumMiB: Int): Int {
        require(maximumMiB in 1..MAX_MIB) { "snapshot maximum must be 1..128 MiB" }
        return maximumMiB * 1024 * 1024
    }

    /** 정렬된 그래프 사실과 보존 근거를 출력하며 로컬 절대경로는 내보내지 않는다. */
    public fun render(snapshot: QuerySnapshot): String = render(snapshot, false, MAX_BYTES)

    /** v2는 간선의 반복 USR을 node 배열 위치로 저장하며 모든 사실과 보존 문맥을 유지한다. */
    public fun render(snapshot: QuerySnapshot, compact: Boolean): String = render(snapshot, compact, MAX_BYTES)

    private fun renderContent(snapshot: QuerySnapshot, compact: Boolean): String {
        val positions = if (compact) snapshot.graph.nodeIds.withIndex().associate { it.value to it.index } else emptyMap()
        val encoding = if (compact) CompactSnapshotGraph(positions.mapKeys { it.key.value }) else null
        return jsonValue(sortedMapOf(
        "format" to FORMAT,
        "version" to if (compact) 2 else 1,
        "toolVersion" to snapshot.toolVersion,
        "revision" to snapshot.revision, "scope" to snapshot.scope,
        "provenance" to snapshot.provenance?.let(BuildWitnessCodec::provenanceValue),
        "processorOutputs" to snapshot.processorOutputs.sortedWith(compareBy({ it.kind }, { it.processor }, { it.processorArtifact }, { it.rawSha256 })).map(ProcessorOutputCodec::value),
        "processorGenerations" to snapshot.processorGenerations.sortedWith(compareBy({ it.processor }, { it.artifactSha256 })).map { generation ->
            sortedMapOf("processor" to generation.processor, "artifactSha256" to generation.artifactSha256,
                "sources" to generation.sources.sortedBy { it.path }.map { sortedMapOf("path" to it.path, "sha256" to it.sha256) })
        },
        "includePrivateMembers" to snapshot.includePrivateMembers,
        "limitations" to snapshot.limitations.distinct().sorted(),
        "suppressed" to snapshot.suppressed.map { it.value }.sorted(),
        "retention" to snapshot.retention.distinct().sortedWith(compareBy({ it.nodeId }, { it.reason.name },
            { it.location?.path.orEmpty() }, { it.location?.line ?: 0 }, { it.location?.column ?: 0 })).map { item ->
            sortedMapOf("nodeId" to item.nodeId.value, "reason" to item.reason.name.lowerCamel(), "location" to locationValue(item.location))
                .let { value -> item.externalBridge?.let { (value + ("externalBridge" to ExternalRetentionCodec.evidenceValue(it))).toSortedMap() } ?: value }
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

    /** 저장 전에 선택한 byte 상한을 적용하며 문서 schema와 정렬은 바꾸지 않는다. */
    public fun render(snapshot: QuerySnapshot, compact: Boolean, maximumBytes: Int): String =
        renderContent(snapshot, compact).also { requireMaximum(it, maximumBytes) }

    /** 불완전한 그래프를 정상 결과로 처리하지 않도록 타입·중복·참조 대상을 조립 전에 검증한다. */
    public fun parse(content: String): QuerySnapshot = parse(content, MAX_BYTES)

    /** 파일 adapter가 이미 적용한 같은 raw byte 상한을 direct String 호출에도 적용한다. */
    public fun parse(content: String, maximumBytes: Int): QuerySnapshot {
        requireMaximum(content, maximumBytes)
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
            RetentionEvidence(NodeId(string(item["nodeId"])), enumValue(item["reason"]), location(item["location"]),
                item["externalBridge"]?.let { ExternalRetentionCodec.evidence(objectValue(it)) })
        }
        require(retention.all { it.reason != RetentionReason.EXTERNAL_BRIDGE || it.nodeId in ids }) {
            "query snapshot contains unmatched external bridge identities; recapture the snapshot"
        }
        val suppressed = strings(document["suppressed"]).map(::NodeId).toSet()
        require(suppressed.all(ids::contains)) { "query snapshot contains invalid baseline references" }
        return QuerySnapshot(CodeGraph(nodes, edges, calls, providers), retention, strings(document["limitations"]), suppressed,
            boolean(document["includePrivateMembers"]), string(document["toolVersion"]),
            optionalString(document["revision"]), optionalString(document["scope"]),
            document["provenance"]?.let(BuildWitnessCodec::provenance),
            document["processorGenerations"]?.let { values -> list(values).map { raw ->
                val generation = objectValue(raw)
                dev.kartograph.core.ProcessorGeneration(string(generation["processor"]), string(generation["artifactSha256"]),
                    list(generation["sources"]).map { source -> objectValue(source).let {
                        dev.kartograph.core.CompilerEvidenceSource(string(it["path"]), string(it["sha256"]))
                    } })
            } }.orEmpty(),
            document["processorOutputs"]?.let { values -> list(values).map { ProcessorOutputCodec.observation(it) } }.orEmpty())
    }

    /** UTF-8 byte 배열을 추가로 만들지 않고 저장 문서의 explicit 상한을 검증한다. */
    public fun requireMaximum(content: String, maximumBytes: Int) {
        require(maximumBytes in 1..QuerySnapshotCodec.maximumBytes(MAX_MIB)) {
            "query snapshot byte maximum must be between 1 byte and 128 MiB"
        }
        if (!utf8BytesAtMost(content, maximumBytes)) throw QuerySnapshotSizeException()
    }

    /** String을 다시 byte 배열로 복사하지 않고 UTF-8 크기 상한을 조기에 판정한다. */
    private fun utf8BytesAtMost(content: String, maximumBytes: Int): Boolean {
        var bytes = 0L
        var index = 0
        while (index < content.length) {
            val character = content[index]
            bytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                Character.isHighSurrogate(character) && content.getOrNull(index + 1)?.let { Character.isLowSurrogate(it) } == true -> {
                    index++
                    4
                }
                Character.isSurrogate(character) -> 1
                else -> 3
            }
            if (bytes > maximumBytes) return false
            index++
        }
        return true
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

/** 선택한 저장 snapshot byte 상한을 넘었음을 다른 문서 검증 오류와 구분한다. */
public class QuerySnapshotSizeException internal constructor() : IllegalArgumentException("query snapshot is too large")

/** 문서 밖의 입력을 오류 문구에 넣지 않는, 깊이와 크기가 제한된 JSON reader다. */
internal class SnapshotJsonParser(private val text: String, private val generalNumbers: Boolean = false) {
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

    private fun numberValue(): Number {
        val start = offset
        if (text.getOrNull(offset) == '-') offset++
        val digits = offset
        while (text.getOrNull(offset) in '0'..'9') offset++
        checkInput(offset > digits && (text[digits] != '0' || offset == digits + 1))
        checkInput(offset - start <= MAX_NUMBER_CHARACTERS)
        if (!generalNumbers) return text.substring(start, offset).toLongOrNull() ?: invalid()
        var decimal = false
        if (text.getOrNull(offset) == '.') {
            decimal = true
            offset++
            val fraction = offset
            while (text.getOrNull(offset) in '0'..'9') offset++
            checkInput(offset > fraction)
        }
        if (text.getOrNull(offset) == 'e' || text.getOrNull(offset) == 'E') {
            decimal = true
            offset++
            if (text.getOrNull(offset) == '+' || text.getOrNull(offset) == '-') offset++
            val exponent = offset
            var magnitude = 0
            while (text.getOrNull(offset) in '0'..'9') {
                val digit = text[offset++].digitToInt()
                checkInput(magnitude <= (MAX_EXPONENT_MAGNITUDE - digit) / 10)
                magnitude = magnitude * 10 + digit
            }
            checkInput(offset > exponent)
        }
        checkInput(offset - start <= MAX_NUMBER_CHARACTERS)
        val number = text.substring(start, offset)
        return if (decimal) number.toBigDecimalOrNull() ?: invalid()
            else number.toLongOrNull() ?: number.toBigIntegerOrNull() ?: invalid()
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

    private companion object {
        const val MAX_NUMBER_CHARACTERS = 128
        const val MAX_EXPONENT_MAGNITUDE = 10_000
    }
}
