@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
package probe

import java.io.File
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol

/** Kotlin 2.4.10 전용 비교 실험이며 배포 plugin에 포함하지 않는다. 전체 재컴파일만 검사한다. */
class ReferenceRegistrar : CompilerPluginRegistrar() {
    override val supportsK2 = true
    override val pluginId = "kartograph.reference-experiment"
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val directory = requireNotNull(configuration[JVMConfigurationKeys.OUTPUT_DIRECTORY])
        directory.mkdirs()
        val output = File(directory, "compiler-references.tsv")
        output.writeText("compiler\t${org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION}\t-\t0\n")
        FirExtensionRegistrarAdapter.registerExtension(ReferenceFirRegistrar(output))
        IrGenerationExtension.registerExtension(object : IrGenerationExtension {
            override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
                moduleFragment.acceptChildrenVoid(object : IrVisitorVoid() {
                    private var caller: String? = null
                    override fun visitElement(element: IrElement) { element.acceptChildrenVoid(this) }
                    override fun visitFunction(declaration: IrFunction) {
                        val previous = caller
                        caller = declaration.fqNameWhenAvailable?.asString()
                        declaration.acceptChildrenVoid(this)
                        caller = previous
                    }
                    override fun visitGetField(expression: IrGetField) {
                        val target = expression.symbol.owner.fqNameWhenAvailable?.asString()
                        if (caller != null && target != null) synchronized(output) {
                            output.appendText("ir\t$caller\t$target\t${expression.startOffset}\n")
                        }
                        expression.acceptChildrenVoid(this)
                    }
                })
            }
        })
    }
}

private class ReferenceFirRegistrar(private val output: File) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() { +{ session: FirSession -> ReferenceCheckers(session, output) } }
}

private class ReferenceCheckers(session: FirSession, output: File) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers = object : ExpressionCheckers() {
        override val propertyAccessExpressionCheckers = setOf(References(output))
    }
}

private class References(private val output: File) : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val symbol = (expression.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirPropertySymbol ?: return
        if (!symbol.resolvedStatus.isConst) return
        val caller = context.containingDeclarations.filterIsInstance<FirCallableSymbol<*>>().lastOrNull()?.callableId ?: return
        val offset = expression.source?.startOffset ?: return
        // 상수 원문은 기록하지 않고 해석된 symbol과 source offset만 비교한다.
        synchronized(output) { output.appendText("fir\t$caller\t${symbol.callableId}\t$offset\n") }
    }
}
