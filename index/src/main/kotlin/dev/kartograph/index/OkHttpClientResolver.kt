package dev.kartograph.index

/** 소스 한 위치다(프로젝트 기준 상대 경로 + 문자 오프셋). 파일을 건너 같은 호출을 가리키는 키다. */
internal data class SourcePosition(val file: String, val offset: Int)

/**
 * Retrofit 인스턴스가 요청을 보내는 OkHttp client의 재작성 증거다.
 *
 * @property adders client를 만든 빌더의 `addInterceptor`·`addNetworkInterceptor` 호출 위치다(`newBuilder()`로 복사한 원본 client의
 *   호출 포함). 그중 URL을 바꾸는 인터셉터가 붙은 것은 [InterceptorRewriteIndex]가 정한다
 * @property forced 재작성하지 않음을 증명하지 못한 `Authenticator`·`EventListener` 부착과 OkHttpClient가 아닌 `callFactory` 위치다.
 *   요청 URL을 바꿀 수 있는 것으로 본다
 * @property unknown client의 원천을 끝까지 풀지 못했다(모르는 매개변수·빌더를 넘기는 호출·인터셉터 목록 직접 조작 등)
 */
internal data class RetrofitClientFacts(
    val adders: Set<SourcePosition> = emptySet(),
    val forced: Set<SourcePosition> = emptySet(),
    val unknown: Boolean = false,
) {
    /** 두 원천 중 어느 쪽 client든 쓰일 수 있을 때의 합이다. 하나라도 모르면 모른다. */
    fun merge(other: RetrofitClientFacts): RetrofitClientFacts =
        RetrofitClientFacts(adders + other.adders, forced + other.forced, unknown || other.unknown)

    companion object {
        /** 인터셉터 없는 client(`OkHttpClient()`, `client`를 주지 않은 Retrofit의 기본 client)다. */
        val DEFAULT: RetrofitClientFacts = RetrofitClientFacts()

        /** 원천을 모르는 client다. */
        val UNKNOWN: RetrofitClientFacts = RetrofitClientFacts(unknown = true)
    }
}

/**
 * URL을 바꾸는 client라 Retrofit base를 적용하지 않은 근거다.
 *
 * @property rewrites client에 붙은 재작성(URL을 바꾸는 인터셉터가 붙은 호출과 [RetrofitClientFacts.forced])의 수다
 * @property pathPreserved 모든 재작성이 요청 경로를 그대로 두고 authority(scheme·host·port)만 바꿈을 증명했다
 * @property methodPreserved 모든 재작성이 요청 method를 그대로 둠을 증명했다
 * @property withheldBase 적용하지 않은 base다. 경로를 보존하는 재작성이면 요청 경로의 상한을 만드는 데 쓴다
 */
internal data class RetrofitRewrite(val rewrites: Int, val pathPreserved: Boolean, val methodPreserved: Boolean, val withheldBase: RetrofitBaseUrl?)

/**
 * `Retrofit.Builder().client(x)`의 `x`처럼 OkHttpClient를 내는 식을 따라가 그 client의 인터셉터 부착 위치를 모은다.
 *
 * 따라가는 모양: `OkHttpClient()`, `OkHttpClient.Builder()…build()` 사슬(`apply`·`also` 블록 포함), `other.newBuilder()…build()`
 * (원본 client의 인터셉터를 물려받는다), 같은 함수에서 다른 곳으로 넘기지 않은 빌더 지역 변수(`val b = OkHttpClient.Builder()`와
 * `b.addInterceptor(…)` 문장들), 그리고 [SourceValueResolver]의 지역 `val`·속성·함수 몸체·`@Provides`·Koin. 빌더 메서드가 아닌
 * 호출(프로젝트 확장 함수 등), `interceptors()` 목록 직접 조작, 원천을 모르는 값은 모르는 client다 — 모르는 곳에서 인터셉터가
 * 붙었을 수 있기 때문이다.
 */
internal class OkHttpClientResolver(files: List<RouteSourceFile>, declarations: SourceDeclarations) :
    SourceValueResolver<RetrofitClientFacts>(files, declarations) {
    override val typeName: String = "OkHttpClient"
    override val providers: List<Pair<RouteSourceFile, RouteFunctionDecl>> get() = declarations.okHttpClientProviders
    override val koinBuilder: Regex = Regex("\\bOkHttpClient\\s*(?:\\.\\s*Builder\\s*)?\\(")

    override fun unknown(ref: String?): RetrofitClientFacts = RetrofitClientFacts.UNKNOWN

    override fun combine(values: List<RetrofitClientFacts>): RetrofitClientFacts = values.reduce(RetrofitClientFacts::merge)

    /** OkHttpClient 식 [start, end)의 client 증거다. [budget]은 Retrofit 해석과 공유해 전체 재귀 한도를 지킨다. */
    fun client(file: RouteSourceFile, start: Int, end: Int, budget: Budget = Budget()): RetrofitClientFacts =
        expression(file, start, end, null, budget)

    override fun expression(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): RetrofitClientFacts {
        val trimmed = skipSpaces(file.masked, start)
        if (!budget.enter(file, trimmed)) return RetrofitClientFacts.UNKNOWN
        try {
            val segments = chainSegments(file, trimmed, end) ?: return RetrofitClientFacts.UNKNOWN
            val rootSize = rootLength(segments)
            return segments.drop(rootSize).fold(root(file, segments.subList(0, rootSize), budget)) { facts, segment ->
                facts.merge(segmentFacts(file, segment))
            }
        } finally {
            budget.leave(file, trimmed)
        }
    }

    /** 빌더 사슬 단위 하나의 증거다. `newBuilder`는 원본 client의 인터셉터를 복사할 뿐이라 그대로 지나간다. */
    private fun segmentFacts(file: RouteSourceFile, segment: ChainSegment): RetrofitClientFacts = when {
        segment.name in ADDERS -> RetrofitClientFacts(adders = setOf(SourcePosition(file.relative, segment.start)))
        segment.name in ATTACHMENTS -> if (attachmentClean(file, segment.arguments, segment.lambda, "uthenticator" in segment.name)) RetrofitClientFacts.DEFAULT
        else RetrofitClientFacts(forced = setOf(SourcePosition(file.relative, segment.start)))
        segment.name in LIST_ACCESSORS -> RetrofitClientFacts.UNKNOWN
        segment.name in SCOPE_BLOCKS && segment.lambda != null && segment.arguments == null -> blockFacts(file, segment.lambda)
        segment.name in BUILDER_METHODS && segment.lambda == null -> RetrofitClientFacts.DEFAULT
        else -> RetrofitClientFacts.UNKNOWN
    }

    /**
     * `apply { … }`·`also { … }` 블록 안의 부착이다. 블록의 모든 부착 호출을 수신 객체와 무관하게 이 client의 것으로 본다(넓게
     * 잡아도 재작성 client가 늘 뿐이다). 인터셉터 목록을 직접 다루면 모른다.
     */
    private fun blockFacts(file: RouteSourceFile, lambda: IntRange): RetrofitClientFacts {
        val text = file.masked.substring(lambda.first, lambda.last + 1)
        if (LIST_ACCESSOR_TOKEN.containsMatchIn(text)) return RetrofitClientFacts.UNKNOWN
        var facts = RetrofitClientFacts.DEFAULT
        ATTACH_CALL.findAll(text).forEach { match ->
            val position = lambda.first + match.range.first
            val call = callShape(file, lambda.first + match.range.last) ?: return RetrofitClientFacts.UNKNOWN
            facts = facts.merge(
                when {
                    match.groupValues[1] in ADDERS -> RetrofitClientFacts(adders = setOf(SourcePosition(file.relative, position)))
                    attachmentClean(file, call.first, call.second, "uthenticator" in match.groupValues[1]) -> RetrofitClientFacts.DEFAULT
                    else -> RetrofitClientFacts(forced = setOf(SourcePosition(file.relative, position)))
                },
            )
        }
        return facts
    }

    /** 사슬의 뿌리(`OkHttpClient()`, `OkHttpClient.Builder()`, 빌더 지역 변수, 참조, 함수 호출, Koin `get()`)를 푼다. */
    private fun root(file: RouteSourceFile, segments: List<ChainSegment>, budget: Budget): RetrofitClientFacts {
        val last = segments.last()
        val qualifier = segments.dropLast(1).joinToString(".") { it.name }
        if (last.lambda != null) return RetrofitClientFacts.UNKNOWN
        if (last.arguments == null) {
            if (qualifier.isEmpty()) localBuilder(file, last.name, last.start, budget)?.let { return it }
            return reference(file, qualifier, last.name, segments.first().start, null, budget)
        }
        if (isFreshClientRoot(file, segments)) return RetrofitClientFacts.DEFAULT
        if (qualifier.isEmpty() && last.name == "get" && file.imports.any { it.path.startsWith("org.koin.") }) return koin(file, last, budget)
        return functionCall(file, qualifier, last.name, last.start, budget)
    }

    /** 뿌리가 새 `OkHttpClient.Builder()`·`OkHttpClient()`인지 본다. */
    private fun isFreshClientRoot(file: RouteSourceFile, root: List<ChainSegment>): Boolean {
        val last = root.last()
        if (last.arguments == null) return false
        val qualifier = root.dropLast(1).joinToString(".") { it.name }
        // 인자를 받는 생성자(다른 client를 복사하는 모양)는 새 빌더가 아니다.
        if (file.masked.substring(last.arguments.first + 1, last.arguments.last).isNotBlank()) return false
        if (last.name == "Builder") return qualifier.endsWith("OkHttpClient") || qualifier.isEmpty() && file.visibleNameOf("okhttp3.OkHttpClient.Builder") == "Builder"
        return last.name == "OkHttpClient" && (qualifier.isEmpty() || qualifier == "okhttp3")
    }

    /**
     * 참조 [name]이 같은 함수의 빌더 지역 변수(`val b = OkHttpClient.Builder()`)면 초기식과 그 변수에 건 모든 문장의 증거다. 빌더
     * 변수가 아니면 null이다. `var`이거나 변수를 다른 곳에 넘기면(인자·반환·대입) 모른다.
     */
    private fun localBuilder(file: RouteSourceFile, name: String, offset: Int, budget: Budget): RetrofitClientFacts? {
        val (isVal, range) = scopes.localInitializer(file, name, offset) ?: return null
        if (!isBuilderExpression(file, range)) return null
        if (!isVal) return RetrofitClientFacts.UNKNOWN
        val uses = localBuilderUses(file, name, range) ?: return RetrofitClientFacts.UNKNOWN
        return uses.fold(expression(file, range.first, range.last + 1, null, budget)) { facts, use ->
            val segments = chainSegments(file, use.first, use.last + 1) ?: return RetrofitClientFacts.UNKNOWN
            segments.drop(1).fold(facts) { merged, segment -> merged.merge(segmentFacts(file, segment)) }
        }
    }

    /** 초기식 [range]가 아직 `build()`하지 않은 OkHttp 빌더(`OkHttpClient.Builder()…`, `x.newBuilder()…`)인지 본다. */
    private fun isBuilderExpression(file: RouteSourceFile, range: IntRange): Boolean {
        val segments = chainSegments(file, skipSpaces(file.masked, range.first), range.last + 1) ?: return false
        if (segments.last().name == "build") return false
        val root = segments.subList(0, rootLength(segments))
        return segments.any { it.name == "newBuilder" } || isFreshClientRoot(file, root) && root.last().name == "Builder"
    }

    /**
     * 빌더 지역 변수 [name]의 선언 뒤 사용마다 그 문장의 사슬 범위다. 모든 사용이 `name.메서드(…)` 사슬이어야 한다 — 그 밖의
     * 사용(인자로 넘김·반환·대입)은 빌더가 다른 곳에서 바뀔 수 있다는 뜻이라 null이다.
     */
    private fun localBuilderUses(file: RouteSourceFile, name: String, declaration: IntRange): List<IntRange>? {
        val function = file.enclosingFunction(declaration.first) ?: return null
        val pattern = Regex("(?<![\\w.])${Regex.escape(name)}\\b")
        val end = function.end.coerceAtMost(file.masked.length)
        val uses = mutableListOf<IntRange>()
        for (use in pattern.findAll(file.masked.substring(0, end), declaration.last + 1)) {
            if (!file.inScope(declaration.first, use.range.first)) continue
            if (file.masked.getOrNull(skipSpaces(file.masked, use.range.last + 1)) != '.') return null
            uses += use.range.first until file.statementEnd(use.range.first)
        }
        return uses
    }

    /**
     * 부착 호출이 속한 빌더의 원천을 증명할 수 있는지 본다 — 새 `OkHttpClient.Builder()`, 복사본을 만드는 `newBuilder()`, 다른 곳에
     * 넘기지 않은 빌더 지역 변수다. [receiver]는 부착 호출 앞의 수신 사슬이다. [InterceptorRewriteIndex]가 재작성 인터셉터가 붙은
     * 빌더를 확인하는 데 쓴다.
     */
    fun builderResolvable(file: RouteSourceFile, receiver: List<ChainSegment>, offset: Int): Boolean {
        val rootSize = rootLength(receiver)
        if (receiver.drop(rootSize).any { it.name !in CHAIN_METHODS }) return false
        if (receiver.any { it.name == "newBuilder" } || isFreshClientRoot(file, receiver.subList(0, rootSize))) return true
        val single = receiver.singleOrNull()?.takeIf { it.isPlain } ?: return false
        val (isVal, range) = scopes.localInitializer(file, single.name, offset) ?: return false
        return isVal && isBuilderExpression(file, range) && localBuilderUses(file, single.name, range) != null
    }

    /**
     * `authenticator`·`proxyAuthenticator`·`eventListener`·`eventListenerFactory`의 인자가 요청을 바꾸지 않음을 증명하는지 본다.
     * `NONE` 상수, 몸체에 재작성 호출이 없는 람다·익명 객체, 몸체에 재작성 호출이 없고 프로젝트 상위 타입이 없는 프로젝트 class의
     * 생성, 그런 값을 담은 지역 `val`·읽기 전용 속성만 증명한다. 그 밖(주입된 값·라이브러리 구현)은 재작성으로 본다.
     * [authenticator]면 `authenticate`가 돌려주는 모든 요청이 `null`이거나 `response.request()`(와 그 `newBuilder()` 사본)여야 한다 —
     * 헬퍼가 만든 요청은 재작성 호출 없이도 다른 곳으로 간다.
     */
    fun attachmentClean(file: RouteSourceFile, arguments: IntRange?, lambda: IntRange?, authenticator: Boolean): Boolean {
        if (arguments == null) return lambda != null && bodyClean(file, lambda, mutableSetOf()) && (!authenticator || returnsResponseRequest(file, lambda))
        if (lambda != null) return false
        val argument = singleArgument(file, arguments) ?: return false
        return implementationClean(file, argument.first, argument.last + 1, mutableSetOf(), authenticator)
    }

    /**
     * 구현 범위(람다·익명 객체·타입 몸체)의 `authenticate`가 돌려주는 식이 모두 `null`·`response.request()` 계열인지 본다. 함수가
     * 없으면(상속) SAM 람다의 마지막 식을 본다.
     */
    private fun returnsResponseRequest(file: RouteSourceFile, range: IntRange): Boolean {
        val masked = file.masked
        val function = file.functions.firstOrNull { it.name == "authenticate" && it.start in range && it.bodyStart >= 0 }
        if (function != null) {
            val response = function.parameters.getOrNull(1)?.name ?: return false
            val end = function.end.coerceAtMost(masked.length)
            val returns = if (masked.getOrNull(function.bodyStart) == '=') listOf((function.bodyStart + 1) until end)
            else scopes.returnRanges(file, (function.bodyStart + 1) until end)
            return returns.all { requestFromResponse(file, it, response, 0) }
        }
        val open = masked.indexOf('{', range.first).takeIf { it in range } ?: return javaLambdaReturns(file, range)
        val close = file.braceEnd(open).takeIf { it > open } ?: return false
        val response = SAM_PARAMETERS.find(masked, open)?.takeIf { it.range.first == open }?.groupValues?.get(2) ?: return javaLambdaReturns(file, range)
        if ("return@" in masked.substring(open, close)) return false
        val last = scopes.lastStatement(file, (open + 1) until close) ?: return false
        val statement = skipSpaces(masked, last.first)
        val arrow = masked.indexOf("->", open)
        return requestFromResponse(file, (if (statement <= arrow) arrow + 2 else statement) until (last.last + 1), response, 0)
    }

    /** Java 람다 `(route, response) -> 식`·`-> { … return 식; }`의 반환 식들이다. */
    private fun javaLambdaReturns(file: RouteSourceFile, range: IntRange): Boolean {
        val match = JAVA_SAM_PARAMETERS.find(file.masked, range.first)?.takeIf { it.range.first == skipSpaces(file.masked, range.first) } ?: return false
        val body = skipSpaces(file.masked, match.range.last + 1)
        if (file.masked.getOrNull(body) != '{') return requestFromResponse(file, body..range.last, match.groupValues[2], 0)
        val close = file.braceEnd(body).takeIf { it > body } ?: return false
        return scopes.returnRanges(file, (body + 1) until close).all { requestFromResponse(file, it, match.groupValues[2], 0) }
    }

    /** 식이 `null`이거나 `response.request()`(`response.request`)로 시작하는 사슬, 또는 그런 값을 담은 지역 `val`로 시작하는 사슬인지 본다. */
    private fun requestFromResponse(file: RouteSourceFile, range: IntRange, response: String, depth: Int): Boolean {
        if (depth > 4) return false
        val from = skipSpaces(file.masked, range.first)
        val end = (range.last + 1).coerceAtMost(file.masked.length)
        if (from >= end) return false
        if (file.masked.substring(from, end).trim() == "null") return true
        val segments = chainSegments(file, from, end) ?: return false
        // 뿌리가 지역 `val`(`val original = response.request()`)이면 그 초기식이 원래 요청 계열이어야 한다.
        segments.first().takeIf { it.isPlain && it.name != response }?.let { root ->
            val (isVal, initializer) = scopes.localInitializer(file, root.name, from) ?: return false
            return isVal && requestFromResponse(file, initializer, response, depth + 1)
        }
        val request = segments.getOrNull(1) ?: return false
        return segments[0].isPlain && segments[0].name == response && request.name == "request" && request.lambda == null &&
            request.arguments?.let { file.masked.substring(it.first + 1, it.last).isBlank() } != false
    }

    private fun implementationClean(file: RouteSourceFile, start: Int, end: Int, visiting: MutableSet<String>, authenticator: Boolean = false): Boolean {
        val from = skipSpaces(file.masked, start)
        val text = file.masked.substring(from, end).trim()
        if (NONE_CONSTANT.matches(text)) return true
        // 익명 객체·익명 class·SAM 람다·Java 람다는 그 몸체 전체를 본다.
        if (INLINE_IMPLEMENTATION.containsMatchIn(text)) {
            return bodyClean(file, from until end, visiting) && (!authenticator || returnsResponseRequest(file, from until end))
        }
        val segments = chainSegments(file, from, end) ?: return false
        val single = segments.singleOrNull()
        if (single != null && single.arguments != null && single.lambda == null) {
            val fqn = declarations.resolveType(file, single.name) ?: return false
            return typeClean(fqn, visiting) && (!authenticator || typeReturnsResponseRequest(fqn))
        }
        if (single == null || !single.isPlain) return false
        // 프로젝트 `object`의 참조는 그 타입 하나다.
        declarations.resolveType(file, single.name)?.takeIf { fqn ->
            declarations.type(fqn)?.let { (owner, type) -> owner.masked.startsWith("object", type.start) } == true
        }?.let { return typeClean(it, visiting) && (!authenticator || typeReturnsResponseRequest(it)) }
        scopes.localInitializer(file, single.name, from)?.let { (isVal, range) ->
            return isVal && implementationClean(file, range.first, range.last + 1, visiting, authenticator)
        }
        val (owner, property) = scopes.propertyInScope(file, single.name, from) ?: declarations.findTopLevelProperty(file, single.name) ?: return false
        val initializer = property.initializer?.takeUnless { property.mutable } ?: return false
        return implementationClean(owner, initializer.first, initializer.last + 1, visiting, authenticator)
    }

    /** 프로젝트 `Authenticator` 타입의 `authenticate`가 원래 요청 계열만 돌려주는지 본다. */
    private fun typeReturnsResponseRequest(fqn: String): Boolean {
        val (file, type) = declarations.type(fqn) ?: return false
        return type.bodyStart >= 0 && returnsResponseRequest(file, type.bodyStart..type.bodyEnd)
    }

    /** 범위에 재작성 호출이 없고, 범위 안에서 만든 프로젝트 타입도 모두 [typeClean]이다. */
    private fun bodyClean(file: RouteSourceFile, range: IntRange, visiting: MutableSet<String>): Boolean {
        if (RequestRewrites.sites(file, range).isNotEmpty()) return false
        return CONSTRUCTION.findAll(file.masked.substring(range.first, range.last + 1)).all { match ->
            val fqn = declarations.resolveType(file, match.groupValues[1]) ?: return@all true
            typeClean(fqn, visiting)
        }
    }

    /** 프로젝트 타입의 몸체에 재작성 호출이 없고 상위 타입이 모두 프로젝트 밖 타입인지 본다(상속한 동작을 증명하지 못하므로). */
    private fun typeClean(fqn: String, visiting: MutableSet<String>): Boolean {
        if (!visiting.add(fqn)) return true
        val (file, type) = declarations.type(fqn) ?: return false
        if (typeSupertypes(file, type).any { declarations.resolveType(file, it) != null }) return false
        if (type.bodyStart < 0) return true
        return bodyClean(file, type.bodyStart..type.bodyEnd, visiting)
    }

    /** 부착 호출 이름 끝 [nameEnd]에서 (인자 괄호 범위, 끝 람다 범위)다. 모양을 읽지 못하면 null이다. */
    fun callShape(file: RouteSourceFile, nameEnd: Int): Pair<IntRange?, IntRange?>? {
        var index = skipSpaces(file.masked, nameEnd + 1)
        var arguments: IntRange? = null
        if (file.masked.getOrNull(index) == '(') {
            val close = balancedEnd(file.code, index).takeIf { it > index } ?: return null
            arguments = index..close
            index = skipSpaces(file.masked, close + 1)
        }
        val lambda = if (file.masked.getOrNull(index) == '{') file.braceEnd(index).takeIf { it > index }?.let { index..it } else null
        return arguments to lambda
    }

    companion object {
        /** 인터셉터를 붙이는 `OkHttpClient.Builder` 메서드다. */
        val ADDERS = setOf("addInterceptor", "addNetworkInterceptor")

        /** 요청을 다시 만들 수 있는 구성 요소를 붙이는 메서드다(재작성하지 않음을 증명하지 못하면 재작성으로 본다). */
        val ATTACHMENTS = setOf("authenticator", "proxyAuthenticator", "eventListener", "eventListenerFactory")

        /** 인터셉터 목록을 직접 돌려주는 메서드다. 모르는 곳에서 목록이 바뀔 수 있다. */
        private val LIST_ACCESSORS = setOf("interceptors", "networkInterceptors")
        private val SCOPE_BLOCKS = setOf("apply", "also")

        /** 요청 URL과 무관한 `OkHttpClient.Builder` 설정 메서드와 빌드·복사 메서드다. */
        private val BUILDER_METHODS = setOf(
            "build", "newBuilder", "connectTimeout", "readTimeout", "writeTimeout", "callTimeout", "pingInterval", "cache", "dns", "proxy",
            "proxySelector", "cookieJar", "socketFactory", "sslSocketFactory", "hostnameVerifier", "certificatePinner", "connectionPool",
            "dispatcher", "followRedirects", "followSslRedirects", "retryOnConnectionFailure", "protocols", "connectionSpecs",
            "minWebSocketMessageToCompress", "fastFallback",
        )

        /** 빌더 사슬에 올 수 있는 모든 메서드다(수신 사슬 증명과 뿌리 길이 판정에 쓴다). */
        private val CHAIN_METHODS = BUILDER_METHODS + ADDERS + ATTACHMENTS + LIST_ACCESSORS + SCOPE_BLOCKS

        private val LIST_ACCESSOR_TOKEN = Regex("\\b(?:interceptors|networkInterceptors)\\b")
        private val ATTACH_CALL = Regex("(?<![\\w])(addInterceptor|addNetworkInterceptor|authenticator|proxyAuthenticator|eventListener|eventListenerFactory)\\s*(?=[({])")
        private val NONE_CONSTANT = Regex("(?:okhttp3\\s*\\.\\s*)?(?:Authenticator|EventListener)\\s*\\.\\s*(?:Companion\\s*\\.\\s*)?NONE")
        /**
         * 몸체를 그대로 읽을 수 있는 구현 식의 시작이다 — Kotlin 익명 객체, `Authenticator`·`EventListener`(`.Factory`)의 Java 익명
         * class·SAM 람다, 빈 블록 람다, Java 람다다.
         */
        private val INLINE_IMPLEMENTATION = Regex(
            "^(?:object\\s*:\\s*(?:okhttp3\\s*\\.\\s*)?(?:Authenticator|EventListener(?:\\s*\\.\\s*Factory)?)\\b[^{,]*\\{|" +
                "new\\s+(?:okhttp3\\s*\\.\\s*)?(?:Authenticator|EventListener(?:\\s*\\.\\s*Factory)?)\\s*\\(\\s*\\)\\s*\\{|" +
                "(?:okhttp3\\s*\\.\\s*)?(?:Authenticator|EventListener(?:\\s*\\.\\s*Factory)?)\\s*\\{|\\{|\\(?[\\w\\s,]*\\)?\\s*->)",
        )
        private val SAM_PARAMETERS = Regex("\\{\\s*([A-Za-z_]\\w*)\\s*,\\s*([A-Za-z_]\\w*)\\s*->")
        private val JAVA_SAM_PARAMETERS = Regex("\\(\\s*([A-Za-z_]\\w*)\\s*,\\s*([A-Za-z_]\\w*)\\s*\\)\\s*->")
        private val CONSTRUCTION = Regex("(?<![\\w.])(?:new\\s+)?([A-Z]\\w*(?:\\s*\\.\\s*[A-Z]\\w*)*)\\s*\\(")

        /** 사슬의 뿌리 단위 수다. 앞의 이름 단위들과, 그 뒤가 빌더 메서드가 아닌 호출이면 그 호출까지다. */
        fun rootLength(segments: List<ChainSegment>): Int {
            var plain = 0
            while (plain < segments.size && segments[plain].isPlain) plain++
            if (plain == 0) return 1
            val next = segments.getOrNull(plain) ?: return plain
            return if ((next.arguments != null || next.lambda != null) && next.name !in CHAIN_METHODS) plain + 1 else plain
        }
    }
}

/**
 * 요청 URL을 바꾸는 어휘 호출(재작성 지점)이다 — `newBuilder()` 뒤 사슬의 `url`·`host`·scheme·port·경로 변경 호출(`apply { … }`
 * 블록 안 포함)과 새 요청 생성(`Request.Builder()`, OkHttp 5 `Request(…)`)이다.
 */
internal object RequestRewrites {
    private val NEW_BUILDER = Regex("\\.\\s*newBuilder\\s*\\(\\s*\\)")
    private val FRESH_REQUEST = Regex("(?<![\\w.])(?:new\\s+)?(?:okhttp3\\s*\\.\\s*)?Request\\s*(?:\\.\\s*Builder\\s*)?\\(")

    /** 요청 경로를 바꾸는 `HttpUrl.Builder` 메서드다. */
    val PATH_METHODS = setOf(
        "encodedPath", "addPathSegment", "addPathSegments", "addEncodedPathSegment", "addEncodedPathSegments", "setPathSegment",
        "setEncodedPathSegment", "removePathSegment",
    )

    /** 요청이 가는 곳을 바꾸는 메서드다(`Request.Builder.url`과 `HttpUrl.Builder`의 authority·경로 변경). */
    val REWRITE_METHODS = setOf("url", "host", "scheme", "port") + PATH_METHODS

    /** 요청 method를 바꾸는 `Request.Builder` 메서드다. */
    val METHOD_METHODS = setOf("method", "get", "post", "put", "patch", "delete", "head")

    /** [range] 안의 재작성 지점 위치들이다. */
    fun sites(file: RouteSourceFile, range: IntRange = file.masked.indices): List<Int> {
        val text = file.masked.substring(0, (range.last + 1).coerceAtMost(file.masked.length))
        val builders = NEW_BUILDER.findAll(text, range.first.coerceAtLeast(0)).filter { match ->
            calls(file, match.range.last + 1).any { it.name in REWRITE_METHODS }
        }.map { it.range.first }
        val fresh = FRESH_REQUEST.findAll(text, range.first.coerceAtLeast(0)).map { it.range.first }
        return (builders + fresh).toList().sorted()
    }

    /** `newBuilder()` 사슬 시작 위치들이다(재작성 여부와 무관). method 변경을 찾는 데 쓴다. */
    fun builders(file: RouteSourceFile, range: IntRange): List<Int> =
        NEW_BUILDER.findAll(file.masked.substring(0, (range.last + 1).coerceAtMost(file.masked.length)), range.first).map { it.range.last + 1 }.toList()

    /** 새 요청 생성이 있는지 본다. */
    fun buildsFreshRequest(file: RouteSourceFile, range: IntRange): Boolean =
        FRESH_REQUEST.containsMatchIn(file.masked.substring(range.first, (range.last + 1).coerceAtMost(file.masked.length)))

    /**
     * [from]부터 이어지는 `.name(…)` 호출들이다. `.apply { … }`·`.also { … }`·`.run { … }` 블록은 그 안의 수신 객체 없는 호출도
     * 사슬의 호출로 본다.
     */
    fun calls(file: RouteSourceFile, from: Int): List<ChainCall> {
        val calls = mutableListOf<ChainCall>()
        var index = from
        while (true) {
            val dot = skipSpaces(file.masked, index)
            if (file.masked.getOrNull(dot) != '.') return calls
            val block = SCOPE_BLOCK.find(file.masked, dot + 1)
            if (block != null) {
                val close = file.braceEnd(block.range.last).takeIf { it > block.range.last } ?: return calls
                BLOCK_CALL.findAll(file.masked.substring(0, close), block.range.last + 1).forEach { match ->
                    val open = match.range.last
                    val end = balancedEnd(file.code, open).takeIf { it > open } ?: return@forEach
                    calls += ChainCall(match.groupValues[1], open..end)
                }
                index = close + 1
                continue
            }
            val name = CALL.find(file.masked, dot + 1) ?: return calls
            val close = balancedEnd(file.code, name.range.last).takeIf { it > name.range.last } ?: return calls
            calls += ChainCall(name.groupValues[1], name.range.last..close)
            index = close + 1
        }
    }

    private val CALL = Regex("\\G\\s*([A-Za-z_]\\w*)\\s*\\(")
    private val SCOPE_BLOCK = Regex("\\G\\s*(?:apply|also|run)\\s*\\{")
    private val BLOCK_CALL = Regex("(?<![\\w.])(?:(?:this|it)\\s*\\.\\s*)?([A-Za-z_]\\w*)\\s*\\(")
}

/** 사슬 호출 하나다. [arguments]는 인자 괄호의 (`(` 위치, `)` 위치) 범위다. */
internal data class ChainCall(val name: String, val arguments: IntRange)

/**
 * 인자 괄호 [arguments](`(`…`)`)의 유일한 인자 범위다. 괄호·대괄호·중괄호 깊이 0의 쉼표로 나누고 끝 쉼표(Kotlin trailing
 * comma)는 인자로 세지 않는다. 인자가 없거나 둘 이상이면 null이다.
 */
internal fun singleArgument(file: RouteSourceFile, arguments: IntRange): IntRange? {
    val masked = file.masked
    val parts = mutableListOf<IntRange>()
    var depth = 0
    var start = arguments.first + 1
    for (index in arguments.first + 1 until arguments.last) {
        when (masked[index]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) { parts += start until index; start = index + 1 }
        }
    }
    parts += start until arguments.last
    val nonBlank = parts.filter { masked.substring(it.first, it.last + 1).isNotBlank() }
    val single = nonBlank.singleOrNull() ?: return null
    val from = skipSpaces(masked, single.first)
    var to = single.last
    while (to > from && masked[to].isWhitespace()) to--
    return from..to
}

/** 타입 머리의 상위 타입 이름들이다(Kotlin `: A, B<T>`, Java `extends A implements B`). */
internal fun typeSupertypes(file: RouteSourceFile, type: RouteTypeDecl): List<String> {
    val headerEnd = if (type.bodyStart >= 0) type.bodyStart else file.statementEnd(type.start)
    val header = file.masked.substring(type.start, headerEnd.coerceAtLeast(type.start))
    val list = if (file.isJava) {
        listOfNotNull(JAVA_EXTENDS_LIST.find(header)?.groupValues?.get(1), JAVA_IMPLEMENTS_LIST.find(header)?.groupValues?.get(1)).joinToString(",")
    } else {
        // 주 생성자 괄호를 건너뛴 뒤 첫 `:`부터가 상위 타입 목록이다.
        var depth = 0
        var colon = -1
        for ((index, character) in header.withIndex()) {
            when (character) {
                '(', '<' -> depth++
                ')' -> depth--
                '>' -> if (header.getOrNull(index - 1) != '-') depth--
                ':' -> if (depth == 0) { colon = index; break }
            }
        }
        if (colon < 0) "" else header.substring(colon + 1)
    }
    return splitTopLevel(list).map { it.substringBefore('<').substringBefore('(').substringBefore(" by ").trim() }.filter { SUPERTYPE_NAME.matches(it) }
}

private val JAVA_EXTENDS_LIST = Regex("\\bextends\\s+([^{]+?)(?:\\bimplements\\b|$)")
private val JAVA_IMPLEMENTS_LIST = Regex("\\bimplements\\s+([^{]+)$")
private val SUPERTYPE_NAME = Regex("[A-Za-z_][\\w.]*")
