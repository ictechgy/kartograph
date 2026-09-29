package dev.kartograph.index

/**
 * OkHttp `HttpUrl`로 받아들여지는 Retrofit base URL이다.
 *
 * @property authority userinfo를 뗀 소문자 `host[:port]`다(isthmus `authority` 문법). scheme 기본 포트는 지운다
 * @property path `/`로 시작하고 끝나는 base 경로다. 상대 경로는 이 경로 뒤에 붙는다
 */
internal data class RetrofitBaseUrl(val authority: String, val path: String) {
    companion object {
        /**
         * `baseUrl(String)`·`HttpUrl.get(String)` 리터럴을 나눈다. http(s) 절대 URL이 아니거나, host가 계약 문법에 맞지 않거나,
         * 경로가 `/`로 끝나지 않으면(`Retrofit.Builder.baseUrl`이 `baseUrl must end in /`로 거부) null이다. query·fragment는
         * 상대 경로 해석에서 버려지므로 뗀다.
         */
        fun parse(text: String): RetrofitBaseUrl? {
            val match = BASE_URL.matchEntire(text.trim()) ?: return null
            val scheme = text.trim().substringBefore("://").lowercase()
            val raw = match.groupValues[1].substringAfterLast('@').lowercase().takeIf(AUTHORITY::matches) ?: return null
            val authority = normalizePort(raw, scheme) ?: return null
            val path = match.groupValues[2].ifEmpty { "/" }
            if (!path.endsWith('/')) return null
            return RetrofitBaseUrl(authority, path)
        }

        /**
         * OkHttp `HttpUrl`처럼 scheme의 기본 포트(http 80, https 443)를 지운다 — 요청의 Host에 기본 포트가 실리지 않기 때문이다.
         * 1~65535 밖이거나 앞에 0이 붙은 포트는 적힌 모양과 실제 포트가 달라질 수 있어 받지 않는다(null).
         */
        private fun normalizePort(authority: String, scheme: String): String? {
            val port = PORT.find(authority) ?: return authority
            val digits = port.groupValues[1]
            val value = digits.toInt()
            if (value !in 1..65535 || digits.startsWith('0')) return null
            val default = if (scheme == "https") 443 else 80
            return if (value == default) authority.removeSuffix(port.value) else authority
        }

        private val PORT = Regex(":([0-9]{1,5})$")

        private val BASE_URL = Regex("(?i)https?://([^/?#\\s]+)([^?#\\s]*)(?:[?#]\\S*)?")
        private val AUTHORITY = Regex(
            "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*|\\[[0-9a-f:.]+])(?::[0-9]{1,5})?$",
        )
    }
}

/**
 * 프로젝트 전체에서 Retrofit 서비스 인터페이스가 어느 base로 만들어지는지 모은 색인이다.
 *
 * `retrofit.create(Service::class.java)`·`create(Service.class)`·`create<Service>()` 호출과, `Class<T>`·reified 타입 매개변수를
 * 받아 `create`로 넘기는 생성 함수(`ServiceGenerator.createService(Service::class.java)`)의 호출을 찾아 수신 식을
 * [RetrofitInstanceResolver]로 푼다. 상위 서비스 인터페이스의 메서드는 하위 인터페이스 프록시로도 불리므로 하위의 결합을
 * 물려받는다. 테스트 소스의 `create`(MockWebServer 등)는 production base가 아니라 보지 않는다. URL을 바꾸는 OkHttp
 * 인터셉터가 있으면 base를 신뢰하지 않고 선언 신원만 남긴다.
 *
 * @param files 스캔한 source 파일 전체다(테스트 파일 포함 가능)
 * @param buildConfig `BuildConfig` 필드 해석기다
 */
internal class RetrofitBaseIndex(files: List<RouteSourceFile>, buildConfig: BuildConfigFields) {
    private val production = files.filter { !it.isTest }
    private val resolver = RetrofitInstanceResolver(production, buildConfig)

    /** Retrofit 동사 어노테이션을 가진 서비스 인터페이스 FQN이다. */
    private val services: Set<String> = files.filter { it.source.isNotEmpty() }.flatMap { file ->
        val importsRetrofit = file.imports.any { it.path.startsWith("retrofit2.http.") }
        RETROFIT_VERB.findAll(file.masked).filter { importsRetrofit || it.groups[1] != null }.mapNotNull { match ->
            file.enclosingTypes(match.range.first).lastOrNull()?.let { typeFqn(file, it) }
        }.toList()
    }.toSet()

    /** URL을 바꾸는 OkHttp 인터셉터 호출 수다(`url-rewrite-interceptors:`). */
    val urlRewriters: Int = production.sumOf(::countUrlRewrites)

    /** 서비스 → 직접 상위 서비스 인터페이스다. */
    private val superServices: Map<String, Set<String>> = files.filter { it.source.isNotEmpty() }.flatMap { file ->
        file.types.mapNotNull { type ->
            val fqn = typeFqn(file, type)
            if (fqn !in services) return@mapNotNull null
            fqn to supertypeNames(file, type).mapNotNull { resolver.declarations.resolveType(file, it) ?: sameFileType(file, it) }
                .filter { it in services }.toSet()
        }
    }.toMap()

    /** 서비스 → 그 서비스로 `create`한 결합들이다. */
    private val direct: Map<String, List<RetrofitBinding>> = collectBindings()

    /**
     * [service]의 메서드가 요청을 보낼 수 있는 결합들이다 — 자신과 (전이적) 하위 서비스로 만든 결합의 합이다. `create` 호출을
     * 찾지 못했으면 빈 목록이다. 인터셉터가 URL을 바꾸면 base를 지운 결합이다.
     */
    fun bindings(service: String): List<RetrofitBinding> {
        val owners = services.filter { candidate -> candidate == service || service in ancestors(candidate) }
        val bindings = owners.sorted().flatMap { direct[it].orEmpty() }.distinct()
        return if (urlRewriters > 0) bindings.map { it.copy(base = null) }.distinct() else bindings
    }

    private fun ancestors(service: String): Set<String> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(superServices[service].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (seen.add(next)) queue += superServices[next].orEmpty()
        }
        return seen
    }

    private fun sameFileType(file: RouteSourceFile, name: String): String? =
        file.types.filter { it.name == name.substringAfterLast('.') }.map { typeFqn(file, it) }.distinct().singleOrNull()

    /** 타입 머리의 상위 타입 이름들이다(Kotlin `: A, B<T>`, Java `extends A, B`). */
    private fun supertypeNames(file: RouteSourceFile, type: RouteTypeDecl): List<String> {
        if (type.bodyStart < 0) return emptyList()
        val header = file.masked.substring(type.start, type.bodyStart)
        val list = if (file.isJava) JAVA_EXTENDS.find(header)?.groupValues?.get(1) else header.substringAfter(':', "").takeIf { ':' in header }
        return list.orEmpty().split(',').map { it.substringBefore('<').substringBefore('(').trim() }.filter { SUPERTYPE_NAME.matches(it) }
    }

    private fun collectBindings(): Map<String, List<RetrofitBinding>> {
        val bindings = mutableMapOf<String, MutableList<RetrofitBinding>>()
        val creators = production.flatMap(::genericCreators)
        production.forEach { file ->
            CLASS_LITERAL_CREATE.findAll(file.masked).forEach { match ->
                val service = serviceOf(file, match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }) ?: return@forEach
                val dot = match.groups[1]?.range?.first
                val receiverStart = dot?.let { receiverStart(file, it) }
                // 생성 함수 자신의 호출(`ServiceGenerator.create(Api::class.java)`)은 아래 생성 함수 경로가 처리한다.
                if (receiverStart != null && creators.any { it.isCalledBy(file.masked.substring(receiverStart, dot).trim(), match.groupValues[0]) }) return@forEach
                if (dot == null && creators.any { it.name == "create" && it.file === file }) return@forEach
                bindings.getOrPut(service) { mutableListOf() } += if (dot == null || receiverStart == null) listOf(RetrofitBinding(null, null))
                else resolver.resolveReceiver(file, receiverStart, dot)
            }
        }
        creators.forEach { creator ->
            production.forEach { file -> creator.callers(file, resolver.declarations).forEach { service -> bindings.getOrPut(service) { mutableListOf() } += creator.bindings } }
        }
        return bindings.mapValues { (_, value) -> value.distinct() }
    }

    /** [file]에서 쓴 이름 [name]이 서비스 인터페이스면 그 FQN이다. */
    private fun serviceOf(file: RouteSourceFile, name: String): String? =
        (resolver.declarations.resolveType(file, name) ?: sameFileType(file, name))?.takeIf { it in services }

    /**
     * `Class<T>` 매개변수나 reified 타입 매개변수를 `create`로 넘기는 생성 함수들이다. 수신 식의 결합은 함수 선언에서 한 번 푼다.
     */
    private fun genericCreators(file: RouteSourceFile): List<GenericCreator> = GENERIC_CREATE.findAll(file.masked).mapNotNull { match ->
        val function = file.enclosingFunction(match.range.first) ?: return@mapNotNull null
        val argument = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
        val parameters = functionParameterTypes(file, function)
        val header = file.masked.substring(function.start, parameterListOpen(file, function) ?: function.start)
        val byClass = match.groupValues[2].isNotEmpty() && parameters[argument]?.let(CLASS_TYPE::matches) == true
        val byReified = match.groupValues[2].isEmpty() && Regex("\\breified\\s+${Regex.escape(argument)}\\b").containsMatchIn(header)
        if (!byClass && !byReified) return@mapNotNull null
        val dot = match.groups[1]?.range?.first
        val receiverStart = dot?.let { receiverStart(file, it) }
        val bindings = if (dot == null || receiverStart == null) listOf(RetrofitBinding(null, null)) else resolver.resolveReceiver(file, receiverStart, dot)
        GenericCreator(file, function.name, file.enclosingTypes(function.start).lastOrNull()?.name, byClass, bindings)
    }.toList()

    private fun functionParameterTypes(file: RouteSourceFile, function: RouteFunctionDecl): Map<String, String> {
        val open = parameterListOpen(file, function) ?: return emptyMap()
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return emptyMap()
        return callArguments(file.code, open, close).mapNotNull { text ->
            val parameter = if (file.isJava) javaSourceParameter(text) else kotlinSourceParameter(text)
            parameter?.let { it.name to text.substringAfter(':', text).trim().removePrefix("final").trim() }
        }.associate { (name, text) -> name to (if (file.isJava) text.substringBeforeLast(name).trim() else text) }
    }

    /** 인터셉터 파일 안에서 요청 URL을 바꾸는 빌더 호출(`newBuilder()` 뒤 `url`·`host`·경로 변경)을 센다. */
    private fun countUrlRewrites(file: RouteSourceFile): Int {
        if (!INTERCEPTOR.containsMatchIn(file.masked)) return 0
        return NEW_BUILDER.findAll(file.masked).count { match ->
            insideInterceptor(file, match.range.first) && chainedCalls(file, match.range.last + 1).any { it in REWRITE_METHODS }
        }
    }

    /** [offset]이 `intercept` 함수 몸체나 인터셉터 람다(`Interceptor { … }`, `addInterceptor { … }`) 안인지 본다. */
    private fun insideInterceptor(file: RouteSourceFile, offset: Int): Boolean {
        if (file.functions.any { it.name == "intercept" && offset in it.bodyStart..it.end }) return true
        return INTERCEPTOR_BLOCK.findAll(file.masked.substring(0, offset)).any { block ->
            val open = block.range.last
            file.braceEnd(open).let { close -> close > offset }
        }
    }

    /** [from]부터 이어지는 `.name(…)` 호출 이름들이다. */
    private fun chainedCalls(file: RouteSourceFile, from: Int): List<String> {
        val names = mutableListOf<String>()
        var index = from
        while (true) {
            val dot = skipSpaces(file.masked, index)
            if (file.masked.getOrNull(dot) != '.') return names
            val name = Regex("\\G\\s*([A-Za-z_]\\w*)\\s*\\(").find(file.masked, dot + 1) ?: return names
            val close = balancedEnd(file.code, name.range.last).takeIf { it > name.range.last } ?: return names
            names += name.groupValues[1]
            index = close + 1
        }
    }

    private companion object {
        val RETROFIT_VERB = Regex("@(retrofit2\\s*\\.\\s*http\\s*\\.\\s*)?(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|HTTP)\\b")
        val JAVA_EXTENDS = Regex("\\bextends\\s+([^{]+?)(?:\\bimplements\\b|$)")
        val SUPERTYPE_NAME = Regex("[A-Za-z_][\\w.]*")

        /** `.create(X::class.java)`·`.create(X.class)`·`.create<X>()`와 수신 식 없는 `create(…)`다. 1번 그룹은 점 위치다. */
        val CLASS_LITERAL_CREATE = Regex(
            "(?:(\\.)\\s*|(?<![\\w.])(?=create))create\\s*(?:\\(\\s*([A-Za-z_][\\w.]*)\\s*::\\s*class\\s*\\.\\s*java\\s*\\)|" +
                "\\(\\s*([A-Za-z_][\\w.]*)\\s*\\.\\s*class\\s*\\)|<\\s*([A-Za-z_][\\w.]*)\\s*>\\s*\\(\\s*\\))",
        )

        /** 생성 함수 안의 `create(param)`·`create(T::class.java)`·`create<T>()`다. */
        val GENERIC_CREATE = Regex(
            "(?:(\\.)\\s*|(?<![\\w.])(?=create))create\\s*(?:\\(\\s*([A-Za-z_]\\w*)\\s*\\)|\\(\\s*([A-Za-z_]\\w*)\\s*::\\s*class\\s*\\.\\s*java\\s*\\)|" +
                "<\\s*([A-Za-z_]\\w*)\\s*>\\s*\\(\\s*\\))",
        )
        val INTERCEPTOR = Regex("\\bInterceptor\\b")
        /** `Class<T>`·`java.lang.Class<*>` 매개변수 타입이다(`ClassKind` 같은 다른 타입은 아니다). */
        val CLASS_TYPE = Regex("(?:java\\.lang\\.)?Class\\s*<.*>\\??", RegexOption.DOT_MATCHES_ALL)
        /** 인터셉터 람다·등록 블록의 여는 괄호다(`Interceptor { … }`, `addInterceptor { … }`). */
        val INTERCEPTOR_BLOCK = Regex("\\b(?:Interceptor|addInterceptor|addNetworkInterceptor)\\s*\\{")
        val NEW_BUILDER = Regex("\\.\\s*newBuilder\\s*\\(\\s*\\)")
        val REWRITE_METHODS = setOf(
            "url", "host", "scheme", "port", "encodedPath", "addPathSegment", "addPathSegments", "addEncodedPathSegment",
            "addEncodedPathSegments", "setPathSegment", "setEncodedPathSegment", "removePathSegment",
        )
    }
}

/**
 * 서비스 인터페이스를 인자로 받아 Retrofit `create`로 넘기는 생성 함수다.
 *
 * @property file 함수를 선언한 파일이다
 * @property name 함수 이름이다
 * @property owner 함수를 감싸는 타입의 단순 이름이다. 최상위 함수면 null이다
 * @property byClass `Class<T>` 매개변수로 받으면 참, reified 타입 매개변수(`create<T>()`)면 거짓이다
 * @property bindings 함수 안 `create` 수신 식의 결합이다
 */
private class GenericCreator(val file: RouteSourceFile, val name: String, val owner: String?, val byClass: Boolean, val bindings: List<RetrofitBinding>) {
    private val classCall = Regex("(?<![\\w])(?:([A-Za-z_][\\w.]*)\\s*\\.\\s*)?${Regex.escape(name)}\\s*\\(\\s*([A-Za-z_][\\w.]*)\\s*(?:::\\s*class\\s*\\.\\s*java|\\.\\s*class)\\s*[,)]")
    private val reifiedCall = Regex("(?<![\\w])(?:([A-Za-z_][\\w.]*)\\s*\\.\\s*)?${Regex.escape(name)}\\s*<\\s*([A-Za-z_][\\w.]*)\\s*>\\s*\\(")

    /** 한정자가 이 함수를 가리키는지 본다 — 없거나, 소유 타입 이름(동반 객체·`INSTANCE` 포함)이다. */
    private fun acceptsQualifier(qualifier: String?): Boolean {
        if (qualifier == null) return name != "create"
        val cleaned = qualifier.split('.').filter { it != "INSTANCE" && it != "Companion" }.lastOrNull()
        return owner != null && cleaned == owner
    }

    /** 수신 식 [receiver]와 호출 원문 [call]이 이 생성 함수의 호출인지 본다(직접 `create` 목록에서 빼기 위해). */
    fun isCalledBy(receiver: String, call: String): Boolean =
        call.trimStart('.').trimStart().startsWith(name) && owner != null && acceptsQualifier(receiver)

    /** [file]에서 이 함수를 서비스 인터페이스 인자로 부른 곳의 서비스 FQN들이다. */
    fun callers(file: RouteSourceFile, declarations: SourceDeclarations): List<String> =
        (if (byClass) classCall else reifiedCall).findAll(file.masked).mapNotNull { match ->
            if (!acceptsQualifier(match.groups[1]?.value)) return@mapNotNull null
            declarations.resolveType(file, match.groupValues[2])
                ?: file.types.filter { it.name == match.groupValues[2] }.map { typeFqn(file, it) }.singleOrNull()
        }.toList()
}

/**
 * `.create` 앞 점 위치 [dot]에서 뒤로 걸어 수신 식이 시작하는 위치를 찾는다. 이름·호출 괄호·타입 인자·끝 람다·`!!`·`?.`로 이어진
 * 사슬만 따라가며 줄바꿈으로 이어진 사슬(`\n    .build()`)도 포함한다.
 *
 * @return 수신 식 시작 위치. 수신 식이 없으면 null이다
 */
internal fun receiverStart(file: RouteSourceFile, dot: Int): Int? {
    val masked = file.masked
    var index = if (masked.getOrNull(dot - 1) == '?') dot - 1 else dot
    var start: Int? = null
    while (true) {
        var cursor = index - 1
        while (cursor >= 0 && masked[cursor].isWhitespace()) cursor--
        while (cursor >= 1 && masked[cursor] == '!' && masked[cursor - 1] == '!') { cursor -= 2; while (cursor >= 0 && masked[cursor].isWhitespace()) cursor-- }
        if (cursor >= 0 && (masked[cursor] == '}' || masked[cursor] == ')')) {
            cursor = matchingOpen(masked, cursor) ?: return start
            cursor--
            while (cursor >= 0 && masked[cursor].isWhitespace()) cursor--
            if (cursor >= 0 && masked[cursor] == ')') { cursor = (matchingOpen(masked, cursor) ?: return start) - 1; while (cursor >= 0 && masked[cursor].isWhitespace()) cursor-- }
            if (cursor >= 0 && masked[cursor] == '>') { cursor = (matchingOpen(masked, cursor) ?: return start) - 1; while (cursor >= 0 && masked[cursor].isWhitespace()) cursor-- }
        }
        var identifierEnd = cursor
        while (cursor >= 0 && (masked[cursor].isLetterOrDigit() || masked[cursor] == '_')) cursor--
        if (cursor == identifierEnd) return start
        val word = masked.substring(cursor + 1, identifierEnd + 1)
        if (word in RECEIVER_STOP_WORDS) return start
        start = cursor + 1
        var before = cursor
        while (before >= 0 && masked[before].isWhitespace()) before--
        if (before >= 2 && masked.startsWith("new", before - 2) && (before < 3 || !masked[before - 3].isLetterOrDigit())) start = before - 2
        if (before >= 0 && masked[before] == '.') { index = if (masked.getOrNull(before - 1) == '?') before - 1 else before; continue }
        return start
    }
}

private val RECEIVER_STOP_WORDS = setOf("return", "else", "throw", "in", "is", "as", "if", "when", "val", "var", "new")

/** 닫는 괄호 [close](`)`·`}`·`>`)에 짝이 맞는 여는 괄호 위치다. 문자열이 가려진 뷰에서 센다. */
private fun matchingOpen(masked: String, close: Int): Int? {
    val closing = masked[close]
    val opening = when (closing) { ')' -> '('; '}' -> '{'; '>' -> '<'; else -> return null }
    var depth = 0
    for (index in close downTo 0) {
        when (masked[index]) {
            closing -> depth++
            opening -> { depth--; if (depth == 0) return index }
        }
    }
    return null
}
