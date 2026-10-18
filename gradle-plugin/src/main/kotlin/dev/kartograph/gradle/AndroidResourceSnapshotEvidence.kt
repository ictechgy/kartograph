package dev.kartograph.gradle

import dev.kartograph.core.*
import dev.kartograph.index.*
import java.nio.file.Path

/** Gradle build 실행 경계 안의 선택적 리소스 채널이다. 일반 CLI scan에는 적용하지 않는다. */
internal object AndroidResourceSnapshotEvidence {
    data class Result(
        val facts: AndroidResourceEvidence? = null,
        val bindings: AndroidResourceBindingEvidence? = null,
        val limitations: List<String> = emptyList(),
    )

    fun capture(
        project: Path,
        scope: String,
        namespace: String,
        providerRoots: List<Path>,
        producerArtifact: String,
        provenance: SnapshotProvenance,
        indexed: IndexedClasses,
        retainedGraph: CodeGraph,
    ): Result {
        fun unavailable(reason: String, facts: AndroidResourceEvidence? = null) =
            Result(facts = facts, limitations = listOf("android-resource-bindings-unavailable: $reason"))
        val root = project.toAbsolutePath().normalize()
        if (providerRoots.size > AndroidResourceEvidence.MAX_ROOTS) return unavailable("root-limit-exceeded")
        val absoluteRoots = providerRoots.map { it.toAbsolutePath().normalize() }
        if (absoluteRoots.any { !it.startsWith(root) || it == root }) return unavailable("external-or-project-root")
        val paths = absoluteRoots.map { root.relativize(it).toString().replace('\\', '/') }.distinct().sorted()
        val captured = AndroidResourceCapture.capture(root, scope, namespace, paths,
            AndroidResourceCaptureContext.TRUSTED_QUIESCENT_BUILD)
        if (captured is AndroidResourceCaptureResult.Unavailable) return unavailable("capture-" + captured.reason.name.lowercase())
        val facts = (captured as AndroidResourceCaptureResult.Captured).evidence
        val slot = producerArtifact.removePrefix(":").replace(':', '-')
        val options = InputFingerprint("options", "$slot-options", AndroidResourceBindingIdentity.namespaceOptions(namespace))
        val bound = try {
            AndroidResourceBinder.bind(facts, facts.roots.map { it.input }, options, provenance,
                provenance.inputs.filter { it.role == "classes" }, indexed, retainedGraph, scope)
        } catch (_: IllegalArgumentException) {
            return unavailable("admission-invalid-record", facts)
        }
        return when (bound) {
            is AndroidResourceBindingResult.Captured -> {
                if (bound.evidence.producerArtifact != producerArtifact) unavailable("producer-identity-mismatch", facts)
                else Result(facts, bound.evidence, listOf(
                    "android-resource-bindings-captured: retained nonfinal R layout fields; XML impact mapping is unavailable",
                    "android-resource-layout-coverage: custom views, IDs, navigation and data/view binding are unavailable",
                ))
            }
            is AndroidResourceBindingResult.Unavailable -> unavailable("admission-" + bound.reason.name.lowercase(), facts)
        }
    }
}
