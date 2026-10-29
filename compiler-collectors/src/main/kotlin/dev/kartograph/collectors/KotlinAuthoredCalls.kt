@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
package dev.kartograph.collectors

import java.nio.file.Path
import java.util.IdentityHashMap
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirLabel
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.originalOrSelf
import org.jetbrains.kotlin.fir.references.*
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/** ordinary-body-v1은 성공한 callback의 수가 아니라 모든 body node를 분류한다. */
internal class KotlinAuthoredCalls(private val options: EvidenceProtocol.Options) {
    private data class PendingCall(val target: FirFunctionSymbol<*>, val path: String, val start: Int, val end: Int)
    private data class Body(val symbol: FirFunctionSymbol<*>, val path: String, val start: Int, val end: Int,
        val nodes: Int, val observed: Int, val reasons: Set<String>, val calls: List<PendingCall>)
    private var retainedNodes = 0L
    private var retainedBytes = 0L
    private val bodies = IdentityHashMap<FirFunctionSymbol<*>, Body>()

    fun observe(function: FirNamedFunction, context: CheckerContext) {
        val path = context.containingFile?.path ?: return
        val source = function.source ?: return
        if (source.kind !== KtRealSourceElementKind || !function.origin.fromSource) return
        require(bodies.size < EvidenceProtocol.MAX_ROWS) { "authored caller limit exceeded" }
        val reasons = sortedSetOf<String>()
        if (function.isLocal || function.status.isInline || function.status.isSuspend || function.status.isExpect ||
            function.status.isActual || function.status.isExternal || function.valueParameters.any { it.defaultValue != null } ||
            function.contextParameters.isNotEmpty()) reasons += "UNSUPPORTED_DECLARATION"
        if (function.body == null) reasons += "NO_BODY"
        val calls = mutableListOf<PendingCall>()
        var nodes = 0
        var observed = 0
        var depth = 0
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<FirElement, Boolean>())
        function.body?.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                // FIR의 공유된 receiver/type 노드는 동일 semantic node로 한 번만 분류한다.
                if (!visited.add(element)) return
                require(++retainedNodes <= EvidenceProtocol.MAX_ROWS && ++nodes <= AuthoredCallProtocol.MAX_NODES && ++depth <= AuthoredCallProtocol.MAX_DEPTH) {
                    "authored body resource limit exceeded"
                }
                try {
                    when (element) {
                        is FirFunctionCall -> {
                            val reference = element.calleeReference as? FirResolvedNamedReference
                            val target = (reference?.resolvedSymbol as? FirFunctionSymbol<*>)?.originalOrSelf()
                            val selector = reference?.source
                            if (element.origin != FirFunctionCallOrigin.Regular || element.source?.kind !== KtRealSourceElementKind ||
                                selector?.kind !== KtRealSourceElementKind || target == null || target is FirConstructorSymbol ||
                                reference.name != target.name || target.resolvedStatus.isInline || target.resolvedStatus.isSuspend ||
                                target.fir.valueParameters.any { it.defaultValue != null } || target.fir.contextParameters.isNotEmpty()) {
                                reasons += "UNSUPPORTED_CALL"
                            } else {
                                observed++
                                val targetFile = context.session.firProvider.getFirCallableContainerFile(target)?.sourceFile?.path
                                if (targetFile == null) reasons += "UNMAPPED_EXTERNAL_TARGET"
                                else if (selector.startOffset < source.startOffset || selector.endOffset <= selector.startOffset ||
                                    selector.endOffset > source.endOffset) reasons += "UNMAPPED_POSITION"
                                else {
                                    retainedBytes += 256L + 4L * (path.length + targetFile.length)
                                    require(retainedBytes <= EvidenceProtocol.MAX_ENCODED_BYTES) { "authored collector exceeds retained byte limit" }
                                    calls += PendingCall(target, canonical(targetFile), selector.startOffset, selector.endOffset)
                                }
                            }
                        }
                        is FirPropertyAccessExpression -> {
                            val symbol = (element.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
                            if (symbol !is FirValueParameterSymbol && !(symbol is FirPropertySymbol && symbol.isLocal)) {
                                reasons += "PROPERTY_ACCESS"
                            }
                        }
                        is FirProperty -> if (!element.isLocal || element.delegate != null || element.getter != null || element.setter != null) {
                            reasons += "UNSUPPORTED_DECLARATION"
                        }
                        is FirBlock, is FirReturnExpression, is FirLiteralExpression, is FirStringConcatenationCall,
                        is FirArgumentList, is FirResolvedNamedReference, is FirThisReference, is FirResolvedTypeRef,
                        is FirControlFlowGraphReference, is org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition,
                        is org.jetbrains.kotlin.fir.references.impl.FirStubReference,
                        is org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyBackingField,
                        is FirDeclarationStatus, is FirThisReceiverExpression, is FirWhenExpression, is FirWhenBranch,
                        is FirBooleanOperatorExpression, is FirWhileLoop, is FirDoWhileLoop, is FirBreakExpression,
                        is FirContinueExpression, is FirLabel, is FirResolvedQualifier -> Unit
                        else -> { reasons += "UNSUPPORTED_SYNTAX"; return }
                    }
                    element.acceptChildren(this)
                } finally { depth-- }
            }
        })
        retainedBytes += 512L + 4L * path.length
        require(retainedBytes <= EvidenceProtocol.MAX_ENCODED_BYTES) { "authored collector exceeds retained byte limit" }
        val body = Body(function.symbol, canonical(path), source.startOffset, source.endOffset, nodes, observed, reasons, calls)
        require(bodies.put(function.symbol, body) == null) { "duplicate authored callable observation" }
    }

    fun finish(identities: IdentityHashMap<FirFunctionSymbol<*>, LinkedHashSet<String>>,
        sources: Map<String, EvidenceProtocol.Source>, artifact: String, compiler: String) {
        var unmapped = 0
        val rows = mutableListOf<AuthoredCallProtocol.Caller>()
        for (body in bodies.values) {
            val callers = identities[body.symbol].orEmpty()
            val file = sources[body.path]
            if (callers.isEmpty() || file == null) { unmapped++; continue }
            val reasons = body.reasons.toMutableSet()
            if (callers.size != 1) reasons += "AMBIGUOUS_BACKEND_ID"
            val calls = mutableListOf<AuthoredCallProtocol.Call>()
            for (call in body.calls) {
                val targets = identities[call.target].orEmpty()
                val targetSource = sources[call.path]
                if (targets.size != 1 || targetSource == null) reasons += "UNMAPPED_TARGET"
                else calls += AuthoredCallProtocol.Call(targets.single(), "PROJECT", targetSource.path(), call.start, call.end)
            }
            for (caller in callers) rows += AuthoredCallProtocol.Caller(caller, file, body.start, body.end,
                body.nodes, body.observed, reasons.sorted(), calls)
        }
        AuthoredCallProtocol.write(options, compiler, artifact, sources.values, unmapped, rows)
    }
    private fun canonical(path: String): String = Path.of(path).toRealPath().toString()
}

internal class KotlinAuthoredChecker(private val state: KotlinAuthoredCalls) :
    FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: org.jetbrains.kotlin.diagnostics.DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) { state.observe(declaration, context) }
}
