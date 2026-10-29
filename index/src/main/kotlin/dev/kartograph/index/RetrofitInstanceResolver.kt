package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Retrofit 서비스가 만들어지는 base 결합 하나다.
 *
 * @property base 정적으로 푼 base URL이다. null이면 값을 증명하지 못했다(실행 시점 값·모호한 DI·빌더에 base 없음)
 *   또는 URL을 바꾸는 client라 적용하지 않았다([rewrite])
 * @property baseRef base를 공급하는 선언(Retrofit 인스턴스를 담은 함수·속성·DI provider)의 생산자 id다. 선언을 찾지 못하면 null이다
 * @property client 이 인스턴스가 요청을 보내는 OkHttp client의 재작성 증거다(`client(…)`·`callFactory(…)`)
 * @property rewrite URL을 바꾸는 client라 base를 적용하지 않았을 때의 근거다. [RetrofitBaseIndex.bindings]가 채운다
 */
internal data class RetrofitBinding(
    val base: RetrofitBaseUrl?,
    val baseRef: String?,
    val client: RetrofitClientFacts = RetrofitClientFacts.UNKNOWN,
    val rewrite: RetrofitRewrite? = null,
)

/**
 * Retrofit `create` 호출의 수신 식을 따라가 그 인스턴스의 `baseUrl` 값과 선언 신원, 요청을 보내는 OkHttp client를 푼다.
 *
 * 따라가는 모양: 같은 식의 `Retrofit.Builder()…baseUrl(x)…build()` 사슬(`apply { baseUrl(x) }` 포함), 같은 함수의 `val`, 감싸는
 * 타입·파일·다른 파일의 속성(`= …`, `by lazy { … }`, getter, `var`·Java 필드의 모든 대입), 함수 호출의 식 몸체·`return`,
 * Dagger/Hilt `@Provides` provider(`@Inject` 생성자·필드와 `@Provides` 매개변수, 한정자 일치), Koin `get()`과 `single`·`factory`
 * 정의([SourceValueResolver]). base 값은 리터럴·Kotlin 템플릿·같은 파일/다른 파일 상수·읽기 전용 속성의 초기식·`BuildConfig`
 * 필드([BuildConfigFields])·`HttpUrl` 래퍼를 푼다. 사슬의 마지막 `client(…)`·`callFactory(…)`는 [OkHttpClientResolver]로 풀고,
 * 없으면 Retrofit이 만드는 기본 client(인터셉터 없음)이거나 `newBuilder()`로 복사한 인스턴스의 client다.
 * 그 밖의 식은 값을 모르는 결합으로 남긴다 — 추측한 base로 템플릿을 확정하지 않는다.
 */
internal class RetrofitInstanceResolver(
    files: List<RouteSourceFile>,
    private val buildConfig: BuildConfigFields,
    declarations: SourceDeclarations = SourceDeclarations(files),
) : SourceValueResolver<List<RetrofitBinding>>(files, declarations) {
    /** `client(…)`·`callFactory(…)` 인자의 OkHttp client 해석기다. */
    val clients: OkHttpClientResolver = OkHttpClientResolver(files, declarations)

    override val typeName: String = "Retrofit"
    override val providers: List<Pair<RouteSourceFile, RouteFunctionDecl>> get() = declarations.retrofitProviders
    override val koinBuilder: Regex = Regex("\\bRetrofit\\s*\\.\\s*Builder\\s*\\(")

    private val pathResolvers = java.util.IdentityHashMap<RouteSourceFile, RoutePathResolver>()

    /**
     * `create` 수신 식 [start, end)의 결합들이다. 수신 식이 비어 있으면(`with(retrofit) { create(…) }`) 모르는 결합 하나다.
     */
    fun resolveReceiver(file: RouteSourceFile, start: Int, end: Int): List<RetrofitBinding> {
        if (start >= end || file.masked.substring(start, end).isBlank()) return listOf(RetrofitBinding(null, null))
        return expression(file, start, end, scopes.declarationId(file, start), Budget())
    }

    override fun unknown(ref: String?): List<RetrofitBinding> = listOf(RetrofitBinding(null, ref))

    override fun combine(values: List<List<RetrofitBinding>>): List<RetrofitBinding> = values.flatten().distinct()

    /**
     * Retrofit 값을 내는 식 [start, end)를 푼다. 끝에서부터 사슬을 읽어 마지막 `baseUrl`과 마지막 `client`·`callFactory`를 찾는다.
     * base를 찾은 뒤 읽을 수 없는 단위를 만나면 base는 유지하고 client만 모른다.
     */
    override fun expression(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): List<RetrofitBinding> {
        val trimmedStart = skipSpaces(file.masked, start)
        if (!budget.enter(file, trimmedStart)) return unknown(ref)
        try {
            val segments = chainSegments(file, trimmedStart, end) ?: return unknown(ref)
            val rootSize = rootLength(segments)
            var base: List<RetrofitBinding>? = null
            var client: RetrofitClientFacts? = null
            for (index in segments.lastIndex downTo rootSize) {
                val segment = segments[index]
                when {
                    segment.name in CLIENT_SETTERS && segment.arguments != null -> if (client == null) client = clientArgument(file, segment.name, segment.start, segment.arguments, budget)
                    segment.name == "baseUrl" && segment.arguments != null -> if (base == null) base = baseBindings(file, segment.arguments, ref, budget)
                    segment.name in SCOPE_BLOCKS && segment.lambda != null && segment.arguments == null -> {
                        if (base == null) lambdaCall(file, segment.lambda, BASE_URL_CALL)?.let { base = baseBindings(file, it.second, ref, budget) }
                        if (client == null) client = blockClient(file, segment.lambda, budget)
                    }
                    segment.name in BUILDER_METHODS -> Unit
                    else -> return base?.withClient(client ?: RetrofitClientFacts.UNKNOWN) ?: unknown(ref)
                }
                val found = base
                if (found != null && client != null) return found.withClient(client!!)
            }
            val rooted = root(file, segments.subList(0, rootSize), ref, budget)
            val found = base
            return when {
                found != null -> found.withClient(client ?: rooted.map { it.client }.reduce(RetrofitClientFacts::merge))
                client != null -> rooted.withClient(client!!)
                else -> rooted
            }
        } finally {
            budget.leave(file, trimmedStart)
        }
    }

    /**
     * 범위 함수 블록 안의 `client(…)`·`callFactory(…)` 호출들의 client다. 조건문 가지나 `it.client(…)`처럼 어느 호출이 마지막에
     * 적용될지 모르므로 블록 안의 모든 호출을 합친다(넓게 잡아도 재작성 client가 늘 뿐이다). 호출이 없으면 null이다.
     */
    private fun blockClient(file: RouteSourceFile, lambda: IntRange, budget: Budget): RetrofitClientFacts? {
        var merged: RetrofitClientFacts? = null
        for (match in CLIENT_CALL.findAll(file.masked.substring(0, lambda.last), lambda.first + 1)) {
            val open = match.range.last
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return RetrofitClientFacts.UNKNOWN
            val facts = clientArgument(file, match.groupValues[1], match.range.first, open..close, budget)
            merged = merged?.merge(facts) ?: facts
        }
        return merged
    }

    private fun List<RetrofitBinding>.withClient(client: RetrofitClientFacts): List<RetrofitBinding> = map { it.copy(client = client) }.distinct()

    /**
     * `client(x)`·`callFactory(x)` 인자의 client 증거다. `callFactory`에 OkHttpClient임을 증명하지 못한 값(직접 구현한
     * `Call.Factory`·모르는 값)이 오면 요청을 바꿀 수 있는 것으로 본다 — 이 Retrofit에 직접 붙었으므로 이 인스턴스만의 재작성이다.
     */
    private fun clientArgument(file: RouteSourceFile, name: String, position: Int, arguments: IntRange, budget: Budget): RetrofitClientFacts {
        val argument = singleArgument(file, arguments)
        val facts = if (argument == null) RetrofitClientFacts.UNKNOWN else clients.client(file, argument.first, argument.last + 1, budget)
        if (name == "callFactory" && facts.unknown) return RetrofitClientFacts(forced = setOf(SourcePosition(file.relative, position)))
        return facts
    }

    /** 사슬의 뿌리(`Retrofit.Builder()`, 참조, 함수 호출, Koin `get()`)를 푼다. */
    private fun root(file: RouteSourceFile, segments: List<ChainSegment>, ref: String?, budget: Budget): List<RetrofitBinding> {
        val last = segments.last()
        val qualifier = segments.dropLast(1).joinToString(".") { it.name }
        if (last.lambda != null) return unknown(ref)
        if (last.arguments == null) return reference(file, qualifier, last.name, segments.first().start, ref, budget)
        if (last.name == "Builder" && (qualifier.endsWith("Retrofit") || qualifier.isEmpty() && file.visibleNameOf("retrofit2.Retrofit.Builder") == "Builder")) {
            // baseUrl 없이 build()하면 Retrofit이 거부한다 — 이 결합으로는 요청이 나가지 않는다. client를 주지 않으면 Retrofit은
            // 인터셉터 없는 새 OkHttpClient를 만든다.
            return listOf(RetrofitBinding(null, ref, RetrofitClientFacts.DEFAULT))
        }
        if (qualifier.isEmpty() && last.name == "get" && file.imports.any { it.path.startsWith("org.koin.") }) return koin(file, last, budget)
        return functionCall(file, qualifier, last.name, last.start, budget)
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
        /** 수신 객체를 그대로 돌려주는 범위 함수다. 블록 안의 `baseUrl(…)`·`client(…)`를 읽는다. */
        val SCOPE_BLOCKS = setOf("apply", "also")
        /** 요청을 보내는 client를 정하는 `Retrofit.Builder` 메서드다(마지막 호출이 이긴다). */
        val CLIENT_SETTERS = setOf("client", "callFactory")
        val BASE_URL_CALL = Regex("(?<![\\w.])(?:this\\s*\\.\\s*)?(baseUrl)\\s*\\(")
        val CLIENT_CALL = Regex("(?<![\\w])(client|callFactory)\\s*\\(")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val HTTP_URL_EXTENSION = Regex("(.+?)\\s*\\.\\s*(?:toHttpUrl|toHttpUrlOrNull)\\s*\\(\\s*\\)\\s*(?:!!)?", RegexOption.DOT_MATCHES_ALL)
        val HTTP_URL_FACTORY = Regex("(?:okhttp3\\s*\\.\\s*)?HttpUrl\\s*\\.\\s*(?:Companion\\s*\\.\\s*)?(?:get|parse)\\s*(\\().*", RegexOption.DOT_MATCHES_ALL)
    }
}

/** base를 바꾸지 않는 `Retrofit`·`Retrofit.Builder` 메서드다. */
private val BUILDER_METHODS = setOf(
    "build", "client", "callFactory", "addConverterFactory", "addCallAdapterFactory", "callbackExecutor", "validateEagerly", "newBuilder",
)

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

/**
 * 람다 블록 [lambda] 바로 안(중첩 블록 밖)의 마지막 [pattern] 호출의 (이름, 인자 괄호 범위)다. [pattern]의 1번 그룹이 호출
 * 이름이고 매치는 여는 괄호에서 끝난다.
 */
private fun lambdaCall(file: RouteSourceFile, lambda: IntRange, pattern: Regex): Pair<String, IntRange>? =
    pattern.findAll(file.masked.substring(0, lambda.last), lambda.first + 1).lastOrNull { match ->
        file.masked.substring(lambda.first + 1, match.range.first).let { text -> text.count { it == '{' } == text.count { it == '}' } }
    }?.let { match -> balancedEnd(file.code, match.range.last).takeIf { it > match.range.last }?.let { match.groupValues[1] to (match.range.last..it) } }
