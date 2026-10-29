package dev.kartograph.cli

import dev.kartograph.core.CodeGraph
import dev.kartograph.core.RetentionEvidence
import dev.kartograph.export.ExternalRetentionCaveats
import dev.kartograph.export.ExternalRetentionCodec
import dev.kartograph.export.ExternalRetentionDocument
import dev.kartograph.export.ExternalRetentionDocumentContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** 문서 파일을 제한해 읽고 dead·capture가 같은 전체 ID 일치 계약을 사용하게 한다. */
internal object ExternalRetentionFiles {
    private const val MAXIMUM = 16 * 1024 * 1024

    fun read(path: Path): ExternalRetentionDocument {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && before.size() <= MAXIMUM) { "external retention input must be a regular file no larger than 16 MiB" }
        // JDK의 portable open에는 O_NONBLOCK 옵션이 없다. 검사 직후의 악의적인 FIFO 교체에 대한 atomicity는 주장하지 않는다.
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM + 1) }
        require(bytes.size <= MAXIMUM) { "external retention input is too large; narrow the inputs" }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && bytes.size.toLong() == after.size()) {
            "external retention input changed while reading; retry with a stable completed document"
        }
        return ExternalRetentionCodec.parseDocument(bytes)
    }

    fun requireMatches(graph: CodeGraph, evidence: List<RetentionEvidence>, contexts: List<ExternalRetentionDocumentContext>) {
        if (!evidence.all { graph.contains(it.nodeId) }) throw ExternalRetentionInputException(
            "external retention identifiers are absent from the indexed graph; regenerate facts and retentions from the same build" +
                ExternalRetentionCaveats.messages(contexts).joinToString(separator = "; ", prefix = if (contexts.isEmpty()) "" else "; ")
        )
    }
}

/** 검증된 문맥의 제한된 진단만 CLI 실패 경로로 전달한다. */
internal class ExternalRetentionInputException(message: String) : IllegalArgumentException(message)
