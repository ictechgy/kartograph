package dev.kartograph.index

import dev.kartograph.core.SourceLocation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream

internal fun <T : Any> readXml(xmlFile: Path, readElement: (XMLStreamReader) -> T?): List<T> = try {
    xmlFile.inputStream().use { input ->
        val reader = secureXmlInputFactory().createXMLStreamReader(input)
        try {
            buildList {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) readElement(reader)?.let(::add)
                }
            }
        } finally {
            reader.close()
        }
    }
} catch (error: IOException) {
    throw AndroidResourceScanningException("Android XML cannot be read", error)
} catch (error: XMLStreamException) {
    throw AndroidResourceScanningException("Android XML is invalid or uses a prohibited external entity", error)
}

internal fun projectRelativePath(projectRoot: Path, sourceFile: Path): String {
    val realRoot = try {
        projectRoot.toRealPath()
    } catch (error: IOException) {
        throw AndroidResourceScanningException("project root cannot be resolved", error)
    }
    val realSource = try {
        sourceFile.toRealPath()
    } catch (error: IOException) {
        throw AndroidResourceScanningException("Android XML cannot be resolved", error)
    }
    if (!realSource.startsWith(realRoot)) {
        throw AndroidResourceScanningException("Android XML is outside the project root")
    }
    return realRoot.relativize(realSource).toString().replace('\\', '/')
}

internal fun xmlSourceLines(xmlFile: Path): List<String> = try {
    Files.readAllLines(xmlFile)
} catch (error: IOException) {
    throw AndroidResourceScanningException("Android XML cannot be read", error)
}

internal fun xmlValueLocation(sourcePath: String, sourceLines: List<String>, value: String, endLine: Int): SourceLocation {
    val endIndex = (endLine - 1).coerceAtMost(sourceLines.lastIndex)
    val valueLine = (endIndex downTo 0).firstOrNull { index ->
        val sourceLine = sourceLines[index]
        value in sourceLine || '<' in sourceLine
    }?.takeIf { index -> value in sourceLines[index] }
    return SourceLocation(sourcePath, line = valueLine?.plus(1) ?: endLine)
}

private fun secureXmlInputFactory(): XMLInputFactory = XMLInputFactory.newFactory().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
    xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ ->
        throw XMLStreamException("external XML entities are disabled")
    }
}
