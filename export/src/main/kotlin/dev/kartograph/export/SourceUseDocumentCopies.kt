package dev.kartograph.export

import dev.kartograph.analysis.ImpactFilter
import dev.kartograph.analysis.ImpactNode
import dev.kartograph.analysis.ImpactReport
import dev.kartograph.analysis.ImpactSummary
import dev.kartograph.analysis.SymbolQueryDocument
import dev.kartograph.analysis.SymbolQueryNeighbor
import java.util.Collections

/** 기존 예산으로 제한된 typed 결과만 복사한다. JSON descriptor·문자열은 만들지 않는다. */
internal fun sourceFrozenQuery(value: SymbolQueryDocument): SymbolQueryDocument = value.copy(
    limitations = frozen(value.limitations), candidates = value.candidates?.let(::frozen),
    result = value.result?.let { result -> result.copy(
        reachability = result.reachability.copy(path = result.reachability.path?.let(::frozen)),
        usedBy = frozenItems(result.usedBy, ::neighbor), dependsOn = frozenItems(result.dependsOn, ::neighbor),
        members = frozenItems(result.members, ::neighbor), declaredIn = result.declaredIn?.let(::neighbor),
    ) },
)

/** 새 문서가 legacy mutable collection의 후속 변경에 따라 달라지지 않게 한다. */
internal fun sourceFrozenImpact(value: ImpactReport): ImpactReport = value.copy(
    changed = frozenItems(value.changed, ::impactNode), affected = frozenItems(value.affected, ::impactNode),
    unresolved = frozenItems(value.unresolved) { it.copy(candidates = frozen(it.candidates)) },
    limitations = frozen(value.limitations),
    navigation = value.navigation.copy(filters = filter(value.navigation.filters),
        observed = summary(value.navigation.observed), filtered = summary(value.navigation.filtered)),
    budgets = value.budgets.copy(visited = Collections.unmodifiableMap(LinkedHashMap(value.budgets.visited)),
        visitLimitReached = frozenSet(value.budgets.visitLimitReached)),
)

private fun neighbor(value: SymbolQueryNeighbor): SymbolQueryNeighbor = value.copy(
    edges = frozen(value.edges), references = frozen(value.references),
)

private fun impactNode(value: ImpactNode): ImpactNode = value.copy(
    node = value.node.copy(jvmModifiers = frozenSet(value.node.jvmModifiers), attributes = frozenSet(value.node.attributes),
        annotations = frozenSet(value.node.annotations), supertypes = frozenSet(value.node.supertypes)),
    presentIn = frozenSet(value.presentIn), observedIn = frozenSet(value.observedIn),
    paths = frozenItems(value.paths) { path -> path.copy(nodes = frozen(path.nodes),
        edges = frozenItems(path.edges) { edge -> edge.copy(callSiteLines = frozen(edge.callSiteLines)) }) },
    retention = frozenItems(value.retention) { retention -> retention.copy(evidence = retention.evidence.copy(
        externalBridge = retention.evidence.externalBridge?.let { it.copy(callers = frozen(it.callers)) },
    )) }, facts = frozen(value.facts),
    pathOmissions = frozenItems(value.pathOmissions) { it.copy(edgeKinds = frozen(it.edgeKinds)) },
)

private fun filter(value: ImpactFilter): ImpactFilter = value.copy(
    modules = frozenSet(value.modules), affectedFiles = frozenSet(value.affectedFiles), kinds = frozenSet(value.kinds),
)

private fun summary(value: ImpactSummary): ImpactSummary = value.copy(
    byModule = frozen(value.byModule), byFile = frozen(value.byFile), byTestStatus = frozen(value.byTestStatus),
    byRelation = frozen(value.byRelation), byPathStatus = frozen(value.byPathStatus),
)

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> frozenSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun <T, R> frozenItems(values: List<T>, transform: (T) -> R): List<R> {
    val result = ArrayList<R>(values.size)
    for (value in values) result.add(transform(value))
    return Collections.unmodifiableList(result)
}
