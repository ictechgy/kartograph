package dev.kartograph.core

/** source 선언의 semantic body 범위다. 쿼리가 요청한 종류를 inventory에서 먼저 선택한다. */
public enum class CompilerSourceBodyKind {
    ORDINARY_METHOD, LOCAL_METHOD, ANONYMOUS_METHOD, ABSTRACT_METHOD, NATIVE_METHOD,
    CONSTRUCTOR, IMPLICIT_CONSTRUCTOR, PROPERTY_INITIALIZER, STATIC_INITIALIZER, INSTANCE_INITIALIZER,
    PARAMETER_DEFAULT, ACCESSOR, LOCAL_FUNCTION, LAMBDA, UNSUPPORTED,
}

/** 생성 제외 allowlist는 비어 있다. source에 유일하게 연결되지 않은 실제 method는 UNKNOWN이다. */
public enum class CompilerBackendOrigin { SOURCE_BOUND, UNKNOWN }

/** 실제 source Tree/FIR 선언이다. compiler가 range를 제공하지 않으면 양쪽 offset이 null이다. */
public data class CompilerSourceBody(
    val key: String,
    val file: CompilerEvidenceSource,
    val offsetUtf16: Int?,
    val endOffsetUtf16: Int?,
    val kind: CompilerSourceBodyKind,
    val bodyPresent: Boolean,
    val compilerOrigin: String,
    val methods: List<NodeId>,
) {
    init {
        require(key.matches(Regex("b[0-9]{1,9}"))) { "invalid source body key" }
        require(SourceDeclarationEvidence.isPortablePath(file.path)) { "invalid source body path" }
        require(if (offsetUtf16 == null) endOffsetUtf16 == null else endOffsetUtf16 != null && offsetUtf16 >= 0 && endOffsetUtf16 > offsetUtf16) {
            "invalid source body range"
        }
        requireInventoryOrigin(compilerOrigin)
        require(methods.size <= 200_000 && methods.distinct().size == methods.size) { "invalid source body methods" }
        methods.forEach(::requireInventoryMethod)
    }
}

/** 클래스뿐 아니라 metadata 등 실제 task 출력도 모두 고정한다. */
public data class CompilerInventoryOutput(val path: String, val sha256: String, val owner: String?) {
    init {
        require(SourceDeclarationEvidence.isPortablePath(path)) { "invalid inventory output path" }
        requireFingerprint(sha256)
        require((owner != null) == path.endsWith(".class")) { "inventory class output owner is missing" }
        require(owner == null || owner.isNotEmpty() && path == "$owner.class") { "inventory output owner/path mismatch" }
    }
}

/** CALL 유무와 무관하게 실제 방출된 모든 JVM method를 표현한다. */
public data class CompilerInventoryMethod(
    val id: NodeId,
    val access: Int,
    val hasCode: Boolean,
    val origin: CompilerBackendOrigin,
    val sourceBody: String?,
    val compilerOrigin: String,
) {
    init {
        requireInventoryMethod(id)
        require(access in 0..65535 && hasCode != (access and (0x0400 or 0x0100) != 0)) { "inventory method code/flags mismatch" }
        require((origin == CompilerBackendOrigin.SOURCE_BOUND) == (sourceBody != null)) { "inventory backend binding mismatch" }
        require(sourceBody == null || sourceBody.matches(Regex("b[0-9]{1,9}"))) { "invalid backend body key" }
        requireInventoryOrigin(compilerOrigin)
    }
}

/**
 * 한 완료 compiler task의 전체 source/output inventory다. scope/artifact는 기존 성공 witness의 identity다.
 * 현재 파일 검증과 snapshot capture-time assertion은 다르며 project 전체 완전성은 별도 쿼리가 판단한다.
 */
public data class CompilerCallerInventory(
    val scope: String,
    val artifact: String,
    val outputRoot: String,
    val sources: List<CompilerEvidenceSource>,
    val bodies: List<CompilerSourceBody>,
    val outputs: List<CompilerInventoryOutput>,
    val methods: List<CompilerInventoryMethod>,
    val coordinateBasis: CompilerSourceCoordinateBasis,
    val compilerVersion: String,
    val inputToken: String,
    val collectorArtifactSha256: String,
    val evidenceSha256: String,
    val authoredEvidenceSha256: String,
    val sourceBodyCounts: Map<String, Int> = inventoryBodyCounts(sources, bodies),
    val projectionComplete: Boolean = true,
) {
    init {
        require(listOf(scope, artifact).all { it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_.:-]+")) }) { "invalid inventory task identity" }
        require(SourceDeclarationEvidence.isPortablePath(outputRoot)) { "invalid inventory output root" }
        require(sources.isNotEmpty() && sources.size + bodies.size.toLong() + outputs.size + methods.size + bodies.sumOf { it.methods.size.toLong() } <= 200_000) {
            "caller inventory is missing or oversized"
        }
        require(sources.map { it.path }.distinct().size == sources.size && sources.all { SourceDeclarationEvidence.isPortablePath(it.path) }) { "invalid inventory sources" }
        require(sourceBodyCounts.keys == sources.map { it.path }.toSet() && sourceBodyCounts.values.all { it in 0..200_000 } &&
            sourceBodyCounts == inventoryBodyCounts(sources, bodies)) { "source body inventory count mismatch" }
        require(bodies.map { it.key }.distinct().size == bodies.size && sources.associateBy { it.path }.let { byPath -> bodies.all { byPath[it.file.path] == it.file } }) { "invalid inventory source body" }
        require(outputs.any { it.owner != null }) { "caller inventory requires an emitted class output" }
        require(outputs.map { it.path }.distinct().size == outputs.size && outputs.mapNotNull { it.owner }.distinct().size == outputs.count { it.owner != null }) {
            "duplicate inventory outputs"
        }
        require(methods.map { it.id }.distinct().size == methods.size) { "missing or duplicate inventory methods" }
        val owners = outputs.mapNotNull { it.owner }.toSet()
        require(methods.all { it.id.value.removePrefix("method:").substringBefore('#') in owners }) { "inventory method output is missing" }
        val methodIds = methods.map { it.id }.toSet()
        require(bodies.all { body -> body.methods.all { it in methodIds } }) { "source body references an absent emitted method" }
        val links = mutableMapOf<NodeId, MutableList<String>>()
        bodies.forEach { body -> body.methods.forEach { links.getOrPut(it) { mutableListOf() }.add(body.key) } }
        require(methods.all { method ->
            val selected = links[method.id].orEmpty()
            if (selected.size == 1) method.origin == CompilerBackendOrigin.SOURCE_BOUND && method.sourceBody == selected.single()
            else method.origin == CompilerBackendOrigin.UNKNOWN && method.sourceBody == null
        }) { "inventory source/backend association is contradictory" }
        require(compilerVersion.length in 1..128 && compilerVersion.matches(Regex("[A-Za-z0-9._+\\-]+"))) { "invalid caller inventory compiler version" }
        require(if (coordinateBasis == CompilerSourceCoordinateBasis.KOTLIN_UTF16_NORMALIZED_SOURCE) compilerVersion == "2.4.20"
            else compilerVersion.substringBefore('.') in setOf("17", "21")) { "unsupported caller inventory compiler" }
        listOf(inputToken, collectorArtifactSha256, evidenceSha256, authoredEvidenceSha256).forEach(::requireFingerprint)
    }

    /** 필터에서 빠진 output method도 inventory에 남겨 unknown을 지운 작은 universe를 만들지 않는다. */
    public fun retainingNodes(nodes: Map<NodeId, GraphNode>): CompilerCallerInventory =
        if (!projectionComplete || outputs.mapNotNull { it.owner }.all { owner -> nodes[NodeId("class:$owner")]?.let {
            it.kind in setOf(NodeKind.CLASS, NodeKind.INTERFACE, NodeKind.OBJECT, NodeKind.ENUM, NodeKind.ANNOTATION_CLASS) && NodeAttribute.EXTERNAL_STUB !in it.attributes
        } == true } && methods.all { method -> nodes[method.id]?.let {
            (if (method.id.value.substringAfter('#').startsWith("<init>(")) it.kind == NodeKind.CONSTRUCTOR
                else it.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION)) && NodeAttribute.EXTERNAL_STUB !in it.attributes &&
                it.jvmModifiers == inventoryJvmModifiers(method.access)
        } == true }) this else copy(projectionComplete = false)
}

private fun requireFingerprint(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) { "invalid inventory fingerprint" } }
private fun requireInventoryOrigin(value: String) { require(value.length in 1..256 && value.none { it.code < 32 }) { "invalid compiler origin" } }
private fun requireInventoryMethod(id: NodeId) {
    val value = id.value
    require(value.length <= 4096 && value.startsWith("method:") && value.none { it.code < 32 || it.isWhitespace() }) { "invalid inventory method identity" }
    val split = value.indexOf('#', 7)
    val parameters = value.indexOf('(', split + 1)
    require(split > 7 && parameters > split + 1 && value.lastIndexOf('#') == split) { "invalid inventory method identity" }
    fun owner(text: String): Boolean = text.isNotEmpty() && text.split('/').all { it.isNotEmpty() } && text.none { it in ".;[():#" }
    require(owner(value.substring(7, split))) { "invalid inventory method owner" }
    val name = value.substring(split + 1, parameters)
    require(name in setOf("<init>", "<clinit>") || name.none { it in ".;[/<>():#" }) { "invalid inventory method name" }
    var offset = parameters + 1
    fun type(void: Boolean) {
        var dimensions = 0
        while (value.getOrNull(offset) == '[') { offset++; dimensions++ }
        require(dimensions <= 255) { "inventory array descriptor exceeds limit" }
        when (value.getOrNull(offset++)) {
            'V' -> require(void && dimensions == 0) { "invalid inventory void descriptor" }
            'Z', 'B', 'C', 'S', 'I', 'F', 'J', 'D' -> Unit
            'L' -> { val end = value.indexOf(';', offset); require(end > offset && owner(value.substring(offset, end))) { "invalid inventory object descriptor" }; offset = end + 1 }
            else -> throw IllegalArgumentException("invalid inventory descriptor")
        }
    }
    while (value.getOrNull(offset) != ')') type(false)
    offset++; type(true)
    require(offset == value.length && (name !in setOf("<init>", "<clinit>") || value.endsWith(")V")) && (name != "<clinit>" || value.endsWith("#<clinit>()V"))) { "invalid inventory method descriptor" }
}

private fun inventoryBodyCounts(sources: List<CompilerEvidenceSource>, bodies: List<CompilerSourceBody>): Map<String, Int> {
    val counts = bodies.groupingBy { it.file.path }.eachCount()
    return sources.associate { it.path to counts.getOrDefault(it.path, 0) }
}

private fun inventoryJvmModifiers(access: Int): Set<JvmModifier> = buildSet {
    if (access and 0x0010 != 0) add(JvmModifier.FINAL)
    if (access and 0x0400 != 0) add(JvmModifier.ABSTRACT)
    if (access and 0x1000 != 0) add(JvmModifier.SYNTHETIC)
    if (access and 0x0100 != 0) add(JvmModifier.NATIVE)
    if (access and 0x0008 != 0) add(JvmModifier.STATIC)
}
