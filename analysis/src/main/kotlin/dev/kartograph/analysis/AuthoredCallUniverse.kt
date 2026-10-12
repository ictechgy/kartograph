package dev.kartograph.analysis

import dev.kartograph.core.*

/** 요청자가 명시한 module별 source 파일과 body 종류다. inventory 행에서 역으로 만들지 않는다. */
public data class AuthoredCallerScope(
    val filesByModule: Map<String, Set<String>>,
    val bodyKinds: Set<CompilerSourceBodyKind>,
    val wholeProject: Boolean = false,
) {
    init {
        require(filesByModule.isNotEmpty() && filesByModule.size <= 256 && filesByModule.values.sumOf { it.size.toLong() } <= 200_000) { "authored caller scope is missing or oversized" }
        require(filesByModule.keys.all { it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_.:-]+")) } &&
            filesByModule.values.all { files -> files.isNotEmpty() && files.all(SourceDeclarationEvidence::isPortablePath) }) { "invalid authored caller source scope" }
        require(bodyKinds.isNotEmpty() && (!wholeProject || bodyKinds == CompilerSourceBodyKind.entries.toSet())) { "whole-project authored scope requires every body kind" }
    }
}

/** missing inventory/identity는 UNKNOWN이고 관측된 미지원 body는 INCOMPLETE다. */
public enum class AuthoredUniverseStatus { COMPLETE, INCOMPLETE, UNKNOWN }

/** 부분 positive와 전체 집합을 구분하며 scope 및 제외하지 않은 미지원 이유를 함께 반환한다. */
public data class AuthoredCallUniverseResult(
    val scope: AuthoredCallerScope,
    val status: AuthoredUniverseStatus,
    val sourceBodies: Int,
    val callers: Set<NodeId>,
    val calls: Map<NodeId, List<CompilerAuthoredCall>>,
    val limitations: List<String>,
)

/** coverage를 먼저 검증한 뒤 authoredCall v1의 명시적 source universe를 반환한다. */
public object AuthoredCallUniverse {
    public fun examine(graph: CodeGraph, scope: AuthoredCallerScope): AuthoredCallUniverseResult =
        examineCaptured(graph, captureScope(scope))

    private fun captureScope(scope: AuthoredCallerScope): AuthoredCallerScope {
        // 외부 mutable collection이 생성 후 커졌어도 복사 전에 한계를 확인하고 순회를 제한한다.
        require(scope.filesByModule.size in 1..256 && scope.bodyKinds.size in 1..CompilerSourceBodyKind.entries.size) {
            "authored caller scope is missing or oversized"
        }
        val modules = sortedMapOf<String, Set<String>>()
        var moduleCount = 0
        var remaining = 200_000
        for ((module, files) in scope.filesByModule) {
            require(++moduleCount <= 256 && module.length in 1..200 && module.matches(Regex("[A-Za-z0-9_.:-]+"))) {
                "invalid authored caller module scope"
            }
            require(files.size in 1..remaining) { "authored caller files are missing or oversized" }
            val paths = sortedSetOf<String>()
            var count = 0
            for (path in files) {
                require(++count <= remaining && path.length <= 16_384 && SourceDeclarationEvidence.isPortablePath(path) && paths.add(path)) {
                    "invalid or oversized authored caller source scope"
                }
            }
            require(paths.isNotEmpty() && modules.put(module, java.util.Collections.unmodifiableSet(paths)) == null) {
                "missing or duplicate authored caller source scope"
            }
            remaining -= count
        }
        val kinds = mutableSetOf<CompilerSourceBodyKind>()
        var kindCount = 0
        for (kind in scope.bodyKinds) require(++kindCount <= CompilerSourceBodyKind.entries.size &&
            kind in CompilerSourceBodyKind.entries && kinds.add(kind)) { "invalid authored caller body kinds" }
        // 재검증한 깊은 불변 snapshot만 결과에 노출한다. 현재 build 검증을 대신하지 않는다.
        return AuthoredCallerScope(java.util.Collections.unmodifiableMap(modules),
            java.util.Collections.unmodifiableSet(kinds), scope.wholeProject)
    }

    private fun examineCaptured(graph: CodeGraph, scope: AuthoredCallerScope): AuthoredCallUniverseResult {
        val limitations = sortedSetOf<String>()
        var unknown = false
        var incomplete = false
        var bodies = 0
        val selected = sortedSetOf<NodeId>()
        val calls = sortedMapOf<NodeId, List<CompilerAuthoredCall>>()
        val inventories = graph.compilerCallerInventories.filter { it.scope in scope.filesByModule }
        val ownership = mutableMapOf<Pair<String, String>, CompilerCallerInventory>()
        for (inventory in inventories) for (source in inventory.sources) {
            if (ownership.putIfAbsent(inventory.scope to source.path, inventory) != null) {
                unknown = true; limitations += "duplicate-source-owner"
            }
        }
        for ((module, files) in scope.filesByModule) for (file in files) if (module to file !in ownership) {
            unknown = true; limitations += "missing-source-inventory"
        }
        for (inventory in inventories) {
            if (!inventory.projectionComplete) { unknown = true; limitations += "projected-output-inventory" }
            val methods = inventory.methods.associateBy { it.id }
            val requestedFiles = scope.filesByModule.getValue(inventory.scope)
            for (body in inventory.bodies.filter { it.file.path in requestedFiles && it.kind in scope.bodyKinds }) {
                bodies++
                if (body.methods.size != 1 || methods[body.methods.singleOrNull()]?.sourceBody != body.key) {
                    unknown = true; limitations += "unmapped-source-body"; continue
                }
                val method = methods.getValue(body.methods.single())
                selected += method.id
                if (body.kind == CompilerSourceBodyKind.ABSTRACT_METHOD && !body.bodyPresent && !method.hasCode && method.access and 0x0400 != 0 &&
                    body.offsetUtf16 != null && body.compilerOrigin.let { it == "JAVAC_EXPLICIT" || it == "FIR:Source:KtRealSourceElementKind" }) {
                    calls[method.id] = emptyList(); continue
                }
                if (body.kind !in setOf(CompilerSourceBodyKind.ORDINARY_METHOD, CompilerSourceBodyKind.LOCAL_METHOD, CompilerSourceBodyKind.ANONYMOUS_METHOD)) {
                    incomplete = true; limitations += "unsupported-source-body:${body.kind.name}"; continue
                }
                if (!body.bodyPresent || !method.hasCode || body.compilerOrigin !in setOf("JAVAC_EXPLICIT", "FIR:Source:KtRealSourceElementKind")) {
                    unknown = true; limitations += "contradictory-caller-body"; continue
                }
                val row = graph.compilerAuthoredCallsFrom(method.id)
                if (row == null || row.file != body.file || row.offsetUtf16 != body.offsetUtf16 || row.endOffsetUtf16 != body.endOffsetUtf16 ||
                    row.inputToken != inventory.inputToken || row.collectorArtifactSha256 != inventory.collectorArtifactSha256 ||
                    row.evidenceSha256 != inventory.authoredEvidenceSha256 || row.coordinateBasis != inventory.coordinateBasis || row.compilerVersion != inventory.compilerVersion) {
                    unknown = true; limitations += "missing-or-mismatched-caller-coverage"; continue
                }
                calls[method.id] = row.calls
                if (row.coverage != AuthoredCallCoverage.COMPLETE) { incomplete = true; limitations += "incomplete-caller-coverage" }
            }
        }
        if (scope.wholeProject) {
            val allMethods = graph.nodes.values.filter { it.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION, NodeKind.CONSTRUCTOR) && NodeAttribute.EXTERNAL_STUB !in it.attributes }.map { it.id }.toSet()
            val allClasses = graph.nodes.values.filter { it.kind in setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS) && NodeAttribute.EXTERNAL_STUB !in it.attributes }.map { it.id }.toSet()
            val accountedClasses = inventories.flatMap { it.outputs }.mapNotNull { it.owner }.map { NodeId("class:$it") }
            if (accountedClasses.toSet() != allClasses || accountedClasses.distinct().size != accountedClasses.size) {
                unknown = true; limitations += "missing-or-ambiguous-class-inventory"
            }
            val accounted = inventories.flatMap { it.methods }.map { it.id }
            if (accounted.toSet() != allMethods || accounted.distinct().size != accounted.size) { unknown = true; limitations += "missing-or-ambiguous-output-inventory" }
            val requestedSources = scope.filesByModule.flatMap { (module, files) -> files.map { module to it } }.toSet()
            val retainedSources = graph.compilerCallerInventories.flatMap { inventory -> inventory.sources.map { inventory.scope to it.path } }.toSet()
            if (requestedSources != retainedSources) {
                unknown = true; limitations += "whole-project-source-scope-omission"
            }
            if (inventories.any { it.methods.any { method -> method.origin == CompilerBackendOrigin.UNKNOWN } }) {
                unknown = true; limitations += "unknown-backend-origin"
            }
        }
        return AuthoredCallUniverseResult(scope, if (unknown) AuthoredUniverseStatus.UNKNOWN else if (incomplete) AuthoredUniverseStatus.INCOMPLETE else AuthoredUniverseStatus.COMPLETE,
            bodies, selected, calls, limitations.toList())
    }
}
