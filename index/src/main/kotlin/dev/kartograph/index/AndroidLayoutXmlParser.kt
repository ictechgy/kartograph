package dev.kartograph.index

import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidLayoutInclude
import dev.kartograph.core.AndroidLayoutIncludeKind
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.CompilerEvidenceSource
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

/** 입력 바이트의 해시와 XML 관측이다. root inventory나 producer admission을 증명하지 않는다. */
public class AndroidLayoutXmlObservation internal constructor(
    public val definition: AndroidLayoutDefinition,
    includes: List<AndroidLayoutInclude>,
) {
    public val includes: List<AndroidLayoutInclude> = Collections.unmodifiableList(ArrayList(includes))
}

/**
 * 호출자가 준 XML 바이트만 읽는 순수 parser다. 경로를 열거나 include target을 재귀 탐색하지 않는다.
 * 파일 시스템 capture는 안전한 root 순회와 producer 대응을 별도로 검증해야 한다.
 */
public object AndroidLayoutXmlParser {
    public const val MAX_XML_BYTES: Int = 2 * 1024 * 1024
    public const val MAX_DEPTH: Int = 128
    public const val MAX_ELEMENTS: Int = 100_000
    public const val MAX_ATTRIBUTES: Int = 10_000
    public const val MAX_INCLUDES: Int = 10_000

    public fun parse(
        key: AndroidResourceKey,
        rootId: String,
        sourcePath: String,
        relativePath: String,
        qualifier: String,
        bytes: ByteArray,
    ): AndroidLayoutXmlObservation {
        require(bytes.size <= MAX_XML_BYTES) { "layout XML exceeds byte limit" }
        // 호출자 배열을 그대로 읽지 않는다. 해시와 parser는 이 복사본 하나를 공유한다.
        val retained = bytes.copyOf()
        val sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(retained))
        val definition = AndroidLayoutDefinition(key, rootId, CompilerEvidenceSource(sourcePath, sha256), relativePath, qualifier)
        val includes = ArrayList<AndroidLayoutInclude>()
        try {
            ByteArrayInputStream(retained).use { input ->
                val reader = secureXmlInputFactory().createXMLStreamReader(input)
                try {
                    var depth = 0
                    var elements = 0
                    var attributes = 0
                    while (reader.hasNext()) {
                        when (reader.next()) {
                            XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
                                throw XMLStreamException("DTD and entity references are prohibited")
                            XMLStreamConstants.START_ELEMENT -> {
                                depth++
                                require(depth <= MAX_DEPTH) { "layout XML exceeds nesting limit" }
                                require(++elements <= MAX_ELEMENTS) { "layout XML exceeds element limit" }
                                require(reader.attributeCount <= MAX_ATTRIBUTES - attributes) { "layout XML exceeds attribute limit" }
                                require(reader.namespaceCount <= MAX_ATTRIBUTES - attributes - reader.attributeCount) { "layout XML exceeds attribute and namespace limit" }
                                attributes += reader.attributeCount + reader.namespaceCount
                                if (reader.localName == "include" && reader.namespaceURI.isNullOrEmpty()) {
                                    require(includes.size < MAX_INCLUDES) { "layout XML exceeds include limit" }
                                    val layout = (0 until reader.attributeCount).singleOrNull { index ->
                                        reader.getAttributeLocalName(index) == "layout" && reader.getAttributeNamespace(index).isNullOrEmpty()
                                    }?.let(reader::getAttributeValue)
                                    val (kind, target) = classify(layout, key.namespace)
                                    includes += AndroidLayoutInclude(definition.id, includes.size, kind, target)
                                }
                            }
                            XMLStreamConstants.END_ELEMENT -> depth--
                        }
                    }
                } finally {
                    reader.close()
                }
            }
        } catch (error: XMLStreamException) {
            // parser의 원문/시스템 ID를 메시지에 싣지 않는다.
            throw AndroidResourceScanningException("layout XML is invalid or uses prohibited DTD/entities", error)
        }
        return AndroidLayoutXmlObservation(definition, includes)
    }

    private fun classify(value: String?, namespace: String): Pair<AndroidLayoutIncludeKind, AndroidResourceKey?> {
        val literal = value?.let(LITERAL::matchEntire)
        if (literal != null) {
            val targetNamespace = literal.groupValues[1].ifEmpty { namespace }
            val target = AndroidResourceKey(targetNamespace, "layout", literal.groupValues[2])
            val kind = if (targetNamespace == namespace) AndroidLayoutIncludeKind.LITERAL else AndroidLayoutIncludeKind.EXTERNAL_NAMESPACE
            return kind to target
        }
        val kind = if (value != null && (value.startsWith("@{") || value.startsWith("@={") || value.startsWith("?")))
            AndroidLayoutIncludeKind.DYNAMIC else AndroidLayoutIncludeKind.UNSUPPORTED
        return kind to null
    }

    private val LITERAL = Regex("@(?:([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*):)?layout/([a-z_][a-z0-9_]*)")
}
