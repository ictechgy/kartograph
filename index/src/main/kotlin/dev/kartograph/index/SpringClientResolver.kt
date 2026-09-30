package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.UrlPart

/** Spring 명령형 HTTP 클라이언트의 종류다. [types]는 이 종류를 담는 타입의 FQN이다. */
internal enum class SpringClientKind(val types: List<String>, val builderTypes: List<String>) {
    REST_TEMPLATE(
        listOf("org.springframework.web.client.RestTemplate", "org.springframework.web.client.RestOperations"),
        listOf("org.springframework.boot.web.client.RestTemplateBuilder", "org.springframework.boot.restclient.RestTemplateBuilder"),
    ),
    REST_CLIENT(listOf("org.springframework.web.client.RestClient"), listOf("org.springframework.web.client.RestClient.Builder")),
    WEB_CLIENT(
        listOf("org.springframework.web.reactive.function.client.WebClient"),
        listOf("org.springframework.web.reactive.function.client.WebClient.Builder"),
    ),
}

/**
 * 식이 가리키는 Spring 클라이언트(또는 빌더)다.
 *
 * @property kind 클라이언트 종류다
 * @property bases 이 인스턴스가 쓸 수 있는 base 결합들이다(여러 원천이면 여럿)
 * @property builder 아직 `build()`하지 않은 빌더다 — 빌더는 요청을 보내지 않는다
 * @property rootUri `RestTemplateBuilder.rootUri`로 정한 root다. `build()`에서 base가 된다(Boot가 마지막에 적용)
 */
internal data class SpringClientInstance(
    val kind: SpringClientKind,
    val bases: List<SpringClientBase>,
    val builder: Boolean = false,
    val rootUri: List<SpringClientBase>? = null,
)

/**
 * 문자열 식의 값이다.
 *
 * @property text 정적으로 푼 값이다. 모르면 null이다
 * @property ref 값을 모를 때 그 원천 선언(`@Value` 속성·필드 등)의 생산자 id다
 * @property profileDependent 쓴 설정 값을 다른 프로필이 다르게 정한다
 */
internal data class SpringStringValue(val text: String?, val ref: String? = null, val profileDependent: Boolean = false)

/**
 * Spring HTTP 클라이언트 식을 따라가 종류와 base 결합을 푼다.
 *
 * 따라가는 모양: `RestClient.builder()…baseUrl(x)…build()`, `RestClient.create(x)`, `WebClient.builder()`·`create(x)`,
 * `RestTemplate()`, `RestTemplateBuilder…rootUri(x)…build()`, `uriTemplateHandler`·`uriBuilderFactory(DefaultUriBuilderFactory(x))`,
 * `mutate()`, 같은 함수의 `val`, 속성·필드(초기식·lazy·getter·모든 대입), 함수 몸체, `@Bean` 메서드(타입·`@Qualifier`·`@Primary`·
 * 매개변수 이름으로 고름), Spring Boot가 주입하는 빌더(`RestClient.Builder` 등 — base 없음, 프로젝트에 customizer가 있으면 모름).
 * base 값은 리터럴·템플릿·상수·읽기 전용 속성·`@Value("\${key}")`(저장소 안 기본 프로필 설정)를 푼다. 그 밖은 모르는 base다.
 *
 * @param files 스캔한 production source 파일이다
 * @param placeholdersFor 소스 파일의 설정 후보로 만든 플레이스홀더 해석기다
 */
internal class SpringClientResolver(
    files: List<RouteSourceFile>,
    private val placeholdersFor: (RouteSourceFile) -> SpringPlaceholders,
) {
    /** 파일 밖 선언 색인이다. */
    val declarations: SourceDeclarations = SourceDeclarations(files)

    /** 값 원천을 찾는 공유 어휘 도구다. */
    val scopes: SourceScopes = SourceScopes(declarations)

    private val pathResolvers = java.util.IdentityHashMap<RouteSourceFile, RoutePathResolver>()

    /** Boot가 주입하는 빌더를 바꾸는 customizer 빈이 프로젝트에 있는지다. 있으면 주입한 빌더의 base를 모른다. */
    private val customized: Boolean = files.any { CUSTOMIZER.containsMatchIn(it.masked) }

    /** `@Bean` 메서드들이다. */
    private val beans: List<Pair<RouteSourceFile, RouteFunctionDecl>> by lazy {
        files.flatMap { file -> file.functions.filter { BEAN.containsMatchIn(functionAnnotations(file, it)) }.map { file to it } }
    }

    /** [file]의 경로 식 해석기다. */
    fun pathResolver(file: RouteSourceFile): RoutePathResolver = pathResolvers.getOrPut(file) { RoutePathResolver(file) }

    /**
     * 식 [start, end)가 Spring 클라이언트 인스턴스면 그 종류와 base 결합이다. 빌더이거나 클라이언트임을 증명하지 못하면 null이다.
     */
    fun client(file: RouteSourceFile, start: Int, end: Int): SpringClientInstance? =
        instance(file, start, end, scopes.declarationId(file, start), Budget())?.takeUnless { it.builder }

    /** 재귀 한도와 순환 방지다. */
    private class Budget {
        private val visiting = mutableSetOf<Pair<RouteSourceFile, Int>>()
        var depth = 0

        fun enter(file: RouteSourceFile, start: Int): Boolean {
            if (depth >= MAX_DEPTH || !visiting.add(file to start)) return false
            depth++
            return true
        }

        fun leave(file: RouteSourceFile, start: Int) {
            visiting.remove(file to start)
            depth--
        }
    }

    /** 식 [start, end)를 인스턴스로 푼다. */
    private fun instance(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): SpringClientInstance? {
        val trimmedStart = skipSpaces(file.masked, start)
        if (trimmedStart >= end || !budget.enter(file, trimmedStart)) return null
        try {
            val segments = chainSegments(file, trimmedStart, end) ?: return null
            val rootSize = rootLength(segments)
            var state = root(file, segments.subList(0, rootSize), ref, budget) ?: return null
            for (segment in segments.drop(rootSize)) state = apply(file, state, segment, ref, budget) ?: return null
            return state
        } finally {
            budget.leave(file, trimmedStart)
        }
    }

    /** 사슬 단위 하나를 인스턴스 상태에 적용한다. 클라이언트·빌더 API가 아니면 null이다. */
    private fun apply(file: RouteSourceFile, state: SpringClientInstance, segment: ChainSegment, ref: String?, budget: Budget): SpringClientInstance? {
        val argument = segment.arguments?.let { callArguments(file.code, it.first, it.last) }?.singleOrNull()
        val offset = (segment.arguments?.first ?: segment.start) + 1
        return when {
            segment.name == "build" && state.builder -> state.copy(builder = false, bases = state.rootUri ?: state.bases, rootUri = null)
            segment.name == "mutate" && !state.builder && state.kind != SpringClientKind.REST_TEMPLATE -> state.copy(builder = true)
            !state.builder -> null
            segment.name == "baseUrl" && argument != null -> state.copy(bases = listOf(baseOf(SpringClientBase.Mode.FACTORY, file, argument, offset, ref, budget)))
            segment.name == "rootUri" && argument != null && state.kind == SpringClientKind.REST_TEMPLATE ->
                state.copy(rootUri = listOf(baseOf(SpringClientBase.Mode.ROOT_URI, file, argument, offset, ref, budget)))
            segment.name in FACTORY_SETTERS && argument != null -> state.copy(bases = listOf(factoryBase(file, argument, offset, ref, budget)))
            segment.name in BUILDER_CONFIG -> state
            // 모르는 빌더 메서드는 base를 바꿀 수 있다 — 종류는 두고 base를 모른다.
            else -> state.copy(bases = listOf(SpringClientBase.unknown(ref)), rootUri = null)
        }
    }

    /** 사슬 뿌리(생성 식·참조·함수 호출)를 푼다. */
    private fun root(file: RouteSourceFile, segments: List<ChainSegment>, ref: String?, budget: Budget): SpringClientInstance? {
        val last = segments.last()
        val qualifier = segments.dropLast(1).joinToString(".") { it.name }
        if (last.lambda != null) return null
        if (last.arguments == null) return reference(file, qualifier, last.name, segments.first().start, ref, budget)
        val arguments = callArguments(file.code, last.arguments.first, last.arguments.last)
        construction(file, qualifier, last.name, arguments, last.arguments.first + 1, ref, budget)?.let { return it }
        val (owner, function) = declarations.findFunction(file, qualifier, last.name, last.start) ?: return null
        return functionBody(owner, function, scopes.functionId(owner, function), budget)
    }

    /** `RestClient.builder()`·`RestClient.create(x)`·`WebClient.…`·`RestTemplate()`·`RestTemplateBuilder()` 생성 식이다. */
    private fun construction(
        file: RouteSourceFile,
        qualifier: String,
        name: String,
        arguments: List<String>,
        offset: Int,
        ref: String?,
        budget: Budget,
    ): SpringClientInstance? {
        val none = listOf(SpringClientBase(SpringClientBase.Mode.NONE))
        if (qualifier.isEmpty() || qualifier == "org.springframework.web.client" || qualifier == "org.springframework.boot.web.client") {
            when {
                name == "RestTemplate" && seesType(file, qualifier, SpringClientKind.REST_TEMPLATE.types.first()) ->
                    return SpringClientInstance(SpringClientKind.REST_TEMPLATE, none)
                name == "RestTemplateBuilder" && SpringClientKind.REST_TEMPLATE.builderTypes.any { seesType(file, qualifier, it) } ->
                    return SpringClientInstance(SpringClientKind.REST_TEMPLATE, none, builder = true)
            }
        }
        val kind = listOf(SpringClientKind.REST_CLIENT, SpringClientKind.WEB_CLIENT).firstOrNull { kind ->
            qualifier.substringAfterLast('.') == kind.types.first().substringAfterLast('.') && seesType(file, qualifier, kind.types.first())
        } ?: return null
        val argument = arguments.singleOrNull()?.takeIf { it.isNotBlank() }
        return when (name) {
            "builder" -> SpringClientInstance(kind, argument?.let { templateBases(file, it, offset, ref, budget) } ?: none, builder = true)
            "create" -> SpringClientInstance(kind, argument?.let { templateBases(file, it, offset, ref, budget) ?: listOf(baseOf(SpringClientBase.Mode.FACTORY, file, it, offset, ref, budget)) } ?: none)
            else -> null
        }
    }

    /** `RestClient.create(restTemplate)`·`builder(restTemplate)`처럼 인자가 RestTemplate이면 그 base다. 아니면 null이다. */
    private fun templateBases(file: RouteSourceFile, argument: String, offset: Int, ref: String?, budget: Budget): List<SpringClientBase>? {
        val start = file.code.indexOf(argument, offset - 1).takeIf { it >= 0 } ?: return null
        val nested = instance(file, start, start + argument.length, ref, budget) ?: return null
        return nested.bases.takeIf { nested.kind == SpringClientKind.REST_TEMPLATE && !nested.builder }
    }

    /** 참조 `qualifier.name`을 지역 선언·매개변수·속성·주 생성자 매개변수로 따라간다. */
    private fun reference(file: RouteSourceFile, qualifier: String, name: String, offset: Int, ref: String?, budget: Budget): SpringClientInstance? {
        if (qualifier.isEmpty()) {
            scopes.localInitializer(file, name, offset)?.let { (isVal, range) ->
                val resolved = instance(file, range.first, range.last + 1, ref, budget) ?: return null
                return if (isVal) resolved else resolved.copy(bases = listOf(SpringClientBase.unknown(ref)))
            }
            scopes.parameter(file, name, offset)?.let { (function, parameter) -> return parameterInstance(file, function, parameter, budget) }
        }
        val property = if (qualifier.isEmpty() || qualifier == "this") scopes.propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        if (property != null) return propertyInstance(property.first, property.second, budget)
        if (qualifier.isEmpty() || qualifier == "this") {
            scopes.constructorParameter(file, name, offset)?.let { (_, parameter) ->
                val kind = typeKind(file, parameter.rawType) ?: return builderInstance(file, parameter.rawType)
                return injected(kind, parameter.annotations, parameter.name, budget)
            }
        }
        return null
    }

    /**
     * 함수 매개변수로 받은 클라이언트다. `@Bean` 메서드와 생성자의 매개변수는 Spring이 주입하므로 빈으로 풀고, 그 밖의 매개변수는
     * 호출자마다 다를 수 있어 base를 모른다.
     */
    private fun parameterInstance(file: RouteSourceFile, function: RouteFunctionDecl, parameter: SourceParameter, budget: Budget): SpringClientInstance? {
        val kind = typeKind(file, parameter.rawType) ?: return builderInstance(file, parameter.rawType)
        val injectedHere = BEAN.containsMatchIn(functionAnnotations(file, function)) || isConstructor(file, function)
        if (!injectedHere) return SpringClientInstance(kind, listOf(SpringClientBase.unknown(null)))
        return injected(kind, parameter.annotations, parameter.name, budget)
    }

    /** Spring Boot가 주입하는 빌더(`RestClient.Builder`·`WebClient.Builder`·`RestTemplateBuilder`)다. base가 없다. */
    private fun builderInstance(file: RouteSourceFile, type: String): SpringClientInstance? {
        val kind = SpringClientKind.entries.firstOrNull { kind -> kind.builderTypes.any { typeMatches(file, type, it) } } ?: return null
        val base = if (customized) SpringClientBase.unknown(null) else SpringClientBase(SpringClientBase.Mode.NONE)
        return SpringClientInstance(kind, listOf(base), builder = true)
    }

    private fun isConstructor(file: RouteSourceFile, function: RouteFunctionDecl): Boolean =
        file.isJava && file.enclosingTypes(function.start).lastOrNull()?.name == function.name

    /** 속성·필드 값의 원천(초기식, lazy, getter, 대입)을 풀고, 원천이 없고 주입 표지가 있으면 빈으로 푼다. */
    private fun propertyInstance(file: RouteSourceFile, property: SourceProperty, budget: Budget): SpringClientInstance? {
        val ref = propertyId(file, property)
        val sources = mutableListOf<IntRange>()
        property.initializer?.takeUnless { scopes.isNullLiteral(file, it) }?.let(sources::add)
        property.lazyBody?.let { scopes.lastStatement(file, it) }?.let(sources::add)
        property.getterBody?.let { body -> sources += if (RETURN.containsMatchIn(file.masked.substring(body))) scopes.returnRanges(file, body) else listOf(body) }
        if (property.mutable || sources.isEmpty() && property.lazyBody == null && property.getterBody == null) sources += scopes.assignments(file, property)
        val declaredKind = property.typeName?.let { typeKind(file, rawPropertyType(file, property) ?: it) }
        if (sources.isEmpty()) {
            val kind = declaredKind ?: return null
            return if (INJECTED.containsMatchIn(property.annotations)) injected(kind, property.annotations, property.name, budget)
            else SpringClientInstance(kind, listOf(SpringClientBase.unknown(ref)))
        }
        val resolved = sources.map { instance(file, it.first, it.last + 1, ref, budget) }
        if (resolved.any { it == null }) return declaredKind?.let { SpringClientInstance(it, listOf(SpringClientBase.unknown(ref))) }
        return merge(resolved.filterNotNull(), ref)
    }

    /** 속성 선언의 타입 원문이다(`RestClient.Builder`처럼 한정된 이름을 보존한다). */
    private fun rawPropertyType(file: RouteSourceFile, property: SourceProperty): String? {
        if (file.isJava) {
            val before = file.masked.substring(0, property.start).trimEnd()
            return Regex("([A-Za-z_][\\w.]*)(?:<[^;=(){}]*>)?$").find(before)?.groupValues?.get(1)
        }
        // 선언 시작은 `lateinit`일 수 있다 — `val`·`var` 뒤의 이름과 타입을 읽는다.
        return KOTLIN_PROPERTY_TYPE.find(file.masked, property.start)?.takeIf { it.range.first == property.start }?.groupValues?.get(1)
    }

    /** 여러 원천의 인스턴스를 합친다. 종류가 갈리면 모른다(null). */
    private fun merge(instances: List<SpringClientInstance>, ref: String?): SpringClientInstance? {
        val kind = instances.map { it.kind }.distinct().singleOrNull() ?: return null
        val builder = instances.map { it.builder }.distinct().singleOrNull() ?: return null
        val bases = instances.flatMap { it.bases }.distinct().ifEmpty { listOf(SpringClientBase.unknown(ref)) }
        return SpringClientInstance(kind, bases, builder, instances.mapNotNull { it.rootUri }.flatten().distinct().ifEmpty { null })
    }

    /** 함수 몸체의 값: 식 몸체면 그 식, 블록이면 모든 `return` 식이다. */
    private fun functionBody(file: RouteSourceFile, function: RouteFunctionDecl, ref: String, budget: Budget): SpringClientInstance? {
        val bodyStart = function.bodyStart
        if (bodyStart < 0 || bodyStart >= file.masked.length) return null
        if (file.masked[bodyStart] == '=') return instance(file, bodyStart + 1, function.end, ref, budget)
        val returns = scopes.returnRanges(file, (bodyStart + 1) until function.end.coerceAtMost(file.masked.length))
        if (returns.isEmpty()) return null
        val resolved = returns.map { instance(file, it.first, it.last + 1, ref, budget) ?: return null }
        return merge(resolved, ref)
    }

    /**
     * 주입 지점([kind] 타입, 어노테이션 [annotations], 이름 [name])에 들어갈 `@Bean`을 고른다 — `@Qualifier("x")`·`@Named("x")`가
     * 있으면 그 이름의 빈, 없으면 빈이 하나일 때 그것, 여럿이면 `@Primary` 하나, 그다음 매개변수·속성 이름과 같은 빈 이름이다.
     * 프로젝트가 선언한 한정자 어노테이션처럼 이름으로 고를 수 없는 한정자가 있거나 고르지 못하면 base를 모른다.
     */
    private fun injected(kind: SpringClientKind, annotations: String, name: String, budget: Budget): SpringClientInstance {
        val unknown = SpringClientInstance(kind, listOf(SpringClientBase.unknown(null)))
        val candidates = beans.filter { (file, function) -> beanKind(file, function) == kind }
        val wanted = QUALIFIER_ARGUMENT.find(annotations)?.groupValues?.get(1) ?: NAMED_ARGUMENT.find(annotations)?.groupValues?.get(1)
        val customQualifier = declarations.qualifiers(annotations).any { !it.startsWith("Named:") }
        val chosen = when {
            customQualifier -> return unknown
            wanted != null -> candidates.filter { (file, function) -> wanted in beanNames(file, function) }.singleOrNull()
            candidates.size == 1 -> candidates.single()
            else -> candidates.filter { (file, function) -> PRIMARY.containsMatchIn(functionAnnotations(file, function)) }.singleOrNull()
                ?: candidates.filter { (file, function) -> name in beanNames(file, function) }.singleOrNull()
        } ?: return unknown
        val (file, function) = chosen
        val resolved = functionBody(file, function, scopes.functionId(file, function), budget) ?: return unknown
        return resolved.takeIf { it.kind == kind && !it.builder } ?: unknown
    }

    /** `@Bean` 메서드 → 종류다. 계산 중인 빈은 null로 두어 서로를 주입하는 빈이 끝없이 재귀하지 않게 한다. */
    private val beanKinds = java.util.IdentityHashMap<RouteFunctionDecl, SpringClientKind?>()

    /** `@Bean` 메서드가 만드는 클라이언트 종류다. 선언한 반환 타입, 없으면 식 몸체를 풀어 정한다. */
    private fun beanKind(file: RouteSourceFile, function: RouteFunctionDecl): SpringClientKind? {
        if (beanKinds.containsKey(function)) return beanKinds[function]
        beanKinds[function] = null
        return computeBeanKind(file, function).also { beanKinds[function] = it }
    }

    private fun computeBeanKind(file: RouteSourceFile, function: RouteFunctionDecl): SpringClientKind? {
        val open = parameterListOpen(file, function) ?: return null
        if (file.isJava) {
            val header = file.masked.substring(function.start, open)
            val type = Regex("([A-Za-z_][\\w.]*)(?:<[^;=(){}]*>)?\\s+[A-Za-z_]\\w*\\s*$").find(header)?.groupValues?.get(1) ?: return null
            return typeKind(file, type)
        }
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return null
        val header = file.masked.substring(close + 1, function.bodyStart.coerceAtLeast(close + 1))
        Regex("^\\s*:\\s*([A-Za-z_][\\w.]*)").find(header)?.let { return typeKind(file, it.groupValues[1]) }
        // 반환 타입을 생략한 식 몸체(`@Bean fun x() = RestTemplateBuilder()….build()`)는 식을 풀어 종류를 정한다.
        if (file.masked.getOrNull(function.bodyStart) != '=') return null
        return instance(file, function.bodyStart + 1, function.end, null, Budget())?.takeUnless { it.builder }?.kind
    }

    /** 빈 이름들이다 — 메서드 이름, `@Bean("x")`·`@Bean(name = …)`·`@Bean(value = …)`의 이름, 메서드의 `@Qualifier("x")`. */
    private fun beanNames(file: RouteSourceFile, function: RouteFunctionDecl): Set<String> {
        val annotations = functionAnnotations(file, function)
        val declared = BEAN_ARGUMENTS.findAll(annotations).flatMap { match ->
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }
        }
        val qualified = QUALIFIER_ARGUMENT.findAll(annotations).map { it.groupValues[1] }
        val explicit = (declared + qualified).toSet()
        return if (declared.any()) explicit else explicit + function.name
    }

    /** 타입 원문이 Spring 클라이언트 타입이면 그 종류다. */
    fun typeKind(file: RouteSourceFile, type: String): SpringClientKind? =
        SpringClientKind.entries.firstOrNull { kind -> kind.types.any { typeMatches(file, type, it) } }

    /** 타입 원문 [type](`RestClient`, `RestClient.Builder`, FQN)이 [fqn]을 가리키는지 본다. 파일의 import·패키지로 확인한다. */
    private fun typeMatches(file: RouteSourceFile, type: String, fqn: String): Boolean {
        val cleaned = type.substringBefore('<').removeSuffix("?").trim()
        if (cleaned == fqn) return true
        val head = cleaned.substringBefore('.')
        val tail = cleaned.substringAfter('.', "")
        val candidates = if (tail.isEmpty()) listOf(fqn) else listOf(fqn.removeSuffix(".$tail")).filter { it != fqn }
        return candidates.any { candidate -> candidate.substringAfterLast('.') == head && file.visibleNameOf(candidate) == head }
    }

    /** 생성 식의 한정자·이름이 [fqn] 타입을 가리키는지 본다(`RestClient.builder()`의 `RestClient`, FQN 한정). */
    private fun seesType(file: RouteSourceFile, qualifier: String, fqn: String): Boolean {
        val simple = fqn.substringAfterLast('.')
        return when {
            qualifier.isEmpty() -> file.visibleNameOf(fqn) == simple
            qualifier == fqn.substringBeforeLast('.') -> true
            qualifier == fqn -> true
            qualifier == simple -> file.visibleNameOf(fqn) == simple
            else -> false
        }
    }

    /** base 인자 하나를 [mode] 결합으로 푼다. 값을 모르면 base 없는 결합(`baseRef`만)이다. */
    private fun baseOf(mode: SpringClientBase.Mode, file: RouteSourceFile, argument: String, offset: Int, ref: String?, budget: Budget): SpringClientBase {
        val value = stringValue(file, argument, offset, budget)
        val parsed = value.text?.let(SpringBaseUrl::parse)
        return SpringClientBase(mode, parsed, ref, value.profileDependent && parsed != null)
    }

    /** `DefaultUriBuilderFactory(x)` 인자면 그 base다. 다른 factory는 모르는 base다. */
    private fun factoryBase(file: RouteSourceFile, argument: String, offset: Int, ref: String?, budget: Budget): SpringClientBase {
        val match = DEFAULT_FACTORY.matchEntire(argument.trim()) ?: return SpringClientBase.unknown(ref)
        val open = argument.indexOf('(', match.range.first)
        val close = balancedEnd(argument, open)
        if (close != argument.trimEnd().length - 1) return SpringClientBase.unknown(ref)
        val inner = callArguments(argument, open, close).singleOrNull()?.takeIf { it.isNotBlank() } ?: return SpringClientBase(SpringClientBase.Mode.NONE)
        return baseOf(SpringClientBase.Mode.FACTORY, file, inner, offset, ref, budget)
    }

    /**
     * 문자열 식 [text]의 값이다. 리터럴·템플릿·연결, 같은 파일/다른 파일 상수, 지역 `val`, 읽기 전용 속성, `@Value("\${key}")` 속성·
     * 필드·매개변수·주 생성자 매개변수를 따라간다. `URI.create(x)`·`URI(x)`는 벗긴다.
     */
    fun stringValue(file: RouteSourceFile, text: String, offset: Int): SpringStringValue = stringValue(file, text, offset, Budget())

    private fun stringValue(file: RouteSourceFile, text: String, offset: Int, budget: Budget): SpringStringValue {
        if (budget.depth >= MAX_DEPTH) return SpringStringValue(null)
        budget.depth++
        try {
            val expression = unwrapUri(unwrapParentheses(text.trim()).removeSuffix("!!").trim())
            pathResolver(file).literalValue(expression, offset)?.let { return SpringStringValue(it) }
            if (REFERENCE.matches(expression)) return referenceValue(file, expression.replace(WHITESPACE, ""), offset, budget)
            val parts = pathResolver(file).parts(expression, offset)
            if (parts.size < 2) return SpringStringValue(null)
            var profileDependent = false
            val pieces = parts.map { part ->
                when (part) {
                    is UrlPart.Literal -> part.text
                    is UrlPart.Value -> {
                        val resolved = part.expression.takeIf(REFERENCE::matches)?.let { referenceValue(file, it.replace(WHITESPACE, ""), offset, budget) }
                        profileDependent = profileDependent || resolved?.profileDependent == true
                        resolved?.text ?: return SpringStringValue(null, resolved?.ref)
                    }
                    is UrlPart.QueryTail -> return SpringStringValue(null)
                }
            }
            return SpringStringValue(pieces.joinToString(""), profileDependent = profileDependent)
        } finally {
            budget.depth--
        }
    }

    /** `URI.create(x)`·`URI(x)`·`new URI(x)`·`java.net.URI.create(x)`를 벗긴다. */
    private fun unwrapUri(expression: String): String {
        val match = URI_WRAPPER.matchEntire(expression) ?: return expression
        val open = match.groups[1]!!.range.first
        val close = balancedEnd(expression, open)
        if (close != expression.length - 1) return expression
        return callArguments(expression, open, close).singleOrNull()?.takeIf { it.isNotBlank() } ?: expression
    }

    /** 참조의 값: 지역 `val`, `@Value` 매개변수, 상수, 속성(`@Value` 또는 읽기 전용 초기식), `@Value` 주 생성자 매개변수 순이다. */
    private fun referenceValue(file: RouteSourceFile, expression: String, offset: Int, budget: Budget): SpringStringValue {
        val name = expression.substringAfterLast('.')
        val qualifier = expression.substringBeforeLast('.', "")
        if (qualifier.isEmpty()) {
            scopes.localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) stringValue(file, file.code.substring(range), range.first, budget) else SpringStringValue(null)
            }
            scopes.parameter(file, name, offset)?.let { (_, parameter) -> return placeholder(file, parameter.annotations, null) ?: SpringStringValue(null) }
        }
        declarations.findConstant(file, qualifier, name)?.let { (owner, constant) -> return stringValue(owner, constant.expression, 0, budget) }
        val property = if (qualifier.isEmpty() || qualifier == "this") scopes.propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        if (property != null) {
            val (owner, declaration) = property
            val ref = propertyId(owner, declaration)
            placeholder(owner, declaration.annotations, ref)?.let { return it }
            val initializer = declaration.initializer?.takeUnless { declaration.mutable } ?: return SpringStringValue(null, ref)
            return stringValue(owner, owner.code.substring(initializer), initializer.first, budget).let { it.copy(ref = it.ref ?: ref) }
        }
        if (qualifier.isEmpty() || qualifier == "this") {
            scopes.constructorParameter(file, name, offset)?.let { (_, parameter) ->
                val owner = file.enclosingTypes(offset).lastOrNull()?.let { typeFqn(file, it) }
                return placeholder(file, parameter.annotations, owner?.let { "kt:$it.$name" }) ?: SpringStringValue(null, owner?.let { "kt:$it.$name" })
            }
        }
        return SpringStringValue(null)
    }

    /** 어노테이션 원문의 `@Value("…")`를 기본 프로필 설정으로 푼다. `@Value`가 없으면 null이다. */
    private fun placeholder(file: RouteSourceFile, annotations: String, ref: String?): SpringStringValue? {
        val match = VALUE_ANNOTATION.find(annotations) ?: return null
        val raw = pathResolver(file).literalValue(match.groupValues[1], 0) ?: return SpringStringValue(null, ref)
        val resolved = placeholdersFor(file).resolve(raw)
        return SpringStringValue(resolved.text, ref, resolved.profileDependent)
    }

    /** 속성·필드의 생산자 id(`kt:` + 소유 타입 + 이름)다. */
    fun propertyId(file: RouteSourceFile, property: SourceProperty): String =
        "kt:" + (listOf(property.ownerFqn(file)).filter { it.isNotEmpty() } + property.name).joinToString(".")

    private companion object {
        const val MAX_DEPTH = 16
        val BEAN = Regex("@(?:org\\.springframework\\.context\\.annotation\\.)?Bean\\b")
        val BEAN_ARGUMENTS = Regex("@(?:org\\.springframework\\.context\\.annotation\\.)?Bean\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)")
        val QUALIFIER_ARGUMENT = Regex("@(?:[\\w.]+\\.)?Qualifier\\s*\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"")
        val KOTLIN_PROPERTY_TYPE = Regex("(?:lateinit\\s+)?(?:val|var)\\s+[A-Za-z_]\\w*\\s*:\\s*([A-Za-z_][\\w.]*)")
        val NAMED_ARGUMENT = Regex("@(?:[\\w.]+\\.)?Named\\s*\\(\\s*(?:value\\s*=\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"")
        val PRIMARY = Regex("@(?:org\\.springframework\\.context\\.annotation\\.)?Primary\\b")
        val INJECTED = Regex("@(?:[\\w.]+\\.)?(?:Autowired|Inject|Resource)\\b")
        val CUSTOMIZER = Regex("\\b(?:RestClientCustomizer|WebClientCustomizer|RestTemplateCustomizer)\\b")
        val RETURN = Regex("\\breturn\\b(?!@)")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val WHITESPACE = Regex("\\s+")
        val URI_WRAPPER = Regex("(?:new\\s+)?(?:java\\s*\\.\\s*net\\s*\\.\\s*)?URI\\s*(?:\\.\\s*create\\s*)?(\\().*", RegexOption.DOT_MATCHES_ALL)
        val DEFAULT_FACTORY = Regex("(?:new\\s+)?(?:org\\.springframework\\.web\\.util\\.)?DefaultUriBuilderFactory\\s*\\(.*", RegexOption.DOT_MATCHES_ALL)
        val VALUE_ANNOTATION = Regex(
            "@(?:(?:field|param|get|set|property)\\s*:\\s*)?(?:org\\.springframework\\.beans\\.factory\\.annotation\\.)?Value\\s*\\(\\s*(?:value\\s*=\\s*)?(\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\)",
        )

        /** base를 정하는 factory 설정 메서드다. */
        val FACTORY_SETTERS = setOf("uriBuilderFactory", "uriTemplateHandler")

        /** base를 바꾸지 않는 빌더 설정 메서드다(RestClient·WebClient·RestTemplateBuilder). */
        val BUILDER_CONFIG = setOf(
            "defaultHeader", "defaultHeaders", "defaultCookie", "defaultCookies", "defaultRequest", "defaultStatusHandler",
            "requestInterceptor", "requestInterceptors", "requestInitializer", "requestInitializers", "requestFactory",
            "messageConverters", "configureMessageConverters", "observationRegistry", "observationConvention", "apply", "filter",
            "filters", "clientConnector", "codecs", "exchangeStrategies", "exchangeFunction", "defaultAttribute", "additionalInterceptors",
            "interceptors", "additionalMessageConverters", "errorHandler", "basicAuthentication", "setConnectTimeout", "setReadTimeout",
            "connectTimeout", "readTimeout", "requestFactorySettings", "detectRequestFactory", "additionalCustomizers", "customizers",
            "additionalRequestCustomizers", "requestCustomizers", "bufferContent", "defaultUriVariables", "clone", "redirects",
            "requestFactoryBuilder", "defaultApiVersion", "apiVersionInserter",
        )

        /** 뿌리 호출 뒤에 올 수 있는 클라이언트·빌더 메서드다 — 뿌리 길이를 정할 때 뿌리로 보지 않는다. */
        val TAIL_METHODS = BUILDER_CONFIG + FACTORY_SETTERS + setOf("build", "mutate", "baseUrl", "rootUri")

        /** 사슬 뿌리 단위 수다. 앞의 이름들, 그 뒤가 빌더 메서드가 아닌 호출이면 그 호출까지다. */
        fun rootLength(segments: List<ChainSegment>): Int {
            var plain = 0
            while (plain < segments.size && segments[plain].isPlain) plain++
            if (plain == 0) return 1
            val next = segments.getOrNull(plain) ?: return plain
            return if (next.arguments != null && next.name !in TAIL_METHODS) plain + 1 else plain
        }
    }
}
