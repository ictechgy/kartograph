package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.ComposedRoute
import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Spring 6 선언형 HTTP 클라이언트(`@HttpExchange` 인터페이스)의 route-call 색인이다.
 *
 * 메서드마다 사실 하나를 내고 신원은 인터페이스 메서드다(Retrofit과 같다 — 호출자는 이 메서드를 `invokeinterface`로 부른다).
 * 경로는 `HttpServiceMethod.initUrl`처럼 타입 수준 `url`과 메서드 수준 `url`을 잇고(둘 다 있으면 사이에 `/`가 없을 때만 넣는다),
 * `HttpServiceProxyFactory`에 넘긴 어댑터(`RestClientAdapter`·`WebClientAdapter`·`RestTemplateAdapter`)의 클라이언트 base에
 * [SpringUriRules]로 붙인다. 프로젝트의 `@Controller`가 구현하는 인터페이스는 `createClient` 호출이 없으면 서버 계약으로 보고
 * 사실을 내지 않는다. `URI` 매개변수가 있으면 URL 전체가 실행 시점 값이라 dynamic이다.
 *
 * @param files 스캔한 source 파일 전체다(테스트 포함 가능)
 * @param resolver 클라이언트 식 해석기다(production 파일만 안다)
 */
internal class SpringExchangeIndex(files: List<RouteSourceFile>, private val resolver: SpringClientResolver) {
    private val production = files.filter { !it.isTest }

    /** base를 풀지 못한 사실을 낸 인터페이스 FQN이다(`unresolved-base-url:`). */
    val unresolvedInterfaces: MutableSet<String> = sortedSetOf()

    /** 인터페이스 FQN → `createClient`로 만든 base 결합들이다. */
    private val bindings: Map<String, List<SpringClientBase>> by lazy { collectBindings() }

    /** 프로젝트 `@Controller`·`@RestController`가 구현하는 인터페이스 FQN이다. */
    private val serverContracts: Set<String> by lazy { collectServerContracts() }

    /** [file]의 `@HttpExchange` 인터페이스 메서드 사실 후보들이다. */
    fun facts(file: RouteSourceFile): List<SpringClientCall> {
        if (!file.imports.any { it.path.startsWith(ANNOTATION_PACKAGE) } && ANNOTATION_PACKAGE !in file.masked) return emptyList()
        return EXCHANGE.findAll(file.masked).flatMap { match -> method(file, match) }.toList()
    }

    private fun method(file: RouteSourceFile, match: MatchResult): List<SpringClientCall> {
        val type = file.enclosingTypes(match.range.first).lastOrNull() ?: return emptyList()
        if (!file.masked.startsWith("interface", type.start)) return emptyList()
        val service = typeFqn(file, type)
        if (service in serverContracts && service !in bindings) return emptyList()
        val annotation = annotationAt(file, match) ?: return emptyList()
        val signature = signature(file, annotation.end) ?: return emptyList()
        val typeLevel = EXCHANGE.findAll(leadingAnnotations(file, type.start)).lastOrNull()
            ?.let { typeMatch -> annotationArguments(file, leadingAnnotations(file, type.start), typeMatch) }
        val method = annotation.verb ?: typeLevel?.verb
        val url = joinUrls(typeLevel?.url, annotation.url)
        val parts = url?.let { value -> if (value.text == null || "\${" in value.text) null else listOf<UrlPart>(UrlPart.Literal(value.text)) }
        val bases = when {
            signature.takesFactory -> listOf(SpringClientBase.unknown(null))
            else -> bindings[service] ?: listOf(SpringClientBase.unknown(null))
        }
        val types = file.enclosingTypes(match.range.first)
        val owner = (listOf(file.packageName.replace('.', '/')).filter { it.isNotEmpty() } + types.joinToString("$") { it.name }).joinToString("/")
        val declaration = RetrofitDeclaration(owner, signature.name, annotation.simpleName, signature.parameterCount, "$ANNOTATION_INTERNAL/${annotation.simpleName}")
        val symbolName = (listOf(file.packageName).filter { it.isNotEmpty() } + types.map { it.name } + signature.name).joinToString(".")
        return bases.map { base ->
            val composed = when {
                signature.takesUri -> ComposedRoute(null, dynamic = true)
                parts == null -> ComposedRoute(null, dynamic = true)
                else -> SpringUriRules.compose(parts, base)
            }
            val unresolved = composed.pathAnchor == "base" && (base.unresolved || base.mode == SpringClientBase.Mode.NONE)
            if (unresolved) unresolvedInterfaces += service
            SpringClientCall(
                start = match.range.first, method = method, composed = composed, expression = null, parts = emptyList(),
                baseRef = base.baseRef, unresolvedBase = false, profileDependent = base.profileDependent,
                symbolName = symbolName, declaration = declaration,
            )
        }.distinct()
    }

    /**
     * 어노테이션 값 하나다.
     *
     * @property text 상수로 푼 값이다. 풀지 못하면 null이다
     */
    private data class AnnotationValue(val text: String?)

    /** 어노테이션 하나의 동사·경로다. [end]는 어노테이션이 끝난 위치다. */
    private data class ExchangeAnnotation(val simpleName: String, val verb: String?, val url: AnnotationValue?, val end: Int)

    private fun annotationAt(file: RouteSourceFile, match: MatchResult): ExchangeAnnotation? =
        annotationArguments(file, file.code, match)

    /** [text]에서 찾은 어노테이션 [match]의 `value`·`url`·`method`를 읽는다. 어노테이션 값은 상수라 파일 밖 상수도 푼다. */
    private fun annotationArguments(file: RouteSourceFile, text: String, match: MatchResult): ExchangeAnnotation? {
        val simpleName = match.groupValues[2]
        val afterName = match.range.last + 1
        val open = skipSpaces(text, afterName).takeIf { text.getOrNull(it) == '(' }
        val close = open?.let { balancedEnd(text, it).takeIf { end -> end > it } ?: return null }
        val arguments = if (open != null && close != null) callArguments(text, open, close).filter { it.isNotBlank() } else emptyList()
        fun named(name: String): String? = arguments.firstNotNullOfOrNull { argument ->
            NAMED.matchEntire(argument)?.takeIf { it.groupValues[1] == name }?.groupValues?.get(2)
        }
        val positional = arguments.firstOrNull { !NAMED.matches(it) }
        val urlText = named("url") ?: named("value") ?: positional
        val offset = if (text === file.code) (open ?: afterName) + 1 else 0
        val url = urlText?.let { AnnotationValue(resolver.stringValue(file, it, offset).text) }
        val verb = FIXED_VERBS[simpleName] ?: named("method")?.let { resolver.stringValue(file, it, offset).text }
            ?.takeIf { it in RouteUrlRules.VERBS }
        return ExchangeAnnotation(simpleName, verb, url, (close ?: match.range.last) + 1)
    }

    /**
     * `HttpServiceMethod.initUrl` — 둘 다 있으면 `type + ("/" if 둘 다 경계 슬래시가 없을 때) + method`, 하나만 있으면 그것, 둘 다 없으면 없다
     * (빈 템플릿 — base 자신).
     */
    private fun joinUrls(typeUrl: AnnotationValue?, methodUrl: AnnotationValue?): AnnotationValue? {
        val typeText = typeUrl?.text
        val methodText = methodUrl?.text
        if (typeUrl != null && typeText == null || methodUrl != null && methodText == null) return AnnotationValue(null)
        val hasType = !typeText.isNullOrBlank()
        val hasMethod = !methodText.isNullOrBlank()
        return when {
            hasType && hasMethod -> AnnotationValue(typeText + (if (!typeText!!.endsWith('/') && !methodText!!.startsWith('/')) "/" else "") + methodText)
            hasMethod -> AnnotationValue(methodText)
            hasType -> AnnotationValue(typeText)
            else -> AnnotationValue("")
        }
    }

    /**
     * 어노테이션 뒤 첫 메서드다.
     *
     * @property takesUri `URI` 매개변수가 있어 URL 전체가 실행 시점 값이다
     * @property takesFactory `UriBuilderFactory` 매개변수가 있어 base가 실행 시점 값이다
     */
    private data class Signature(val name: String, val parameterCount: Int, val takesUri: Boolean, val takesFactory: Boolean)

    private fun signature(file: RouteSourceFile, from: Int): Signature? {
        val window = file.masked.substring(from, (from + 2_000).coerceAtMost(file.masked.length))
        val cleaned = LEADING_ANNOTATIONS.find(window)?.let { from + it.range.last + 1 } ?: from
        val header = (if (file.isJava) JAVA_SIGNATURE else KOTLIN_SIGNATURE).find(file.masked, cleaned) ?: return null
        if (header.range.first - cleaned > 1_000) return null
        val open = header.range.last
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return null
        val parameters = callArguments(file.code, open, close).filter { it.isNotBlank() }
            .mapNotNull { if (file.isJava) javaSourceParameter(it) else kotlinSourceParameter(it) }
        return Signature(
            name = header.groupValues[1],
            parameterCount = parameterCount(file.masked, open, close),
            takesUri = parameters.any { it.rawType == "URI" || it.rawType == "java.net.URI" },
            takesFactory = parameters.any { it.type == "UriBuilderFactory" },
        )
    }

    /** `createClient(Api::class.java)`·`createClient(Api.class)`·`createClient<Api>()` 호출의 수신 식을 따라가 base를 모은다. */
    private fun collectBindings(): Map<String, List<SpringClientBase>> {
        val result = mutableMapOf<String, MutableList<SpringClientBase>>()
        production.forEach { file ->
            CREATE_CLIENT.findAll(file.masked).forEach { match ->
                val name = match.groupValues[2].ifEmpty { match.groupValues[3].ifEmpty { match.groupValues[4] } }
                val service = resolver.declarations.resolveType(file, name)
                    ?: file.types.filter { it.name == name.substringAfterLast('.') }.map { typeFqn(file, it) }.distinct().singleOrNull()
                    ?: return@forEach
                val dot = match.groups[1]?.range?.first
                val receiver = dot?.let { receiverStart(file, it) }?.takeIf { it < dot }
                val bases = receiver?.let { factoryBases(file, it, dot, 0) } ?: listOf(SpringClientBase.unknown(null))
                result.getOrPut(service) { mutableListOf() } += bases
            }
        }
        return result.mapValues { (_, bases) -> bases.distinct() }
    }

    /**
     * `HttpServiceProxyFactory` 식의 어댑터 클라이언트 base다. `builderFor(adapter)`·`builder(adapter)`·`exchangeAdapter(adapter)`를
     * 사슬에서 찾고, 뿌리가 참조면 지역 `val`·속성 초기식·함수 몸체·`@Bean`으로 따라간다.
     */
    private fun factoryBases(file: RouteSourceFile, start: Int, end: Int, depth: Int): List<SpringClientBase>? {
        if (depth > MAX_DEPTH) return null
        val segments = chainSegments(file, skipSpaces(file.masked, start), end) ?: return null
        segments.lastOrNull { it.name in ADAPTER_SETTERS && it.arguments != null }?.let { segment ->
            val argument = callArguments(file.code, segment.arguments!!.first, segment.arguments.last).singleOrNull() ?: return null
            return adapterBases(file, argument, segment.arguments.first + 1, depth + 1)
        }
        val root = segments.first()
        if (!root.isPlain || segments.drop(1).any { it.name !in FACTORY_TAIL }) return null
        return referenceRange(file, root.name, root.start)?.let { (owner, range) -> factoryBases(owner, range.first, range.last + 1, depth + 1) }
    }

    /** `RestClientAdapter.create(x)`·`WebClientAdapter.create(x)`·`forClient(x)`·`RestTemplateAdapter.create(x)`의 클라이언트 base다. */
    private fun adapterBases(file: RouteSourceFile, argument: String, offset: Int, depth: Int): List<SpringClientBase>? {
        val trimmed = argument.trim()
        val start = file.code.indexOf(trimmed, offset - 1).takeIf { it >= 0 } ?: return null
        val match = ADAPTER.find(trimmed)?.takeIf { it.range.first == 0 }
        if (match == null) {
            if (!IDENTIFIER.matches(trimmed) || depth > MAX_DEPTH) return null
            return referenceRange(file, trimmed, start)?.let { (owner, range) -> adapterBases(owner, owner.code.substring(range), range.first, depth + 1) }
        }
        val open = match.range.last
        val close = balancedEnd(trimmed, open).takeIf { it == trimmed.length - 1 } ?: return null
        val inner = callArguments(trimmed, open, close).singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val innerStart = file.code.indexOf(inner, start + open).takeIf { it >= 0 } ?: return null
        return resolver.client(file, innerStart, innerStart + inner.length)?.bases
    }

    /** 이름 하나를 지역 `val`·읽기 전용 속성 초기식으로 따라간 (파일, 식 범위)다. */
    private fun referenceRange(file: RouteSourceFile, name: String, offset: Int): Pair<RouteSourceFile, IntRange>? {
        resolver.scopes.localInitializer(file, name, offset)?.let { (isVal, range) -> return if (isVal) file to range else null }
        val (owner, property) = resolver.scopes.propertyInScope(file, name, offset) ?: return null
        val initializer = property.initializer?.takeUnless { property.mutable } ?: return null
        return owner to initializer
    }

    /** 프로젝트 `@Controller`·`@RestController` 클래스가 상위 타입으로 적은 인터페이스 FQN이다. */
    private fun collectServerContracts(): Set<String> = production.flatMap { file ->
        file.types.filter { type -> !file.masked.startsWith("interface", type.start) && type.bodyStart > type.start }.flatMap { type ->
            if (!CONTROLLER.containsMatchIn(leadingAnnotations(file, type.start))) return@flatMap emptyList()
            SUPERTYPE.findAll(supertypeClause(file, type)).mapNotNull { name -> resolver.declarations.resolveType(file, name.value) }.toList()
        }
    }.toSet()

    /**
     * 타입 머리의 상위 타입 절이다 — Java는 `implements` 뒤, Kotlin은 괄호 밖(주 생성자 매개변수 목록 뒤)의 첫 `:` 뒤다. 주 생성자 매개변수의
     * 타입(`class C(private val api: Api)`)을 상위 타입으로 읽지 않기 위해서다.
     */
    private fun supertypeClause(file: RouteSourceFile, type: RouteTypeDecl): String {
        val header = file.masked.substring(type.start, type.bodyStart)
        if (file.isJava) return header.substringAfter("implements", "")
        var depth = 0
        header.forEachIndexed { index, character ->
            when (character) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                ':' -> if (depth == 0) return header.substring(index + 1).replace(CALL_ARGUMENTS, "")
            }
        }
        return ""
    }

    private companion object {
        /** 상위 클래스 생성자 호출 인자(`: Base(x: Int)`)다 — 인자 안의 이름을 상위 타입으로 읽지 않는다. */
        val CALL_ARGUMENTS = Regex("\\([^()]*\\)")
        const val MAX_DEPTH = 8
        const val ANNOTATION_PACKAGE = "org.springframework.web.service.annotation"
        const val ANNOTATION_INTERNAL = "org/springframework/web/service/annotation"
        val EXCHANGE = Regex("@(org\\s*\\.\\s*springframework\\s*\\.\\s*web\\s*\\.\\s*service\\s*\\.\\s*annotation\\s*\\.\\s*)?(HttpExchange|GetExchange|PostExchange|PutExchange|PatchExchange|DeleteExchange)\\b")
        val FIXED_VERBS = mapOf(
            "GetExchange" to "GET", "PostExchange" to "POST", "PutExchange" to "PUT", "PatchExchange" to "PATCH", "DeleteExchange" to "DELETE",
        )
        val NAMED = Regex("^\\s*([A-Za-z_]\\w*)\\s*=(?!=)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
        val LEADING_ANNOTATIONS = Regex("^(?:\\s*@[A-Za-z_][\\w.]*(?:\\s*\\([^()]*\\))?)*")
        val KOTLIN_SIGNATURE = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?([A-Za-z_]\\w*)\\s*\\(")
        val JAVA_SIGNATURE = Regex("([A-Za-z_]\\w*)\\s*\\(")
        val CREATE_CLIENT = Regex(
            "(?:(\\.)\\s*|(?<![\\w.])(?=createClient))createClient\\s*(?:\\(\\s*([A-Za-z_][\\w.]*)\\s*::\\s*class\\s*\\.\\s*java\\s*\\)|" +
                "\\(\\s*([A-Za-z_][\\w.]*)\\s*\\.\\s*class\\s*\\)|<\\s*([A-Za-z_][\\w.]*)\\s*>\\s*\\(\\s*\\))",
        )
        val ADAPTER_SETTERS = setOf("builderFor", "builder", "exchangeAdapter", "clientAdapter")
        val FACTORY_TAIL = setOf("build", "customArgumentResolver", "conversionService", "embeddedValueResolver", "exchangeAdapterDecorator")
        val ADAPTER = Regex("(?:[\\w.]+\\.)?(?:RestClientAdapter|WebClientAdapter|RestTemplateAdapter)\\s*\\.\\s*(?:create|forClient)\\s*\\(")
        val IDENTIFIER = Regex("[A-Za-z_]\\w*")
        val CONTROLLER = Regex("@(?:[\\w.]+\\.)?(?:RestController|Controller)\\b")
        val SUPERTYPE = Regex("[A-Za-z_][\\w.]*")
    }
}
