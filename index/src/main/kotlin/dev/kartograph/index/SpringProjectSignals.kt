package dev.kartograph.index

/**
 * 어노테이션 매핑 밖에서 라우트를 바꾸거나 더하는 소스 신호다.
 *
 * 함수형 라우터(`RouterFunction`·`router {}`·`coRouter {}`), view controller 등록, 서블릿 등록, JAX-RS, 경로 접두사
 * 설정, 끝 슬래시·경로 매칭 전략 설정을 파일 단위로 센다. 이 경로들은 사실로 내지 않고(모델링하지 않음) 서버 측
 * 한계로 알린다 — 조용히 빠지면 호출이 거짓 `route-call-without-decl`이 된다.
 */
internal class SpringProjectSignals private constructor(
    private val functionalRouteFiles: Int,
    private val viewControllerFiles: Int,
    private val servletFiles: Int,
    private val jaxRsFiles: Int,
    val pathPrefixConfigured: Boolean,
    /** `SpringApplication.run(`·`runApplication<`/`runApplication(`을 부르는 소스 파일 경로다(앱 모듈 판정용). */
    val applicationLaunchers: List<String>,
    val trailingSlashConfigured: Boolean,
    private val antMatcherConfigured: Boolean,
) {
    /**
     * 신호와 Boot 버전으로 한계를 만든다.
     *
     * @param hasHandlers controller 핸들러를 하나 이상 찾았는지다. 빌드 표지가 없어도 핸들러가 있으면 버전을 모른다고 알린다
     */
    fun limitations(config: SpringProjectConfig, hasHandlers: Boolean): List<String> = buildList {
        if (config.frameworkRoutes.isNotEmpty()) {
            add("framework-provided-routes: ${config.frameworkRoutes.joinToString(", ")}; these routes have no project declaration and no synthetic route-decl facts are emitted")
        }
        if (config.bootMajor == null && (config.servlet || config.reactive || hasHandlers)) {
            add("route-framework-version-unknown: no single Spring Boot version was found in the build files; trailingSlash is omitted and Spring Boot 3+ path matching is assumed")
        }
        legacyMatching(config)?.let(::add)
        if (functionalRouteFiles > 0) add("route-coverage: $functionalRouteFiles source file(s) declare functional routes (RouterFunction, router or coRouter DSL) that are not extracted")
        if (viewControllerFiles > 0) add("route-coverage: $viewControllerFiles source file(s) register view, redirect or status controllers that are not extracted")
        if (servletFiles > 0) add("route-coverage: $servletFiles source file(s) register servlets outside the DispatcherServlet that are not extracted")
        if (jaxRsFiles > 0) add("route-coverage: $jaxRsFiles source file(s) use JAX-RS resources that are not extracted")
    }

    /** Boot 2.6 이전 MVC 기본(AntPathMatcher)이나 명시한 AntPathMatcher는 PathPattern 규칙과 다르다. */
    private fun legacyMatching(config: SpringProjectConfig): String? {
        val legacyDefault = config.servlet && config.bootMajor == 2 && (config.bootMinor ?: 0) < 6
        val configured = antMatcherConfigured || config.modules.any { module ->
            (module.lookup("spring.mvc.pathmatch.matching-strategy") as? SpringModuleConfig.Lookup.Value)?.text?.trim()?.lowercase() == "ant-path-matcher"
        }
        if (!legacyDefault && !configured) return null
        return "route-coverage: Spring MVC uses AntPathMatcher (Boot before 2.6 or configured); its suffix, trailing-slash and ** rules are not modeled"
    }

    companion object {
        /** 파일들에서 신호를 센다. 주석을 뺀 뷰에서 찾는다. */
        fun of(files: List<RouteSourceFile>): SpringProjectSignals = SpringProjectSignals(
            functionalRouteFiles = files.count { file -> FUNCTIONAL_IMPORTS.any { prefix -> file.imports.any { it.path.startsWith(prefix) } } && FUNCTIONAL_USE.containsMatchIn(file.masked) },
            viewControllerFiles = files.count { VIEW_CONTROLLER.containsMatchIn(it.masked) },
            servletFiles = files.count { SERVLET.containsMatchIn(it.masked) },
            jaxRsFiles = files.count { file -> file.imports.any { it.path.startsWith("jakarta.ws.rs.") || it.path.startsWith("javax.ws.rs.") } },
            pathPrefixConfigured = files.any { PATH_PREFIX.containsMatchIn(it.masked) },
            applicationLaunchers = files.filter { !it.isTest && LAUNCHER.containsMatchIn(it.masked) }.map { it.relative },
            trailingSlashConfigured = files.any { TRAILING_SLASH.containsMatchIn(it.masked) },
            antMatcherConfigured = files.any { ANT_MATCHER.containsMatchIn(it.masked) },
        )

        private val FUNCTIONAL_IMPORTS = listOf("org.springframework.web.servlet.function.", "org.springframework.web.reactive.function.server.")
        private val FUNCTIONAL_USE = Regex("\\b(?:RouterFunctions\\s*\\.|route\\s*\\(|router\\s*\\{|coRouter\\s*\\{|RouterFunction\\s*<)")
        private val VIEW_CONTROLLER = Regex("\\.\\s*add(?:View|RedirectView|Status)Controller\\s*\\(")
        private val SERVLET = Regex("\\bServletRegistrationBean\\b|@WebServlet\\b")
        private val PATH_PREFIX = Regex("\\.\\s*(?:addPathPrefix|setPathPrefixes)\\s*\\(")
        private val TRAILING_SLASH = Regex("setUseTrailingSlashMatch\\s*\\(\\s*true|useTrailingSlashMatch\\s*=\\s*true|\\bUrlHandlerFilter\\b|setMatchOptionalTrailingSeparator\\s*\\(\\s*true")
        private val ANT_MATCHER = Regex("\\.\\s*setPathMatcher\\s*\\(")
        private val LAUNCHER = Regex("\\bSpringApplication\\s*\\.\\s*run\\s*\\(|\\brunApplication\\s*[<(]|\\bSpringApplicationBuilder\\s*\\(")
    }
}
