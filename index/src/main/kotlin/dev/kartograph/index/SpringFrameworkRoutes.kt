package dev.kartograph.index

import dev.kartograph.core.RouteLimitationScope

/**
 * 프로젝트 선언 없이 Spring Boot·라이브러리가 등록하는 경로의 제공자다. 한계 문구의 라벨과 순서를 정한다.
 *
 * @property label `framework-provided-routes:` 문구에 싣는 제공자 설명이다
 */
internal enum class SpringFrameworkRoute(val label: String) {
    ERROR("error endpoint (/error) accepts every method"),
    WELCOME("welcome page handlers may answer the root path (/)"),
    STATIC("static resources and webjars (/**) may be served for GET and HEAD"),
    ACTUATOR("actuator endpoints (/actuator)"),
    SECURITY("Spring Security login and logout pages"),
    SPRINGDOC("springdoc OpenAPI and Swagger UI serve GET and HEAD"),
    DATA_REST("Spring Data REST repositories"),
    GRAPHQL("GraphQL endpoint"),
    H2_CONSOLE("H2 console"),
}

/**
 * 서버 측 한계 하나와 증명한 스코프다. 스코프가 null이면 문서 전체에 적용된다.
 *
 * @property message 한계 문구다
 * @property scope 이 한계가 가릴 수 있는 요청의 보수적 상한이다. [RouteLimitationScope.limitation]은 [message]다
 */
internal data class ServerLimitation(val message: String, val scope: RouteLimitationScope? = null)

/**
 * `framework-provided-routes:` 한계를 제공자마다 한 줄로 내고, 받을 수 있는 요청의 상한을 증명한 제공자에만 스코프를 붙인다
 * (isthmus GRAPH-EXCHANGE "`framework-provided-routes:`의 스코프").
 *
 * 스코프는 라우트 템플릿과 같은 접두사 규칙(context-path·base-path)을 적용한 요청 경로다. 근거는 Spring Boot 4.1.0·4.1.1과
 * Spring Framework 6.2.10·7.0.8 공식 소스와, 공개 앱·합성 코퍼스를 실제로 띄워 받은 `/actuator/mappings`다
 * (`docs/SPRING-ROUTES.md`).
 *
 * - 오류 컨트롤러: `BasicErrorController`의 `@RequestMapping("${server.error.path:${error.path:/error}}")`, method 제한 없음.
 * - welcome page: 서블릿 `WelcomePageNotAcceptableHandlerMapping`은 루트 경로를 모든 method로 받는다(406). WebFlux는 `GET /`.
 * - 정적 리소스: `ResourceHttpRequestHandler`·`ResourceWebHandler`는 GET·HEAD만 받는다. 위치는 의존성 JAR의
 *   `META-INF/resources`와 빌드 생성물을 포함해 열거할 수 없으므로 파일로 좁히지 않고, `static-path-pattern`과
 *   `webjars-path-pattern`의 리터럴 접두사로만 좁힌다.
 * - actuator: `management.endpoints.web.base-path`(기본 `/actuator`) 아래, Cloud Foundry `/cloudfoundryapplication` 아래.
 * - springdoc: 모든 엔드포인트가 `@GetMapping`이거나 리소스 핸들러다(springdoc-openapi 3.1.0) — 경로 대신 GET·HEAD로 좁힌다.
 * - H2 console: `spring.h2.console.path`(기본 `/h2-console`) 서블릿 매핑 `path` 아래 전체(와일드카드 매핑), method 제한 없음.
 * - Spring Security: [SpringSecurityRoutes]가 `SecurityFilterChain` 구성에서 증명한 필터 응답 경로(서블릿 스택만).
 * - Spring Data REST: 모든 핸들러가 `BasePathAwareHandlerMapping`·`RepositoryRestHandlerMapping`으로 `spring.data.rest.base-path`
 *   아래에 매핑된다(HAL explorer 리소스도 base path 아래). base path가 루트면 증명할 것이 없다.
 * - GraphQL: Boot `GraphQlWebMvcAutoConfiguration`·`GraphQlWebFluxAutoConfiguration`의 라우터가 `spring.graphql.http.path`(3.5 이전과
 *   3.5의 옛 키 `spring.graphql.path`, 기본 `/graphql`)의 GET·POST, `<path>/schema`, GraphiQL 경로, WebSocket 경로만 등록한다.
 *
 * 경로가 설정값에 기대면 기본 프로필 값을 쓰되 다른 프로필이 바꾸면, 또는 값을 풀지 못하면 그 제공자의 스코프를 생략한다.
 *
 * @param apps 프레임워크 경로를 등록할 앱 모듈의 설정이다
 * @param requestPrefix 앱 모듈의 요청 경로 접두사와 앵커다. 웹 서버가 없는 앱이면 null이다
 * @param webStack 앱 모듈의 웹 스택이다(`servlet`·`reactive`·null)
 * @param security Spring Security 필터 응답 경로를 증명하는 분석이다
 */
internal class SpringFrameworkRoutes(
    private val config: SpringProjectConfig,
    private val signals: SpringProjectSignals,
    private val apps: List<SpringModuleConfig>,
    private val requestPrefix: (SpringModuleConfig) -> Pair<String, String>?,
    private val webStack: (SpringModuleConfig) -> String?,
    private val security: SpringSecurityRoutes? = null,
) {
    /** 요청 경로 스코프 원소 모음이다. 제공자 하나의 앱별 결과를 합칠 때 쓴다. */
    private data class Range(
        val templates: Set<String> = emptySet(),
        val prefixes: Set<String> = emptySet(),
        val suffixes: Set<String> = emptySet(),
    ) {
        operator fun plus(other: Range) = Range(templates + other.templates, prefixes + other.prefixes, suffixes + other.suffixes)
    }

    /** 제공자마다 한계 한 줄과, 모든 앱에서 상한을 증명했으면 스코프를 낸다. */
    fun limitations(): List<ServerLimitation> = config.frameworkRoutes.map { route ->
        val message = "framework-provided-routes: ${route.label}; no project declaration and no synthetic route-decl facts are emitted"
        ServerLimitation(message, scope(route)?.let { (range, methods) -> toScope(message, range, methods) })
    }

    /** 웹 서버가 있는 앱과 그 접두사다. 앱이 하나도 없으면(웹 없음) 스코프를 만들 근거가 없다. */
    private fun webApps(): List<Pair<SpringModuleConfig, Pair<String, String>>> = apps.mapNotNull { app -> requestPrefix(app)?.let { app to it } }

    /**
     * 제공자의 요청 상한이다. 앱마다 구해 합치고, 한 앱이라도 증명하지 못하면 null이다.
     *
     * @return (경로 범위, method 목록). method가 비면 모든 method다
     */
    private fun scope(route: SpringFrameworkRoute): Pair<Range, List<String>>? {
        val webApps = webApps().ifEmpty { return null }
        val getHead = listOf("GET", "HEAD")
        return when (route) {
            SpringFrameworkRoute.ERROR -> union(webApps) { app, prefix -> errorRange(app, prefix) }?.let { it to emptyList() }
            SpringFrameworkRoute.WELCOME -> welcome(webApps)
            SpringFrameworkRoute.STATIC -> union(webApps) { app, prefix -> staticRange(app, prefix) }?.let { it to getHead }
            SpringFrameworkRoute.ACTUATOR -> union(webApps) { app, prefix -> actuatorRange(app, prefix) }?.let { it to emptyList() }
            SpringFrameworkRoute.SPRINGDOC -> union(webApps) { _, prefix -> rootRange(prefix) }?.let { it to getHead }
            SpringFrameworkRoute.H2_CONSOLE -> union(webApps) { app, prefix -> h2Range(app, prefix) }?.let { it to emptyList() }
            SpringFrameworkRoute.SECURITY -> securityScope(webApps)
            SpringFrameworkRoute.DATA_REST -> union(webApps) { app, prefix -> dataRestRange(app, prefix) }?.let { it to emptyList() }
            SpringFrameworkRoute.GRAPHQL -> union(webApps) { app, prefix -> graphQlRange(app, prefix) }?.let { it to listOf("GET", "HEAD", "POST") }
        }
    }

    private fun union(
        webApps: List<Pair<SpringModuleConfig, Pair<String, String>>>,
        range: (SpringModuleConfig, Pair<String, String>) -> Range?,
    ): Range? = webApps.fold(Range()) { acc, (app, prefix) -> acc + (range(app, prefix) ?: return null) }

    /** 앱 접두사 아래 모든 경로다. 접두사를 모르면 모든 경로(`/`)다. */
    private fun rootRange(prefix: Pair<String, String>): Range = Range(prefixes = setOf(root(prefix)))

    private fun root(prefix: Pair<String, String>): String = if (prefix.second == "root") prefix.first.ifEmpty { "/" } else "/"

    /**
     * 오류 컨트롤러 경로다. 접두사를 모르면 알 수 없는 앞부분 뒤의 꼬리(`templateSuffixes`)로 적는다. WebFlux 앱에는 오류
     * 컨트롤러가 없다(`ErrorWebExceptionHandler`는 라우트가 아니다).
     */
    private fun errorRange(app: SpringModuleConfig, prefix: Pair<String, String>): Range? {
        if (webStack(app) == "reactive") return Range()
        val path = resolved(app, "\${server.error.path:\${error.path:/error}}") ?: return null
        val converted = SpringPathPatterns.convert(if (prefix.second == "root") prefix.first + SpringPathPatterns.initFullPathPattern(path) else SpringPathPatterns.initFullPathPattern(path))
        val templates = (listOf(converted) + converted.emptyValueVariants).flatMap { listOfNotNull(it.template, it.catchAllPrefix) }
        if (templates.isEmpty()) return null
        if (prefix.second == "root") return Range(templates = templates.toSet())
        if (templates.any { it == "/" || it.split('/').contains("{**}") }) return null
        return Range(suffixes = templates.toSet())
    }

    /** welcome page다. 서블릿은 루트 경로의 모든 method, WebFlux는 `GET /`이다. 접두사를 모르면 증명하지 못한다. */
    private fun welcome(webApps: List<Pair<SpringModuleConfig, Pair<String, String>>>): Pair<Range, List<String>>? {
        if (webApps.any { it.second.second != "root" }) return null
        val templates = webApps.mapTo(sortedSetOf()) { root(it.second) }
        val reactiveOnly = webApps.all { webStack(it.first) == "reactive" }
        return Range(templates = templates) to if (reactiveOnly) listOf("GET", "HEAD") else emptyList()
    }

    /**
     * 정적 리소스와 webjars 경로의 리터럴 접두사다. 설정값을 확정하지 못하거나 프로젝트가 리소스 핸들러를 직접 등록하면 앱
     * 접두사 전체다. webjars는 설정 키가 없는 Boot 버전도 있어 기본값 `/webjars`를 항상 넣는다.
     */
    private fun staticRange(app: SpringModuleConfig, prefix: Pair<String, String>): Range {
        // 스택을 모르면 어느 쪽 키(spring.mvc·spring.webflux)가 쓰이는지 몰라 좁히지 않는다.
        val stack = webStack(app)
        if (prefix.second != "root" || signals.resourceHandlersRegistered || stack == null) return rootRange(prefix)
        val stackKey = if (stack == "reactive") "spring.webflux" else "spring.mvc"
        val patterns = listOf("$stackKey.static-path-pattern" to "/**", "$stackKey.webjars-path-pattern" to "/webjars/**")
            .map { (key, default) -> patternValue(app, key, default) }
        val prefixes = (patterns + "/webjars/**").map { pattern -> pattern?.let(::literalPrefix)?.let { joined(prefix.first, it) } ?: root(prefix) }
        return Range(prefixes = minimal(prefixes))
    }

    /** 경로 설정 키의 값이다. 없으면 [default], 다른 프로필이 바꾸거나 풀지 못하면 null이다. */
    private fun patternValue(app: SpringModuleConfig, key: String, default: String): String? = when (val lookup = app.lookup(key)) {
        SpringModuleConfig.Lookup.Absent -> default
        is SpringModuleConfig.Lookup.Value -> if (lookup.profileDependent) null else resolved(app, lookup.text)
        else -> null
    }

    /**
     * actuator 경로다. base-path가 비면(루트 매핑) 모든 경로라 증명할 수 없다. health group의 `additional-path`는 base-path 밖에
     * 매핑되므로 그 키가 있으면 스코프를 생략한다. 별도 관리 서버의 접두사(`management.server.base-path`, Boot 2의
     * `management.server.servlet.context-path`)도 더한다.
     */
    private fun actuatorRange(app: SpringModuleConfig, prefix: Pair<String, String>): Range? {
        if (prefix.second != "root") return null
        if (app.mayDefineKey { it.startsWith(HEALTH_GROUP) && it.endsWith(ADDITIONAL_PATH) }) return null
        val base = cleanedPath(app, "management.endpoints.web.base-path", "/actuator") ?: return null
        if (base.isEmpty()) return null
        val management = listOf("management.server.base-path", "management.server.servlet.context-path")
            .map { key -> cleanedPath(app, key, "") ?: return null }
        val prefixes = listOf(joined(prefix.first, base), joined(prefix.first, "/cloudfoundryapplication")) + management.map { joined(it, base) }
        return Range(prefixes = minimal(prefixes))
    }

    /**
     * Spring Security 필터가 응답하는 경로다. 필터는 context-path를 뺀 요청 경로에 매칭하므로 앱 접두사를 붙인다. 접두사를 모르거나
     * (DispatcherServlet 경로 등) 서블릿 스택이 아니면 증명하지 않는다. method는 모든 경로의 합집합이다(스코프 하나에 method 목록 하나).
     */
    private fun securityScope(webApps: List<Pair<SpringModuleConfig, Pair<String, String>>>): Pair<Range, List<String>>? {
        val endpoints = security?.endpoints(webApps.mapTo(mutableSetOf()) { it.first.root }, multipleApps = webApps.size > 1)?.ifEmpty { null } ?: return null
        val range = union(webApps) { app, prefix ->
            if (prefix.second != "root" || webStack(app) != "servlet") return@union null
            Range(
                templates = endpoints.filter { !it.prefix }.mapTo(sortedSetOf()) { joined(prefix.first, it.path) },
                prefixes = minimal(endpoints.filter { it.prefix }.map { joined(prefix.first, it.path) }),
            )
        } ?: return null
        val methods = if (endpoints.any { it.methods == null }) emptyList() else endpoints.flatMapTo(sortedSetOf<String>()) { it.methods.orEmpty() }.toList()
        return range to methods
    }

    /**
     * Spring Data REST base path 아래 전체다(모든 method). Boot 자동 구성이 없거나(빌드 표지), 코드가 base path를 바꾸거나
     * `RepositoryRestMvcConfiguration`을 직접 쓰면(Boot 속성이 적용되지 않는다) 증명하지 않는다. WebFlux 앱에는 Data REST가 없다.
     */
    private fun dataRestRange(app: SpringModuleConfig, prefix: Pair<String, String>): Range? {
        if (!config.dataRestAutoConfigured || signals.dataRestConfiguredInCode) return null
        when (webStack(app)) {
            "reactive" -> return Range()
            null -> return null
        }
        if (prefix.second != "root") return null
        // RepositoryRestConfiguration.setBasePath: 끝 `/`를 떼고 앞 `/`를 보충한다. 비면 루트라 증명할 것이 없다.
        val raw = patternValue(app, "spring.data.rest.base-path", "")?.trim() ?: return null
        val base = literalPath(if (raw.isEmpty() || raw.startsWith('/')) raw else "/$raw") ?: return null
        return if (base.isEmpty()) null else Range(prefixes = setOf(joined(prefix.first, base)))
    }

    /**
     * GraphQL 라우터 경로다. HTTP 경로는 옛 키·새 키의 값과 기본값을 모두 넣는다(버전마다 읽는 키가 달라 합집합으로 덮는다).
     * GraphiQL은 켜졌는지와 무관하게 넣고, WebSocket 경로는 설정했을 때만 넣는다.
     */
    private fun graphQlRange(app: SpringModuleConfig, prefix: Pair<String, String>): Range? {
        if (prefix.second != "root") return null
        val paths = listOf("spring.graphql.path", "spring.graphql.http.path").map { key -> cleanedPath(app, key, "/graphql") ?: return null } + "/graphql"
        val graphiql = cleanedPath(app, "spring.graphql.graphiql.path", "/graphiql") ?: return null
        val websocket = if (app.lookup("spring.graphql.websocket.path") == SpringModuleConfig.Lookup.Absent) emptyList()
        else listOf(cleanedPath(app, "spring.graphql.websocket.path", "") ?: return null)
        val all = paths.flatMap { listOf(it, "$it/schema") } + graphiql + websocket
        return Range(templates = all.mapTo(sortedSetOf()) { joined(prefix.first, it) })
    }

    /** H2 console 서블릿 경로다(와일드카드 서블릿 매핑은 `path` 자체도 받는다). */
    private fun h2Range(app: SpringModuleConfig, prefix: Pair<String, String>): Range? {
        if (prefix.second != "root") return null
        val path = cleanedPath(app, "spring.h2.console.path", "/h2-console") ?: return null
        return if (path.isEmpty()) null else Range(prefixes = setOf(joined(prefix.first, path)))
    }

    /**
     * `/`로 시작하는 리터럴 경로 설정값이다. 끝 `/`를 떼고 `/` 하나는 빈 문자열이다. 없으면 [default], 다른 프로필이 바꾸거나
     * 풀지 못하거나 리터럴 템플릿이 아니면 null이다.
     */
    private fun cleanedPath(app: SpringModuleConfig, key: String, default: String): String? = patternValue(app, key, default)?.let(::literalPath)

    /** `/`로 시작하는 리터럴 경로 값을 정리한다. 끝 `/`를 떼고 `/` 하나는 빈 문자열이다. 리터럴 템플릿이 아니면 null이다. */
    private fun literalPath(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return ""
        if (!text.startsWith('/')) return null
        val cleaned = text.removeSuffix("/")
        if (cleaned.isEmpty()) return ""
        return RouteUrlRules.normalizePath(cleaned).takeIf { it.none { c -> c in "{}*?" } && RouteUrlRules.validateTemplate(it) == null }
    }

    /** 플레이스홀더를 앱 설정으로 푼다. 다른 프로필이 바꾸는 값을 썼거나 풀지 못하면 null이다. */
    private fun resolved(app: SpringModuleConfig, text: String): String? =
        SpringPlaceholders(listOf(app)).resolve(text).takeIf { !it.profileDependent }?.text

    /**
     * 경로 패턴의 리터럴 접두사다 — 와일드카드·변수가 없는 앞 세그먼트들이다. 없으면 루트(`/`)다. 정규 템플릿이 아니면 null이다.
     */
    private fun literalPrefix(pattern: String): String? {
        val segments = SpringPathPatterns.initFullPathPattern(pattern.trim()).removePrefix("/").split('/')
        val literals = segments.takeWhile { segment -> segment.isNotEmpty() && segment.none { it in "*?{}" } }
        val prefix = "/" + literals.joinToString("/") { RouteUrlRules.normalizePath(it) }
        return prefix.takeIf { RouteUrlRules.validateTemplate(it) == null }
    }

    /** 앱 접두사와 리터럴 경로를 잇는다. 둘 다 `/`로 시작하거나 비어 있다. 결과는 끝 `/` 없는 root 접두사(또는 `/`)다. */
    private fun joined(first: String, second: String): String = (first + second.removePrefix("/").let { if (it.isEmpty()) "" else "/$it" }).ifEmpty { "/" }

    /** 다른 접두사 아래에 있는 접두사를 뺀다(같은 요청 집합을 두 번 적지 않는다). */
    private fun minimal(prefixes: List<String>): Set<String> = prefixes.toSortedSet().filterTo(sortedSetOf()) { candidate ->
        prefixes.none { other -> other != candidate && (other == "/" || candidate.startsWith("$other/")) }
    }

    /** 범위를 계약 스코프로 바꾼다. 원소 하나라도 계약 문법을 어기면 스코프를 생략한다(문서 전체 효과). */
    private fun toScope(message: String, range: Range, methods: List<String>): RouteLimitationScope? {
        val scope = RouteLimitationScope(message, range.templates.sorted(), range.prefixes.sorted(), range.suffixes.sorted(), methods.sorted())
        return scope.takeIf { RouteLimitationScopes.problem(it) == null }
    }

    private companion object {
        val HEALTH_GROUP = SpringConfigDocuments.normalizeKey("management.endpoint.health.group.")
        val ADDITIONAL_PATH = SpringConfigDocuments.normalizeKey(".additional-path")
    }
}
