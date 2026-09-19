package dev.kartograph.index

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/**
 * 사용자가 제공한 런타임 근거를 관측된 JVM internal class 이름으로 정규화한다.
 * 커버리지를 수집하거나 실행하지 않으며, 입력 파일을 그대로 읽기만 한다.
 */
public class RuntimeEvidenceScanner {
    /** class list와 JaCoCo/Kover XML 리포트를 읽어 중복 없는 internal class 이름을 정렬해 반환한다. */
    public fun scan(classLists: Iterable<Path>, coverageReports: Iterable<Path>): Set<String> {
        val observed = sortedSetOf<String>()
        classLists.forEach { classList -> observed += readClassList(classList) }
        coverageReports.forEach { report -> observed += readCoverageReport(report) }
        return observed
    }

    private fun readClassList(classList: Path): List<String> {
        if (!classList.isRegularFile()) {
            throw RuntimeEvidenceScanningException("runtime class list does not exist or is not a file")
        }
        val lines = try {
            Files.readAllLines(classList)
        } catch (error: IOException) {
            throw RuntimeEvidenceScanningException("runtime class list cannot be read", error)
        }
        return lines.mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) null else normalizeClassName(line, index + 1)
        }
    }

    private fun normalizeClassName(value: String, lineNumber: Int): String {
        val candidate = value.removeSuffix(".class")
        if (candidate.isEmpty() || candidate.startsWith('/') || candidate.endsWith('/') ||
            candidate.contains("//") || candidate.any(Char::isWhitespace) || '#' in candidate
        ) {
            throw RuntimeEvidenceScanningException("runtime class list has an invalid class name at line $lineNumber")
        }
        return candidate.replace('.', '/')
    }

    private fun readCoverageReport(report: Path): List<String> {
        if (!report.isRegularFile()) {
            throw RuntimeEvidenceScanningException("runtime coverage report does not exist or is not a file")
        }
        return try {
            report.inputStream().use { input ->
                val reader = secureXmlInputFactory().createXMLStreamReader(input)
                try {
                    readCoverageClasses(reader)
                } finally {
                    reader.close()
                }
            }
        } catch (error: IOException) {
            throw RuntimeEvidenceScanningException("runtime coverage report cannot be read", error)
        } catch (error: XMLStreamException) {
            throw RuntimeEvidenceScanningException(
                "runtime coverage report is invalid or uses a prohibited external entity",
                error,
            )
        }
    }

    private fun readCoverageClasses(reader: XMLStreamReader): List<String> {
        val observed = mutableSetOf<String>()
        var currentClass: String? = null
        var rootSeen = false
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (!rootSeen) {
                        rootSeen = true
                        if (reader.localName != "report") {
                            throw RuntimeEvidenceScanningException(
                                "runtime coverage report is not a JaCoCo/Kover XML report",
                            )
                        }
                    }
                    when (reader.localName) {
                        "class" -> currentClass = reader.classNameAt()
                        "counter" -> {
                            val className = currentClass
                            if (className != null && reader.coveredCount() > 0) observed += className
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "class") currentClass = null
            }
        }
        if (!rootSeen) throw RuntimeEvidenceScanningException("runtime coverage report is empty")
        return observed.toList()
    }

    private fun XMLStreamReader.classNameAt(): String {
        val name = getAttributeValue(null, "name")
        if (name.isNullOrBlank()) {
            throw RuntimeEvidenceScanningException("runtime coverage report has a class without a name")
        }
        return name.replace('.', '/')
    }

    private fun XMLStreamReader.coveredCount(): Long {
        val value = getAttributeValue(null, "covered")
            ?: throw RuntimeEvidenceScanningException("runtime coverage report has a counter without a covered count")
        return value.toLongOrNull()
            ?: throw RuntimeEvidenceScanningException("runtime coverage report has a non-numeric covered count")
    }
}
