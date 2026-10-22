package dev.kartograph.core

/** 동일 JVM graph의 표시 보강·부분 projection에서만 capture-time 사실을 옮긴다. */
internal object CompilerSourceFacts {
    fun retain(original: CodeGraph, rebuilt: CodeGraph): CodeGraph {
        if (original.compilerAuthoredCallers.isEmpty() && original.compilerCallerInventories.isEmpty()) return rebuilt
        require(rebuilt.compilerAuthoredCallers.isEmpty() && rebuilt.compilerCallerInventories.isEmpty()) {
            "compiler source preservation cannot replace another capture"
        }
        require(rebuilt.nodes.keys.all { it in original.nodes }) { "compiler source preservation cannot rename or aggregate identities" }
        val projected = CodeGraph(original.nodes.values.filter { it.id in rebuilt.nodes }, original.edges, original.externalCalls,
            original.serviceProviders, original.enclosures, original.callbackArguments, original.parameterUses, original.lambdaEscapes)
        require(projected.edges == rebuilt.edges && projected.externalCalls == rebuilt.externalCalls &&
            projected.serviceProviders == rebuilt.serviceProviders && projected.enclosures == rebuilt.enclosures &&
            projected.callbackArguments == rebuilt.callbackArguments && projected.parameterUses == rebuilt.parameterUses &&
            projected.lambdaEscapes == rebuilt.lambdaEscapes) { "compiler source preservation requires unchanged projected relations" }
        val incompatible = rebuilt.nodes.values.filter { structural(it) != structural(projected.nodes.getValue(it.id)) }
            .mapTo(mutableSetOf()) { it.id }
        val sources = original.compilerAuthoredCallers.map { it.file } + original.compilerCallerInventories.flatMap { it.sources }
        val hashes = sources.groupBy { it.path }.mapValues { (_, files) -> files.map { it.sha256 }.toSet() }
        val sourceByMethod = mutableMapOf<NodeId, MutableSet<String>>()
        original.compilerAuthoredCallers.forEach { row ->
            sourceByMethod.getOrPut(row.caller) { mutableSetOf() }.add(row.file.path)
            row.calls.filter { it.origin == AuthoredCallOrigin.PROJECT }.forEach { call ->
                sourceByMethod.getOrPut(call.target) { mutableSetOf() }.add(requireNotNull(call.targetSourcePath))
            }
        }
        original.compilerCallerInventories.forEach { inventory -> inventory.bodies.forEach { body ->
            body.methods.forEach { sourceByMethod.getOrPut(it) { mutableSetOf() }.add(body.file.path) }
        } }
        val contradicted = hashes.filterValues { it.size != 1 }.keys.toMutableSet()
        sourceByMethod.filterValues { it.size != 1 }.forEach { (id, paths) -> incompatible += id; contradicted += paths }
        // 독립 source header는 부정 대조에만 쓴다. 행을 만들거나 source/backend 연결을 추론하지 않는다.
        for (node in original.nodes.values + rebuilt.nodes.values) node.sourceDeclaration?.let { declaration ->
            if (declaration.path in hashes && hashes[declaration.path] != setOf(declaration.sourceSha256)) {
                contradicted += declaration.path
                incompatible += node.id
            }
            sourceByMethod[node.id]?.let { expected ->
                if (expected != setOf(declaration.path)) { incompatible += node.id; contradicted += expected }
            }
        }
        val callers = original.compilerAuthoredCallers.mapNotNull { row ->
            val caller = rebuilt.nodes[row.caller]
            if (caller == null || row.caller in incompatible || row.file.path in contradicted ||
                caller.kind !in setOf(NodeKind.METHOD, NodeKind.FUNCTION) || NodeAttribute.EXTERNAL_STUB in caller.attributes) return@mapNotNull null
            val retained = row.calls.filter { call ->
                val target = rebuilt.nodes[call.target]
                when (call.origin) {
                    AuthoredCallOrigin.PROJECT -> target != null && call.target !in incompatible &&
                        target.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION) && NodeAttribute.EXTERNAL_STUB !in target.attributes &&
                        call.targetSourcePath !in contradicted
                    AuthoredCallOrigin.BINARY -> target == null || call.target !in incompatible &&
                        target.kind in setOf(NodeKind.METHOD, NodeKind.FUNCTION) && NodeAttribute.EXTERNAL_STUB in target.attributes
                }
            }
            if (retained.size == row.calls.size) row else row.copy(calls = retained, coverage = AuthoredCallCoverage.INCOMPLETE,
                reasons = (row.reasons + AuthoredCallReason.FILTERED_TARGET).distinct())
        }
        val inventories = original.compilerCallerInventories.map { inventory ->
            val changed = inventory.sources.any { it.path in contradicted } || inventory.methods.any { it.id in incompatible } ||
                inventory.outputs.mapNotNull { it.owner }.any { NodeId("class:$it") in incompatible }
            if (changed && inventory.projectionComplete) inventory.copy(projectionComplete = false) else inventory
        }
        return rebuilt.withCompilerAuthoredCalls(callers).withCompilerCallerInventories(inventories)
    }

    private fun structural(node: GraphNode): GraphNode = node.copy(moduleName = null, location = null, sourceDeclaration = null,
        attributes = node.attributes - NodeAttribute.SOURCE_DECLARATION_LOCATION)
}
