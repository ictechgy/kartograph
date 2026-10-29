package dev.kartograph.export

import dev.kartograph.analysis.AndroidResourceImpactSelection
import dev.kartograph.core.AndroidLayoutDefinition
import dev.kartograph.core.AndroidResourceKey
import dev.kartograph.core.InputFingerprint

/** 두 impact 형식이 같은 리소스 증거를 기존 출력 예산 안에서 지연 투영한다. */
internal fun resourceImpactSelectionValue(selection: AndroidResourceImpactSelection): Map<String, Any?> = sourceJsonObject(
    "status" to { selection.status.name.lowerCamel() }, "scope" to { selection.scope },
    "namespace" to { selection.namespace }, "coverage" to { selection.coverage },
    "requestedFileCount" to { selection.requestedFileCount },
    "binding" to { selection.binding?.let { binding -> sourceJsonObject(
        "resourceFactsSha256" to { binding.resourceFactsSha256 }, "producerArtifact" to { binding.producerArtifact },
        "producerSha256" to { binding.producerSha256 }, "output" to { resourceInput(binding.output) },
    ) } },
    "files" to { sourceJsonItems(selection.files) { file -> sourceJsonObject(
        "requested" to { file.requested }, "status" to { file.status.name.lowerCamel() },
        "definitions" to { sourceJsonItems(file.definitions, ::resourceDefinition) },
    ) } },
    "resources" to { sourceJsonItems(selection.resources) { resource -> sourceJsonObject(
        "key" to { resourceKey(resource.key) },
        "definitions" to { sourceJsonItems(resource.definitions, ::resourceDefinition) },
        "field" to { resource.outcome.field?.value },
        "unavailable" to { resource.outcome.unavailable?.name?.lowerCamel() },
    ) } },
    "includes" to { sourceJsonItems(selection.includes) { include -> sourceJsonObject(
        "id" to { include.id }, "sourceDefinitionId" to { include.sourceDefinitionId },
        "ordinal" to { include.ordinal }, "kind" to { include.kind.name.lowerCamel() },
        "target" to { include.target?.let(::resourceKey) },
    ) } },
    "selectedFields" to { sourceJsonItems(selection.selectedFields.sorted()) { it.value } },
    "limits" to { sourceJsonObject(
        "maxFiles" to { selection.limits.maxFiles }, "maxResources" to { selection.limits.maxResources },
        "maxDefinitions" to { selection.limits.maxDefinitions }, "maxIncludes" to { selection.limits.maxIncludes },
    ) },
    "limitations" to { selection.limitations },
)

private fun resourceDefinition(value: AndroidLayoutDefinition): Map<String, Any?> = sourceJsonObject(
    "id" to { value.id }, "key" to { resourceKey(value.key) }, "rootId" to { value.rootId },
    "path" to { value.file.path }, "sourceSha256" to { value.file.sha256 },
    "relativePath" to { value.relativePath }, "qualifier" to { value.qualifier },
)

private fun resourceKey(value: AndroidResourceKey): Map<String, Any?> = sourceJsonObject(
    "namespace" to { value.namespace }, "type" to { value.type }, "name" to { value.name },
)

private fun resourceInput(value: InputFingerprint): Map<String, Any?> = sourceJsonObject(
    "role" to { value.role }, "path" to { value.path }, "sha256" to { value.sha256 },
)
