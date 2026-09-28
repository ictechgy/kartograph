package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.RouteDeclEvidence
import dev.kartograph.core.TestSourceSets
import dev.kartograph.core.qualifiedName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Spring MVC·WebFlux 어노테이션 controller를 isthmus http 도메인의 `route-decl` 사실로 수확한다.
 *
 * 값 원천은 두 가지다. class root가 주어지면 바이트코드 어노테이션(상수가 접힌 값)을 쓰고 소스에서는 위치만 얻는다.
 * 없으면 소스 어노테이션과 프로젝트 안 상수를 따라간다. 접두사(context-path·base-path)와 플레이스홀더는 저장소 안
 * Boot 설정의 기본 프로필로 푼다. 증명하지 못한 경로는 버리지 않고 dynamic 사실과 서버 측 한계로 남긴다.
 *
 * @param projectRoot 위치 경로의 기준이 되는 프로젝트 루트
 * @param sourceRoots 스캔할 source 루트들이다. 비어 있으면 프로젝트 전체다
 * @param includeTests 테스트 소스 세트도 스캔해 `testSource`로 표시할지 여부
 * @param service 문서의 서비스 신원이다
 * @param classRoots 값 원천으로 쓸 compiled class root다. snapshot provenance에서 얻는다
 */
public class RouteDeclScanner(
    private val projectRoot: Path,
    private val sourceRoots: List<Path> = emptyList(),
    private val includeTests: Boolean = false,
    private val service: String? = null,
    private val classRoots: List<Path> = emptyList(),
) {
    /**
     * 스캔해 `target: "http"`, `roles: ["server"]`, `dispatch: "specificity"` 문서를 만든다. 사실이 0건이어도
     * target을 유지한다 — "스캔했으나 없음"을 "스캔 안 함"과 구분하는 계약이다.
     *
     * @param graph snapshot 그래프다. 핸들러 메서드 정점이 있으면 `symbol.usr`를 붙인다
     */
    public fun scan(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument {
        val root = projectRoot.toRealPath()
        val files = collectFiles(root)
        val config = SpringProjectConfig.read(root)
        val signals = SpringProjectSignals.of(files)
        val sources = SpringSourceReader(files).read()
        val lines = files.associate { it.relative to it.source }
        val model = SpringModelMerger.merge(SpringBytecodeReader(classRoots).read(), sources) { path, offset -> lineOf(lines[path], offset) }
        val resolver = SpringMappingResolver(model, legacyTypeLevelHandlers = config.bootMajor?.let { it < 3 } == true)
        val emitter = SpringRouteEmitter(config, signals, lines, graph, includeTests, applicationRoots(model, config, signals))
        val handlers = resolver.handlers()
        val facts = emitter.emit(handlers)
        return BridgeFactsDocument(
            generatedAt = bridgeTimestamp(generatedAt?.let(Instant::parse) ?: Instant.now()),
            sourceModifiedAt = files.maxOfOrNull { Files.getLastModifiedTime(root.resolve(it.relative)).toInstant() }?.let(::bridgeTimestamp),
            platform = "kotlin",
            target = "http",
            project = root.toString().replace('\\', '/'),
            facts = facts,
            limitations = (emitter.limitations(resolver.stats) + signals.limitations(config, handlers.isNotEmpty())).distinct().sorted(),
            roles = listOf("server"),
            testSources = if (includeTests) "included" else "excluded",
            service = service,
            dispatch = "specificity",
        )
    }

    /** 스캔 루트의 source를 모아 프로젝트 기준 상대 경로 순서로 정렬한다. 테스트 소스는 포함할 때만 읽는다. */
    private fun collectFiles(root: Path): List<RouteSourceFile> {
        val paths = sortedMapOf<String, Path>()
        sourceRoots.ifEmpty { listOf(root) }.forEach { sourceRoot ->
            ProjectTraversal.walkSources(sourceRoot, includeTests = true) { path ->
                val real = path.toRealPath()
                require(real.startsWith(root)) { "source root is outside the project root" }
                if (!ProjectTraversal.isPruned(root, real)) paths[root.relativize(real).joinToString("/")] = real
            }
        }
        return paths.filter { (relative, _) -> includeTests || !TestSourceSets.isTestSourcePath(relative) }.map { (relative, path) ->
            RouteSourceFile(relative, relative.endsWith(".java"), TestSourceSets.isTestSourcePath(relative),
                ProjectTraversal.readSourceLines(root, path, includeTests = true).joinToString("\n"))
        }
    }

    /**
     * `@SpringBootApplication` 타입이나 Boot 앱 실행 호출이 있는 모듈 루트다. 설정 후보를 앱 모듈로 좁힌다.
     * `@SpringBootConfiguration`만 있는 모듈은 테스트 지원 모듈일 수 있어 세지 않는다.
     */
    private fun applicationRoots(model: List<SpringType>, config: SpringProjectConfig, signals: SpringProjectSignals): Set<String> {
        val annotated = model.filter { type -> !type.isTest && type.annotations.any { it.type in APPLICATION_ANNOTATIONS } }.map { it.sourcePath }
        return (annotated + signals.applicationLaunchers).mapNotNullTo(sortedSetOf()) { path -> config.moduleOf(path)?.root }
    }

    private fun lineOf(source: String?, offset: Int): Int? =
        source?.let { it.substring(0, offset.coerceIn(0, it.length)).count { character -> character == '\n' } + 1 }

    private companion object {
        val APPLICATION_ANNOTATIONS = setOf("org.springframework.boot.autoconfigure.SpringBootApplication")
    }
}

/**
 * 핸들러를 사실로 바꾼다 — 경로 결합, 접두사, 플레이스홀더, 템플릿 변환, 위치, 신원.
 *
 * @param lines 프로젝트 상대 경로 → 소스 원문이다(위치 계산용)
 */
internal class SpringRouteEmitter(
    private val config: SpringProjectConfig,
    private val signals: SpringProjectSignals,
    private val lines: Map<String, String>,
    private val graph: CodeGraph?,
    private val includeTests: Boolean,
    private val applicationRoots: Set<String> = emptySet(),
) {
    private var dynamicPaths = 0
    private var placeholderPaths = 0
    private var profilePlaceholders = 0
    private var unlocated = 0
    private var unresolvedMethods = 0
    private var unresolvedPrefixes = sortedSetOf<String>()
    private var profilePrefixes = sortedSetOf<String>()
    private var webDisabled = 0

    private val trailingSlash: String? = when {
        signals.trailingSlashConfigured -> null
        config.bootMajor == null -> null
        config.bootMajor >= 3 -> "strict"
        else -> "optional"
    }

    /** 모든 핸들러의 사실을 위치·템플릿 순서로 낸다. catch-all 접두사 decl도 여기서 펼친다. */
    fun emit(handlers: List<SpringMappingResolver.Handler>): List<BridgeFact> {
        val facts = handlers.flatMap(::factsOf).distinct()
        return withCatchAllPrefixes(facts).sortedWith(
            compareBy({ it.location.path }, { it.location.line }, { it.location.column }, { it.channel.orEmpty() }, { it.method.orEmpty() },
                { it.routeDecl?.catchAllPrefix == true }),
        )
    }

    /** 사실 하나를 만들 재료다. 접두사 펼침에 원래 catch-all 정보를 남긴다. */
    private data class Draft(val fact: BridgeFact, val catchAllPrefix: String?)

    private val drafts = mutableListOf<Draft>()

    private fun factsOf(handler: SpringMappingResolver.Handler): List<BridgeFact> {
        val location = location(handler) ?: run { unlocated++; return emptyList() }
        val methods = methods(handler) ?: run { unresolvedMethods++; return emptyList() }
        val symbol = symbol(handler)
        val testSource = includeTests && (handler.mappingType.isTest || handler.declaringType.isTest || handler.handlerType.isTest)
        return routes(handler).flatMap { route ->
            methods.map { verb ->
                val fact = BridgeFact(
                    kind = "route-decl", channel = route.channel, method = verb, dynamic = route.template == null,
                    location = location, symbol = symbol, target = "http",
                    routeDecl = RouteDeclEvidence(route.anchor, trailingSlash.takeIf { route.template != null }, narrowed(handler),
                        route.constraints, route.configDefault, testSource = testSource),
                )
                drafts += Draft(fact, route.catchAllPrefix)
                fact
            }
        }
    }

    /** 0세그먼트 catch-all의 접두사 decl을 펼친다. usr가 없으면 계약상 표식을 달 수 없어 일반 decl로 내되 같은 키가 이미 있으면 뺀다. */
    private fun withCatchAllPrefixes(facts: List<BridgeFact>): List<BridgeFact> {
        val keys = facts.filter { !it.dynamic }.mapTo(mutableSetOf()) { it.method to it.channel }
        val prefixes = drafts.filter { it.catchAllPrefix != null }.mapNotNull { draft ->
            val marked = draft.fact.symbol?.usr != null
            if (!marked && (draft.fact.method to draft.catchAllPrefix) in keys) return@mapNotNull null
            val prefixSegments = if (draft.catchAllPrefix == "/") 0 else draft.catchAllPrefix!!.count { it == '/' }
            val original = draft.fact.routeDecl!!
            val evidence = original.copy(catchAllPrefix = marked, paramConstraints = original.paramConstraints.filter { it.segment < prefixSegments })
            draft.fact.copy(channel = draft.catchAllPrefix, routeDecl = evidence)
        }
        return (facts + prefixes).distinct()
    }

    /** 경로 하나다. [template]이 null이면 dynamic이고 [channel]은 패턴 원문이다. */
    private data class Route(
        val channel: String?,
        val template: String?,
        val anchor: String,
        val constraints: List<dev.kartograph.core.RouteParamConstraint> = emptyList(),
        val configDefault: Boolean = false,
        val catchAllPrefix: String? = null,
    )

    private fun routes(handler: SpringMappingResolver.Handler): List<Route> {
        val candidates = config.candidatesFor(handler.handlerType.sourcePath ?: handler.declaringType.sourcePath, applicationRoots)
        val placeholders = SpringPlaceholders(candidates)
        val prefix = prefix(candidates)
        if (prefix.anchor == NO_WEB) { webDisabled++; return emptyList() }
        val typePaths = paths(handler.typeMapping)
        val methodPaths = paths(handler.methodMapping)
        if (typePaths == null || methodPaths == null) { dynamicPaths++; return listOf(Route(null, null, prefix.anchor)) }
        return typePaths.flatMap { typePath -> methodPaths.flatMap { methodPath -> combined(typePath, methodPath, placeholders, prefix) } }.distinct()
    }

    /** 매핑의 경로 목록이다. 매핑이 없거나 비면 빈 경로 하나다. 풀지 못한 값이면 null이다. */
    private fun paths(mapping: SpringMappingResolver.Mapping?): List<String>? {
        val value = mapping?.attributes?.get("path") ?: return listOf("")
        val strings = (value as? SpringValue.Strings)?.values ?: return null
        return strings.ifEmpty { listOf("") }
    }

    private fun combined(typePath: String, methodPath: String, placeholders: SpringPlaceholders, prefix: Prefix): List<Route> {
        val typeResolved = placeholders.resolve(typePath)
        val methodResolved = placeholders.resolve(methodPath)
        if (typeResolved.text == null || methodResolved.text == null) {
            placeholderPaths++
            return listOf(Route(rawChannel(typePath, methodPath), null, prefix.anchor))
        }
        if (typeResolved.profileDependent || methodResolved.profileDependent) profilePlaceholders++
        val typePattern = SpringPathPatterns.initFullPathPattern(typeResolved.text)
        val methodPattern = SpringPathPatterns.initFullPathPattern(methodResolved.text)
        val patterns = if (typePattern.isEmpty() && methodPattern.isEmpty()) listOf("", "/")
        else listOf(SpringPathPatterns.combine(typePattern, methodPattern) ?: run { dynamicPaths++; return listOf(Route(rawChannel(typePath, methodPath), null, prefix.anchor)) })
        val usedDefault = typeResolved.usedDefault || methodResolved.usedDefault || prefix.configDefault
        return patterns.map { pattern -> route(prefix, pattern, usedDefault) }
    }

    private fun route(prefix: Prefix, pattern: String, usedDefault: Boolean): Route {
        val full = prefix.text + pattern
        val converted = SpringPathPatterns.convert(full)
        if (converted.template == null) { dynamicPaths++; return Route(clean(full), null, prefix.anchor) }
        return Route(converted.template, converted.template, prefix.anchor, converted.constraints, usedDefault, converted.catchAllPrefix)
    }

    /** dynamic 사실의 원문 채널이다. 제어 문자를 지우고 계약 길이로 자른다. */
    private fun rawChannel(typePath: String, methodPath: String): String? = clean(listOf(typePath, methodPath).filter(String::isNotEmpty).joinToString(" + "))

    private fun clean(text: String): String? = text.replace(CONTROL, " ").trim().take(RouteUrlRules.MAX_TEMPLATE_LENGTH).ifEmpty { null }

    /** 요청 동사다. 클래스·메서드 동사의 합집합이고(`RequestMethodsRequestCondition.combine`), 비면 `ANY`다. */
    private fun methods(handler: SpringMappingResolver.Handler): List<String>? {
        val verbs = listOfNotNull(handler.typeMapping, handler.methodMapping).flatMap { mapping ->
            when (val value = mapping.attributes["method"]) {
                null -> emptyList()
                is SpringValue.Enums -> value.names
                is SpringValue.Strings -> value.values.filter(String::isNotEmpty)
                SpringValue.Unresolved -> return null
            }
        }.distinct()
        if (verbs.any { it !in RouteUrlRules.VERBS }) return null
        return verbs.ifEmpty { listOf("ANY") }.sorted()
    }

    /** params·headers·consumes·produces·version 조건이 하나라도 있으면 같은 키를 나눈 핸들러다. */
    private fun narrowed(handler: SpringMappingResolver.Handler): Boolean =
        listOfNotNull(handler.typeMapping, handler.methodMapping).any { mapping ->
            NARROWING.any { key ->
                when (val value = mapping.attributes[key]) {
                    null -> false
                    is SpringValue.Strings -> value.values.any(String::isNotBlank)
                    is SpringValue.Enums -> value.names.isNotEmpty()
                    SpringValue.Unresolved -> true
                }
            }
        }

    /**
     * 사실 위치다. 메서드 매핑 어노테이션 토큰(상속이면 그 인터페이스·상위 메서드의 토큰), 없으면 핸들러 메서드 이름
     * 토큰이다. 둘 다 소스에서 찾지 못하면 null이다.
     */
    private fun location(handler: SpringMappingResolver.Handler): dev.kartograph.core.BridgeLocation? {
        val annotationPath = handler.mappingType.sourcePath
        val annotationOffset = handler.methodMapping.annotation.offset
        if (annotationPath != null && annotationOffset != null) return lines[annotationPath]?.let { sourceLocation(annotationPath, it, annotationOffset) }
        val methodPath = handler.declaringType.sourcePath ?: return null
        val nameOffset = handler.method.nameOffset ?: return null
        return lines[methodPath]?.let { sourceLocation(methodPath, it, nameOffset) }
    }

    /** 신원이다. snapshot 그래프에 핸들러 메서드 정점이 있을 때만 usr를 붙인다. */
    private fun symbol(handler: SpringMappingResolver.Handler): BridgeSymbol {
        val qualifiedName = handler.declaringType.name + "." + handler.method.name
        val node = graph?.let { findNode(it, handler) } ?: return BridgeSymbol(qualifiedName)
        return BridgeSymbol(node.qualifiedName, node.id.value)
    }

    private fun findNode(graph: CodeGraph, handler: SpringMappingResolver.Handler): dev.kartograph.core.GraphNode? {
        val owner = handler.declaringType.internalName
        handler.method.descriptor?.let { descriptor -> return graph.nodes[JvmNodeId.methodId(owner, handler.method.name, descriptor)] }
        val prefix = "method:$owner#${handler.method.name}("
        val candidates = graph.nodes.values.filter { it.id.value.startsWith(prefix) }
        return candidates.filter { parameterCount(it.id.value.substringAfter('#').substringAfter(handler.method.name)) == handler.method.parameterCount }
            .singleOrNull()
    }

    /** descriptor의 매개변수 수다. Kotlin `suspend`의 continuation은 세지 않는다. */
    private fun parameterCount(descriptor: String): Int {
        val types = org.objectweb.asm.Type.getArgumentTypes(descriptor)
        return types.size - if (types.lastOrNull()?.internalName == "kotlin/coroutines/Continuation") 1 else 0
    }

    /** 경로 접두사다. [text]는 `/`로 시작하거나 빈 문자열이다. */
    private data class Prefix(val text: String, val anchor: String, val configDefault: Boolean)

    /**
     * 서블릿 스택은 `server.servlet.context-path`(와 기본값이 아닌 `spring.mvc.servlet.path`), WebFlux는
     * `spring.webflux.base-path`다. Boot의 `cleanContextPath`·`cleanBasePath`처럼 끝 `/`를 뗀다. 확정하지 못하면
     * `base` 앵커로 두고 한계로 알린다. 다른 프로필만 값을 바꾸면 기본 프로필 값을 쓰고 한계로 알린다.
     */
    private fun prefix(candidates: List<SpringModuleConfig>): Prefix {
        if (signals.pathPrefixConfigured) return unresolvedPrefix("a WebMvcConfigurer/WebFluxConfigurer path prefix (addPathPrefix)")
        val prefixes = candidates.map { candidate -> candidatePrefix(candidate, SpringPlaceholders(listOf(candidate))) }.distinct()
        return prefixes.singleOrNull() ?: unresolvedPrefix("the route prefix, which differs between application modules,")
    }

    /** 앱 모듈 하나의 접두사다. */
    private fun candidatePrefix(candidate: SpringModuleConfig, placeholders: SpringPlaceholders): Prefix {
        val candidates = listOf(candidate)
        if (candidate.unreadable) return unresolvedPrefix("an unreadable application configuration file")
        val stack = stack(candidate)
        if (stack == NO_WEB) return Prefix("", NO_WEB, false)
        if (candidate.importsConfig) return unresolvedPrefix("spring.config.import")
        // Boot 1.x 키다. 지금 규칙은 Boot 2 이상의 키만 풀므로 이 키가 보이면 접두사를 확정하지 않는다.
        if (candidates.any { it.lookup(LEGACY_CONTEXT_PATH) != SpringModuleConfig.Lookup.Absent }) return unresolvedPrefix(LEGACY_CONTEXT_PATH)
        if (stack != "reactive") {
            val servletPath = prefixValue(SERVLET_PATH, candidates, placeholders) ?: return unresolvedPrefix(SERVLET_PATH)
            if (servletPath.text!!.isNotEmpty()) return unresolvedPrefix(SERVLET_PATH)
            if (servletPath.profileDependent) profilePrefixes += SERVLET_PATH
        }
        val keys = when (stack) { "servlet" -> listOf(CONTEXT_PATH); "reactive" -> listOf(BASE_PATH); else -> listOf(CONTEXT_PATH, BASE_PATH) }
        val values = keys.map { key -> key to (prefixValue(key, candidates, placeholders) ?: return unresolvedPrefix(key)) }
        val present = values.filter { it.second.text!!.isNotEmpty() || it.second.profileDependent }
        if (stack == null && present.isNotEmpty()) return unresolvedPrefix(present.joinToString(" or ") { it.first } + " (web stack unknown)")
        val (key, chosen) = present.firstOrNull() ?: return Prefix("", "root", false)
        if (chosen.profileDependent) profilePrefixes += key
        return Prefix(chosen.text!!, "root", chosen.usedDefault && chosen.text.isNotEmpty())
    }

    private fun unresolvedPrefix(reason: String): Prefix {
        unresolvedPrefixes += reason
        return Prefix("", "base", false)
    }

    /**
     * 접두사 키의 기본 프로필 값이다. 없으면 빈 문자열이고, 다른 프로필에만 있으면 빈 문자열 + [SpringPlaceholderResult.profileDependent]다.
     * 설정 후보끼리 결과가 다르거나 값을 풀지 못하면 null이다. 컨텍스트 경로가 `/`로 시작하지 않으면 Boot가 거부하므로 null이다.
     */
    private fun prefixValue(key: String, candidates: List<SpringModuleConfig>, placeholders: SpringPlaceholders): SpringPlaceholderResult? {
        val lookup = candidates.map { it.lookup(key) }.distinct().singleOrNull() ?: return null
        val raw = when (lookup) {
            SpringModuleConfig.Lookup.Absent -> SpringPlaceholderResult("")
            SpringModuleConfig.Lookup.OtherProfilesOnly -> SpringPlaceholderResult("", profileDependent = true)
            SpringModuleConfig.Lookup.Unknown -> return null
            is SpringModuleConfig.Lookup.Value -> placeholders.resolve(lookup.text).let { it.copy(profileDependent = it.profileDependent || lookup.profileDependent) }
        }
        val text = raw.text?.trim() ?: return null
        val cleaned = when {
            text.isEmpty() || text == "/" -> ""
            key == BASE_PATH -> (if (text.startsWith('/')) text else "/$text").removeSuffix("/")
            key == SERVLET_PATH -> text
            text.startsWith('/') -> text.removeSuffix("/")
            else -> return null
        }
        return raw.copy(text = cleaned)
    }

    /**
     * 웹 스택이다. `spring.main.web-application-type`이 있으면 그것, 모듈 빌드 파일이 한쪽만 쓰면 그것, 둘 다 쓰면 Boot 기본인
     * 서블릿이다. 모듈 빌드 파일로 정하지 못하면 프로젝트 전체에서 한쪽 표지만 보일 때만 정한다. 버전 카탈로그처럼 선언만
     * 모은 파일의 표지로는 실제로 쓰는 쪽을 알 수 없으므로, 양쪽이 다 보이면 모른다(null).
     */
    private fun stack(candidate: SpringModuleConfig): String? {
        when ((candidate.lookup(WEB_APPLICATION_TYPE) as? SpringModuleConfig.Lookup.Value)?.text?.trim()?.lowercase()) {
            "reactive" -> return "reactive"
            "servlet" -> return "servlet"
            "none" -> return NO_WEB
        }
        val servlet = config.moduleMentions(candidate.root, SpringProjectConfig.SERVLET_MARKERS) == true
        val reactive = config.moduleMentions(candidate.root, SpringProjectConfig.REACTIVE_MARKERS) == true
        return when {
            servlet -> "servlet"
            reactive -> "reactive"
            config.servlet && !config.reactive -> "servlet"
            config.reactive && !config.servlet -> "reactive"
            else -> null
        }
    }

    /** 사실이 싣지 못한 것을 서버 측 접두사 한계로 센다. */
    fun limitations(stats: SpringMappingResolver.Stats): List<String> = buildList {
        if (dynamicPaths > 0) add("route-coverage: $dynamicPaths mapping path(s) could not be converted to a canonical template (unresolved constants or pattern shapes such as ?, partial *, several variables in one segment, or a non-final **); their facts are dynamic")
        if (placeholderPaths > 0) add("route-coverage: $placeholderPaths mapping path(s) use placeholders without a default-profile value, or overridden only in other profiles; their facts are dynamic")
        if (profilePlaceholders > 0) add("route-coverage: $profilePlaceholders mapping path(s) use placeholder values that other profiles override; templates use the default profile")
        if (unlocated > 0) add("route-coverage: $unlocated handler method(s) have no source location for their mapping annotation (generated or unscanned sources); their facts are omitted")
        if (unresolvedMethods > 0) add("route-coverage: $unresolvedMethods handler method(s) have request methods that could not be resolved; their facts are omitted")
        if (stats.multipleMappings > 0) add("route-coverage: ${stats.multipleMappings} declaration(s) carry more than one mapping annotation; only the first is used")
        if (stats.unconfirmedControllers.isNotEmpty()) add("route-coverage: ${stats.unconfirmedControllers.size} class(es) declare mappings without a visible @Controller (a library stereotype, an unscanned superclass or another registration may make them handlers); their facts are omitted")
        if (webDisabled > 0) add("route-coverage: $webDisabled handler method(s) belong to application modules with spring.main.web-application-type=none; they serve no routes and no facts are emitted")
        if (stats.partiallyVisibleControllers.isNotEmpty()) add("route-coverage: ${stats.partiallyVisibleControllers.size} controller(s) inherit from types outside the scanned classes and sources; mappings declared there are not visible")
        if (unresolvedPrefixes.isNotEmpty()) add("unresolved-route-prefix: ${unresolvedPrefixes.joinToString(", ")} could not be resolved from in-repo configuration; affected facts use pathAnchor base")
        if (profilePrefixes.isNotEmpty()) add("unresolved-route-prefix: ${profilePrefixes.joinToString(", ")} differs in other profiles; templates use the default profile value")
    }

    private companion object {
        const val CONTEXT_PATH = "server.servlet.context-path"
        const val BASE_PATH = "spring.webflux.base-path"
        const val SERVLET_PATH = "spring.mvc.servlet.path"
        const val LEGACY_CONTEXT_PATH = "server.context-path"
        const val WEB_APPLICATION_TYPE = "spring.main.web-application-type"

        /** 웹 서버를 띄우지 않는 앱(`web-application-type=none`)의 접두사 표식이다. 사실을 내지 않는다. */
        const val NO_WEB = "none"
        val NARROWING = listOf("params", "headers", "consumes", "produces", "version")
        val CONTROL = Regex("\\p{C}")
    }
}
