package dev.kartograph.index

/**
 * Spring Security 필터가 프로젝트 선언 없이 직접 응답하는 요청 하나의 상한이다. 경로는 앱 접두사(context-path)를 붙이기 전의
 * 앱 안 경로다.
 *
 * @property path 정규 템플릿(`/login`)이나 접두사(`/oauth2/authorization`)다
 * @property prefix 참이면 [path]와 그 아래 모든 경로다
 * @property methods 받는 method다. null이면 모든 method다
 */
internal data class SecurityEndpoint(val path: String, val prefix: Boolean, val methods: Set<String>?)

/**
 * 서블릿 Spring Security가 직접 응답하는 경로의 상한을 소스의 `SecurityFilterChain` 구성에서 증명한다. 증명하지 못하면 null이고
 * `framework-provided-routes:` Security 한계는 문서 전체에 적용된다.
 *
 * 근거는 Spring Security 6.0.8·6.5.5·7.1.0(Boot 3.0·3.5·4.1)과 Spring Boot 3.0.13·3.5.6·4.1.0 공식 소스(Maven Central sources JAR)다.
 * - `HttpSecurity` prototype bean은 `csrf`·`exceptionHandling`·`headers`·`sessionManagement`·`securityContext`·`requestCache`·
 *   `anonymous`·`servletApi`·`DefaultLoginPageConfigurer`와 `logout`을 항상 적용한다(`HttpSecurityConfiguration.httpSecurity`).
 * - logout: `LogoutConfigurer.logoutUrl` 기본 `/logout`. `CsrfConfigurer`가 있으면 POST, 없으면 GET·POST·PUT·DELETE다.
 * - form login: `UsernamePasswordAuthenticationFilter`가 `loginProcessingUrl`(기본은 loginPage, loginPage 기본 `/login`)의 POST를
 *   받는다. `loginPage`를 부르지 않으면 `DefaultLoginPageGeneratingFilter`가 loginPage·failureUrl·`/login?logout`의 GET을,
 *   `DefaultLogoutPageGeneratingFilter`가 `GET /logout`을, `DefaultResourcesFilter`(6.4+)가 `GET /default-ui.css`를 받는다.
 * - oauth2 login: `OAuth2AuthorizationRequestRedirectFilter`가 `/oauth2/authorization/{registrationId}`를,
 *   `OAuth2LoginAuthenticationFilter`가 `/login/oauth2/code/` 아래 한 세그먼트를 method 제한 없이 받는다. loginPage가 없으면 기본 로그인 페이지도 켠다.
 * - resource server: 7.x `OAuth2ProtectedResourceMetadataFilter`가 `/.well-known/oauth-protected-resource` 아래 모든 경로의 GET을 받는다.
 * - HTTP Basic·CSRF·CORS·headers·인가 규칙·세션·예외 처리·remember-me·x509 등은 요청을 인증·거부·리다이렉트할 뿐 경로를 더하지 않는다.
 * - CORS 필터는 브라우저 preflight(`Origin`·`Access-Control-Request-Method` 헤더가 있는 OPTIONS)에만 응답한다 — 클라이언트 route-call이 아니다.
 * - 사용자 `SecurityFilterChain` bean이 없거나 조건부면 Boot 기본 체인(`formLogin`·`httpBasic`)이 쓰일 수 있다
 *   (`@ConditionalOnDefaultWebSecurity`). 빌드에 OAuth2·SAML 모듈이 있으면 기본 체인이 달라지므로 증명하지 않는다.
 *
 * 구성이 정적으로 풀리는 경우만 인정한다: `@Bean` 메서드가 `HttpSecurity` 하나를 받아 `SecurityFilterChain`을 돌려주고, 몸체가
 * 그 매개변수의 DSL 호출(lambda·chained·Kotlin `http { }`)과 `build()`뿐이다. 모르는 호출·지역 선언·제어문, 경로를 바꾸는
 * `RequestMatcher`·resolver, 모르는 configurer(`with`·`apply`·`addFilter*`·SAML·WebAuthn·one-time token 등), `WebSecurityCustomizer`,
 * `Customizer` bean, 프로젝트 `AbstractHttpConfigurer`, XML 구성, 리액티브 Security가 보이면 증명하지 않는다. 의존성 JAR이
 * spring.factories나 bean으로 더하는 구성은 저장소 소스로 볼 수 없다(`@Controller` component scan 가정과 같은 한계).
 *
 * @param files 스캔한 소스다. 테스트 소스는 보지 않는다
 * @param bootMajor Spring Boot 주 버전이다. 3·4(Security 6·7)만 증명한다
 * @param defaultChainReplaced 빌드에 Boot 기본 체인을 바꾸는 OAuth2·SAML 모듈 표지가 있다
 * @param factoriesConfigurer 프로젝트 `META-INF/spring.factories`가 기본 `AbstractHttpConfigurer`를 등록한다
 * @param wholeProject 프로젝트 전체 소스를 스캔했다. 일부만 스캔했으면 보지 못한 구성이 있을 수 있다
 * @param moduleRoot 소스 경로를 담은 모듈 루트다. 모르면 null이다
 */
internal class SpringSecurityRoutes(
    private val files: List<RouteSourceFile>,
    private val bootMajor: Int?,
    private val defaultChainReplaced: Boolean,
    private val factoriesConfigurer: Boolean,
    private val wholeProject: Boolean,
    private val moduleRoot: (String) -> String? = { null },
) {
    /**
     * 모든 체인이 받을 수 있는 요청의 합집합이다. 증명하지 못하면 null이다.
     *
     * @param appRoots 웹 앱 모듈 루트다. 앱 모듈 밖(라이브러리 모듈)의 체인 bean은 앱 클래스패스에 있는지 모르므로 조건부로 본다
     * @param multipleApps 웹 앱 모듈이 둘 이상이다. 어느 앱이 체인 bean을 등록하는지 모르므로 Boot 기본 체인도 더한다
     */
    fun endpoints(appRoots: Set<String>, multipleApps: Boolean): List<SecurityEndpoint>? {
        if (!wholeProject || bootMajor == null || bootMajor !in 3..4 || factoriesConfigurer) return null
        val chains = mutableListOf<SecurityChain>()
        var unconditional = false
        for (file in files.filter { !it.isTest }) {
            val text = withoutImports(file.masked)
            if (UNSUPPORTED.containsMatchIn(text)) return null
            val candidates = file.functions.filter { MENTION.containsMatchIn(text.substring(it.start, it.bodyStart)) }
            if (MENTION.findAll(text).any { match -> candidates.none { match.range.first in it.start until it.bodyStart } }) return null
            for (function in candidates) {
                chains += chainOf(file, function) ?: return null
                if (!isConditional(file, function) && moduleRoot(file.relative) in appRoots) unconditional = true
            }
        }
        val defaultChain = chains.isEmpty() || !unconditional || multipleApps
        if (defaultChain && defaultChainReplaced) return null
        return (chains.flatMap { it.endpoints() ?: return null } + if (defaultChain) DEFAULT_CHAIN else emptyList()).distinct()
    }

    /** `@Bean` + `HttpSecurity` 매개변수 하나 + `SecurityFilterChain` 반환인 메서드의 체인 구성이다. 아니면 null이다. */
    private fun chainOf(file: RouteSourceFile, function: RouteFunctionDecl): SecurityChain? {
        val receiver = function.parameters.filter { it.type == "HttpSecurity" }.singleOrNull()?.name ?: return null
        if (!RETURNS_CHAIN.containsMatchIn(file.masked.substring(function.start, function.bodyStart))) return null
        if (annotationNames(file, function).none { it == "Bean" }) return null
        // 블록 몸체는 `{` 다음부터 짝 `}` 앞까지, 식 몸체는 `=` 다음부터 식 끝까지다.
        return SecurityChainWalker(receiver, SecurityTokens.of(file, function.bodyStart + 1, function.end)).walk()
    }

    /**
     * 메서드나 감싸는 타입에 등록과 무관하다고 아는 어노테이션 밖의 것이 있으면 그 체인이 등록되지 않을 수 있다고 본다.
     * `@Conditional*`·`@Profile`뿐 아니라 그것을 메타 어노테이션으로 단 프로젝트 어노테이션(`@StagingOnly`)도 조건일 수 있기 때문이다.
     */
    private fun isConditional(file: RouteSourceFile, function: RouteFunctionDecl): Boolean {
        val typeAnnotations = file.enclosingTypes(function.start).flatMap { type ->
            SpringSourceSyntax.annotations(file, SpringSourceSyntax.segmentStart(file.masked, type.start), type.start).map { it.name.substringAfterLast('.') }
        }
        return (annotationNames(file, function) + typeAnnotations).any { it !in UNCONDITIONAL_ANNOTATIONS }
    }

    private fun annotationNames(file: RouteSourceFile, function: RouteFunctionDecl): List<String> {
        val end = if (file.isJava) function.bodyStart else function.start
        return SpringSourceSyntax.annotations(file, SpringSourceSyntax.segmentStart(file.masked, function.start), end).map { it.name.substringAfterLast('.') }
    }

    /** import 문을 같은 길이의 공백으로 지운다(위치를 보존한다). */
    private fun withoutImports(masked: String): String = IMPORT_LINE.replace(masked) { " ".repeat(it.value.length) }

    private companion object {
        /** 이 이름이 import 밖에 보이면 체인 bean 머리에만 있어야 한다. */
        val MENTION = Regex("\\b(?:HttpSecurity|SecurityFilterChain)\\b")
        val RETURNS_CHAIN = Regex("\\bSecurityFilterChain\\b")
        /** bean 등록 여부를 바꾸지 않는 어노테이션이다. */
        val UNCONDITIONAL_ANNOTATIONS = setOf(
            "Bean", "Configuration", "EnableWebSecurity", "EnableMethodSecurity", "EnableGlobalMethodSecurity", "Order", "Primary",
            "Lazy", "Description", "Deprecated", "SuppressWarnings", "Suppress", "Throws", "JvmStatic", "Override",
            "SpringBootApplication", "SpringBootConfiguration", "EnableAutoConfiguration", "ComponentScan", "Import",
        )
        val IMPORT_LINE = Regex("(?m)^[ \\t]*import\\b[^\\n;]*;?")

        /**
         * 모델링하지 않는 구성 표면이다 — 체인을 바꾸거나 더하는 bean·타입(`WebSecurityCustomizer`, `Customizer`·`ThrowingCustomizer`·
         * Kotlin DSL 함수 bean, 사용자 configurer, 직접 만든 체인, 요청 resolver), XML 구성, 리액티브 Security.
         */
        val UNSUPPORTED = Regex(
            "\\b(?:WebSecurityCustomizer|WebSecurityConfigurerAdapter|SecurityWebFilterChain|ServerHttpSecurity|SecurityConfigurerAdapter|" +
                "DefaultSecurityFilterChain|FilterChainProxy|OAuth2AuthorizationRequestResolver)\\b|" +
                "\\b(?:Throwing)?Customizer\\s*<|\\bAbstractHttpConfigurer\\s*<|Dsl\\s*\\.\\s*\\(\\s*\\)\\s*->|@ImportResource\\b",
        )

        /** Boot 기본 체인(`formLogin`·`httpBasic`, CSRF 켜짐)이 받는 요청이다. */
        val DEFAULT_CHAIN = listOf(
            SecurityEndpoint("/login", false, setOf("GET", "POST")),
            SecurityEndpoint("/logout", false, setOf("GET", "POST")),
            SecurityEndpoint("/default-ui.css", false, setOf("GET")),
        )
    }
}

/** configurer 하나에 기록한 사건이다. 마지막 사건이 `disable()`이면 꺼진 것이다. */
private class ConfigurerState {
    var used = false
    var disabledLast = false
    var everDisabled = false
    val values = mutableMapOf<String, MutableSet<String>>()

    fun use() { used = true; disabledLast = false }

    fun disable() { disabledLast = true; everDisabled = true }

    /** 설정한 값들이다. 설정하지 않았거나 꺼졌다가 다시 쓰였으면(기본값으로 다시 만들어짐) [default]도 넣는다. */
    fun valuesOr(key: String, default: String?): Set<String> {
        val set = values[key].orEmpty()
        return if (default != null && (set.isEmpty() || everDisabled)) set + default else set
    }

    val active: Boolean get() = used && !disabledLast
}

/** 한 `SecurityFilterChain` bean의 configurer 사건이다. logout·CSRF는 기본으로 적용된다. */
private class SecurityChain {
    val configurers = mutableMapOf<String, ConfigurerState>()
    var csrfMayBeDisabled = false

    init {
        state("logout").use()
    }

    fun state(name: String): ConfigurerState = configurers.getOrPut(name) { ConfigurerState() }

    /** 체인이 받을 수 있는 요청이다. 경로 값이 정규 리터럴이 아니면 null이다. */
    fun endpoints(): List<SecurityEndpoint>? {
        val result = mutableListOf<SecurityEndpoint>()
        val logout = state("logout")
        val getOnly = setOf("GET")
        var defaultPage = false
        val failurePaths = mutableSetOf<String>()
        if (logout.active) {
            val methods = if (csrfMayBeDisabled) setOf("DELETE", "GET", "POST", "PUT") else setOf("POST")
            logout.valuesOr("logoutUrl", "/logout").forEach { result += SecurityEndpoint(path(it) ?: return null, false, methods) }
        }
        state("formLogin").takeIf { it.active }?.let { form ->
            val pages = form.valuesOr("loginPage", null)
            val processing = form.values["loginProcessingUrl"].orEmpty().let { set ->
                if (set.isEmpty() || form.everDisabled) set + form.valuesOr("loginPage", "/login") else set
            }
            processing.forEach { result += SecurityEndpoint(path(it) ?: return null, false, setOf("POST")) }
            if (pages.isEmpty() || form.everDisabled) defaultPage = true
            failurePaths += form.valuesOr("failureUrl", null)
        }
        state("oauth2Login").takeIf { it.active }?.let { login ->
            result += SecurityEndpoint("/oauth2/authorization", true, null)
            result += SecurityEndpoint("/login/oauth2/code", true, null)
            if (login.valuesOr("loginPage", null).isEmpty() || login.everDisabled) defaultPage = true
            failurePaths += login.valuesOr("failureUrl", null)
        }
        if (state("oauth2ResourceServer").active) result += SecurityEndpoint("/.well-known/oauth-protected-resource", true, getOnly)
        if (defaultPage) {
            (failurePaths + "/login" + "/default-ui.css").forEach { result += SecurityEndpoint(path(it) ?: return null, false, getOnly) }
            if (logout.active) result += SecurityEndpoint("/logout", false, getOnly)
        }
        return result
    }

    /** 리터럴 경로 값이다. 질의 문자열을 떼고, `/`로 시작하는 정규 템플릿이 아니면(패턴·변수·절대 URL) null이다. */
    private fun path(value: String): String? {
        val text = value.substringBefore('?').trim()
        if (!text.startsWith('/') || text.any { it in "{}*#" }) return null
        val cleaned = text.removeSuffix("/").ifEmpty { "/" }
        return RouteUrlRules.normalizePath(cleaned).takeIf { RouteUrlRules.validateTemplate(it) == null }
    }
}

/** 보안 DSL을 걷기 위한 토큰이다. 문자열은 [literal]이 리터럴 값(템플릿·이스케이프 없음)일 때만 채운다. */
private data class SecurityToken(val text: String, val kind: Kind, val literal: String? = null) {
    enum class Kind { IDENT, STRING, SYMBOL, NEWLINE, OTHER }
}

/** 문자열 내용을 가린 뷰로 구조를 읽고, 원문에서 리터럴 값을 읽는다. 원시 문자열은 가린 뷰에서 보이지 않는다(값 없음). */
private object SecurityTokens {
    private val TWO_CHAR = setOf("->", "::", "?.", "==", "!=", "<=", ">=", "&&", "||", "?:", "!!", "..")

    fun of(file: RouteSourceFile, from: Int, to: Int): List<SecurityToken> {
        val masked = file.masked
        val tokens = mutableListOf<SecurityToken>()
        var index = from
        while (index < to.coerceAtMost(masked.length)) {
            val character = masked[index]
            when {
                character == '\n' -> { tokens += SecurityToken("\n", SecurityToken.Kind.NEWLINE); index++ }
                character.isWhitespace() -> index++
                character.isLetter() || character == '_' || character == '$' -> {
                    val end = identifierEnd(masked, index)
                    tokens += SecurityToken(masked.substring(index, end), SecurityToken.Kind.IDENT); index = end
                }
                character == '`' -> {
                    val end = masked.indexOf('`', index + 1).takeIf { it > index } ?: masked.length
                    tokens += SecurityToken(masked.substring(index + 1, end), SecurityToken.Kind.IDENT); index = end + 1
                }
                character.isDigit() -> {
                    val end = identifierEnd(masked, index)
                    tokens += SecurityToken(masked.substring(index, end), SecurityToken.Kind.OTHER); index = end
                }
                character == '"' || character == '\'' -> {
                    val close = masked.indexOf(character, index + 1).takeIf { it > index } ?: masked.length
                    val value = file.code.substring(index + 1, close.coerceAtMost(file.code.length))
                    val literal = value.takeIf { character == '"' && '\\' !in it && (file.isJava || '$' !in it) }
                    tokens += SecurityToken(value, if (character == '"') SecurityToken.Kind.STRING else SecurityToken.Kind.OTHER, literal)
                    index = close + 1
                }
                masked.substring(index, (index + 2).coerceAtMost(masked.length)) in TWO_CHAR -> {
                    tokens += SecurityToken(masked.substring(index, index + 2), SecurityToken.Kind.SYMBOL); index += 2
                }
                else -> { tokens += SecurityToken(character.toString(), SecurityToken.Kind.SYMBOL); index++ }
            }
        }
        return tokens
    }

    private fun identifierEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_' || text[end] == '$')) end++
        return end
    }
}

/**
 * configurer의 DSL 표면이다. [paths]는 리터럴 경로 하나를 받는 메서드·속성, [opaque]는 인자가 경로를 바꾸지 않는 메서드·속성이다.
 * 둘 다 아닌 이름은 증명을 멈춘다. [opaqueAll]이면 경로를 더하지 않는 configurer라 어떤 호출이든 받아들인다.
 */
private data class ConfigurerSpec(val paths: Set<String> = emptySet(), val opaque: Set<String> = emptySet(), val opaqueAll: Boolean = false)

/**
 * `SecurityFilterChain` bean 몸체의 토큰을 걷는다. 호출 인자는 괄호 짝으로 건너뛰고, configurer lambda·Kotlin DSL 블록은 그
 * configurer 문맥으로, chained 호출(`formLogin().loginPage(…).and()`)은 이름이 속하는 문맥으로 읽는다. 모르는 이름이 나오면 null이다.
 */
private class SecurityChainWalker(private val receiver: String, private val tokens: List<SecurityToken>) {
    private val chain = SecurityChain()

    fun walk(): SecurityChain? = if (walkRange(0, tokens.size, HTTP)) chain else null

    /** [from]부터 [to] 앞까지 걷는다. `;`에서 문맥을 [initial]로 되돌린다. */
    private fun walkRange(from: Int, to: Int, initial: String): Boolean {
        var context = initial
        var index = from
        while (index < to) {
            val token = tokens[index]
            if (token.text == ";") { context = initial; index++; continue }
            if (token.text == "@") { index = skipAnnotation(index + 1, to); continue }
            if (token.kind != SecurityToken.Kind.IDENT) { index++; continue }
            val nextIndex = significant(index + 1, to)
            val next = tokens.getOrNull(nextIndex)?.takeIf { nextIndex < to }?.text
            when {
                next == "(" -> {
                    val close = matching(nextIndex, to) ?: return false
                    val lambda = trailingLambda(close + 1, to)
                    context = call(token.text, nextIndex + 1 until close, lambda, context) ?: return false
                    index = (lambda?.last ?: (close - 1)) + 2
                }
                next == "{" && token.text == receiver && context == HTTP -> index = nextIndex + 1
                next == "{" -> {
                    val close = matching(nextIndex, to) ?: return false
                    context = call(token.text, IntRange.EMPTY, nextIndex + 1 until close, context) ?: return false
                    index = close + 1
                }
                next == "=" -> {
                    val end = statementEnd(nextIndex + 1, to)
                    if (!property(token.text, nextIndex + 1 until end, context)) return false
                    index = end
                }
                next == "::" -> {
                    val method = tokens.getOrNull(nextIndex + 1)?.takeIf { it.kind == SecurityToken.Kind.IDENT } ?: return false
                    if (token.text == "this" || !methodReference(method.text, context)) return false
                    index = nextIndex + 2
                }
                else -> index++
            }
        }
        return true
    }

    /** 이름 호출을 문맥에 맞게 처리하고 다음 문맥을 준다. 인정하지 않으면 null이다. */
    private fun call(name: String, args: IntRange, lambda: IntRange?, context: String): String? {
        if (context != HTTP) {
            val spec = SPECS.getValue(context)
            val allowed = name in COMMON || name == "disable" || name in spec.paths || name in spec.opaque
            // 경로를 더하지 않는 configurer의 중첩 호출은 모두 받되, HttpSecurity 이름이면 chained 문맥을 HttpSecurity로 돌린다.
            if (allowed || (spec.opaqueAll && name !in HTTP_METHODS)) return configurerCall(context, name, args, lambda)
        }
        if (name in COMMON) return context
        if (name !in HTTP_METHODS) return null
        if (name in HTTP_OPAQUE) return HTTP
        chain.state(name).use()
        if (name == "csrf" && !(args.isEmpty() && lambda == null) && !isWithDefaults(args)) chain.csrfMayBeDisabled = true
        if (args.isEmpty() && lambda == null) return name
        val spec = SPECS.getValue(name)
        if (!spec.opaqueAll) {
            if (!args.isEmpty() && !walkRange(args.first, args.last + 1, name)) return null
            if (lambda != null && !walkRange(lambda.first, lambda.last + 1, name)) return null
        }
        return HTTP
    }

    /** configurer 문맥 안의 호출이다. `disable()`은 configurer를 끄고 HttpSecurity로 돌아간다. */
    private fun configurerCall(context: String, name: String, args: IntRange, lambda: IntRange?): String? {
        val spec = SPECS.getValue(context)
        when {
            // 경로를 더하지 않는 configurer 안의 `disable()`은 중첩 설정(`frameOptions().disable()`)일 수 있어 문맥을 유지한다.
            spec.opaqueAll -> { if (context == "csrf") chain.csrfMayBeDisabled = true; return context }
            name == "disable" -> { disable(context); return HTTP }
            name in spec.paths -> {
                if (lambda != null) return null
                val literal = singleLiteral(args) ?: return null
                chain.state(context).values.getOrPut(name) { mutableSetOf() } += literal
            }
        }
        return context
    }

    /** Kotlin DSL 속성 대입이다(`loginPage = "/login"`). */
    private fun property(name: String, value: IntRange, context: String): Boolean {
        if (context == HTTP) return name in HTTP_OPAQUE
        val spec = SPECS.getValue(context)
        return when {
            spec.opaqueAll -> { if (context == "csrf") chain.csrfMayBeDisabled = true; true }
            name in spec.paths -> {
                val literal = singleLiteral(value) ?: return false
                chain.state(context).values.getOrPut(name) { mutableSetOf() } += literal
                true
            }
            else -> name in spec.opaque
        }
    }

    /** 메서드 참조(`AbstractHttpConfigurer::disable`)다. `disable`은 끄고, 경로를 바꾸지 않는 이름만 받아들인다. */
    private fun methodReference(method: String, context: String): Boolean {
        if (context == HTTP) return false
        val spec = SPECS.getValue(context)
        return when {
            method == "disable" -> { disable(context); true }
            spec.opaqueAll -> { if (context == "csrf") chain.csrfMayBeDisabled = true; true }
            else -> method in spec.opaque || method in COMMON
        }
    }

    private fun disable(context: String) {
        chain.state(context).disable()
        if (context == "csrf") chain.csrfMayBeDisabled = true
    }

    /** 인자가 `withDefaults()`·`Customizer.withDefaults()`뿐인지 본다. */
    private fun isWithDefaults(args: IntRange): Boolean {
        val texts = args.map { tokens[it] }.filter { it.kind != SecurityToken.Kind.NEWLINE }.map { it.text }
        return texts == listOf("withDefaults", "(", ")") || texts == listOf("Customizer", ".", "withDefaults", "(", ")")
    }

    /** 범위가 문자열 리터럴 하나뿐이면 그 값이다. */
    private fun singleLiteral(range: IntRange): String? =
        range.map { tokens[it] }.filter { it.kind != SecurityToken.Kind.NEWLINE }.singleOrNull()?.literal

    private fun significant(from: Int, to: Int): Int {
        var index = from
        while (index < to && tokens[index].kind == SecurityToken.Kind.NEWLINE) index++
        return index
    }

    /** 여는 괄호의 짝이다. */
    private fun matching(open: Int, to: Int): Int? {
        var depth = 0
        for (index in open until to) {
            when (tokens[index].text) {
                "(", "{", "[" -> depth++
                ")", "}", "]" -> { depth--; if (depth == 0) return index }
            }
        }
        return null
    }

    /** 닫는 괄호 바로 뒤(같은 줄)의 Kotlin trailing lambda 범위다. */
    private fun trailingLambda(from: Int, to: Int): IntRange? {
        if (from >= to || tokens[from].text != "{") return null
        val close = matching(from, to) ?: return null
        return from + 1 until close
    }

    /** Kotlin 속성 값의 끝이다 — 괄호 깊이 0의 줄바꿈·`;`·닫는 괄호. */
    private fun statementEnd(from: Int, to: Int): Int {
        var depth = 0
        var index = from
        while (index < to) {
            when (tokens[index].text) {
                "(", "{", "[" -> depth++
                ")", "}", "]" -> { if (depth == 0) return index; depth-- }
                ";", "\n" -> if (depth == 0 && index > from) return index
            }
            index++
        }
        return to
    }

    private fun skipAnnotation(from: Int, to: Int): Int {
        var index = from
        if (index < to && tokens[index].kind == SecurityToken.Kind.IDENT) index++
        if (index < to && tokens[index].text == "(") index = (matching(index, to) ?: (to - 1)) + 1
        return index
    }

    private companion object {
        const val HTTP = ""

        /** 어느 문맥에서든 경로를 바꾸지 않는 이름이다. */
        val COMMON = setOf("and", "withDefaults")

        /** 요청에 응답하는 필터를 더하지 않는 configurer다(Security 6.0.8·6.5.5·7.1.0 configurer의 `addFilter` 대상 확인). */
        val NO_ENDPOINT = listOf(
            "cors", "headers", "authorizeHttpRequests", "authorizeRequests", "httpBasic", "sessionManagement", "exceptionHandling",
            "securityContext", "requestCache", "anonymous", "servletApi", "rememberMe", "x509", "jee", "portMapper",
        )

        val AUTHENTICATION_FILTER_OPAQUE = setOf(
            "successHandler", "failureHandler", "authenticationSuccessHandler", "authenticationFailureHandler", "permitAll",
            "authenticationDetailsSource", "defaultSuccessUrl", "securityContextRepository",
        )

        val SPECS: Map<String, ConfigurerSpec> = NO_ENDPOINT.associateWith { ConfigurerSpec(opaqueAll = true) } + mapOf(
            "csrf" to ConfigurerSpec(opaqueAll = true),
            // 7.x 보호 자원 메타데이터 경로는 상수라 어떤 설정도 경로를 바꾸지 않는다.
            "oauth2ResourceServer" to ConfigurerSpec(opaqueAll = true),
            "logout" to ConfigurerSpec(
                paths = setOf("logoutUrl"),
                opaque = setOf("addLogoutHandler", "clearAuthentication", "invalidateHttpSession", "logoutSuccessUrl", "permitAll",
                    "deleteCookies", "logoutSuccessHandler", "defaultLogoutSuccessHandlerFor"),
            ),
            "formLogin" to ConfigurerSpec(
                paths = setOf("loginPage", "loginProcessingUrl", "failureUrl"),
                opaque = AUTHENTICATION_FILTER_OPAQUE + setOf("usernameParameter", "passwordParameter", "failureForwardUrl", "successForwardUrl"),
            ),
            "oauth2Login" to ConfigurerSpec(
                paths = setOf("loginPage", "failureUrl"),
                opaque = AUTHENTICATION_FILTER_OPAQUE + setOf("clientRegistrationRepository", "authorizedClientRepository",
                    "authorizedClientService", "oidcSessionRegistry", "tokenEndpoint", "userInfoEndpoint", "userService",
                    "oidcUserService", "userAuthoritiesMapper", "accessTokenResponseClient"),
            ),
        )

        /** HttpSecurity 수준에서 경로를 더하지 않는 호출이다(`securityMatcher`는 체인이 받는 요청을 좁히기만 한다). */
        val HTTP_OPAQUE = setOf("securityMatcher", "securityMatchers", "userDetailsService", "authenticationProvider", "authenticationManager", "build")

        val HTTP_METHODS = SPECS.keys + HTTP_OPAQUE
    }
}
