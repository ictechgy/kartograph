package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.ComposedRoute
import dev.kartograph.index.RouteUrlRules.JoinMode
import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Spring HTTP 클라이언트 인스턴스가 경로에 base를 붙이는 방식이다.
 *
 * @property mode 결합 방식이다
 * @property base 정적으로 푼 base URL이다. null이면 값을 모르거나(`baseRef`) base가 없다([Mode.NONE])
 * @property baseRef base를 공급하는 선언(클라이언트를 담은 `@Bean` 메서드·속성·필드)의 생산자 id(`kt:` 소스 한정 이름)다
 * @property profileDependent base 값을 다른 프로필이 다르게 정한다 — 기본 프로필 값을 썼다
 */
internal data class SpringClientBase(
    val mode: Mode,
    val base: SpringBaseUrl? = null,
    val baseRef: String? = null,
    val profileDependent: Boolean = false,
) {
    /** base 결합 방식이다. */
    enum class Mode {
        /** `DefaultUriBuilderFactory(baseUrl)` — RestClient·WebClient `baseUrl`, `RestClient.create(url)`, 설정한 `uriTemplateHandler`다. */
        FACTORY,

        /** Spring Boot `RestTemplateBuilder.rootUri` — `/`로 시작하는 템플릿에만 root를 문자열로 붙인다. */
        ROOT_URI,

        /** base가 없다(`RestTemplate()`, base 없는 빌더). 템플릿이 그대로 URI다. */
        NONE,
    }

    /** base 값을 모르는 결합인지다. base가 없는 것([Mode.NONE])과 구분한다. */
    val unresolved: Boolean get() = mode != Mode.NONE && base == null

    companion object {
        /** 값을 모르는 base다. */
        fun unknown(ref: String?): SpringClientBase = SpringClientBase(Mode.FACTORY, null, ref)
    }
}

/**
 * Spring이 받아들이는 base URL 리터럴이다.
 *
 * @property authority userinfo를 뗀 소문자 `host[:port]`다. scheme 기본 포트는 지운다(Retrofit base와 같은 정규화)
 * @property path base의 경로다. 비어 있을 수 있고 끝 `/`를 강제하지 않는다 — Spring은 문자열로 이어 붙인다
 */
internal data class SpringBaseUrl(val authority: String, val path: String) {
    companion object {
        /**
         * `http(s)://host[:port][/path][?query][#fragment]` 리터럴을 나눈다. host가 계약 문법에 맞지 않거나 URI 템플릿 변수(`{x}`)를
         * 담으면 null이다 — 변수는 `defaultUriVariables`로 채워질 수 있어 값을 모른다. query·fragment는 경로와 무관해 뗀다.
         */
        fun parse(text: String): SpringBaseUrl? {
            val trimmed = text.trim()
            if ('{' in trimmed) return null
            val match = BASE_URL.matchEntire(trimmed) ?: return null
            val scheme = trimmed.substringBefore("://").lowercase()
            val raw = match.groupValues[1].substringAfterLast('@').lowercase().takeIf(AUTHORITY::matches) ?: return null
            val authority = withoutDefaultPort(raw, scheme) ?: return null
            return SpringBaseUrl(authority, match.groupValues[2])
        }

        private fun withoutDefaultPort(authority: String, scheme: String): String? {
            val port = PORT.find(authority) ?: return authority
            val digits = port.groupValues[1]
            if (digits.toInt() !in 1..65535 || digits.startsWith('0')) return null
            val default = if (scheme == "https") "443" else "80"
            return if (digits == default) authority.removeSuffix(port.value) else authority
        }

        private val PORT = Regex(":([0-9]{1,5})$")
        private val BASE_URL = Regex("(?i)https?://([^/?#\\s]+)([^?#\\s]*)(?:[?#]\\S*)?")
        private val AUTHORITY = Regex(
            "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*|\\[[0-9a-f:.]+])(?::[0-9]{1,5})?$",
        )
    }
}

/**
 * Spring `UriBuilderFactory`·`RestTemplateBuilder.rootUri`의 base + 템플릿 결합 규칙이다(Spring Framework 6.2.19·Boot 3.5.16 소스로
 * 확인, 출처 줄은 `docs/SPRING-CLIENTS.md`의 base 결합 절).
 *
 * - `DefaultUriBuilderFactory`는 host 없는 템플릿이면 base 빌더를 복제해 템플릿 경로를 **문자열로 이어 붙이고**
 *   (`FullPathComponentBuilder.append`), 빌드할 때 `//`를 `/`로 줄인다(`getSanitizedPath`). 슬래시를 넣지 않으므로 base 경로
 *   `/api` + `users`는 `/apiusers`다. host가 있는 템플릿(`http://…`, `//…`)은 base를 쓰지 않는다.
 * - `rootUri`는 `/`로 시작하는 템플릿에만 root 문자열을 앞에 붙이고, 그 밖의 템플릿은 그대로 둔다.
 * - URI 템플릿 변수 `{name}`(`UriComponents.NAMES_PATTERN`)은 값 조각이다 — 세그먼트 전체를 채우면 `{}`, 아니면 dynamic이다.
 * - 점 세그먼트는 지우지 않는다(`normalize()`를 부르지 않는다).
 */
internal object SpringUriRules {
    /**
     * base 결합 [base]로 템플릿 조각 [template]의 경로를 조립한다.
     *
     * @param template 템플릿 식을 푼 조각이다. 리터럴 안의 `{name}`은 아직 나누지 않은 상태로 받는다
     */
    fun compose(template: List<UrlPart>, base: SpringClientBase): ComposedRoute {
        val parts = splitVariables(mergeLiterals(template))
        if (parts.isEmpty()) return baseOnly(base)
        val first = parts.first()
        val firstText = (first as? UrlPart.Literal)?.text
        // rootUri는 `/`로 시작하는 템플릿에만 붙는다(`//`로 시작해도 문자열로 붙인다).
        if (base.mode == SpringClientBase.Mode.ROOT_URI && firstText != null && firstText.startsWith('/')) return joined(parts, base)
        if (firstText == null || isAbsolute(firstText)) return collapse(RouteUrlRules.compose(parts, JoinMode.Concat(null)))
        return when (base.mode) {
            SpringClientBase.Mode.FACTORY -> joined(parts, base)
            // host 없는 템플릿은 base 없이 보낼 수 없다(`URI is not absolute`) — 경로를 확정하지 않는다.
            SpringClientBase.Mode.ROOT_URI, SpringClientBase.Mode.NONE ->
                if (firstText.startsWith('/')) collapse(RouteUrlRules.compose(parts, JoinMode.Declared("base"))) else ComposedRoute(null, dynamic = true)
        }
    }

    /**
     * 템플릿 경로를 base 경로 뒤에 문자열로 잇는다. base가 리터럴이면 host 루트부터 확정한 root 템플릿이고, 모르면 `/`로 시작하는
     * 템플릿만 base 뒤 꼬리로 증명된다 — `x`는 base가 `/`로 끝나는지에 따라 `/a/x`·`/ax`로 갈려 dynamic이다.
     */
    private fun joined(parts: List<UrlPart>, base: SpringClientBase): ComposedRoute {
        val known = base.base
        val first = (parts.first() as UrlPart.Literal).text
        if (known == null) {
            if (!first.startsWith('/')) return ComposedRoute(null, dynamic = true, limitation = AMBIGUOUS_BASE_JOIN)
            return collapse(RouteUrlRules.compose(parts, JoinMode.Declared("base")))
        }
        val prefixed = listOf(UrlPart.Literal(known.path + first)) + parts.drop(1)
        return collapse(RouteUrlRules.compose(prefixed, JoinMode.Declared("root"))).copy(authority = known.authority)
    }

    /** 템플릿이 비었으면 base 자신에게 보낸다. */
    private fun baseOnly(base: SpringClientBase): ComposedRoute {
        val known = base.base ?: return ComposedRoute(null, dynamic = true)
        return collapse(RouteUrlRules.compose(listOf(UrlPart.Literal(known.path.ifEmpty { "/" })), JoinMode.Declared("root")))
            .copy(authority = known.authority)
    }

    /** scheme 있는 URL이나 network-path(`//host`)다 — host가 있어 base를 쓰지 않는다. */
    private fun isAbsolute(text: String): Boolean = SCHEME.containsMatchIn(text) || text.startsWith("//")

    /** Spring `getSanitizedPath`처럼 템플릿·접두사의 `//`를 `/`로 줄인다. */
    private fun collapse(route: ComposedRoute): ComposedRoute = route.copy(
        template = route.template?.let(::collapseSlashes),
        channelPrefix = route.channelPrefix?.let(::collapseSlashes),
    )

    /** 연속한 `/`를 하나로 줄인다. */
    fun collapseSlashes(path: String): String = DOUBLE_SLASH.replace(path, "/")

    /**
     * 리터럴 안의 URI 템플릿 변수 `{name}`·`{name:regex}`를 값 조각으로 나눈다. host 앞(scheme)까지 포함해 모든 자리에서 나눈다 —
     * host 안의 변수는 host를 모르게 만든다.
     */
    fun splitVariables(parts: List<UrlPart>): List<UrlPart> = parts.flatMap { part ->
        if (part !is UrlPart.Literal || '{' !in part.text) return@flatMap listOf(part)
        val result = mutableListOf<UrlPart>()
        var last = 0
        VARIABLE.findAll(part.text).forEach { match ->
            if (match.range.first > last) result += UrlPart.Literal(part.text.substring(last, match.range.first))
            result += UrlPart.Value(match.value)
            last = match.range.last + 1
        }
        if (last < part.text.length) result += UrlPart.Literal(part.text.substring(last))
        result
    }

    private fun mergeLiterals(parts: List<UrlPart>): List<UrlPart> {
        val result = mutableListOf<UrlPart>()
        parts.forEach { part ->
            val last = result.lastOrNull()
            when {
                part is UrlPart.Literal && part.text.isEmpty() -> Unit
                part is UrlPart.Literal && last is UrlPart.Literal -> result[result.lastIndex] = UrlPart.Literal(last.text + part.text)
                else -> result += part
            }
        }
        return result
    }

    private const val AMBIGUOUS_BASE_JOIN = "ambiguous-base-join:"
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val DOUBLE_SLASH = Regex("/{2,}")

    /** Spring `UriComponents.NAMES_PATTERN`(`\{([^/]+?)\}`)이다. */
    private val VARIABLE = Regex("\\{[^/]+?}")
}
