package dev.kartograph.index

import dev.kartograph.core.RetentionEvidence
import dev.kartograph.core.RetentionReason
import java.nio.file.Path
import javax.xml.stream.XMLStreamReader

/** Android manifest의 component class 참조를 파일·줄 근거와 함께 읽는다. */
public class AndroidManifestScanner(private val projectRoot: Path) {
    /** relative, bare, fully qualified component name을 JVM class ID로 정규화한다. */
    public fun scan(manifest: Path, namespace: String): List<RetentionEvidence> {
        require(namespace.isNotBlank()) { "Android namespace must not be blank" }
        val sourcePath = projectRelativePath(projectRoot, manifest)
        return readXml(manifest) { reader ->
            if (reader.localName !in COMPONENT_ELEMENTS) return@readXml null
            val declaredName = reader.getAttributeValue(ANDROID_NAMESPACE, "name") ?: return@readXml null
            val qualifiedName = declaredName.qualify(namespace) ?: return@readXml null
            ManifestReference(qualifiedName, declaredName, reader.location.lineNumber)
        }.map { reference ->
            RetentionEvidence(
                nodeId = JvmNodeId.classId(reference.qualifiedName.replace('.', '/')),
                reason = RetentionReason.MANIFEST_COMPONENT,
                location = xmlValueLocation(sourcePath, manifest, reference.declaredName, reference.endLine),
            )
        }
    }

    private fun String.qualify(namespace: String): String? {
        if (isBlank() || contains('$')) return null
        return when {
            startsWith('.') -> namespace + this
            contains('.') -> this
            else -> "$namespace.$this"
        }
    }

    private data class ManifestReference(
        val qualifiedName: String,
        val declaredName: String,
        val endLine: Int,
    )

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val COMPONENT_ELEMENTS = setOf("activity", "application", "instrumentation", "provider", "receiver", "service")
    }
}
