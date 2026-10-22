package dev.kartograph.index

import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader

/**
 * Android resource XML의 custom view와 component class 참조를 읽는다.
 *
 * @property projectRoot 근거 위치의 기준 디렉터리
 * @property buildRoot project 밖으로 옮긴 선언된 build 디렉터리다. 그 아래 생성 resource는 `build/...`로 기록한다
 */
public class AndroidXmlScanner @JvmOverloads constructor(
    private val projectRoot: Path,
    private val buildRoot: Path? = null,
) {
    /** resource root 아래 XML을 정렬 탐색해 JVM class ID와 파일·줄 근거를 반환한다. */
    public fun scan(resourceRoot: Path): List<RetentionEvidence> {
        if (!resourceRoot.isDirectory()) {
            throw AndroidResourceScanningException("Android resource root does not exist")
        }
        // root realpath와 XML factory는 스캔 한 번에 하나씩만 만든다 — 파일마다 다시 풀고 만들면 resource 수만큼 비용이 는다.
        // 모두 처음 필요할 때 만들어 파일마다 만들던 때와 오류 순서가 같다. 스캔은 단일 스레드라 factory를 공유해도 된다.
        val realRoot by lazy(LazyThreadSafetyMode.NONE) { realProjectRoot(projectRoot) }
        val realBuild = lazy(LazyThreadSafetyMode.NONE) { realBuildRoot(buildRoot) }
        val factory = lazy(LazyThreadSafetyMode.NONE, ::secureXmlInputFactory)
        return discoverXmlFiles(resourceRoot).flatMap { xmlFile ->
            scanFile(xmlFile, projectRelativePath(realRoot, xmlFile, realBuild), factory)
        }
    }

    /** 파일을 한 번 읽어 줄 위치용 줄 목록과 StAX 입력에 같이 쓴다. 참조가 없는 파일도 엄격한 UTF-8 디코딩을 거친다. */
    private fun scanFile(xmlFile: Path, sourcePath: String, factory: Lazy<XMLInputFactory>): List<RetentionEvidence> {
        val content = readXmlBytes(xmlFile)
        val sourceLines = xmlSourceLines(content)
        return readXml(content, factory.value) { reader ->
            val className = reader.referencedClassName() ?: return@readXml null
            XmlReference(className, reader.location.lineNumber)
        }.map { reference ->
            RetentionEvidence(
                nodeId = JvmNodeId.classId(reference.className.replace('.', '/')),
                reason = RetentionReason.XML_LAYOUT,
                location = xmlValueLocation(sourcePath, sourceLines, reference.className, reference.endLine),
            )
        }
    }

    private fun discoverXmlFiles(resourceRoot: Path): List<Path> = try {
        Files.walk(resourceRoot).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".xml") }
                .sorted()
                .toList()
        }
    } catch (error: IOException) {
        throw AndroidResourceScanningException("Android resource root cannot be read", error)
    }

    private fun XMLStreamReader.referencedClassName(): String? {
        val candidate = when {
            localName == "view" -> getAttributeValue(null, "class")
            localName == FRAGMENT_CONTAINER_VIEW ->
                getAttributeValue(ANDROID_NAMESPACE, "name")
            localName in NAMED_COMPONENT_ELEMENTS -> getAttributeValue(ANDROID_NAMESPACE, "name")
            localName.contains('.') -> localName
            else -> null
        }
        return candidate?.takeIf { name -> name.contains('.') }
    }

    private data class XmlReference(val className: String, val endLine: Int)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val FRAGMENT_CONTAINER_VIEW = "androidx.fragment.app.FragmentContainerView"
        val NAMED_COMPONENT_ELEMENTS = setOf("activity", "dialog", "fragment")
    }
}
