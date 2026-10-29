package dev.kartograph.core

/** 지원 생성기의 정확한 navigation→Java source mapping과 관측한 바이트다. */
public data class GenerationSource(val input: String, val path: String, val qualifiedName: String, val sha256: String) {
    init {
        generationPath(input); generationPath(path); generationHash(sha256)
        require(path.endsWith(".java") && qualifiedName.length in 1..1024 && qualifiedName.none { it.code < 32 || it.code == 127 } &&
            qualifiedName.split('.').all { part ->
                val points = part.codePoints().toArray()
                points.isNotEmpty() && Character.isJavaIdentifierStart(points.first()) && points.all { it !in 0xD800..0xDFFF } &&
                    points.drop(1).all(Character::isJavaIdentifierPart)
            }) { "invalid generated Java source identity" }
    }
}

/** 성공한 cold 생성 관측이다. 숨은 IO·생산자 인증·런타임 사용을 보장하지 않는다. */
public data class GenerationReceipt(
    val scope: String, val task: String, val policy: String, val attempt: String,
    val outputRoot: String, val inputs: List<InputFingerprint>, val mapping: InputFingerprint,
    val outputs: List<InputFingerprint>, val sources: List<GenerationSource>,
) {
    init { requireValid() }

    /** mutable collection을 전달한 호출자도 저장·검증 경계에서 현재 내용을 다시 확인한다. */
    public fun requireValid() {
        generationScope(scope); generationTask(task); generationHash(attempt); generationPath(outputRoot)
        require(policy == POLICY) { "unsupported generation policy" }
        require(inputs.size in 1..MAX_ROWS && outputs.size <= MAX_ROWS && sources.size <= MAX_ROWS) { "generation receipt exceeds row limits" }
        require(inputs.map { it.path }.toSet().size == inputs.size && outputs.map { it.path }.toSet().size == outputs.size) {
            "duplicate generation input or output identity"
        }
        val roles = inputs.mapTo(mutableSetOf()) { it.role }
        require(roles == INPUT_ROLES && inputs.count { it.role in ARTIFACT_ROLES } <= MAX_ARTIFACTS) { "incomplete or unsupported generation inputs" }
        inputs.forEach { generationPath(it.path); generationHash(it.sha256) }
        require(mapping.role == "generationMapping") { "invalid generation mapping role" }
        generationPath(mapping.path); generationHash(mapping.sha256)
        outputs.forEach {
            generationPath(it.path); generationHash(it.sha256)
            require(it.role == "generatedSource" && it.path.startsWith("$outputRoot/")) { "generated output is outside its root" }
        }
        require(mapping.path !in inputs.map { it.path } && mapping.path !in outputs.map { it.path }) { "generation mapping overlaps data inputs or outputs" }
        val navigation = inputs.filter { it.role == "navigation" }.mapTo(mutableSetOf()) { it.path }
        val outputHashes = outputs.associate { it.path to it.sha256 }
        require(sources.map { it.path }.toSet().size == sources.size && sources.map { it.qualifiedName }.toSet().size == sources.size) {
            "duplicate generated source mapping"
        }
        sources.forEach {
            // copy의 생성자 검증은 전달된 목록이 나중에 바뀐 경우에도 경계 검사를 유지한다.
            it.copy()
            require(it.input in navigation && outputHashes[it.path] == it.sha256) { "generated source does not match navigation and output inventory" }
        }
    }

    public companion object {
        public const val POLICY: String = "safeargs-java-2.10.2-cold-v1"
        public const val MAX_ROWS: Int = 100_000
        public const val MAX_ARTIFACTS: Int = 256
        internal val ARTIFACT_ROLES: Set<String> = setOf("generatorArtifact", "generatorActionArtifact")
        private val INPUT_ROLES = ARTIFACT_ROLES + setOf("navigation", "generatorOptions")
    }
}

/** 실제 원본·선택 class 바이트가 같을 때만 기록하는 선언 귀속이다. */
public data class GeneratedDeclaration(
    val scope: String, val generatorTask: String, val attempt: String, val compilerTask: String,
    val source: String, val sourceSha256: String, val owner: NodeId, val symbols: List<NodeId>,
    val originalRoot: String, val selectedRoot: String, val classPath: String, val classSha256: String,
) {
    init {
        generationScope(scope); generationTask(generatorTask); generationTask(compilerTask)
        generationHash(attempt); generationHash(sourceSha256); generationHash(classSha256)
        listOf(source, originalRoot, selectedRoot, classPath).forEach(::generationPath)
        require(source.endsWith(".java") && classPath.endsWith(".class")) { "invalid generated declaration path" }
        val internalName = classPath.removeSuffix(".class")
        require(internalName.none { it in ".;[" } && owner.value == "class:$internalName") { "generated class path does not match its owner" }
        require(symbols.size in 1..GenerationReceipt.MAX_ROWS && symbols.toSet().size == symbols.size && owner in symbols) {
            "invalid generated declaration symbols"
        }
        require(symbols.all { id -> id.value.length in 1..16_384 && id.value.none { it.code < 32 || it.code == 127 } &&
            (id == owner || id.value.startsWith("method:$internalName#") || id.value.startsWith("field:$internalName#")) }) {
            "generated symbol belongs to another class"
        }
    }
}

/** 생성 관측과 그래프 귀속을 함께 보관하며 어느 쪽의 근거도 조용히 버리지 않는다. */
public data class GenerationEvidence(
    val receipts: List<GenerationReceipt>, val declarations: List<GeneratedDeclaration>,
    val observations: List<GenerationConsumptionObservation>,
) {
    init { requireValid() }

    /** 전체 문서의 합산 상한과 receipt→선언 대응을 검사한다. */
    public fun requireValid() {
        require(receipts.size in 1..256 && declarations.size <= GenerationReceipt.MAX_ROWS) { "generation evidence exceeds row limits" }
        require(receipts.sumOf { it.inputs.size.toLong() } <= GenerationReceipt.MAX_ROWS &&
            receipts.sumOf { it.outputs.size.toLong() } <= GenerationReceipt.MAX_ROWS &&
            receipts.sumOf { it.sources.size.toLong() } <= GenerationReceipt.MAX_ROWS &&
            declarations.sumOf { it.symbols.size.toLong() } <= GenerationReceipt.MAX_ROWS &&
            receipts.sumOf { row -> row.inputs.count { it.role in GenerationReceipt.ARTIFACT_ROLES }.toLong() } <= GenerationReceipt.MAX_ARTIFACTS) {
            "generation evidence exceeds total inventory limits"
        }
        receipts.forEach { it.requireValid() }
        val receiptByTask = receipts.associateBy { it.scope to it.task }
        require(receiptByTask.size == receipts.size && declarations.map { it.owner }.toSet().size == declarations.size) {
            "ambiguous generation receipt or class ownership"
        }
        require(observations.size == receipts.size && observations.map { it.originContext.scope to it.originContext.task }.toSet() == receiptByTask.keys &&
            observations.map { it.invocation }.distinct().size == 1) { "generation consumption observations are missing or ambiguous" }
        require(observations.sumOf { it.originContext.providerBindings.sumOf { binding -> binding.paths.size.toLong() } } <= GenerationReceipt.MAX_ROWS &&
            observations.sumOf { it.currentContext.providerBindings.sumOf { binding -> binding.paths.size.toLong() } } <= GenerationReceipt.MAX_ROWS) {
            "generation consumption bindings exceed total row limit"
        }
        observations.forEach { observation ->
            observation.requireValid()
            val context = observation.originContext
            val receipt = receiptByTask.getValue(context.scope to context.task)
            require(context.receiptSha256 == GenerationDigests.receipt(receipt) && context.outputRoot == receipt.outputRoot && context.mappingPath == receipt.mapping.path &&
                context.providerBindings.single { it.property == "navigationFiles" }.paths.toSet() == receipt.inputs.filter { it.role == "navigation" }.map { it.path }.toSet() &&
                context.actionArtifacts.toSet() == receipt.inputs.filter { it.role == "generatorActionArtifact" }.toSet() &&
                observation.currentInventorySha256 == GenerationDigests.inventory(receipt)) { "generation consumption does not match its receipt" }
            require(observation.currentAttempt == null || observation.currentAttempt == receipt.attempt) { "current cold generation attempt does not match its receipt" }
        }
        val symbols = mutableSetOf<NodeId>()
        val sourceMappings = receipts.associate { receipt -> (receipt.scope to receipt.task) to receipt.sources.associateBy { it.path } }
        declarations.forEach { declaration ->
            declaration.copy()
            val key = declaration.scope to declaration.generatorTask
            val receipt = requireNotNull(receiptByTask[key]) { "generated declaration has no receipt" }
            val source = requireNotNull(sourceMappings.getValue(key)[declaration.source]) { "generated declaration has no source mapping" }
            require(receipt.attempt == declaration.attempt && source.sha256 == declaration.sourceSha256) { "generated declaration receipt mismatch" }
            val mappedClass = source.qualifiedName.replace('.', '/')
            val actualClass = declaration.classPath.removeSuffix(".class")
            require(actualClass == mappedClass || actualClass.startsWith("$mappedClass$")) { "generated class does not belong to its mapped Java source" }
            require(declaration.symbols.all(symbols::add)) { "generated symbol is claimed twice" }
        }
    }

    /** 저장된 구조·scope·compiler 원천을 검사한다. 파일 바이트의 실제 대조는 adapter 책임이다. */
    public fun requireMatches(graph: CodeGraph, provenance: SnapshotProvenance, scope: String) {
        requireValid()
        require(provenance.memberScopes.isEmpty() && receipts.all { it.scope == scope } && provenance.witnesses.all { it.scope == scope }) {
            "generation evidence requires a single matching scope"
        }
        observations.forEach { observation ->
            val rows = declarations.filter { it.scope == observation.originContext.scope && it.generatorTask == observation.originContext.task }
            require(observation.currentDeclarationsSha256 == GenerationDigests.declarations(rows, provenance)) { "generation consumption compiler or declaration evidence changed" }
        }
        val witnesses = provenance.witnesses.groupBy { it.scope to it.artifact }
        val selected = provenance.inputs.filter { it.role == "classes" }.groupBy { it.path }
        val members = graph.edges.filter { it.kind == EdgeKind.MEMBER }.mapTo(mutableSetOf()) { it.source to it.target }
        val claimed = mutableSetOf<NodeId>()
        declarations.forEach { declaration ->
            val witness = witnesses[scope to declaration.compilerTask]?.singleOrNull()
            require(witness != null) { "generated declaration requires an unambiguous compiler witness" }
            val original = witness.outputs.singleOrNull { it.role == "classes" && it.path == declaration.originalRoot }
            val target = selected[declaration.selectedRoot]?.singleOrNull()
            require(original != null && target != null) { "generated declaration root is outside compiler or selected provenance" }
            if (original != target) {
                val observations = provenance.transformations.filter { it.scope == scope && original in it.originalOutputs && target in it.selectedOutputs }
                require(observations.size == 1 && observations.single().changedClasses.none { it.name == declaration.classPath.removeSuffix(".class") }) {
                    "generated declaration is not a byte-identical transformed artifact member"
                }
                val observation = observations.single()
                val scopeOutputs = provenance.witnesses.filter { it.scope == scope }.flatMap { it.outputs }.toSet()
                require(observation.originalOutputs.all { it in scopeOutputs } &&
                    observation.selectedOutputs.all { selected[it.path]?.singleOrNull() == it }) { "generation transformation roots do not match provenance" }
            }
            require(graph.nodes[declaration.owner]?.kind == NodeKind.CLASS) { "generated declaration owner is not a captured class" }
            declaration.symbols.forEach { id ->
                val node = graph.nodes[id]
                require(node != null && node.synthesized && NodeAttribute.GENERATED_SOURCE_EVIDENCE in node.attributes &&
                    NodeAttribute.EXTERNAL_STUB !in node.attributes && (id == declaration.owner || declaration.owner to id in members)) {
                    "generated declaration does not match graph symbols and membership"
                }
                claimed += id
            }
        }
        require(graph.nodes.values.filter { NodeAttribute.GENERATED_SOURCE_EVIDENCE in it.attributes }.mapTo(mutableSetOf()) { it.id } == claimed) {
            "generated graph marker has no declaration evidence"
        }
    }
}

internal fun generationScope(value: String) {
    require(value.length in 1..200 && Regex("[A-Za-z0-9_.:-]+").matches(value)) { "invalid generation scope" }
}

internal fun generationTask(value: String) {
    require(value.length in 1..1024 && Regex("(:[A-Za-z0-9_.-]+)+").matches(value)) { "invalid generation task" }
}

internal fun generationHash(value: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "invalid generation content fingerprint" }
}

internal fun generationPath(value: String) {
    require(value.length in 1..4096 && value.none { it.code < 32 || it.code == 127 || it in "\\:" } &&
        value.split('/').none { it.isEmpty() || it == "." || it == ".." } && value.codePoints().allMatch { it !in 0xD800..0xDFFF }) {
        "invalid portable generation identity"
    }
}
