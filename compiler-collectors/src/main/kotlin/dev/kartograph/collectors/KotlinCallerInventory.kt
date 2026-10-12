@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
package dev.kartograph.collectors

import java.nio.file.Path
import java.util.IdentityHashMap
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.descriptors.Modality

/** FIR file 전체를 순회하고 미지원 body도 방출 identity와 함께 보존한다. */
internal class KotlinCallerInventory(private val options: EvidenceProtocol.Options) {
    private data class Pending(val body: CallerInventoryProtocol.Body, val function: FirFunctionSymbol<*>?)
    private val bodies = mutableListOf<Pending>()
    val sources = linkedMapOf<String, EvidenceProtocol.Source>()
    private val backend = sortedMapOf<String, String>()
    private var nodes = 0
    private var bytes = 0L

    fun observe(file: FirFile) {
        val path = Path.of(requireNotNull(file.sourceFile?.path) { "caller inventory source is unavailable" }).toRealPath()
        val source = EvidenceProtocol.source(options.root(), path)
        require(sources.putIfAbsent(path.toString(), source) == null) { "duplicate caller inventory source file" }
        val visited = java.util.Collections.newSetFromMap(IdentityHashMap<FirElement, Boolean>())
        val containers = mutableListOf<FirClass>()
        val declarations = mutableListOf<FirDeclaration>()
        var depth = 0
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (!visited.add(element)) return
                require(++nodes <= EvidenceProtocol.MAX_ROWS && ++depth <= AuthoredCallProtocol.MAX_DEPTH) { "caller inventory source limit exceeded" }
                if (element is FirClass) containers += element
                try {
                    when (element) {
                        is FirNamedFunction -> {
                            val kind = when {
                                element.status.modality == Modality.ABSTRACT -> "ABSTRACT_METHOD"
                                element.status.isExternal -> "NATIVE_METHOD"
                                declarations.lastOrNull() is FirFunction -> "LOCAL_FUNCTION"
                                containers.lastOrNull() is FirAnonymousObject -> "ANONYMOUS_METHOD"
                                containers.lastOrNull()?.isLocal == true -> "LOCAL_METHOD"
                                element.isLocal -> "LOCAL_FUNCTION"
                                else -> "ORDINARY_METHOD"
                            }
                            add(element, source, kind, element.body != null, element.symbol)
                        }
                        is FirConstructor -> add(element, source,
                            if (element.source?.kind === KtRealSourceElementKind) "CONSTRUCTOR" else "IMPLICIT_CONSTRUCTOR", true, element.symbol)
                        is FirPropertyAccessor -> add(element, source, "ACCESSOR", element.body != null, element.symbol)
                        is FirAnonymousFunction -> add(element, source, "LAMBDA", element.body != null, element.symbol)
                        is FirAnonymousInitializer -> add(element, source, "INSTANCE_INITIALIZER", true, null)
                        is FirProperty -> if (!element.isLocal) add(element, source, "PROPERTY_INITIALIZER", element.initializer != null || element.delegate != null, null)
                        is FirValueParameter -> if (element.defaultValue != null) add(element, source, "PARAMETER_DEFAULT", true, null)
                    }
                    if (element is FirDeclaration) declarations += element
                    element.acceptChildren(this)
                    if (element is FirDeclaration) declarations.removeAt(declarations.lastIndex)
                } finally {
                    if (element is FirClass) containers.removeAt(containers.lastIndex)
                    depth--
                }
            }
        })
    }
    private fun add(declaration: FirDeclaration, source: EvidenceProtocol.Source, kind: String, present: Boolean, function: FirFunctionSymbol<*>?) {
        val position = declaration.source
        val available = position != null && position.startOffset >= 0 && position.endOffset > position.startOffset
        val origin = "FIR:${declaration.origin}:${position?.kind?.javaClass?.simpleName ?: "NO_SOURCE"}"
        bytes += 512L + 4L * (source.path().length + origin.length)
        require(bodies.size < EvidenceProtocol.MAX_ROWS && bytes <= EvidenceProtocol.MAX_ENCODED_BYTES) { "caller inventory source byte limit exceeded" }
        bodies += Pending(CallerInventoryProtocol.Body("b${bodies.size}", source.path(), if (available) position.startOffset else -1,
            if (available) position.endOffset else -1, kind, present, origin, emptyList()), function)
    }
    fun method(identity: String, origin: String) {
        require(backend.size < EvidenceProtocol.MAX_ROWS && backend.putIfAbsent(identity, origin) == null) { "duplicate caller inventory emitted method" }
    }
    fun finish(factory: ClassFileFactory, identities: IdentityHashMap<FirFunctionSymbol<*>, LinkedHashSet<String>>, artifact: String, compiler: String) {
        var total = 0L
        var methodCount = 0
        val emitted = factory.asList()
        require(emitted.size <= EvidenceProtocol.MAX_ROWS) { "caller inventory output count exceeds limit" }
        val outputs = emitted.map { output ->
            val bytes = output.asByteArray()
            total += bytes.size
            require(total <= CallerOutputHeaders.MAX_OUTPUT_BYTES) { "caller inventory output limit exceeded" }
            CallerOutputHeaders.read(output.relativePath, bytes).also {
                methodCount += it.methods().size
                require(methodCount <= EvidenceProtocol.MAX_ROWS) { "caller inventory method count exceeds limit" }
            }
        }
        val actual = outputs.flatMap { it.methods() }.map { it.identity() }.toSet()
        require(actual == backend.keys) { "caller inventory method callbacks differ from emitted outputs" }
        val rows = bodies.map { pending ->
            val body = pending.body
            CallerInventoryProtocol.Body(body.key(), body.path(), body.start(), body.end(), body.kind(), body.present(), body.origin(),
                identities[pending.function].orEmpty().filter { it in actual })
        }
        sources.values.forEach { source -> require(EvidenceProtocol.source(options.root(), options.root().resolve(source.path())) == source) {
            "caller inventory source changed during compilation"
        } }
        CallerInventoryProtocol.write(options, compiler, artifact, sources.values, rows, outputs, backend)
    }
}
internal class KotlinCallerInventoryChecker(private val state: KotlinCallerInventory) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: org.jetbrains.kotlin.diagnostics.DiagnosticReporter)
    override fun check(declaration: FirFile) { state.observe(declaration) }
}
