package dev.kartograph.index

import dev.kartograph.core.CompilerEvidence
import dev.kartograph.core.CompilerEvidenceSource
import dev.kartograph.core.CompilerReference
import dev.kartograph.core.CompilerReferenceKind
import dev.kartograph.core.NodeId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** 한도가 있는 compiler 원시 문서를 읽는다. 해석 성공과 빌드 증명은 별도 단계다. */
public object CompilerEvidenceReader {
    public const val MAX_BYTES: Int = 16 * 1024 * 1024
    public const val MAX_ROWS: Int = 200_000

    /** 파일이 늘어나거나 손상돼도 무제한 읽기나 부분 문서 수락으로 바꾸지 않는다. */
    public fun read(file: Path): CompilerEvidence {
        require(Files.isRegularFile(file) && !Files.isSymbolicLink(file)) { "compiler evidence must be a regular file" }
        val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "compiler evidence exceeds the byte limit" }
        return parse(utf8(bytes))
    }

    /** 버전·행 종류·개수·중복·인코딩을 검증하며 미지원 필드를 조용히 무시하지 않는다. */
    public fun parse(text: String): CompilerEvidence {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "compiler evidence exceeds the byte limit" }
        val rows = text.lineSequence().filter(String::isNotEmpty).take(MAX_ROWS + 1).toList()
        require(rows.size <= MAX_ROWS) { "compiler evidence exceeds the row limit" }
        require(rows.firstOrNull() == "format\tkartograph-compiler-evidence\t1") { "unsupported compiler evidence format" }
        val headers = mutableMapOf<String, String>()
        val sources = mutableListOf<CompilerEvidenceSource>()
        val references = mutableListOf<CompilerReference>()
        for (row in rows.drop(1)) {
            val fields = row.split('\t')
            when (fields[0]) {
                "collector", "compiler", "token", "artifact", "unmapped" -> {
                    require(fields.size == 2 && fields[0] !in headers) { "invalid compiler evidence header" }
                    headers[fields[0]] = fields[1]
                }
                "source" -> {
                    require(fields.size == 3) { "invalid compiler source inventory row" }
                    sources += CompilerEvidenceSource(decode(fields[1]), fields[2])
                }
                "edge" -> {
                    require(fields.size == 4) { "invalid compiler reference row" }
                    val kind = when (fields[3]) {
                        "constant" -> CompilerReferenceKind.CONSTANT
                        "binding" -> CompilerReferenceKind.BINDING
                        else -> throw IllegalArgumentException("unsupported compiler reference kind")
                    }
                    references += CompilerReference(NodeId(decode(fields[1])), NodeId(decode(fields[2])), kind)
                }
                else -> throw IllegalArgumentException("unsupported compiler evidence row")
            }
        }
        require(headers.keys == setOf("collector", "compiler", "token", "artifact", "unmapped")) { "incomplete compiler evidence headers" }
        val unmapped = headers.getValue("unmapped").toIntOrNull()
            ?: throw IllegalArgumentException("invalid unmapped compiler reference count")
        return CompilerEvidence(headers.getValue("collector"), headers.getValue("compiler"), headers.getValue("token"),
            headers.getValue("artifact"), sources.sortedBy { it.path }, unmapped,
            references.distinct().sortedWith(compareBy({ it.source }, { it.target }, { it.kind.name })))
    }

    private fun decode(value: String): String {
        val bytes = try { Base64.getUrlDecoder().decode(value) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid compiler evidence encoding")
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "noncanonical compiler evidence encoding" }
        return utf8(bytes).also { require(it.isNotEmpty() && it.none { char -> char.code < 32 }) { "invalid compiler evidence value" } }
    }

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("compiler evidence is not valid UTF-8")
    }
}
