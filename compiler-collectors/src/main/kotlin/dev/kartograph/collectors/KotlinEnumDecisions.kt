@file:OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)

package dev.kartograph.collectors

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.contracts.description.LogicOperationKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.getChild
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.expressions.FirBooleanOperatorExpression
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirEqualityOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCallOrigin
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenSubjectExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.expressions.isImplicitWhenSubjectVariable
import org.jetbrains.kotlin.fir.expressions.unwrapSmartcastExpression
import org.jetbrains.kotlin.fir.originalOrSelf
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.toRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.FirResolvedTypeRef
import org.jetbrains.kotlin.fir.types.ProjectionKind
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.ClassIdBasedLocality
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.kotlin.types.Variance

/**
 * 소스 위치의 검토 후보이며, 호출 선택이나 런타임 도달 가능성의 증명은 아니다.
 * importer는 enum 정의를 신고된 출력 루트에 독립적으로 연결해야 한다.
 */
internal class KotlinEnumWhenChecker(private val state: KotlinEvidenceState) :
    FirExpressionChecker<FirWhenExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: org.jetbrains.kotlin.diagnostics.DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        if (!state.enumDecisionsEnabled) return
        val source = expression.source
        if (source == null || source.kind !== KtRealSourceElementKind || source.elementType != KtNodeTypes.WHEN) return
        val authoredElse = expression.branches.filter { branch ->
            val branchSource = branch.source
            branchSource != null && branchSource.kind === KtRealSourceElementKind &&
                branchSource.getChild(KtTokens.ELSE_KEYWORD, depth = 1) != null
        }
        val enumSymbol = enumWhenSubjectType(expression, context)?.let { enumClassSymbol(it, context) }
        if (enumSymbol == null) {
            if (authoredElse.isNotEmpty() && expression.branches.any { enumRelatedCondition(it.condition, context) }) {
                state.observeEnumDecision()
                state.recordEnumUnsupported()
            }
            return
        }
        state.observeEnumDecision()
        if (authoredElse.isEmpty()) {
            state.recordEnumIgnored()
            return
        }
        val owner = enumJvmOwner(enumSymbol.classId)
        val fallback = authoredElse.singleOrNull()
        if (owner == null || fallback == null || expression.branches.lastOrNull() !== fallback ||
            fallback.hasGuard || fallback.condition !is FirElseIfTrueCondition) {
            state.recordEnumUnsupported()
            return
        }
        val constants = linkedSetOf<String>()
        for (branch in expression.branches) {
            if (branch === fallback) continue
            if (branch.hasGuard || !collectEnumWhenLabels(branch.condition, expression, enumSymbol.classId, owner, constants)) {
                state.recordEnumUnsupported()
                return
            }
        }
        recordEnumDecisionAt(state, context, source, owner, "EXPLICIT_FALLBACK", constants)
    }
}

internal class KotlinEnumMembershipChecker(private val state: KotlinEvidenceState) :
    FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: org.jetbrains.kotlin.diagnostics.DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!state.enumDecisionsEnabled) return
        val reference = expression.calleeReference as? FirResolvedNamedReference ?: return
        val selected = reference.resolvedSymbol as? FirFunctionSymbol<*> ?: return
        if (selected.name.asString() != "contains") return
        val rawArgument = expression.arguments.singleOrNull() ?: return
        val argument = unwrapNonSpreadArgument(rawArgument) ?: return
        val enumSymbol = enumClassSymbol(argument.resolvedType, context) ?: return
        state.observeEnumDecision()
        val owner = enumJvmOwner(enumSymbol.classId)
        val source = expression.source
        if (owner == null || source == null || source.kind !== KtRealSourceElementKind ||
            hasUnsupportedMembershipNegation(expression, context)) {
            state.recordEnumUnsupported()
            return
        }
        val kind = when (expression.origin) {
            FirFunctionCallOrigin.Regular -> if (reference.source?.kind === KtRealSourceElementKind) "FINITE_MEMBERSHIP" else null
            FirFunctionCallOrigin.Operator -> when (reference.source?.kind) {
                KtRealSourceElementKind -> "FINITE_MEMBERSHIP"
                KtFakeSourceElementKind.DesugaredInvertedContains -> "NEGATED_FINITE_MEMBERSHIP"
                else -> null
            }
            else -> null
        }
        val receiver = expression.explicitReceiver?.unwrapSmartcastExpression() as? FirFunctionCall
        val factory = (receiver?.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirFunctionSymbol<*>
        if (kind == null || !isStandardCollectionContains(selected.originalOrSelf()) ||
            receiver == null || receiver.source?.kind !== KtRealSourceElementKind ||
            receiver.origin != FirFunctionCallOrigin.Regular || factory == null ||
            !isStandardFiniteCollectionFactory(factory.originalOrSelf())) {
            state.recordEnumUnsupported()
            return
        }
        val arguments = ArrayList<FirExpression>()
        if (!flattenFiniteCollectionArguments(receiver.arguments, arguments)) {
            state.recordEnumUnsupported()
            return
        }
        val constants = linkedSetOf<String>()
        for (element in arguments) {
            val entry = resolvedEnumEntry(element)
            if (entry == null || entry.callableId.classId != enumSymbol.classId) {
                state.recordEnumUnsupported()
                return
            }
            constants += enumEntryIdentity(owner, entry)
        }
        recordEnumDecisionAt(state, context, source, owner, kind, constants)
    }
}

private fun enumWhenSubjectType(expression: FirWhenExpression, context: CheckerContext): ConeKotlinType? {
    val variable = expression.subjectVariable ?: return null
    val useInitializer = variable.isImplicitWhenSubjectVariable ||
        (context.languageVersionSettings.supportsFeature(LanguageFeature.ImprovedExhaustivenessCheckForSubjectVariable24) &&
            (variable.returnTypeRef as? FirResolvedTypeRef)?.delegatedTypeRef == null)
    val declared = if (useInitializer) null else (variable.returnTypeRef as? FirResolvedTypeRef)?.coneType
    return declared ?: variable.initializer?.resolvedType
}

private fun enumClassSymbol(type: ConeKotlinType, context: CheckerContext): FirRegularClassSymbol? =
    type.toRegularClassSymbol(context.session)?.takeIf { it.classKind == ClassKind.ENUM_CLASS }

@OptIn(ClassIdBasedLocality::class)
private fun enumJvmOwner(id: ClassId): String? = if (id.isLocal) null else JvmClassName.byClassId(id).internalName

private fun enumEntryIdentity(owner: String, entry: FirEnumEntrySymbol): String =
    "field:" + owner + "#" + entry.name.asString() + ":L" + owner + ";"

private fun resolvedEnumEntry(expression: FirExpression): FirEnumEntrySymbol? {
    val access = expression.unwrapSmartcastExpression() as? FirPropertyAccessExpression ?: return null
    return (access.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol as? FirEnumEntrySymbol
}

private fun collectEnumWhenLabels(expression: FirExpression, whenExpression: FirWhenExpression, enumId: ClassId,
    owner: String, constants: MutableSet<String>): Boolean {
    val condition = expression.unwrapSmartcastExpression()
    if (condition is FirBooleanOperatorExpression) {
        return condition.kind == LogicOperationKind.OR &&
            collectEnumWhenLabels(condition.leftOperand, whenExpression, enumId, owner, constants) &&
            collectEnumWhenLabels(condition.rightOperand, whenExpression, enumId, owner, constants)
    }
    val equality = condition as? FirEqualityOperatorCall ?: return false
    if (equality.operation != FirOperation.EQ && equality.operation != FirOperation.IDENTITY) return false
    if (equality.arguments.size != 2) return false
    val subject = equality.arguments[0].unwrapSmartcastExpression() as? FirWhenSubjectExpression ?: return false
    if ((subject.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol !== whenExpression.subjectVariable?.symbol) return false
    val entry = resolvedEnumEntry(equality.arguments[1]) ?: return false
    if (entry.callableId.classId != enumId) return false
    constants += enumEntryIdentity(owner, entry)
    return constants.size <= EvidenceProtocol.MAX_ENUM_CONSTANTS
}

private fun enumRelatedCondition(expression: FirExpression, context: CheckerContext): Boolean {
    val value = expression.unwrapSmartcastExpression()
    if (enumClassSymbol(value.resolvedType, context) != null) return true
    return when (value) {
        is FirBooleanOperatorExpression -> enumRelatedCondition(value.leftOperand, context) || enumRelatedCondition(value.rightOperand, context)
        is FirCall -> value.arguments.any { enumRelatedCondition(it, context) }
        is FirWrappedArgumentExpression -> enumRelatedCondition(value.expression, context)
        else -> false
    }
}

private fun unwrapNonSpreadArgument(expression: FirExpression): FirExpression? {
    val value = expression.unwrapSmartcastExpression()
    return if (value is FirWrappedArgumentExpression) {
        if (value.isSpread) null else unwrapNonSpreadArgument(value.expression)
    } else value
}

private fun flattenFiniteCollectionArguments(arguments: List<FirExpression>, destination: MutableList<FirExpression>): Boolean {
    for (argument in arguments) {
        when (val value = argument.unwrapSmartcastExpression()) {
            is FirVarargArgumentsExpression -> if (!flattenFiniteCollectionArguments(value.arguments, destination)) return false
            is FirWrappedArgumentExpression -> if (value.isSpread ||
                !flattenFiniteCollectionArguments(listOf(value.expression), destination)) return false
            else -> {
                if (destination.size == EvidenceProtocol.MAX_ENUM_CONSTANTS) return false
                destination += value
            }
        }
    }
    return true
}

private fun isStandardCollectionContains(symbol: FirFunctionSymbol<*>): Boolean {
    val id = symbol.callableId ?: return false
    return id.packageName.asString() == "kotlin.collections" &&
        id.className?.asString() in setOf("Collection", "List", "Set") && id.callableName.asString() == "contains" &&
        symbol.fir.valueParameters.size == 1 &&
        (symbol.origin == FirDeclarationOrigin.Library || symbol.origin.isBuiltIns)
}

/** Library 출처와 모델링한 선언 형태만으로 stdlib 산출물의 진위를 보증하지 않는다. */
private fun isStandardFiniteCollectionFactory(symbol: FirFunctionSymbol<*>): Boolean {
    val id = symbol.callableId ?: return false
    val expectedReturn = when (id.callableName.asString()) {
        "setOf" -> StandardClassIds.Set
        "listOf" -> StandardClassIds.List
        else -> return false
    }
    val declaration = symbol.fir
    if (id.classId != null || id.packageName.asString() != "kotlin.collections" ||
        symbol.origin != FirDeclarationOrigin.Library ||
        declaration.receiverParameter != null || declaration.dispatchReceiverType != null ||
        declaration.contextParameters.isNotEmpty() || symbol.resolvedStatus.isSuspend) return false
    val parameter = symbol.typeParameterSymbols.singleOrNull() ?: return false
    if (parameter.isReified || parameter.variance != Variance.INVARIANT) return false
    val bound = parameter.resolvedBounds.singleOrNull()?.coneType as? ConeClassLikeType ?: return false
    if (bound.lookupTag.classId != StandardClassIds.Any || !bound.isMarkedNullable || bound.typeArguments.isNotEmpty()) return false
    val result = symbol.resolvedReturnType as? ConeClassLikeType ?: return false
    if (result.lookupTag.classId != expectedReturn || result.isMarkedNullable ||
        !isExactFactoryTypeParameter(result.typeArguments.singleOrNull() as? ConeKotlinType, parameter)) return false
    val values = declaration.valueParameters
    if (values.isEmpty()) return true
    val value = values.singleOrNull() ?: return false
    if (value.defaultValue != null || value.isCrossinline || value.isNoinline) return false
    val valueType = value.symbol.resolvedReturnType
    if (!value.isVararg) return isExactFactoryTypeParameter(valueType, parameter)
    val array = valueType as? ConeClassLikeType ?: return false
    if (array.lookupTag.classId != StandardClassIds.Array || array.isMarkedNullable) return false
    val element = array.typeArguments.singleOrNull() as? ConeKotlinTypeProjection ?: return false
    return element.kind == ProjectionKind.OUT && isExactFactoryTypeParameter(element.type, parameter)
}

private fun isExactFactoryTypeParameter(type: ConeKotlinType?, parameter: FirTypeParameterSymbol): Boolean {
    val reference = type as? ConeTypeParameterType ?: return false
    return !reference.isMarkedNullable && reference.lookupTag.typeParameterSymbol === parameter
}

/** 직접 작성한 !contains/.not()는 중첩·괄호 형태도 포함해 내부의 양성 행을 억제한다. */
private fun hasUnsupportedMembershipNegation(expression: FirFunctionCall, context: CheckerContext): Boolean {
    val elements = context.containingElements
    val currentIndex = elements.indexOfLast { it === expression }
    if (currentIndex < 0) return true
    val expectsSyntheticInversion = expression.origin == FirFunctionCallOrigin.Operator &&
        expression.calleeReference.source?.kind === KtFakeSourceElementKind.DesugaredInvertedContains
    var sawSyntheticInversion = false
    var child: FirExpression = expression
    for (index in currentIndex - 1 downTo 0) {
        val parent = elements[index]
        if (parent is FirSmartCastExpression && parent.originalExpression === child) {
            child = parent
            continue
        }
        val call = parent as? FirFunctionCall ?: break
        if (call.explicitReceiver !== child) break
        val reference = call.calleeReference as? FirResolvedNamedReference ?: return true
        val symbol = (reference.resolvedSymbol as? FirFunctionSymbol<*>)?.originalOrSelf() ?: return true
        if (symbol.name.asString() != "not") break
        val id = symbol.callableId ?: return true
        val isBooleanNot = id.packageName.asString() == "kotlin" && id.className?.asString() == "Boolean" &&
            symbol.fir.valueParameters.isEmpty() && (symbol.origin == FirDeclarationOrigin.Library || symbol.origin.isBuiltIns)
        if (!isBooleanNot) return true
        if (!expectsSyntheticInversion || sawSyntheticInversion || call.origin != FirFunctionCallOrigin.Operator ||
            call.source?.kind !== KtFakeSourceElementKind.DesugaredInvertedContains ||
            reference.source?.kind !== KtFakeSourceElementKind.DesugaredInvertedContains) return true
        sawSyntheticInversion = true
        child = call
    }
    return expectsSyntheticInversion && !sawSyntheticInversion
}

private fun recordEnumDecisionAt(state: KotlinEvidenceState, context: CheckerContext, source: KtSourceElement,
    owner: String, kind: String, constants: Set<String>) {
    val path = context.containingFile?.path
    val mapping = context.containingFileSymbol?.fir?.sourceFileLinesMapping
    val coordinates = if (mapping != null && source.startOffset >= 0 && source.endOffset > source.startOffset &&
        source.endOffset <= mapping.lastOffset) mapping.getLineAndColumnByOffset(source.startOffset) else null
    if (path == null || coordinates == null) {
        state.recordEnumUnsupported()
        return
    }
    val host = context.containingDeclarations.filterIsInstance<FirClassLikeSymbol<*>>().lastOrNull() as? FirRegularClassSymbol
    val lexicalClass = host?.classId?.let(::enumJvmOwner)?.let { "class:$it" }
    val lexicalLambda = context.containingDeclarations.any { it is FirAnonymousFunctionSymbol }
    state.recordEnumDecision(path, source.startOffset, source.endOffset, coordinates.first + 1, coordinates.second + 1,
        lexicalClass, "class:$owner", kind, constants.sorted(), lexicalLambda)
}
