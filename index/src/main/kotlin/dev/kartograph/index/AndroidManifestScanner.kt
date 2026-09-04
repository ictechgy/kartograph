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
            when (reader.localName) {
                "activity-alias" -> listOf(reader.requiredClassReference("targetActivity", namespace, sourcePath))
                in COMPONENT_ELEMENTS -> reader.getAttributeValue(ANDROID_NAMESPACE, "name")
                    ?.let { declaredName ->
                        listOf(declaredName.toReference(namespace, sourcePath, reader.location.lineNumber))
                    }
                "meta-data" -> METADATA_CLASS_ATTRIBUTES.mapNotNull { attribute ->
                    reader.getAttributeValue(ANDROID_NAMESPACE, attribute)
                        ?.toMetadataReference(namespace, sourcePath, reader.location.lineNumber)
                }.distinctBy(ManifestReference::qualifiedName)
                else -> null
            }
        }.flatten().map { reference ->
            RetentionEvidence(
                nodeId = JvmNodeId.classId(reference.qualifiedName.replace('.', '/')),
                reason = RetentionReason.MANIFEST_COMPONENT,
                location = xmlValueLocation(sourcePath, manifest, reference.declaredName, reference.endLine),
            )
        }
    }

    private fun XMLStreamReader.requiredClassReference(
        attribute: String,
        namespace: String,
        sourcePath: String,
    ): ManifestReference {
        val declaredName = getAttributeValue(ANDROID_NAMESPACE, attribute)
            ?: throw AndroidResourceScanningException(
                "activity-alias is missing android:targetActivity at $sourcePath:${location.lineNumber}",
            )
        return declaredName.toReference(namespace, sourcePath, location.lineNumber)
    }

    private fun String.toReference(namespace: String, sourcePath: String, lineNumber: Int): ManifestReference =
        ManifestReference(qualify(namespace, sourcePath, lineNumber), this, lineNumber)

    private fun String.toMetadataReference(
        namespace: String,
        sourcePath: String,
        lineNumber: Int,
    ): ManifestReference? {
        val candidate = substringAfterLast(':')
        if (!candidate.startsWith('.') && '.' !in candidate) return null
        return candidate.toReference(namespace, sourcePath, lineNumber)
    }

    private fun String.qualify(namespace: String, sourcePath: String, lineNumber: Int): String {
        if (contains("\${")) {
            throw AndroidResourceScanningException(
                "manifest contains an unresolved class placeholder at $sourcePath:$lineNumber",
            )
        }
        if (isBlank()) {
            throw AndroidResourceScanningException(
                "manifest component class name is blank at $sourcePath:$lineNumber",
            )
        }
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
        val METADATA_CLASS_ATTRIBUTES = listOf("name", "value")
        val COMPONENT_ELEMENTS = setOf("activity", "application", "instrumentation", "provider", "receiver", "service")
    }
}
