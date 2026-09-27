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

/**
 * XML 근거 위치를 project 기준 상대 경로로 만든다.
 *
 * Gradle이 선언한 build 디렉터리를 project 밖으로 옮긴 경우(사용자 저장소를 깨끗하게 두는 CI·외부 capture) merged
 * manifest와 생성 resource는 project 밖에 생긴다. 이 파일은 같은 project의 build 출력이므로 [buildRoot]를 받으면
 * 관례 위치와 같은 `build/<build 디렉터리 기준 상대 경로>`로 기록한다. 그래서 build 위치를 옮겨도 근거 위치가 같고
 * 절대 경로를 결과에 남기지 않는다. [buildRoot]가 없거나 그 밖의 파일은 기존처럼 거부한다.
 *
 * @param projectRoot 근거 위치의 기준인 project 디렉터리
 * @param sourceFile 읽을 XML 파일
 * @param buildRoot project 밖일 수 있는 선언된 build 디렉터리. project 안이면 project 기준 경로가 먼저 쓰인다
 * @return `/` 구분자의 이동 가능한 상대 경로
 */
internal fun projectRelativePath(projectRoot: Path, sourceFile: Path, buildRoot: Path? = null): String {
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
    if (realSource.startsWith(realRoot)) return realRoot.relativize(realSource).toString().replace('\\', '/')
    val realBuild = buildRoot?.takeIf { Files.isDirectory(it) }?.let { root ->
        try {
            root.toRealPath()
        } catch (error: IOException) {
            throw AndroidResourceScanningException("build directory cannot be resolved", error)
        }
    }
    if (realBuild == null || !realSource.startsWith(realBuild)) {
        throw AndroidResourceScanningException("Android XML is outside the project root and its build directory")
    }
    return (listOf(BUILD_DIRECTORY_ALIAS) + realBuild.relativize(realSource).map { it.toString() }).joinToString("/")
}

/** project 밖 build 디렉터리의 근거 위치에 붙이는 관례 이름이다. 기본 Gradle 배치의 상대 경로와 같다. */
internal const val BUILD_DIRECTORY_ALIAS: String = "build"

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

internal fun secureXmlInputFactory(): XMLInputFactory = XMLInputFactory.newFactory().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
    xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ ->
        throw XMLStreamException("external XML entities are disabled")
    }
}
