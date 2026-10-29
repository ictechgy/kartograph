package dev.kartograph.index

import dev.kartograph.core.SourceLocation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader
import kotlin.io.path.inputStream

internal fun <T : Any> readXml(xmlFile: Path, readElement: (XMLStreamReader) -> T?): List<T> = try {
    xmlFile.inputStream().use { input -> parseXml(secureXmlInputFactory(), input, readElement) }
} catch (error: IOException) {
    throw AndroidResourceScanningException("Android XML cannot be read", error)
} catch (error: XMLStreamException) {
    throw AndroidResourceScanningException("Android XML is invalid or uses a prohibited external entity", error)
}

/**
 * 이미 읽은 XML 바이트를 호출자가 공유하는 [factory]로 읽는다.
 *
 * 같은 resource 트리의 XML 여러 개를 읽는 스캐너가 파일마다 factory를 새로 만들지 않고, 줄 위치용으로 읽은 파일을 다시 열지도
 * 않게 한다. [factory]는 [secureXmlInputFactory]로 만든 것이어야 DTD·외부 entity 거부가 유지된다.
 */
internal fun <T : Any> readXml(content: ByteArray, factory: XMLInputFactory, readElement: (XMLStreamReader) -> T?): List<T> = try {
    parseXml(factory, ByteArrayInputStream(content), readElement)
} catch (error: XMLStreamException) {
    throw AndroidResourceScanningException("Android XML is invalid or uses a prohibited external entity", error)
}

/** 시작 요소마다 [readElement]를 불러 null이 아닌 결과를 모은다. reader는 끝나면 닫는다. */
private fun <T : Any> parseXml(factory: XMLInputFactory, input: InputStream, readElement: (XMLStreamReader) -> T?): List<T> {
    val reader = factory.createXMLStreamReader(input)
    try {
        return buildList {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) readElement(reader)?.let(::add)
            }
        }
    } finally {
        reader.close()
    }
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
internal fun projectRelativePath(projectRoot: Path, sourceFile: Path, buildRoot: Path? = null): String =
    projectRelativePath(realProjectRoot(projectRoot), sourceFile, lazy { realBuildRoot(buildRoot) })

/**
 * [projectRelativePath]와 같은 규칙이지만 root의 realpath를 호출자가 풀어 넘긴다.
 *
 * 같은 project의 XML 여러 개를 읽는 스캐너가 파일마다 root를 다시 풀지 않게 한다. build root는 project 밖 파일을 처음 만날 때
 * 한 번만 풀도록 [Lazy]로 받는다 — 파일마다 풀던 때와 오류가 나는 경우가 같다.
 *
 * @param realRoot [realProjectRoot]로 푼 project root
 * @param realBuild [realBuildRoot]로 푸는 선언된 build 디렉터리
 */
internal fun projectRelativePath(realRoot: Path, sourceFile: Path, realBuild: Lazy<Path?>): String {
    val realSource = try {
        sourceFile.toRealPath()
    } catch (error: IOException) {
        throw AndroidResourceScanningException("Android XML cannot be resolved", error)
    }
    if (realSource.startsWith(realRoot)) return realRoot.relativize(realSource).toString().replace('\\', '/')
    val build = realBuild.value
    if (build == null || !realSource.startsWith(build)) {
        throw AndroidResourceScanningException("Android XML is outside the project root and its build directory")
    }
    return (listOf(BUILD_DIRECTORY_ALIAS) + build.relativize(realSource).map { it.toString() }).joinToString("/")
}

/** XML 근거 위치의 기준인 project root의 realpath다. 풀 수 없으면 사용 가능한 오류로 알린다. */
internal fun realProjectRoot(projectRoot: Path): Path = try {
    projectRoot.toRealPath()
} catch (error: IOException) {
    throw AndroidResourceScanningException("project root cannot be resolved", error)
}

/** 선언된 build 디렉터리의 realpath다. 없거나 디렉터리가 아니면 null이고, 풀 수 없으면 오류로 알린다. */
internal fun realBuildRoot(buildRoot: Path?): Path? = buildRoot?.takeIf { Files.isDirectory(it) }?.let { root ->
    try {
        root.toRealPath()
    } catch (error: IOException) {
        throw AndroidResourceScanningException("build directory cannot be resolved", error)
    }
}

/** project 밖 build 디렉터리의 근거 위치에 붙이는 관례 이름이다. 기본 Gradle 배치의 상대 경로와 같다. */
internal const val BUILD_DIRECTORY_ALIAS: String = "build"

internal fun xmlSourceLines(xmlFile: Path): List<String> = try {
    Files.readAllLines(xmlFile)
} catch (error: IOException) {
    throw AndroidResourceScanningException("Android XML cannot be read", error)
}

/** XML 파일의 바이트를 한 번 읽는다. 줄 목록([xmlSourceLines])과 StAX 입력([readXml])이 같은 바이트를 쓴다. */
internal fun readXmlBytes(xmlFile: Path): ByteArray = try {
    Files.readAllBytes(xmlFile)
} catch (error: IOException) {
    throw AndroidResourceScanningException("Android XML cannot be read", error)
}

/**
 * 이미 읽은 XML 바이트를 [Files.readAllLines]와 같은 규칙으로 줄 목록으로 만든다.
 *
 * 같은 엄격한 UTF-8 decoder(잘못된 바이트는 오류)와 [java.io.BufferedReader.readLine]을 써서 `\n`·`\r`·`\r\n`에서 끊고, 끝
 * 줄바꿈 뒤에는 빈 줄을 만들지 않는다. 줄 위치를 쓰지 않는 파일도 반드시 거친다 — UTF-8이 아닌 XML을 조용히 통과시키지 않는다.
 */
internal fun xmlSourceLines(content: ByteArray): List<String> = try {
    InputStreamReader(ByteArrayInputStream(content), StandardCharsets.UTF_8.newDecoder()).buffered().use { it.readLines() }
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
