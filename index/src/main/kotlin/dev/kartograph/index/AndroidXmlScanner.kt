package dev.kartograph.index

import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import javax.xml.stream.XMLStreamReader

/** Android resource XML의 custom view와 component class 참조를 읽는다. */
public class AndroidXmlScanner(private val projectRoot: Path) {
    /** resource root 아래 XML을 정렬 탐색해 JVM class ID와 파일·줄 근거를 반환한다. */
    public fun scan(resourceRoot: Path): List<RetentionEvidence> {
        if (!resourceRoot.isDirectory()) {
            throw AndroidResourceScanningException("Android resource root does not exist")
        }
        return discoverXmlFiles(resourceRoot).flatMap { xmlFile -> scanFile(xmlFile) }
    }

    private fun scanFile(xmlFile: Path): List<RetentionEvidence> {
        val sourcePath = projectRelativePath(projectRoot, xmlFile)
        val sourceLines = xmlSourceLines(xmlFile)
        return readXml(xmlFile) { reader ->
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
