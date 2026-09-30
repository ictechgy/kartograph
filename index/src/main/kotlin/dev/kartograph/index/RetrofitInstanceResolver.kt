package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Retrofit 서비스가 만들어지는 base 결합 하나다.
 *
 * @property base 정적으로 푼 base URL이다. null이면 값을 증명하지 못했다(실행 시점 값·모호한 DI·빌더에 base 없음)
 * @property baseRef base를 공급하는 선언(Retrofit 인스턴스를 담은 함수·속성·DI provider)의 생산자 id다. 선언을 찾지 못하면 null이다
 */
internal data class RetrofitBinding(val base: RetrofitBaseUrl?, val baseRef: String?)

/**
 * Retrofit `create` 호출의 수신 식을 따라가 그 인스턴스의 `baseUrl` 값과 선언 신원을 푼다.
 *
 * 따라가는 모양: 같은 식의 `Retrofit.Builder()…baseUrl(x)…build()` 사슬(`apply { baseUrl(x) }` 포함), 같은 함수의 `val`, 감싸는
 * 타입·파일·다른 파일의 속성(`= …`, `by lazy { … }`, getter, `var`·Java 필드의 모든 대입), 함수 호출의 식 몸체·`return`,
 * Dagger/Hilt `@Provides` provider(`@Inject` 생성자·필드와 `@Provides` 매개변수, 한정자 일치), Koin `get()`과 `single`·`factory`
 * 정의. base 값은 리터럴·Kotlin 템플릿·같은 파일/다른 파일 상수·읽기 전용 속성의 초기식·`BuildConfig` 필드([BuildConfigFields])·
 * `HttpUrl` 래퍼를 푼다.
 * 그 밖의 식은 값을 모르는 결합으로 남긴다 — 추측한 base로 템플릿을 확정하지 않는다.
 */
internal class RetrofitInstanceResolver(
    files: List<RouteSourceFile>,
    private val buildConfig: BuildConfigFields,
) {
    /** 파일 밖 선언 색인이다. */
    val declarations: SourceDeclarations = SourceDeclarations(files)

    /** 값 원천을 찾는 공유 어휘 도구다. */
    private val scopes = SourceScopes(declarations)

    private val pathResolvers = java.util.IdentityHashMap<RouteSourceFile, RoutePathResolver>()

    /** Koin `single`·`factory` 정의 중 Retrofit을 만드는 것들이다. */
    private val koinDefinitions: List<KoinDefinition> by lazy { collectKoinDefinitions(files) }

    /**
     * `create` 수신 식 [start, end)의 결합들이다. 수신 식이 비어 있으면(`with(retrofit) { create(…) }`) 모르는 결합 하나다.
     */
    fun resolveReceiver(file: RouteSourceFile, start: Int, end: Int): List<RetrofitBinding> {
        if (start >= end || file.masked.substring(start, end).isBlank()) return listOf(RetrofitBinding(null, null))
        return expression(file, start, end, scopes.declarationId(file, start), Budget())
    }

    /** 재귀 한도와 순환 방지다 — 서로를 참조하는 속성·함수가 무한히 따라가지 않게 한다. */
    private class Budget {
        // RouteSourceFile은 data class가 아니라 동등성이 곧 인스턴스 신원이다.
        private val visiting = mutableSetOf<Pair<RouteSourceFile, Int>>()
        var depth = 0

        fun enter(file: RouteSourceFile, start: Int): Boolean {
            if (depth >= MAX_DEPTH || !visiting.add(file to start)) return false
            depth++
            return true
        }

        fun leave(file: RouteSourceFile, start: Int) {
            visiting.remove(file to start)
            depth--
        }
    }

    private fun unknown(ref: String?): List<RetrofitBinding> = listOf(RetrofitBinding(null, ref))

    /** Retrofit 값을 내는 식 [start, end)를 푼다. */
    private fun expression(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): List<RetrofitBinding> {
        val trimmedStart = skipSpaces(file.masked, start)
        if (!budget.enter(file, trimmedStart)) return unknown(ref)
        try {
            val segments = chainSegments(file, trimmedStart, end) ?: return unknown(ref)
            val rootSize = rootLength(segments)
            for (index in segments.lastIndex downTo rootSize) {
                val segment = segments[index]
                when {
                    segment.name == "baseUrl" && segment.arguments != null -> return baseBindings(file, segment.arguments, ref, budget)
                    segment.name in SCOPE_BLOCKS && segment.lambda != null && segment.arguments == null -> {
                        lambdaBaseUrl(file, segment.lambda)?.let { return baseBindings(file, it, ref, budget) }
                    }
                    segment.name in BUILDER_METHODS -> Unit
                    else -> return unknown(ref)
                }
            }
            return root(file, segments.subList(0, rootSize), ref, budget)
        } finally {
            budget.leave(file, trimmedStart)
        }
    }

    /** 사슬의 뿌리(`Retrofit.Builder()`, 참조, 함수 호출, Koin `get()`)를 푼다. */
    private fun root(file: RouteSourceFile, segments: List<ChainSegment>, ref: String?, budget: Budget): List<RetrofitBinding> {
        val last = segments.last()
        val qualifier = segments.dropLast(1).joinToString(".") { it.name }
        if (last.lambda != null) return unknown(ref)
        if (last.arguments == null) return reference(file, qualifier, last.name, segments.first().start, ref, budget)
        if (last.name == "Builder" && (qualifier.endsWith("Retrofit") || qualifier.isEmpty() && file.visibleNameOf("retrofit2.Retrofit.Builder") == "Builder")) {
            // baseUrl 없이 build()하면 Retrofit이 거부한다 — 이 결합으로는 요청이 나가지 않는다.
            return unknown(ref)
        }
        if (qualifier.isEmpty() && last.name == "get" && file.imports.any { it.path.startsWith("org.koin.") }) return koin(file, last, budget)
        val (owner, function) = declarations.findFunction(file, qualifier, last.name, last.start) ?: return unknown(null)
        return functionBody(owner, function, scopes.functionId(owner, function), budget)
    }

    /** 식별자 참조 `qualifier.name`을 지역 `val`·매개변수·속성으로 따라간다. */
    private fun reference(file: RouteSourceFile, qualifier: String, name: String, offset: Int, ref: String?, budget: Budget): List<RetrofitBinding> {
        if (qualifier.isEmpty()) {
            scopes.localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) expression(file, range.first, range.last + 1, ref, budget) else unknown(ref)
            }
            scopes.parameter(file, name, offset)?.let { (function, parameter) -> return injectedParameter(file, function, parameter, budget) }
        }
        val property = if (qualifier.isEmpty() || qualifier == "this") scopes.propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        if (property != null) return propertyBindings(property.first, property.second, budget)
        if (qualifier.isEmpty() || qualifier == "this") {
            scopes.constructorParameter(file, name, offset)?.let { (header, parameter) -> return injectedConstructorParameter(header, parameter, budget) }
        }
        return unknown(null)
    }

    /** 속성·필드 값의 원천(초기식, lazy 본문의 마지막 식, getter, 가변이면 모든 대입)을 푼다. */
    private fun propertyBindings(file: RouteSourceFile, property: SourceProperty, budget: Budget): List<RetrofitBinding> {
        val ref = "kt:" + (listOf(property.ownerFqn(file)).filter { it.isNotEmpty() } + property.name).joinToString(".")
        val sources = mutableListOf<IntRange>()
        property.initializer?.takeUnless { scopes.isNullLiteral(file, it) }?.let(sources::add)
        property.lazyBody?.let { scopes.lastStatement(file, it) }?.let(sources::add)
        property.getterBody?.let { body -> sources += if (file.masked.substring(body).contains(RETURN)) scopes.returnRanges(file, body) else listOf(body) }
        if (property.mutable || sources.isEmpty() && property.lazyBody == null && property.getterBody == null) sources += scopes.assignments(file, property)
        if (sources.isEmpty()) {
            return if (INJECT.containsMatchIn(property.annotations)) injected(declarations.qualifiers(property.annotations), budget) else unknown(ref)
        }
        return sources.flatMap { expression(file, it.first, it.last + 1, ref, budget) }.distinct()
    }

    /** 함수 몸체의 값: 식 몸체면 그 식, 블록이면 모든 `return` 식이다. */
    private fun functionBody(file: RouteSourceFile, function: RouteFunctionDecl, ref: String, budget: Budget): List<RetrofitBinding> {
        val bodyStart = function.bodyStart
        if (bodyStart < 0 || bodyStart >= file.masked.length) return unknown(ref)
        if (file.masked[bodyStart] == '=') return expression(file, bodyStart + 1, function.end, ref, budget)
        val returns = scopes.returnRanges(file, (bodyStart + 1) until function.end.coerceAtMost(file.masked.length))
        if (returns.isEmpty()) return unknown(ref)
        return returns.flatMap { expression(file, it.first, it.last + 1, ref, budget) }.distinct()
    }

    /**
     * 함수 매개변수로 받은 Retrofit이다. `@Provides` provider의 매개변수와 `@Inject` 생성자(Java)의 매개변수만 DI 그래프에서 풀고,
     * 그 밖의 매개변수는 호출자마다 다를 수 있어 모른다.
     */
    private fun injectedParameter(file: RouteSourceFile, function: RouteFunctionDecl, parameter: SourceParameter, budget: Budget): List<RetrofitBinding> {
        if (parameter.type != "Retrofit") return unknown(null)
        val annotations = functionAnnotations(file, function)
        if (!PROVIDES.containsMatchIn(annotations) && !INJECT.containsMatchIn(annotations)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** Kotlin 주 생성자 매개변수로 받은 Retrofit이다. `@Inject constructor`일 때만 DI로 푼다. */
    private fun injectedConstructorParameter(header: String, parameter: SourceParameter, budget: Budget): List<RetrofitBinding> {
        if (parameter.type != "Retrofit" || !INJECT.containsMatchIn(header)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** 한정자가 같은 `@Provides` Retrofit provider가 하나일 때만 그 몸체를 푼다. 없거나 여럿이면 모른다. */
    private fun injected(qualifiers: Set<String>, budget: Budget): List<RetrofitBinding> {
        val provider = declarations.retrofitProviders.filter { (file, function) ->
            declarations.qualifiers(functionAnnotations(file, function)) == qualifiers
        }.singleOrNull() ?: return unknown(null)
        return functionBody(provider.first, provider.second, scopes.functionId(provider.first, provider.second), budget)
    }

    /** Koin `get()`·`get<Retrofit>()`·`get(named("x"))`을 한정자가 같은 Retrofit 정의 하나로 푼다. */
    private fun koin(file: RouteSourceFile, segment: ChainSegment, budget: Budget): List<RetrofitBinding> {
        val typeArgument = segment.typeArguments?.trim()?.substringAfterLast('.')
        if (typeArgument != null && typeArgument != "Retrofit") return unknown(null)
        val qualifier = segment.arguments?.let { koinQualifier(file.code.substring(it.first + 1, it.last)) }
        val definition = koinDefinitions.filter { it.qualifier == qualifier }.singleOrNull() ?: return unknown(null)
        return expression(definition.file, definition.body.first, definition.body.last + 1, definition.ref, budget)
    }

    private fun collectKoinDefinitions(files: List<RouteSourceFile>): List<KoinDefinition> = files
        .filter { file -> file.imports.any { it.path.startsWith("org.koin.") } }
        .flatMap { file ->
            KOIN_DEFINITION.findAll(file.masked).mapNotNull { match ->
                val open = match.range.last
                val close = file.braceEnd(open).takeIf { it > open } ?: return@mapNotNull null
                val body = scopes.lastStatement(file, (open + 1) until close) ?: return@mapNotNull null
                val typeArgument = match.groupValues[2].trim().substringAfterLast('.')
                val statement = file.masked.substring(body)
                val buildsRetrofit = typeArgument == "Retrofit" || typeArgument.isEmpty() && RETROFIT_BUILD_CHAIN.containsMatchIn(statement)
                if (!buildsRetrofit) return@mapNotNull null
                val qualifier = match.groups[3]?.let { koinQualifier(file.code.substring(it.range.first + 1, it.range.last)) }
                val ref = (scopes.declarationId(file, match.range.first) ?: "kt:${file.packageName}") + (qualifier?.let { "#$it" } ?: "")
                KoinDefinition(file, body, qualifier, ref)
            }.toList()
        }

    /** Koin 한정자 인자(`named("x")`, `qualifier = named("x")`)를 `named:x`로 정규화한다. 없으면 null이다. */
    private fun koinQualifier(arguments: String): String? {
        val match = KOIN_NAMED.find(arguments) ?: return null
        return "named:" + (scriptString(match.groupValues[1]) ?: match.groupValues[1].trim())
    }

    /** base 인자 범위의 결합들이다. 값 집합의 각 URL마다 하나다. */
    private fun baseBindings(file: RouteSourceFile, arguments: IntRange, ref: String?, budget: Budget): List<RetrofitBinding> {
        val argument = callArguments(file.code, arguments.first, arguments.last).singleOrNull() ?: return unknown(ref)
        val values = baseValues(file, argument, arguments.first + 1, budget) ?: return unknown(ref)
        return values.map { RetrofitBinding(RetrofitBaseUrl.parse(it), ref) }.distinct()
    }

    /**
     * base URL 식의 값 집합이다. 리터럴·템플릿·상수(같은 파일·다른 파일)·지역 `val`·`BuildConfig`·`HttpUrl` 래퍼를 따라가고,
     * 값을 증명하지 못하면 null이다.
     */
    private fun baseValues(file: RouteSourceFile, text: String, offset: Int, budget: Budget): List<String>? {
        if (budget.depth >= MAX_DEPTH) return null
        budget.depth++
        try {
            val expression = unwrapHttpUrl(unwrapParentheses(text.trim()).removeSuffix("!!").trim())
            resolver(file).literalValue(expression, offset)?.let { return listOf(it) }
            if (REFERENCE.matches(expression)) return referenceValues(file, expression, offset, budget)
            // 다른 파일 상수가 섞인 연결·템플릿이다. 조각마다 값이 하나일 때만 잇는다.
            val parts = resolver(file).parts(expression, offset)
            if (parts.size < 2) return null
            val pieces = parts.map { part ->
                when (part) {
                    is UrlPart.Literal -> part.text
                    is UrlPart.Value -> part.expression.takeIf(REFERENCE::matches)?.let { referenceValues(file, it, offset, budget) }?.singleOrNull() ?: return null
                    is UrlPart.QueryTail -> return null
                }
            }
            return listOf(pieces.joinToString(""))
        } finally {
            budget.depth--
        }
    }

    /** `x.toHttpUrl()`·`HttpUrl.get(x)`·`HttpUrl.parse(x)!!` 같은 OkHttp 래퍼를 벗긴 문자열 식이다. */
    private fun unwrapHttpUrl(expression: String): String {
        HTTP_URL_EXTENSION.matchEntire(expression)?.let { return unwrapHttpUrl(it.groupValues[1].trim()) }
        HTTP_URL_FACTORY.matchEntire(expression)?.let { match ->
            val open = match.groups[1]!!.range.first
            val close = balancedEnd(expression, open)
            if (close == expression.length - 1) return unwrapHttpUrl(expression.substring(open + 1, close).trim())
        }
        return expression
    }

    /** 참조 식의 값: `BuildConfig` 필드, 지역 `val`, 파일 상수(같은 파일·다른 파일), 읽기 전용 속성 순이다. */
    private fun referenceValues(file: RouteSourceFile, expression: String, offset: Int, budget: Budget): List<String>? {
        val name = expression.substringAfterLast('.')
        val qualifier = expression.substringBeforeLast('.', "")
        if (qualifier == "BuildConfig" || qualifier.endsWith(".BuildConfig")) {
            val referencePackage = if ('.' in qualifier) qualifier.removeSuffix(".BuildConfig")
            else file.imports.firstOrNull { it.path.endsWith(".BuildConfig") && it.alias == null }?.path?.removeSuffix(".BuildConfig") ?: file.packageName
            return buildConfig.values(file, name, referencePackage)
        }
        if (qualifier.isEmpty()) {
            scopes.localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) baseValues(file, file.code.substring(range), range.first, budget) else null
            }
            if (scopes.parameter(file, name, offset) != null) return null
        }
        declarations.findConstant(file, qualifier, name)?.let { (owner, constant) -> return baseValues(owner, constant.expression, 0, budget) }
        // 상수가 아닌 읽기 전용 속성(`private val baseUrl = "…"`, `val url = HttpUrl.get("…")`)도 초기식이 값이다.
        val property = if (qualifier.isEmpty() || qualifier == "this") scopes.propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        val (owner, declaration) = property ?: return null
        val initializer = declaration.initializer?.takeUnless { declaration.mutable } ?: return null
        return baseValues(owner, owner.code.substring(initializer), initializer.first, budget)
    }

    private fun resolver(file: RouteSourceFile): RoutePathResolver = pathResolvers.getOrPut(file) { RoutePathResolver(file) }

    private companion object {
        const val MAX_DEPTH = 16

        /** 수신 객체를 그대로 돌려주는 범위 함수다. 블록 안의 `baseUrl(…)`을 읽는다. */
        val SCOPE_BLOCKS = setOf("apply", "also")
        val PROVIDES = Regex("@(?:dagger\\.)?Provides\\b")
        val INJECT = Regex("@(?:javax\\.inject\\.|jakarta\\.inject\\.)?Inject\\b")
        val RETURN = Regex("\\breturn\\b(?!@)")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val HTTP_URL_EXTENSION = Regex("(.+?)\\s*\\.\\s*(?:toHttpUrl|toHttpUrlOrNull)\\s*\\(\\s*\\)\\s*(?:!!)?", RegexOption.DOT_MATCHES_ALL)
        val HTTP_URL_FACTORY = Regex("(?:okhttp3\\s*\\.\\s*)?HttpUrl\\s*\\.\\s*(?:Companion\\s*\\.\\s*)?(?:get|parse)\\s*(\\().*", RegexOption.DOT_MATCHES_ALL)
        val KOIN_DEFINITION = Regex("(?<![\\w.])(single|factory|scoped)\\s*(?:<\\s*([\\w.]+)\\s*>)?\\s*(\\((?:[^()]|\\([^()]*\\))*\\))?\\s*\\{")
        val KOIN_NAMED = Regex("\\bnamed\\s*\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\)")
        val RETROFIT_BUILD_CHAIN = Regex("\\bRetrofit\\s*\\.\\s*Builder\\s*\\(")
    }
}

/** base를 바꾸지 않는 `Retrofit`·`Retrofit.Builder` 메서드다. */
private val BUILDER_METHODS = setOf(
    "build", "client", "callFactory", "addConverterFactory", "addCallAdapterFactory", "callbackExecutor", "validateEagerly", "newBuilder",
)

/** Koin 정의 하나다. [body]는 정의 람다의 마지막 식 범위다. */
private data class KoinDefinition(val file: RouteSourceFile, val body: IntRange, val qualifier: String?, val ref: String)

/**
 * 매개변수 하나다. [annotations]는 매개변수에 붙은 어노테이션 원문이다(DI 한정자용). [type]은 한정·제네릭을 뗀 단순 이름이고,
 * [rawType]은 한정을 보존한 타입 표기다(`RestClient.Builder`와 `WebClient.Builder`를 가르기 위해서다).
 */
internal data class SourceParameter(val name: String, val type: String, val annotations: String, val rawType: String = type)

private val PARAMETER_ANNOTATION = Regex("@[A-Za-z_][\\w.]*(?::[A-Za-z_]\\w*)?(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?")
private val KOTLIN_PARAMETER_MODIFIERS = Regex("\\b(?:vararg|noinline|crossinline|val|var|private|public|internal|protected|override|open|final)\\b")
private val KOTLIN_NAMED_TYPE = Regex("^([A-Za-z_]\\w*)\\s*:\\s*([^=]+)")

/** Kotlin 매개변수 원문(`@Named("a") private val retrofit: Retrofit`)을 읽는다. */
internal fun kotlinSourceParameter(text: String): SourceParameter? {
    val annotations = PARAMETER_ANNOTATION.findAll(text).joinToString(" ") { it.value }
    val cleaned = PARAMETER_ANNOTATION.replace(text, " ").replace(KOTLIN_PARAMETER_MODIFIERS, " ").trim()
    val match = KOTLIN_NAMED_TYPE.find(cleaned) ?: return null
    return SourceParameter(match.groupValues[1], parameterTypeName(match.groupValues[2]), annotations, rawTypeName(match.groupValues[2]))
}

/** Java 매개변수 원문(`@Named("a") final Retrofit retrofit`)을 읽는다. */
internal fun javaSourceParameter(text: String): SourceParameter? {
    val annotations = PARAMETER_ANNOTATION.findAll(text).joinToString(" ") { it.value }
    val cleaned = PARAMETER_ANNOTATION.replace(text, " ").replace(Regex("\\bfinal\\b"), " ").trim()
    val name = Regex("[A-Za-z_]\\w*$").find(cleaned)?.value ?: return null
    return SourceParameter(name, parameterTypeName(cleaned.removeSuffix(name)), annotations, rawTypeName(cleaned.removeSuffix(name)))
}

/** 제네릭·nullable·기본값을 뗀 타입 표기다(한정은 보존한다). */
private fun rawTypeName(type: String): String = type.substringBefore('<').substringBefore('=').trim().removeSuffix("?").trim()

private fun parameterTypeName(type: String): String =
    type.substringBefore('<').substringBefore('=').trim().removeSuffix("?").substringAfterLast('.').trim()

/**
 * 사슬 식의 멤버 접근 단위 하나다(`name<T>(args) { lambda }`).
 *
 * @property start 단위가 시작하는 위치다
 * @property arguments 인자 괄호의 (`(` 위치, `)` 위치) 범위다. 인자 괄호가 없으면 null이다
 * @property typeArguments 타입 인자 원문이다
 * @property lambda 끝 람다의 (`{` 위치, `}` 위치) 범위다
 */
internal data class ChainSegment(val name: String, val start: Int, val arguments: IntRange?, val typeArguments: String?, val lambda: IntRange?) {
    val isPlain: Boolean get() = arguments == null && lambda == null && typeArguments == null
}

/**
 * 사슬 식 [start, end)를 최상위 `.`(`?.` 포함)에서 나눠 단위로 읽는다. 읽을 수 없는 모양(연산자·캐스트·메서드 참조)이면 null이다.
 */
internal fun chainSegments(file: RouteSourceFile, start: Int, end: Int): List<ChainSegment>? {
    val masked = file.masked
    val bounds = mutableListOf<Pair<Int, Int>>()
    var depth = 0
    var angle = 0
    var segmentStart = start
    var index = start
    while (index < end) {
        val character = masked[index]
        when {
            character == '(' || character == '[' || character == '{' -> depth++
            character == ')' || character == ']' || character == '}' -> depth--
            character == '<' && depth == 0 && masked.getOrNull(index - 1)?.let { it.isLetterOrDigit() || it == '_' } == true -> angle++
            character == '>' && angle > 0 && masked.getOrNull(index - 1) != '-' -> angle--
            character == '.' && depth == 0 && angle == 0 -> {
                bounds += segmentStart to (if (masked.getOrNull(index - 1) == '?') index - 1 else index)
                segmentStart = index + 1
            }
        }
        index++
    }
    bounds += segmentStart to end
    return bounds.map { (from, to) -> parseSegment(file, from, to) ?: return null }.takeIf { it.isNotEmpty() }
}

private val SEGMENT_IDENTIFIER = Regex("\\G[A-Za-z_]\\w*")

/** 단위 하나 `new? name <T>? (args)? {lambda}? !!?`를 읽는다. 남는 글자가 있으면 null이다. */
private fun parseSegment(file: RouteSourceFile, from: Int, to: Int): ChainSegment? {
    val masked = file.masked
    var index = skipSpaces(masked, from)
    if (masked.startsWith("new", index) && masked.getOrNull(index + 3)?.isWhitespace() == true) index = skipSpaces(masked, index + 3)
    val start = index
    val identifier = SEGMENT_IDENTIFIER.find(masked, index)?.takeIf { it.range.first == index } ?: return null
    index = skipSpaces(masked, identifier.range.last + 1)
    var typeArguments: String? = null
    if (index < to && masked[index] == '<') {
        val close = angleEnd(masked, index, to) ?: return null
        typeArguments = file.code.substring(index + 1, close)
        index = skipSpaces(masked, close + 1)
    }
    var arguments: IntRange? = null
    if (index < to && masked[index] == '(') {
        val close = balancedEnd(file.code, index).takeIf { it in index until to } ?: return null
        arguments = index..close
        index = skipSpaces(masked, close + 1)
    }
    var lambda: IntRange? = null
    if (index < to && masked[index] == '{') {
        val close = file.braceEnd(index).takeIf { it in index until to } ?: return null
        lambda = index..close
        index = skipSpaces(masked, close + 1)
    }
    while (index < to && (masked[index] == '!' || masked[index] == '?' || masked[index].isWhitespace())) index++
    if (index < to) return null
    return ChainSegment(identifier.value, start, arguments, typeArguments, lambda)
}

private fun angleEnd(masked: String, open: Int, limit: Int): Int? {
    var depth = 0
    for (index in open until limit) {
        when (masked[index]) {
            '<' -> depth++
            '>' -> { depth--; if (depth == 0) return index }
            '(', ')', '{', '}', ';', '=' -> return null
        }
    }
    return null
}

/**
 * 사슬의 뿌리 단위 수다. 앞의 이름 단위들(패키지·타입·변수)과, 그 뒤가 빌더 메서드가 아닌 호출이면 그 호출까지다.
 */
private fun rootLength(segments: List<ChainSegment>): Int {
    var plain = 0
    while (plain < segments.size && segments[plain].isPlain) plain++
    if (plain == 0) return 1
    val next = segments.getOrNull(plain) ?: return plain
    val builderMethod = next.name in BUILDER_METHODS || next.name == "baseUrl" || next.name == "apply" || next.name == "also"
    return if (next.arguments != null && !builderMethod) plain + 1 else plain
}

/** 람다 블록 [lambda] 바로 안의 마지막 `baseUrl(…)` 인자 괄호 범위다. */
private fun lambdaBaseUrl(file: RouteSourceFile, lambda: IntRange): IntRange? {
    val pattern = Regex("(?<![\\w.])(?:this\\s*\\.\\s*)?baseUrl\\s*\\(")
    return pattern.findAll(file.masked.substring(0, lambda.last), lambda.first + 1).lastOrNull { match ->
        file.masked.substring(lambda.first + 1, match.range.first).let { text -> text.count { it == '{' } == text.count { it == '}' } }
    }?.let { match -> balancedEnd(file.code, match.range.last).takeIf { it > match.range.last }?.let { match.range.last..it } }
}
