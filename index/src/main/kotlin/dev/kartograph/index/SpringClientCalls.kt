package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.ComposedRoute
import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Spring HTTP 클라이언트 호출 하나에서 낸 route-call 후보다. base 결합마다 하나씩 만든다.
 *
 * @property start 호출 식(수신 식 또는 인터페이스 메서드 어노테이션)의 시작 위치다
 * @property method 동사다. 증명하지 못하면 null(`methodDynamic`)이다
 * @property composed base와 결합한 경로다
 * @property expression dynamic일 때 channel로 실을 경로 식 원문이다
 * @property parts 경로 식을 푼 조각이다. 매개변수 싱크 판정에 쓴다(`@HttpExchange`는 비어 있다 — 어노테이션 값은 상수다)
 * @property baseRef base를 공급하는 선언의 생산자 id다
 * @property unresolvedBase base 값을 몰라 경로를 host 루트부터 확정하지 못했다(`unresolved-base-url:`로 센다)
 * @property profileDependent 쓴 설정 값을 다른 프로필이 다르게 정한다
 * @property symbolName 사실의 소스 한정 이름이다. null이면 감싸는 선언이다
 * @property declaration `@HttpExchange` 인터페이스 메서드의 선언이다(snapshot 신원 부착용)
 */
internal data class SpringClientCall(
    val start: Int,
    val method: String?,
    val composed: ComposedRoute,
    val expression: String?,
    val parts: List<UrlPart>,
    val baseRef: String?,
    val unresolvedBase: Boolean,
    val profileDependent: Boolean,
    val symbolName: String? = null,
    val declaration: RetrofitDeclaration? = null,
)

/**
 * 한 파일의 Spring HTTP 클라이언트 호출을 찾는다 — `RestTemplate`의 요청 메서드, `RestClient`·`WebClient`의 동사 + `.uri(…)` 사슬,
 * `@HttpExchange` 인터페이스 메서드다. 수신 식은 [SpringClientResolver]로 클라이언트임을 증명한 것만 본다.
 *
 * @param resolver 클라이언트 식 해석기다
 * @param exchanges `@HttpExchange` 인터페이스의 base 색인이다
 */
internal class SpringClientCalls(private val resolver: SpringClientResolver, private val exchanges: SpringExchangeIndex) {
    /**
     * [file]의 호출을 [sink]로 넘긴다. 클라이언트 호출로 보이지만 모양을 모델링하지 못한 호출은 [unmodeled]로 센다.
     */
    fun scan(file: RouteSourceFile, sink: (SpringClientCall) -> Unit, unmodeled: () -> Unit) {
        exchanges.facts(file).forEach(sink)
        if (mentionsSpringClients(file)) FileScan(file, sink, unmodeled).run()
    }

    /** Spring 클라이언트 패키지를 import하거나 FQN으로 쓰는 파일만 본다. */
    private fun mentionsSpringClients(file: RouteSourceFile): Boolean =
        file.imports.any { import -> CLIENT_PACKAGES.any { import.path.startsWith(it) } } || CLIENT_PACKAGES.any { it in file.masked }

    private inner class FileScan(
        private val file: RouteSourceFile,
        private val sink: (SpringClientCall) -> Unit,
        private val unmodeled: () -> Unit,
    ) {
        private val paths = resolver.pathResolver(file)

        fun run() {
            CALL.findAll(file.masked).forEach(::scanCall)
        }

        private fun scanCall(match: MatchResult) {
            val dot = match.range.first
            val name = match.groupValues[1]
            val open = match.range.last
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return
            val receiver = receiverStart(file, dot)?.takeIf { it < dot }
            val client = receiver?.let { resolver.client(file, it, dot) }
            if (client == null) {
                // 클라이언트 전용 메서드 이름·`.get().uri(` 사슬인데 수신 식을 증명하지 못했다 — 놓친 호출로 센다.
                if (name in DISTINCT_TEMPLATE_METHODS || name in VERBS && firstUri(close + 1) != null || templateShaped(name, open, close)) unmodeled()
                return
            }
            when {
                client.kind == SpringClientKind.REST_TEMPLATE && name in TEMPLATE_METHODS -> restTemplate(receiver, name, open, close, client)
                client.kind != SpringClientKind.REST_TEMPLATE && name in VERBS -> fluent(receiver, name, open, close, client)
            }
        }

        /**
         * 이름이 흔한 RestTemplate 메서드(`put`·`delete`·`exchange`·`execute`)는 첫 인자가 URL 템플릿으로 보일 때(절대 URL이거나 URI 템플릿
         * 변수 `{x}`를 담은 문자열)만 놓친 호출로 센다 — 다른 객체의 같은 이름 메서드(`map.put("/key", value)`)를 세지 않기 위해서다.
         */
        private fun templateShaped(name: String, open: Int, close: Int): Boolean {
            if (name !in setOf("put", "delete", "exchange", "execute")) return false
            val first = callArguments(file.code, open, close).firstOrNull()?.trim() ?: return false
            if ('"' !in first) return false
            return "://" in first || URL_TEMPLATE_VARIABLE.containsMatchIn(first)
        }

        // ---- RestTemplate ----

        private fun restTemplate(start: Int, name: String, open: Int, close: Int, client: SpringClientInstance) {
            val arguments = callArguments(file.code, open, close).filter { it.isNotBlank() }
            val needsMethod = name == "exchange" || name == "execute"
            // `exchange(RequestEntity, …)`는 URL·동사가 요청 객체 안에 있다 — 모델링하지 않는다.
            if (arguments.isEmpty() || needsMethod && arguments.size < 3) return unmodeled()
            val method = if (needsMethod) methodOf(arguments[1], open) else TEMPLATE_METHODS.getValue(name)
            val url = urlExpression(arguments.first(), open + 1) ?: return unmodeled()
            // URI 인자는 uriTemplateHandler를 거치지 않는다(`execute(URI, …)`) — base 없이 그대로다.
            val bases = if (url.isUri) listOf(SpringClientBase(SpringClientBase.Mode.NONE)) else client.bases
            emitAll(start, method, url, bases)
        }

        // ---- RestClient·WebClient ----

        private fun fluent(start: Int, verb: String, open: Int, close: Int, client: SpringClientInstance) {
            val method = if (verb == "method") callArguments(file.code, open, close).singleOrNull()?.let { methodOf(it, open) } else verb.uppercase()
            val uri = firstUri(close + 1) ?: return unmodeled()
            val arguments = uri.arguments?.let { callArguments(file.code, it.first, it.last) }.orEmpty().filter { it.isNotBlank() }
            val lambda = uri.lambda?.let { file.code.substring(it.first + 1, it.last) } ?: arguments.firstOrNull()?.takeIf(::isLambda)
            val url = when {
                // `.uri { it.path(…).build() }` — base 빌더를 복제해 경로를 잇는다.
                arguments.isEmpty() || arguments.first() == lambda -> lambda?.let { builderLambda(it, uri.start) }
                else -> urlExpression(arguments.first(), uri.start)?.let { template ->
                    // `.uri(template, Function)`은 템플릿 빌더에 함수가 경로를 더 붙일 수 있다.
                    val extra = (lambda ?: arguments.getOrNull(1)?.takeIf(::isLambda))?.let { builderLambda(it, uri.start) }
                    if (extra == null) template else template.copy(parts = template.parts + extra.parts)
                }
            } ?: return unmodeled()
            val bases = when {
                !url.isUri -> client.bases
                // RestClient는 상대 URI를 base에 RFC 3986으로 해석하고, WebClient는 그대로 보낸다.
                client.kind == SpringClientKind.REST_CLIENT -> return emitResolvedUri(start, method, url, client.bases)
                else -> listOf(SpringClientBase(SpringClientBase.Mode.NONE))
            }
            emitAll(start, method, url, bases)
        }

        /** 동사 호출 뒤 사슬에서 첫 `.uri(…)` 단위다. 다른 사슬 단위를 지나 문장 끝에 닿으면 null이다. */
        private fun firstUri(from: Int): ChainSegment? {
            var index = from
            while (true) {
                val dot = skipSpaces(file.masked, index)
                if (file.masked.getOrNull(dot) == '?') index = dot + 1
                val at = skipSpaces(file.masked, index)
                if (file.masked.getOrNull(at) != '.') return null
                val name = Regex("\\G\\s*([A-Za-z_]\\w*)\\s*(?:<[^()\\n]*>)?\\s*").find(file.masked, at + 1) ?: return null
                var cursor = name.range.last + 1
                var arguments: IntRange? = null
                if (file.masked.getOrNull(cursor) == '(') {
                    val close = balancedEnd(file.code, cursor).takeIf { it > cursor } ?: return null
                    arguments = cursor..close
                    cursor = skipSpaces(file.masked, close + 1)
                }
                var lambda: IntRange? = null
                if (file.masked.getOrNull(cursor) == '{') {
                    val close = file.braceEnd(cursor).takeIf { it > cursor } ?: return null
                    lambda = cursor..close
                    cursor = close + 1
                }
                if (arguments == null && lambda == null) return null
                if (name.groupValues[1] == "uri") return ChainSegment("uri", at, arguments, null, lambda)
                index = cursor
            }
        }

        private fun isLambda(text: String): Boolean = LAMBDA_HEAD.containsMatchIn(text.trim()) || text.trim().startsWith("{")

        /**
         * `.uri { … }`·`uri(b -> …)`의 본문이 매개변수에서 시작하는 `path`·`pathSegment`·query 사슬이면 그 경로 조각이다.
         * scheme·host를 바꾸거나 모르는 메서드를 부르면 null(모델링하지 않음)이다.
         */
        private fun builderLambda(text: String, offset: Int): UrlExpression? {
            val trimmed = text.trim().removePrefix("{").removeSuffix("}").trim()
            val head = LAMBDA_HEAD.find(trimmed)
            val parameter = head?.groupValues?.get(1) ?: head?.groupValues?.get(2) ?: "it"
            var body = if (head != null) trimmed.substring(head.range.last + 1).trim() else trimmed
            body = body.removePrefix("{").removeSuffix("}").trim().removePrefix("return").trim().removeSuffix(";").trim()
            if (!body.startsWith(parameter)) return null
            val start = file.code.indexOf(body, offset).takeIf { it >= 0 } ?: return null
            val segments = chainSegments(file, start, start + body.length) ?: return null
            if (segments.first().name != parameter || !segments.first().isPlain) return null
            // 람다는 base를 복제한 빌더(`uriBuilderFactory.builder()`)를 받는다 — 경로는 템플릿처럼 base 경로 뒤에 문자열로 붙는다.
            return builderParts(segments.drop(1), emptyList(), isUri = false, allowHost = false)
        }

        /** 경로 인자 식 하나를 템플릿 조각으로 푼다 — 문자열 템플릿, `UriComponentsBuilder` 사슬, URI 생성 식, 지역 `val`이다. */
        private fun urlExpression(text: String, offset: Int, depth: Int = 0): UrlExpression? {
            val trimmed = unwrapParentheses(text.trim())
            val start = file.code.indexOf(trimmed, offset).takeIf { it >= 0 } ?: offset
            URI_WRAPPER.matchEntire(trimmed)?.let { match ->
                val open = match.groups[1]!!.range.first
                val close = balancedEnd(trimmed, open)
                if (close == trimmed.length - 1) {
                    val inner = callArguments(trimmed, open, close).singleOrNull() ?: return null
                    return urlExpression(inner, start + open + 1, depth + 1)?.copy(isUri = true)
                }
            }
            if (trimmed.startsWith("UriComponentsBuilder") || trimmed.startsWith("org.springframework.web.util.UriComponentsBuilder")) {
                return uriComponents(start, start + trimmed.length)
            }
            if (IDENTIFIER.matches(trimmed) && depth < MAX_DEPTH) {
                file.localDeclaration(trimmed, start)?.takeIf { it.isVal }?.let { local ->
                    val initializer = local.initializer
                    if (initializer.contains("UriComponentsBuilder") || URI_WRAPPER.matches(initializer.trim())) {
                        val position = file.code.lastIndexOf(initializer, start).takeIf { it >= 0 } ?: return null
                        return urlExpression(initializer, position, depth + 1)
                    }
                }
            }
            val pieces = templatePieces(trimmed, start)
            return UrlExpression(pieces.parts, trimmed, isUri = false, pieces.leadingRef, pieces.profileDependent)
        }

        /** 문자열 식의 조각이다. 값 조각이 참조면 `@Value`·상수·속성으로 한 번 더 푼다. */
        private fun templatePieces(text: String, offset: Int): Pieces {
            var profileDependent = false
            var leadingRef: String? = null
            val parts = paths.parts(text, offset).mapIndexed { index, part ->
                if (part !is UrlPart.Value || !REFERENCE.matches(part.expression)) return@mapIndexed part
                val value = resolver.stringValue(file, part.expression, offset)
                profileDependent = profileDependent || value.profileDependent
                if (value.text == null && index == 0) leadingRef = value.ref
                value.text?.let { UrlPart.Literal(it) } ?: part
            }
            return Pieces(parts, leadingRef, profileDependent)
        }

        private fun templateParts(text: String, offset: Int): List<UrlPart> = templatePieces(text, offset).parts

        /**
         * `UriComponentsBuilder.fromUriString(x)`·`fromHttpUrl(x)`·`fromPath(x)`·`newInstance()` 사슬이다. 끝이 `toUriString()`이면
         * 템플릿 문자열, `toUri()`면 URI다. 모르는 메서드가 있으면 null이다.
         */
        private fun uriComponents(start: Int, end: Int): UrlExpression? {
            val segments = chainSegments(file, start, end) ?: return null
            val rootIndex = segments.indexOfFirst { it.arguments != null }
            if (rootIndex < 0) return null
            val root = segments[rootIndex]
            val rootArgument = callArguments(file.code, root.arguments!!.first, root.arguments.last).singleOrNull()?.takeIf { it.isNotBlank() }
            val initial = when (root.name) {
                "fromUriString", "fromHttpUrl", "fromPath" -> templateParts(rootArgument ?: return null, root.arguments.first + 1)
                "newInstance" -> emptyList()
                else -> return null
            }
            return builderParts(segments.drop(rootIndex + 1), initial, isUri = false, allowHost = true)
        }

        /** 빌더 메서드 사슬을 조각에 적용한다. `toUri()`면 URI, `toUriString()`이면 템플릿이다. */
        private fun builderParts(segments: List<ChainSegment>, initial: List<UrlPart>, isUri: Boolean, allowHost: Boolean): UrlExpression? {
            var parts = initial
            var uri = isUri
            var scheme: String? = null
            var host: List<UrlPart>? = null
            for (segment in segments) {
                val arguments = segment.arguments?.let { callArguments(file.code, it.first, it.last) }.orEmpty().filter { it.isNotBlank() }
                val offset = (segment.arguments?.first ?: segment.start) + 1
                when (segment.name) {
                    "path" -> parts = parts + pathAfterSegments(parts, templateParts(arguments.singleOrNull() ?: return null, offset))
                    "pathSegment" -> parts = trimTrailingSlash(parts) + arguments.flatMap { listOf(UrlPart.Literal("/")) + segmentParts(it, offset) }
                    "scheme" -> if (allowHost) scheme = resolver.stringValue(file, arguments.singleOrNull() ?: return null, offset).text ?: return null else return null
                    "host" -> if (allowHost) host = templateParts(arguments.singleOrNull() ?: return null, offset) else return null
                    "port" -> return null
                    "toUri" -> uri = true
                    "toUriString" -> uri = false
                    in QUERY_METHODS -> Unit
                    else -> return null
                }
            }
            val absolute = host?.let { hostParts -> listOf(UrlPart.Literal("${scheme ?: "http"}://")) + hostParts + rooted(parts) } ?: parts
            return UrlExpression(absolute, null, uri)
        }

        /** `pathSegment` 값 하나 — 리터럴은 인코딩되는 한 세그먼트, 그 밖은 값 조각이다. */
        private fun segmentParts(text: String, offset: Int): List<UrlPart> {
            val value = resolver.stringValue(file, text, offset).text
            return listOf(if (value != null && '/' !in value) UrlPart.Literal(value.replace("{", "%7B").replace("}", "%7D")) else UrlPart.Value(text))
        }

        /** `pathSegment` 뒤의 `path`는 `/`를 앞에 붙인다(`CompositePathComponentBuilder.addPath`). */
        private fun pathAfterSegments(previous: List<UrlPart>, added: List<UrlPart>): List<UrlPart> {
            val first = added.firstOrNull() as? UrlPart.Literal
            val afterSegment = previous.lastOrNull() != null && previous.size >= 2 && previous[previous.size - 2] == UrlPart.Literal("/")
            return if (afterSegment && first != null && !first.text.startsWith('/')) listOf(UrlPart.Literal("/")) + added else added
        }

        private fun trimTrailingSlash(parts: List<UrlPart>): List<UrlPart> {
            val last = parts.lastOrNull() as? UrlPart.Literal ?: return parts
            return if (last.text.endsWith('/')) parts.dropLast(1) + UrlPart.Literal(last.text.dropLast(1)) else parts
        }

        private fun rooted(parts: List<UrlPart>): List<UrlPart> {
            val first = parts.firstOrNull() as? UrlPart.Literal ?: return parts
            return if (first.text.startsWith('/')) parts else listOf(UrlPart.Literal("/")) + parts
        }

        /** `HttpMethod.GET`·`HttpMethod.valueOf("GET")`·문자열 상수의 동사다. 증명하지 못하면 null이다. */
        private fun methodOf(text: String, offset: Int): String? {
            val trimmed = text.trim()
            HTTP_METHOD_CONSTANT.matchEntire(trimmed)?.let { return it.groupValues[1].takeIf { verb -> verb in RouteUrlRules.VERBS } }
            HTTP_METHOD_VALUE_OF.matchEntire(trimmed)?.let { match ->
                return paths.literalValue(match.groupValues[1], offset)?.takeIf { it in RouteUrlRules.VERBS }
            }
            // `import static …HttpMethod.GET` 뒤의 `GET`이다.
            if (trimmed in RouteUrlRules.VERBS && file.imports.any { it.path.endsWith("HttpMethod.$trimmed") || it.path.endsWith("HttpMethod.*") }) return trimmed
            return null
        }

        /** base 결합마다 사실 후보를 낸다. 같은 결과는 하나로 합친다. */
        private fun emitAll(start: Int, method: String?, url: UrlExpression, bases: List<SpringClientBase>) {
            bases.map { base ->
                val composed = SpringUriRules.compose(url.parts, base)
                val baseAnchored = composed.pathAnchor == "base"
                val ref = if (url.parts.firstOrNull() is UrlPart.Value) url.leadingRef ?: base.baseRef.takeIf { baseAnchored } else base.baseRef
                SpringClientCall(
                    start = start, method = method, composed = composed, expression = url.expression, parts = url.parts,
                    // 앞 조각이 값을 모르는 base(`"${'$'}url/x"`)이거나 클라이언트 base를 모르면 host 루트부터 확정하지 못한 호출이다.
                    baseRef = ref, unresolvedBase = baseAnchored && (url.parts.firstOrNull() is UrlPart.Value || base.unresolved || base.mode == SpringClientBase.Mode.NONE),
                    profileDependent = base.profileDependent || url.profileDependent,
                )
            }.distinct().forEach(sink)
        }

        /**
         * RestClient `.uri(URI)`다 — 절대 URI면 그대로, 상대 URI면 base에 RFC 3986으로 해석한다(`URI.resolve`). `/x`는 host 루트, `x`는
         * base 경로의 마지막 `/` 뒤를 버리고 붙인다. base를 모르는 상대 `x`는 dynamic이다.
         */
        private fun emitResolvedUri(start: Int, method: String?, url: UrlExpression, bases: List<SpringClientBase>) {
            val first = (url.parts.firstOrNull() as? UrlPart.Literal)?.text
            val absolute = first == null || SCHEME.containsMatchIn(first) || first.startsWith("//")
            if (absolute) return emitAll(start, method, url, listOf(SpringClientBase(SpringClientBase.Mode.NONE)))
            bases.map { base ->
                val known = base.base
                val parts = url.parts
                val composed = when {
                    first.startsWith('/') -> RouteUrlRules.compose(parts, RouteUrlRules.JoinMode.Declared("root", resolveDotSegments = true))
                        .copy(authority = known?.authority)
                    known != null -> RouteUrlRules.compose(
                        listOf(UrlPart.Literal(known.path.substringBeforeLast('/', "") + "/" + first)) + parts.drop(1),
                        RouteUrlRules.JoinMode.Declared("root", resolveDotSegments = true),
                    ).copy(authority = known.authority)
                    else -> ComposedRoute(null, dynamic = true)
                }
                SpringClientCall(start, method, composed, url.expression, parts, base.baseRef, unresolvedBase = known == null, profileDependent = base.profileDependent)
            }.distinct().forEach(sink)
        }
    }

    /**
     * 경로 식 하나를 푼 결과다.
     *
     * @property isUri `URI` 값이라 RestTemplate의 uriTemplateHandler를 거치지 않는다
     * @property leadingRef 첫 조각이 값을 모르는 참조면 그 원천 선언 id다(`baseRef`)
     * @property profileDependent 설정으로 푼 조각을 다른 프로필이 다르게 정한다
     */
    private data class UrlExpression(
        val parts: List<UrlPart>,
        val expression: String?,
        val isUri: Boolean,
        val leadingRef: String? = null,
        val profileDependent: Boolean = false,
    )

    /** 문자열 식을 푼 조각과 부가 정보다. */
    private data class Pieces(val parts: List<UrlPart>, val leadingRef: String?, val profileDependent: Boolean)

    private companion object {
        const val MAX_DEPTH = 8
        val CLIENT_PACKAGES = listOf("org.springframework.web.client", "org.springframework.web.reactive.function.client", "org.springframework.boot.web.client")

        /** RestTemplate 요청 메서드 → 동사다. `exchange`·`execute`는 두 번째 인자가 동사다. */
        val TEMPLATE_METHODS: Map<String, String?> = mapOf(
            "getForObject" to "GET", "getForEntity" to "GET", "postForObject" to "POST", "postForEntity" to "POST",
            "postForLocation" to "POST", "put" to "PUT", "patchForObject" to "PATCH", "delete" to "DELETE",
            "headForHeaders" to "HEAD", "optionsForAllow" to "OPTIONS", "exchange" to null, "execute" to null,
        )

        /** RestTemplate에만 있는 이름이다 — 수신 식을 증명하지 못해도 놓친 호출로 센다. */
        val DISTINCT_TEMPLATE_METHODS = TEMPLATE_METHODS.keys - setOf("put", "delete", "execute", "exchange")

        val VERBS = setOf("get", "post", "put", "patch", "delete", "head", "options", "method")
        val CALL = Regex("\\.\\s*(" + (TEMPLATE_METHODS.keys + VERBS).joinToString("|") + ")\\s*(?:<[^()\\n]*>)?\\s*\\(")
        val QUERY_METHODS = setOf(
            "queryParam", "queryParams", "queryParamIfPresent", "query", "replaceQuery", "replaceQueryParam", "replaceQueryParams",
            "fragment", "encode", "build", "buildAndExpand", "expand", "uriVariables", "userInfo",
        )
        val IDENTIFIER = Regex("[A-Za-z_]\\w*")
        val URL_TEMPLATE_VARIABLE = Regex("/[^\"]*\\{[A-Za-z_][^/{}\"]*}")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        val LAMBDA_HEAD = Regex("^(?:\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?\\s*->|\\{\\s*([A-Za-z_]\\w*)\\s*->)")
        val HTTP_METHOD_CONSTANT = Regex("(?:org\\.springframework\\.http\\.)?HttpMethod\\s*\\.\\s*([A-Z]+)")
        val HTTP_METHOD_VALUE_OF = Regex("(?:org\\.springframework\\.http\\.)?HttpMethod\\s*\\.\\s*(?:valueOf|resolve)\\s*\\((.*)\\)", RegexOption.DOT_MATCHES_ALL)
        val URI_WRAPPER = Regex("(?:new\\s+)?(?:java\\s*\\.\\s*net\\s*\\.\\s*)?URI\\s*(?:\\.\\s*create\\s*)?(\\().*", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Spring 클라이언트 스캔 구성이다 — 설정·해석기·`@HttpExchange` 색인을 한 번 만든다.
 *
 * @property calls 파일별 호출 스캐너다
 * @property exchanges `@HttpExchange` 색인이다(스캔 뒤 미해석 인터페이스를 읽는다)
 */
internal class SpringClientSetup(val calls: SpringClientCalls, val exchanges: SpringExchangeIndex) {
    companion object {
        /**
         * Spring 클라이언트나 `@HttpExchange`를 쓰는 파일이 있을 때만 구성을 만든다. 설정은 저장소 안 기본 프로필(`application*.yml`·
         * `.properties`)이고, 앱 모듈(`@SpringBootApplication`·Boot 실행 호출)이 있으면 그 모듈 설정을 후보로 쓴다.
         */
        fun of(root: java.nio.file.Path, files: List<RouteSourceFile>): SpringClientSetup? {
            if (files.none(::mentionsSpring)) return null
            val config = SpringProjectConfig.read(root)
            val production = files.filter { !it.isTest }
            val launchers = SpringProjectSignals.of(production).applicationLaunchers
            val annotated = production.filter { BOOT_APPLICATION.containsMatchIn(it.masked) }.map { it.relative }
            val appRoots = (annotated + launchers).mapNotNullTo(sortedSetOf()) { config.moduleOf(it)?.root }
            val cache = java.util.IdentityHashMap<RouteSourceFile, SpringPlaceholders>()
            val resolver = SpringClientResolver(production) { file ->
                cache.getOrPut(file) { SpringPlaceholders(config.candidatesFor(file.relative, appRoots)) }
            }
            val exchanges = SpringExchangeIndex(files, resolver)
            return SpringClientSetup(SpringClientCalls(resolver, exchanges), exchanges)
        }

        private fun mentionsSpring(file: RouteSourceFile): Boolean = SPRING_CLIENT_MARKERS.any { marker ->
            file.imports.any { it.path.startsWith(marker) } || marker in file.masked
        }

        private val SPRING_CLIENT_MARKERS = listOf(
            "org.springframework.web.client", "org.springframework.web.reactive.function.client", "org.springframework.boot.web.client",
            "org.springframework.web.service.annotation",
        )
        private val BOOT_APPLICATION = Regex("@(?:org\\.springframework\\.boot\\.autoconfigure\\.)?SpringBootApplication\\b")
    }
}
