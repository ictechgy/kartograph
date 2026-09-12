@file:OptIn(
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class,
)

package dev.kartograph.collectors

import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.IdentityHashMap
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGenerator
import org.jetbrains.kotlin.backend.jvm.extensions.ClassGeneratorExtension
import org.jetbrains.kotlin.codegen.ClassFileFactory
import org.jetbrains.kotlin.codegen.extensions.ClassFileFactoryFinalizerExtension
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.RecordComponentVisitor

private object KotlinEvidenceConfiguration {
    val ROOT = CompilerConfigurationKey<String>("kartograph.compiler.evidence.root")
    val OUTPUT = CompilerConfigurationKey<String>("kartograph.compiler.evidence.output")
    val TOKEN = CompilerConfigurationKey<String>("kartograph.compiler.evidence.token")
}

/** Kotlin compiler 2.4.10이 해석한 옵션을 compiler plugin configuration으로 전달한다. */
public class KotlinEvidenceCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "kartograph.compiler-evidence"
    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("root", "<path>", "project root for source identities", true, false),
        CliOption("output", "<path>", "raw evidence TSV file or directory", true, false),
        CliOption("token", "<path>", "compiler input token file", true, false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "root" -> configuration.put(KotlinEvidenceConfiguration.ROOT, value)
            "output" -> configuration.put(KotlinEvidenceConfiguration.OUTPUT, value)
            "token" -> configuration.put(KotlinEvidenceConfiguration.TOKEN, value)
            else -> error("unsupported Kotlin compiler evidence option")
        }
    }
}

/** FIR semantic references와 JVM backend가 실제로 방출한 이름을 같은 source declaration에 연결한다. */
public class KotlinEvidenceRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true
    override val pluginId: String = "kartograph.compiler-evidence"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val root = EvidenceProtocol.pathOption(requireNotNull(configuration[KotlinEvidenceConfiguration.ROOT]))
        val output = EvidenceProtocol.pathOption(requireNotNull(configuration[KotlinEvidenceConfiguration.OUTPUT]))
        val token = EvidenceProtocol.pathOption(requireNotNull(configuration[KotlinEvidenceConfiguration.TOKEN]))
        val options = EvidenceProtocol.Options(root, output, token, "kotlin-constants")
        EvidenceProtocol.invalidateOutput(options)
        val state = KotlinEvidenceState(options)
        KotlinCompilerBridge.registerFir(this, KotlinFirRegistrar(state))
        KotlinCompilerBridge.register(this, IrGenerationExtension.Companion, object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                state.recordSources(moduleFragment)
            }
        })
        KotlinCompilerBridge.register(this, ClassGeneratorExtension.Companion, state.classGenerator())
        KotlinCompilerBridge.register(this, ClassFileFactoryFinalizerExtension.Companion, state.finalizer())
    }
}

private class KotlinFirRegistrar(private val state: KotlinEvidenceState) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> KotlinReferenceCheckers(session, state) }
    }
}

private class KotlinReferenceCheckers(session: FirSession, private val state: KotlinEvidenceState) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val propertyAccessExpressionCheckers = setOf(KotlinReferenceChecker(state))
    }
}

private class KotlinReferenceChecker(private val state: KotlinEvidenceState) : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: org.jetbrains.kotlin.diagnostics.DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol ?: return
        if (!symbol.resolvedStatus.isConst) return
        if (context.containingDeclarations.none { it is FirCallableSymbol<*> }) {
            this@KotlinReferenceChecker.state.recordUnmapped()
            return
        }
        val sourceFile = context.containingFile
        val path = sourceFile?.path
        val declaration = context.containingDeclarations.filterIsInstance<FirFunctionSymbol<*>>().lastOrNull()?.source
        val offset = expression.source?.startOffset
        if (path == null || declaration == null || offset == null) {
            this@KotlinReferenceChecker.state.recordUnmapped()
            return
        }
        this@KotlinReferenceChecker.state.recordReference(path, declaration.startOffset, declaration.endOffset, offset, symbol)
    }
}

private data class SourcePoint(val path: String, val start: Int, val end: Int, val use: Int)
private data class Reference(val caller: SourcePoint, val target: String)
private data class MethodCandidate(val path: String, val start: Int, val end: Int, val identity: String)
private data class FieldCandidate(val callableId: String, val identity: String)

private class KotlinEvidenceState(private val options: EvidenceProtocol.Options) {
    private val initialToken = EvidenceProtocol.readToken(options)
    private val artifact = EvidenceProtocol.artifactFingerprint(KotlinEvidenceRegistrar::class.java)
    private val sources = linkedMapOf<String, EvidenceProtocol.Source>()
    private val references = LinkedHashSet<Reference>()
    private val methods = LinkedHashSet<MethodCandidate>()
    private val fields = LinkedHashSet<FieldCandidate>()
    private val propertyNames = IdentityHashMap<IrPropertySymbol, String>()
    private val originalFields = IdentityHashMap<IrField, String>()
    private var unresolvedReferences = 0
    private var invalid = false
    private var invalidReason: String? = null

    fun recordSources(module: IrModuleFragment) {
        // lowering 전의 의미적 선언과 backend가 방출하는 동일 symbol/field 객체를 연결한다.
        module.acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
            override fun visitProperty(declaration: IrProperty) {
                declaration.fqNameWhenAvailable?.asString()?.let { name ->
                    propertyNames[declaration.symbol] = name
                    declaration.backingField?.let { originalFields[it] = name }
                }
                declaration.acceptChildrenVoid(this)
            }
        })
        module.files.forEach { file ->
            try {
                val sourceFile = KotlinCompilerBridge.ioFile(file)?.toPath() ?: error("Kotlin source file is unavailable")
                val source = EvidenceProtocol.source(options.root(), sourceFile)
                val key = sourceFile.toAbsolutePath().normalize().toString()
                val previous = sources.putIfAbsent(key, source)
                if (previous != null && previous != source) fail("Kotlin source changed during collection")
            } catch (_: RuntimeException) {
                fail("Kotlin compiler source inventory is unavailable")
            }
        }
    }

    fun recordReference(callerPath: String, callerStart: Int, callerEnd: Int, use: Int, target: FirPropertySymbol) {
        val targetId = target.callableId?.asSingleFqName()?.asString()
        if (targetId == null) {
            recordUnmapped()
            return
        }
        references += Reference(
            SourcePoint(Path.of(callerPath).toAbsolutePath().normalize().toString(), callerStart, callerEnd, use),
            targetId,
        )
    }

    fun recordUnmapped() {
        unresolvedReferences++
    }

    fun classGenerator(): ClassGeneratorExtension = object : ClassGeneratorExtension {
        override fun generateClass(generator: ClassGenerator, irClass: IrClass?): ClassGenerator =
            RecordingClassGenerator(generator, this@KotlinEvidenceState)
    }

    fun finalizer(): ClassFileFactoryFinalizerExtension = object : ClassFileFactoryFinalizerExtension {
        override fun finalizeClassFactory(classFactory: ClassFileFactory) {
            finish(classFactory)
        }
    }

    fun recordMethod(function: IrFunction?, owner: String?, name: String, descriptor: String) {
        if (function == null || owner == null) return
        val file = KotlinCompilerBridge.ioFileForDeclaration(function) ?: return
        val start = function.startOffset
        val end = function.endOffset
        if (start < 0 || end < start) return
        methods += MethodCandidate(
            file.toPath().toAbsolutePath().normalize().toString(),
            start,
            end,
            "method:$owner#$name$descriptor",
        )
    }

    fun recordField(field: IrField?, owner: String?, name: String, descriptor: String) {
        if (field == null || owner == null) return
        val callableId = field.correspondingPropertySymbol?.let(propertyNames::get) ?: originalFields[field] ?: return
        fields += FieldCandidate(callableId, "field:$owner#$name:$descriptor")
    }

    private fun finish(classFactory: ClassFileFactory) {
        if (invalid || classFactory.asList().isEmpty() || sources.isEmpty()) return
        try {
            if (EvidenceProtocol.readToken(options) != initialToken) {
                fail("Kotlin compiler evidence token changed during compilation")
                return
            }
            val sourceValues = sources.values.toList()
            sourceValues.forEach { source ->
                val current = EvidenceProtocol.source(options.root(), options.root().resolve(source.path()))
                if (current != source) {
                    fail("Kotlin source changed during compilation")
                    return
                }
            }
            val edges = linkedSetOf<EvidenceProtocol.Edge>()
            var unmapped = unresolvedReferences
            references.forEach { reference ->
                val caller = methods.filter { method ->
                    method.path == reference.caller.path && method.start >= reference.caller.start && method.end <= reference.caller.end &&
                        reference.caller.use >= method.start && reference.caller.use < method.end
                }
                val target = fields.filter { field -> field.callableId == reference.target }
                if (caller.size != 1 || target.size != 1) unmapped++
                else edges += EvidenceProtocol.Edge(caller.single().identity, target.single().identity, "constant")
            }
            EvidenceProtocol.write(options, KotlinCompilerVersion.VERSION, artifact, sourceValues, unmapped, edges)
            if (unmapped > 0) diagnostic("unmapped Kotlin compiler references")
        } catch (error: RuntimeException) {
            fail("Kotlin compiler evidence could not be published")
            throw error
        }
    }

    private fun fail(reason: String) {
        invalid = true
        if (invalidReason == null) invalidReason = reason
        diagnostic(reason)
    }

    private fun diagnostic(reason: String) {
        System.err.println("Kartograph compiler evidence incomplete: $reason")
    }
}

private class RecordingClassGenerator(
    private val delegate: ClassGenerator,
    private val state: KotlinEvidenceState,
) : ClassGenerator {
    private var owner: String? = null

    override fun defineClass(version: Int, access: Int, name: String, signature: String?, superName: String, interfaces: Array<out String>) {
        owner = name
        delegate.defineClass(version, access, name, signature, superName, interfaces)
    }

    override fun newField(field: IrField?, access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
        state.recordField(field, owner, name, descriptor)
        return delegate.newField(field, access, name, descriptor, signature, value)
    }

    override fun newMethod(function: IrFunction?, access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
        state.recordMethod(function, owner, name, descriptor)
        return delegate.newMethod(function, access, name, descriptor, signature, exceptions)
    }

    override fun newRecordComponent(name: String, descriptor: String, signature: String?): RecordComponentVisitor =
        delegate.newRecordComponent(name, descriptor, signature)

    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = delegate.visitAnnotation(descriptor, visible)

    override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) =
        delegate.visitInnerClass(name, outerName, innerName, access)

    override fun visitEnclosingMethod(owner: String, name: String?, descriptor: String?) = delegate.visitEnclosingMethod(owner, name, descriptor)

    override fun visitSource(name: String, debug: String?) = delegate.visitSource(name, debug)

    override fun done(wasFullyGenerated: Boolean) = delegate.done(wasFullyGenerated)
}
