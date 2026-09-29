package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * Retrofit 서비스가 만들어지는 base 결합 하나다.
 *
 * @property base 정적으로 푼 base URL이다. null이면 값을 증명하지 못했다(실행 시점 값·모호한 DI·빌더에 base 없음)
 * @property baseRef base를 공급하는 선언(Retrofit 인스턴스를 담은 함수·속성·DI provider)의 생산자 id다. 선언을 찾지 못하면 null이다
 */
internal data class RetrofitBinding(val base: RetrofitBaseUrl?, val baseRef: String?)

/**
 * Retrofit `create` 호출의 수신 식을 따라가 그 인스턴스의 `baseUrl` 값과 선언 신원을 푼다.
 *
 * 따라가는 모양: 같은 식의 `Retrofit.Builder()…baseUrl(x)…build()` 사슬(`apply { baseUrl(x) }` 포함), 같은 함수의 `val`, 감싸는
 * 타입·파일·다른 파일의 속성(`= …`, `by lazy { … }`, getter, `var`·Java 필드의 모든 대입), 함수 호출의 식 몸체·`return`,
 * Dagger/Hilt `@Provides` provider(`@Inject` 생성자·필드와 `@Provides` 매개변수, 한정자 일치), Koin `get()`과 `single`·`factory`
 * 정의. base 값은 리터럴·Kotlin 템플릿·같은 파일/다른 파일 상수·읽기 전용 속성의 초기식·`BuildConfig` 필드([BuildConfigFields])·
 * `HttpUrl` 래퍼를 푼다.
 * 그 밖의 식은 값을 모르는 결합으로 남긴다 — 추측한 base로 템플릿을 확정하지 않는다.
 */
internal class RetrofitInstanceResolver(
    files: List<RouteSourceFile>,
    private val buildConfig: BuildConfigFields,
) {
    /** 파일 밖 선언 색인이다. */
    val declarations: SourceDeclarations = SourceDeclarations(files)

    private val pathResolvers = java.util.IdentityHashMap<RouteSourceFile, RoutePathResolver>()

    /** Koin `single`·`factory` 정의 중 Retrofit을 만드는 것들이다. */
    private val koinDefinitions: List<KoinDefinition> by lazy { collectKoinDefinitions(files) }

    /**
     * `create` 수신 식 [start, end)의 결합들이다. 수신 식이 비어 있으면(`with(retrofit) { create(…) }`) 모르는 결합 하나다.
     */
    fun resolveReceiver(file: RouteSourceFile, start: Int, end: Int): List<RetrofitBinding> {
        if (start >= end || file.masked.substring(start, end).isBlank()) return listOf(RetrofitBinding(null, null))
        return expression(file, start, end, declarationId(file, start), Budget())
    }

    /** 재귀 한도와 순환 방지다 — 서로를 참조하는 속성·함수가 무한히 따라가지 않게 한다. */
    private class Budget {
        // RouteSourceFile은 data class가 아니라 동등성이 곧 인스턴스 신원이다.
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

    private fun unknown(ref: String?): List<RetrofitBinding> = listOf(RetrofitBinding(null, ref))

    /** Retrofit 값을 내는 식 [start, end)를 푼다. */
    private fun expression(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): List<RetrofitBinding> {
        val trimmedStart = skipSpaces(file.masked, start)
        if (!budget.enter(file, trimmedStart)) return unknown(ref)
        try {
            val segments = chainSegments(file, trimmedStart, end) ?: return unknown(ref)
            val rootSize = rootLength(segments)
            for (index in segments.lastIndex downTo rootSize) {
                val segment = segments[index]
                when {
                    segment.name == "baseUrl" && segment.arguments != null -> return baseBindings(file, segment.arguments, ref, budget)
                    segment.name in SCOPE_BLOCKS && segment.lambda != null && segment.arguments == null -> {
                        lambdaBaseUrl(file, segment.lambda)?.let { return baseBindings(file, it, ref, budget) }
                    }
                    segment.name in BUILDER_METHODS -> Unit
                    else -> return unknown(ref)
                }
            }
            return root(file, segments.subList(0, rootSize), ref, budget)
        } finally {
            budget.leave(file, trimmedStart)
        }
    }

    /** 사슬의 뿌리(`Retrofit.Builder()`, 참조, 함수 호출, Koin `get()`)를 푼다. */
    private fun root(file: RouteSourceFile, segments: List<ChainSegment>, ref: String?, budget: Budget): List<RetrofitBinding> {
        val last = segments.last()
        val qualifier = segments.dropLast(1).joinToString(".") { it.name }
        if (last.lambda != null) return unknown(ref)
        if (last.arguments == null) return reference(file, qualifier, last.name, segments.first().start, ref, budget)
        if (last.name == "Builder" && (qualifier.endsWith("Retrofit") || qualifier.isEmpty() && file.visibleNameOf("retrofit2.Retrofit.Builder") == "Builder")) {
            // baseUrl 없이 build()하면 Retrofit이 거부한다 — 이 결합으로는 요청이 나가지 않는다.
            return unknown(ref)
        }
        if (qualifier.isEmpty() && last.name == "get" && file.imports.any { it.path.startsWith("org.koin.") }) return koin(file, last, budget)
        val (owner, function) = declarations.findFunction(file, qualifier, last.name, last.start) ?: return unknown(null)
        return functionBody(owner, function, functionId(owner, function), budget)
    }

    /** 식별자 참조 `qualifier.name`을 지역 `val`·매개변수·속성으로 따라간다. */
    private fun reference(file: RouteSourceFile, qualifier: String, name: String, offset: Int, ref: String?, budget: Budget): List<RetrofitBinding> {
        if (qualifier.isEmpty()) {
            localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) expression(file, range.first, range.last + 1, ref, budget) else unknown(ref)
            }
            parameter(file, name, offset)?.let { (function, parameter) -> return injectedParameter(file, function, parameter, budget) }
        }
        val property = if (qualifier.isEmpty() || qualifier == "this") propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        if (property != null) return propertyBindings(property.first, property.second, budget)
        if (qualifier.isEmpty() || qualifier == "this") {
            constructorParameter(file, name, offset)?.let { (header, parameter) -> return injectedConstructorParameter(header, parameter, budget) }
        }
        return unknown(null)
    }

    /** 감싸는 타입(안쪽부터)과 같은 파일 최상위에서 [name] 속성을 찾는다. */
    private fun propertyInScope(file: RouteSourceFile, name: String, offset: Int): Pair<RouteSourceFile, SourceProperty>? {
        val chain = file.enclosingTypes(offset)
        for (depth in chain.size downTo 0) {
            val owners = chain.take(depth)
            val found = declarations.properties(file).filter { property ->
                property.name == name && property.containers.size == owners.size && property.containers.indices.all { property.containers[it] === owners[it] }
            }
            if (found.isNotEmpty()) return found.singleOrNull()?.let { file to it }
        }
        return null
    }

    /** 속성·필드 값의 원천(초기식, lazy 본문의 마지막 식, getter, 가변이면 모든 대입)을 푼다. */
    private fun propertyBindings(file: RouteSourceFile, property: SourceProperty, budget: Budget): List<RetrofitBinding> {
        val ref = "kt:" + (listOf(property.ownerFqn(file)).filter { it.isNotEmpty() } + property.name).joinToString(".")
        val sources = mutableListOf<IntRange>()
        property.initializer?.takeUnless { isNullLiteral(file, it) }?.let(sources::add)
        property.lazyBody?.let { lastStatement(file, it) }?.let(sources::add)
        property.getterBody?.let { body -> sources += if (file.masked.substring(body).contains(RETURN)) returnRanges(file, body) else listOf(body) }
        if (property.mutable || sources.isEmpty() && property.lazyBody == null && property.getterBody == null) sources += assignments(file, property)
        if (sources.isEmpty()) {
            return if (INJECT.containsMatchIn(property.annotations)) injected(declarations.qualifiers(property.annotations), budget) else unknown(ref)
        }
        return sources.flatMap { expression(file, it.first, it.last + 1, ref, budget) }.distinct()
    }

    /** 가변 속성·필드에 대한 같은 파일의 대입(`name = …`, `this.name = …`) 우변 범위다. `null` 대입과 명명 인자는 뺀다. */
    private fun assignments(file: RouteSourceFile, property: SourceProperty): List<IntRange> {
        val owner = property.containers.lastOrNull()?.name
        val qualifiers = listOfNotNull("this", owner).joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<![\\w.])(?:(?:$qualifiers)\\s*\\.\\s*)?${Regex.escape(property.name)}\\s*=(?![=>])")
        return pattern.findAll(file.masked).mapNotNull { match ->
            val before = file.masked.substring(0, match.range.first).trimEnd()
            if (before.endsWith('(') || before.endsWith(',') || before.endsWith("val") || before.endsWith("var")) return@mapNotNull null
            if (match.range.first <= property.start && property.start <= match.range.last) return@mapNotNull null
            // 한정자 없는 대입은 속성의 소유 타입 몸체 안에서만, 같은 이름의 지역 변수·매개변수가 가리지 않을 때만 이 속성의 대입이다.
            val qualified = match.value.contains('.') && !match.value.trimStart().startsWith("this")
            if (!qualified && !assignsInOwnerScope(file, property, match.range.first)) return@mapNotNull null
            if (!match.value.contains('.') && shadowedLocally(file, property.name, match.range.first)) return@mapNotNull null
            val start = match.range.last + 1
            (start until file.statementEnd(start)).takeUnless { isNullLiteral(file, it) }
        }.toList()
    }

    /**
     * [offset]의 대입이 [property]의 스코프 안인지 본다 — 감싸는 타입 사슬이 속성의 소유 타입 사슬로 시작하고, 그 사이의 더 안쪽
     * 타입이 같은 이름의 속성을 따로 선언하지 않아야 한다(다른 class의 같은 이름 속성에 한 대입을 빌려 오지 않기 위해서다).
     */
    private fun assignsInOwnerScope(file: RouteSourceFile, property: SourceProperty, offset: Int): Boolean {
        val chain = file.enclosingTypes(offset)
        val owners = property.containers
        if (chain.size < owners.size || owners.indices.any { chain[it] !== owners[it] }) return false
        val inner = chain.drop(owners.size)
        return declarations.properties(file).none { other ->
            other !== property && other.name == property.name && other.containers.lastOrNull()?.let { owner -> inner.any { it === owner } } == true
        }
    }

    /** [offset]을 감싸는 함수가 [name] 매개변수나 그 앞의 지역 선언(초기식 없는 Java 선언 포함)을 가지는지 본다. */
    private fun shadowedLocally(file: RouteSourceFile, name: String, offset: Int): Boolean {
        val function = file.enclosingFunction(offset) ?: return false
        if (parameter(file, name, offset) != null) return true
        val escaped = Regex.escape(name)
        val declaration = Regex("\\b(?:val|var)\\s+$escaped\\b|\\b(?!(?:return|throw|else|new|case)\\b)[A-Za-z_][\\w.]*(?:<[^;=()]*>)?\\s+$escaped\\s*[;=,)]")
        val bodyStart = function.bodyStart.coerceAtLeast(function.start)
        return declaration.findAll(file.masked.substring(0, offset), bodyStart).any { file.inScope(it.range.first, offset) }
    }

    private fun isNullLiteral(file: RouteSourceFile, range: IntRange): Boolean = file.masked.substring(range).trim() == "null"

    /** 함수 몸체의 값: 식 몸체면 그 식, 블록이면 모든 `return` 식이다. */
    private fun functionBody(file: RouteSourceFile, function: RouteFunctionDecl, ref: String, budget: Budget): List<RetrofitBinding> {
        val bodyStart = function.bodyStart
        if (bodyStart < 0 || bodyStart >= file.masked.length) return unknown(ref)
        if (file.masked[bodyStart] == '=') return expression(file, bodyStart + 1, function.end, ref, budget)
        val returns = returnRanges(file, (bodyStart + 1) until function.end.coerceAtMost(file.masked.length))
        if (returns.isEmpty()) return unknown(ref)
        return returns.flatMap { expression(file, it.first, it.last + 1, ref, budget) }.distinct()
    }

    /** 범위 안의 `return 식` 식 범위다. 레이블 반환(`return@x`)과 `return null`은 뺀다. */
    private fun returnRanges(file: RouteSourceFile, body: IntRange): List<IntRange> =
        RETURN.findAll(file.masked.substring(0, (body.last + 1).coerceAtMost(file.masked.length)), body.first).mapNotNull { match ->
            val start = match.range.last + 1
            (start until file.statementEnd(start)).takeUnless { isNullLiteral(file, it) || file.masked.substring(it).isBlank() }
        }.toList()

    /** 블록(람다 본문) 안 마지막 문장의 범위다. */
    private fun lastStatement(file: RouteSourceFile, body: IntRange): IntRange? {
        var position = body.first
        var last: IntRange? = null
        while (position <= body.last) {
            val end = file.statementEnd(position).coerceAtMost(body.last + 1)
            if (file.masked.substring(position, end).isNotBlank()) last = position until end
            position = end + 1
        }
        return last
    }

    /**
     * 함수 매개변수로 받은 Retrofit이다. `@Provides` provider의 매개변수와 `@Inject` 생성자(Java)의 매개변수만 DI 그래프에서 풀고,
     * 그 밖의 매개변수는 호출자마다 다를 수 있어 모른다.
     */
    private fun injectedParameter(file: RouteSourceFile, function: RouteFunctionDecl, parameter: SourceParameter, budget: Budget): List<RetrofitBinding> {
        if (parameter.type != "Retrofit") return unknown(null)
        val annotations = functionAnnotations(file, function)
        if (!PROVIDES.containsMatchIn(annotations) && !INJECT.containsMatchIn(annotations)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** Kotlin 주 생성자 매개변수로 받은 Retrofit이다. `@Inject constructor`일 때만 DI로 푼다. */
    private fun injectedConstructorParameter(header: String, parameter: SourceParameter, budget: Budget): List<RetrofitBinding> {
        if (parameter.type != "Retrofit" || !INJECT.containsMatchIn(header)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** 한정자가 같은 `@Provides` Retrofit provider가 하나일 때만 그 몸체를 푼다. 없거나 여럿이면 모른다. */
    private fun injected(qualifiers: Set<String>, budget: Budget): List<RetrofitBinding> {
        val provider = declarations.retrofitProviders.filter { (file, function) ->
            declarations.qualifiers(functionAnnotations(file, function)) == qualifiers
        }.singleOrNull() ?: return unknown(null)
        return functionBody(provider.first, provider.second, functionId(provider.first, provider.second), budget)
    }

    /** Koin `get()`·`get<Retrofit>()`·`get(named("x"))`을 한정자가 같은 Retrofit 정의 하나로 푼다. */
    private fun koin(file: RouteSourceFile, segment: ChainSegment, budget: Budget): List<RetrofitBinding> {
        val typeArgument = segment.typeArguments?.trim()?.substringAfterLast('.')
        if (typeArgument != null && typeArgument != "Retrofit") return unknown(null)
        val qualifier = segment.arguments?.let { koinQualifier(file.code.substring(it.first + 1, it.last)) }
        val definition = koinDefinitions.filter { it.qualifier == qualifier }.singleOrNull() ?: return unknown(null)
        return expression(definition.file, definition.body.first, definition.body.last + 1, definition.ref, budget)
    }

    private fun collectKoinDefinitions(files: List<RouteSourceFile>): List<KoinDefinition> = files
        .filter { file -> file.imports.any { it.path.startsWith("org.koin.") } }
        .flatMap { file ->
            KOIN_DEFINITION.findAll(file.masked).mapNotNull { match ->
                val open = match.range.last
                val close = file.braceEnd(open).takeIf { it > open } ?: return@mapNotNull null
                val body = lastStatement(file, (open + 1) until close) ?: return@mapNotNull null
                val typeArgument = match.groupValues[2].trim().substringAfterLast('.')
                val statement = file.masked.substring(body)
                val buildsRetrofit = typeArgument == "Retrofit" || typeArgument.isEmpty() && RETROFIT_BUILD_CHAIN.containsMatchIn(statement)
                if (!buildsRetrofit) return@mapNotNull null
                val qualifier = match.groups[3]?.let { koinQualifier(file.code.substring(it.range.first + 1, it.range.last)) }
                val ref = (declarationId(file, match.range.first) ?: "kt:${file.packageName}") + (qualifier?.let { "#$it" } ?: "")
                KoinDefinition(file, body, qualifier, ref)
            }.toList()
        }

    /** Koin 한정자 인자(`named("x")`, `qualifier = named("x")`)를 `named:x`로 정규화한다. 없으면 null이다. */
    private fun koinQualifier(arguments: String): String? {
        val match = KOIN_NAMED.find(arguments) ?: return null
        return "named:" + (scriptString(match.groupValues[1]) ?: match.groupValues[1].trim())
    }

    /** base 인자 범위의 결합들이다. 값 집합의 각 URL마다 하나다. */
    private fun baseBindings(file: RouteSourceFile, arguments: IntRange, ref: String?, budget: Budget): List<RetrofitBinding> {
        val argument = callArguments(file.code, arguments.first, arguments.last).singleOrNull() ?: return unknown(ref)
        val values = baseValues(file, argument, arguments.first + 1, budget) ?: return unknown(ref)
        return values.map { RetrofitBinding(RetrofitBaseUrl.parse(it), ref) }.distinct()
    }

    /**
     * base URL 식의 값 집합이다. 리터럴·템플릿·상수(같은 파일·다른 파일)·지역 `val`·`BuildConfig`·`HttpUrl` 래퍼를 따라가고,
     * 값을 증명하지 못하면 null이다.
     */
    private fun baseValues(file: RouteSourceFile, text: String, offset: Int, budget: Budget): List<String>? {
        if (budget.depth >= MAX_DEPTH) return null
        budget.depth++
        try {
            val expression = unwrapHttpUrl(unwrapParentheses(text.trim()).removeSuffix("!!").trim())
            resolver(file).literalValue(expression, offset)?.let { return listOf(it) }
            if (REFERENCE.matches(expression)) return referenceValues(file, expression, offset, budget)
            // 다른 파일 상수가 섞인 연결·템플릿이다. 조각마다 값이 하나일 때만 잇는다.
            val parts = resolver(file).parts(expression, offset)
            if (parts.size < 2) return null
            val pieces = parts.map { part ->
                when (part) {
                    is UrlPart.Literal -> part.text
                    is UrlPart.Value -> part.expression.takeIf(REFERENCE::matches)?.let { referenceValues(file, it, offset, budget) }?.singleOrNull() ?: return null
                    is UrlPart.QueryTail -> return null
                }
            }
            return listOf(pieces.joinToString(""))
        } finally {
            budget.depth--
        }
    }

    /** `x.toHttpUrl()`·`HttpUrl.get(x)`·`HttpUrl.parse(x)!!` 같은 OkHttp 래퍼를 벗긴 문자열 식이다. */
    private fun unwrapHttpUrl(expression: String): String {
        HTTP_URL_EXTENSION.matchEntire(expression)?.let { return unwrapHttpUrl(it.groupValues[1].trim()) }
        HTTP_URL_FACTORY.matchEntire(expression)?.let { match ->
            val open = match.groups[1]!!.range.first
            val close = balancedEnd(expression, open)
            if (close == expression.length - 1) return unwrapHttpUrl(expression.substring(open + 1, close).trim())
        }
        return expression
    }

    /** 참조 식의 값: `BuildConfig` 필드, 지역 `val`, 파일 상수(같은 파일·다른 파일), 읽기 전용 속성 순이다. */
    private fun referenceValues(file: RouteSourceFile, expression: String, offset: Int, budget: Budget): List<String>? {
        val name = expression.substringAfterLast('.')
        val qualifier = expression.substringBeforeLast('.', "")
        if (qualifier == "BuildConfig" || qualifier.endsWith(".BuildConfig")) {
            val referencePackage = if ('.' in qualifier) qualifier.removeSuffix(".BuildConfig")
            else file.imports.firstOrNull { it.path.endsWith(".BuildConfig") && it.alias == null }?.path?.removeSuffix(".BuildConfig") ?: file.packageName
            return buildConfig.values(file, name, referencePackage)
        }
        if (qualifier.isEmpty()) {
            localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) baseValues(file, file.code.substring(range), range.first, budget) else null
            }
            if (parameter(file, name, offset) != null) return null
        }
        declarations.findConstant(file, qualifier, name)?.let { (owner, constant) -> return baseValues(owner, constant.expression, 0, budget) }
        // 상수가 아닌 읽기 전용 속성(`private val baseUrl = "…"`, `val url = HttpUrl.get("…")`)도 초기식이 값이다.
        val property = if (qualifier.isEmpty() || qualifier == "this") propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        val (owner, declaration) = property ?: return null
        val initializer = declaration.initializer?.takeUnless { declaration.mutable } ?: return null
        return baseValues(owner, owner.code.substring(initializer), initializer.first, budget)
    }

    private fun resolver(file: RouteSourceFile): RoutePathResolver = pathResolvers.getOrPut(file) { RoutePathResolver(file) }

    /**
     * 같은 함수에서 [offset] 앞에 선언된 지역 변수의 (val 여부, 초기식 범위)다. Kotlin `val`·`var`, Java `Retrofit x =`·`var x =`·
     * `String x =`를 본다.
     */
    private fun localInitializer(file: RouteSourceFile, name: String, offset: Int): Pair<Boolean, IntRange>? {
        val function = file.enclosingFunction(offset) ?: return null
        val escaped = Regex.escape(name)
        val pattern = if (file.isJava) Regex("\\b(final\\s+)?(?:[A-Za-z_][\\w.]*(?:<[^;=()]*>)?)\\s+$escaped\\s*=(?!=)")
        else Regex("\\b(val|var)\\s+$escaped\\b\\s*(?::[^=\\n]+)?=(?!=)")
        val searchStart = function.bodyStart.coerceAtLeast(function.start)
        val match = pattern.findAll(file.masked.substring(0, offset.coerceAtMost(file.masked.length)), searchStart)
            .lastOrNull { file.inScope(it.range.first, offset) } ?: return null
        val start = match.range.last + 1
        // Java 지역 변수는 다시 대입되지 않았을 때만(사실상 final) 값으로 본다.
        val isVal = if (file.isJava) !Regex("(?<![\\w.])$escaped\\s*=(?![=>])").containsMatchIn(file.masked.substring(start, offset.coerceAtLeast(start)))
        else match.groupValues[1] == "val"
        return isVal to (start until file.statementEnd(start))
    }

    /** [offset]을 감싸는 함수의 [name] 매개변수다. */
    private fun parameter(file: RouteSourceFile, name: String, offset: Int): Pair<RouteFunctionDecl, SourceParameter>? {
        val function = file.enclosingFunction(offset) ?: return null
        return functionParameters(file, function).firstOrNull { it.name == name }?.let { function to it }
    }

    /** 감싸는 타입들의 Kotlin 주 생성자 매개변수 중 [name]이다. 결과의 첫 값은 생성자 머리 원문(`@Inject` 판정용)이다. */
    private fun constructorParameter(file: RouteSourceFile, name: String, offset: Int): Pair<String, SourceParameter>? {
        if (file.isJava) return null
        for (type in file.enclosingTypes(offset).reversed()) {
            val header = file.masked.substring(type.start, type.bodyStart.coerceAtLeast(type.start))
            val open = header.indexOf('(').takeIf { it >= 0 }?.plus(type.start) ?: continue
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: continue
            val parameter = callArguments(file.code, open, close).mapNotNull(::kotlinSourceParameter).firstOrNull { it.name == name } ?: continue
            return file.code.substring(type.start, open) to parameter
        }
        return null
    }

    /** 함수 매개변수 목록이다(어노테이션 원문 포함). */
    private fun functionParameters(file: RouteSourceFile, function: RouteFunctionDecl): List<SourceParameter> {
        val open = parameterListOpen(file, function) ?: return emptyList()
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return emptyList()
        return callArguments(file.code, open, close).mapNotNull { if (file.isJava) javaSourceParameter(it) else kotlinSourceParameter(it) }
    }

    /**
     * [offset]을 감싸는 선언의 생산자 id(`kt:` + 소스 한정 이름)다. 함수 안이면 함수, 속성 초기식 안이면 속성, 아니면 타입이다.
     */
    fun declarationId(file: RouteSourceFile, offset: Int): String? {
        val function = file.enclosingFunction(offset)
        val member = function?.name ?: declarations.properties(file).firstOrNull { offset in it.start..it.end }?.name
        val names = listOf(file.packageName).filter { it.isNotEmpty() } + file.enclosingTypes(offset).map { it.name } + listOfNotNull(member)
        if (names.isEmpty() || member == null && file.enclosingTypes(offset).isEmpty()) return null
        return "kt:" + names.joinToString(".")
    }

    private fun functionId(file: RouteSourceFile, function: RouteFunctionDecl): String =
        "kt:" + (listOf(declarations.containerFqn(file, function.start)).filter { it.isNotEmpty() } + function.name).joinToString(".")

    private companion object {
        const val MAX_DEPTH = 16

        /** 수신 객체를 그대로 돌려주는 범위 함수다. 블록 안의 `baseUrl(…)`을 읽는다. */
        val SCOPE_BLOCKS = setOf("apply", "also")
        val PROVIDES = Regex("@(?:dagger\\.)?Provides\\b")
        val INJECT = Regex("@(?:javax\\.inject\\.|jakarta\\.inject\\.)?Inject\\b")
        val RETURN = Regex("\\breturn\\b(?!@)")
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*")
        val HTTP_URL_EXTENSION = Regex("(.+?)\\s*\\.\\s*(?:toHttpUrl|toHttpUrlOrNull)\\s*\\(\\s*\\)\\s*(?:!!)?", RegexOption.DOT_MATCHES_ALL)
        val HTTP_URL_FACTORY = Regex("(?:okhttp3\\s*\\.\\s*)?HttpUrl\\s*\\.\\s*(?:Companion\\s*\\.\\s*)?(?:get|parse)\\s*(\\().*", RegexOption.DOT_MATCHES_ALL)
        val KOIN_DEFINITION = Regex("(?<![\\w.])(single|factory|scoped)\\s*(?:<\\s*([\\w.]+)\\s*>)?\\s*(\\((?:[^()]|\\([^()]*\\))*\\))?\\s*\\{")
        val KOIN_NAMED = Regex("\\bnamed\\s*\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\)")
        val RETROFIT_BUILD_CHAIN = Regex("\\bRetrofit\\s*\\.\\s*Builder\\s*\\(")
    }
}

/** base를 바꾸지 않는 `Retrofit`·`Retrofit.Builder` 메서드다. */
private val BUILDER_METHODS = setOf(
    "build", "client", "callFactory", "addConverterFactory", "addCallAdapterFactory", "callbackExecutor", "validateEagerly", "newBuilder",
)

/** Koin 정의 하나다. [body]는 정의 람다의 마지막 식 범위다. */
private data class KoinDefinition(val file: RouteSourceFile, val body: IntRange, val qualifier: String?, val ref: String)

/** 매개변수 하나다. [annotations]는 매개변수에 붙은 어노테이션 원문이다(DI 한정자용). */
internal data class SourceParameter(val name: String, val type: String, val annotations: String)

private val PARAMETER_ANNOTATION = Regex("@[A-Za-z_][\\w.]*(?::[A-Za-z_]\\w*)?(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?")
private val KOTLIN_PARAMETER_MODIFIERS = Regex("\\b(?:vararg|noinline|crossinline|val|var|private|public|internal|protected|override|open|final)\\b")
private val KOTLIN_NAMED_TYPE = Regex("^([A-Za-z_]\\w*)\\s*:\\s*([^=]+)")

/** Kotlin 매개변수 원문(`@Named("a") private val retrofit: Retrofit`)을 읽는다. */
internal fun kotlinSourceParameter(text: String): SourceParameter? {
    val annotations = PARAMETER_ANNOTATION.findAll(text).joinToString(" ") { it.value }
    val cleaned = PARAMETER_ANNOTATION.replace(text, " ").replace(KOTLIN_PARAMETER_MODIFIERS, " ").trim()
    val match = KOTLIN_NAMED_TYPE.find(cleaned) ?: return null
    return SourceParameter(match.groupValues[1], parameterTypeName(match.groupValues[2]), annotations)
}

/** Java 매개변수 원문(`@Named("a") final Retrofit retrofit`)을 읽는다. */
internal fun javaSourceParameter(text: String): SourceParameter? {
    val annotations = PARAMETER_ANNOTATION.findAll(text).joinToString(" ") { it.value }
    val cleaned = PARAMETER_ANNOTATION.replace(text, " ").replace(Regex("\\bfinal\\b"), " ").trim()
    val name = Regex("[A-Za-z_]\\w*$").find(cleaned)?.value ?: return null
    return SourceParameter(name, parameterTypeName(cleaned.removeSuffix(name)), annotations)
}

private fun parameterTypeName(type: String): String =
    type.substringBefore('<').substringBefore('=').trim().removeSuffix("?").substringAfterLast('.').trim()

/**
 * 사슬 식의 멤버 접근 단위 하나다(`name<T>(args) { lambda }`).
 *
 * @property start 단위가 시작하는 위치다
 * @property arguments 인자 괄호의 (`(` 위치, `)` 위치) 범위다. 인자 괄호가 없으면 null이다
 * @property typeArguments 타입 인자 원문이다
 * @property lambda 끝 람다의 (`{` 위치, `}` 위치) 범위다
 */
internal data class ChainSegment(val name: String, val start: Int, val arguments: IntRange?, val typeArguments: String?, val lambda: IntRange?) {
    val isPlain: Boolean get() = arguments == null && lambda == null && typeArguments == null
}

/**
 * 사슬 식 [start, end)를 최상위 `.`(`?.` 포함)에서 나눠 단위로 읽는다. 읽을 수 없는 모양(연산자·캐스트·메서드 참조)이면 null이다.
 */
internal fun chainSegments(file: RouteSourceFile, start: Int, end: Int): List<ChainSegment>? {
    val masked = file.masked
    val bounds = mutableListOf<Pair<Int, Int>>()
    var depth = 0
    var angle = 0
    var segmentStart = start
    var index = start
    while (index < end) {
        val character = masked[index]
        when {
            character == '(' || character == '[' || character == '{' -> depth++
            character == ')' || character == ']' || character == '}' -> depth--
            character == '<' && depth == 0 && masked.getOrNull(index - 1)?.let { it.isLetterOrDigit() || it == '_' } == true -> angle++
            character == '>' && angle > 0 && masked.getOrNull(index - 1) != '-' -> angle--
            character == '.' && depth == 0 && angle == 0 -> {
                bounds += segmentStart to (if (masked.getOrNull(index - 1) == '?') index - 1 else index)
                segmentStart = index + 1
            }
        }
        index++
    }
    bounds += segmentStart to end
    return bounds.map { (from, to) -> parseSegment(file, from, to) ?: return null }.takeIf { it.isNotEmpty() }
}

private val SEGMENT_IDENTIFIER = Regex("\\G[A-Za-z_]\\w*")

/** 단위 하나 `new? name <T>? (args)? {lambda}? !!?`를 읽는다. 남는 글자가 있으면 null이다. */
private fun parseSegment(file: RouteSourceFile, from: Int, to: Int): ChainSegment? {
    val masked = file.masked
    var index = skipSpaces(masked, from)
    if (masked.startsWith("new", index) && masked.getOrNull(index + 3)?.isWhitespace() == true) index = skipSpaces(masked, index + 3)
    val start = index
    val identifier = SEGMENT_IDENTIFIER.find(masked, index)?.takeIf { it.range.first == index } ?: return null
    index = skipSpaces(masked, identifier.range.last + 1)
    var typeArguments: String? = null
    if (index < to && masked[index] == '<') {
        val close = angleEnd(masked, index, to) ?: return null
        typeArguments = file.code.substring(index + 1, close)
        index = skipSpaces(masked, close + 1)
    }
    var arguments: IntRange? = null
    if (index < to && masked[index] == '(') {
        val close = balancedEnd(file.code, index).takeIf { it in index until to } ?: return null
        arguments = index..close
        index = skipSpaces(masked, close + 1)
    }
    var lambda: IntRange? = null
    if (index < to && masked[index] == '{') {
        val close = file.braceEnd(index).takeIf { it in index until to } ?: return null
        lambda = index..close
        index = skipSpaces(masked, close + 1)
    }
    while (index < to && (masked[index] == '!' || masked[index] == '?' || masked[index].isWhitespace())) index++
    if (index < to) return null
    return ChainSegment(identifier.value, start, arguments, typeArguments, lambda)
}

private fun angleEnd(masked: String, open: Int, limit: Int): Int? {
    var depth = 0
    for (index in open until limit) {
        when (masked[index]) {
            '<' -> depth++
            '>' -> { depth--; if (depth == 0) return index }
            '(', ')', '{', '}', ';', '=' -> return null
        }
    }
    return null
}

/**
 * 사슬의 뿌리 단위 수다. 앞의 이름 단위들(패키지·타입·변수)과, 그 뒤가 빌더 메서드가 아닌 호출이면 그 호출까지다.
 */
private fun rootLength(segments: List<ChainSegment>): Int {
    var plain = 0
    while (plain < segments.size && segments[plain].isPlain) plain++
    if (plain == 0) return 1
    val next = segments.getOrNull(plain) ?: return plain
    val builderMethod = next.name in BUILDER_METHODS || next.name == "baseUrl" || next.name == "apply" || next.name == "also"
    return if (next.arguments != null && !builderMethod) plain + 1 else plain
}

/** 람다 블록 [lambda] 바로 안의 마지막 `baseUrl(…)` 인자 괄호 범위다. */
private fun lambdaBaseUrl(file: RouteSourceFile, lambda: IntRange): IntRange? {
    val pattern = Regex("(?<![\\w.])(?:this\\s*\\.\\s*)?baseUrl\\s*\\(")
    return pattern.findAll(file.masked.substring(0, lambda.last), lambda.first + 1).lastOrNull { match ->
        file.masked.substring(lambda.first + 1, match.range.first).let { text -> text.count { it == '{' } == text.count { it == '}' } }
    }?.let { match -> balancedEnd(file.code, match.range.last).takeIf { it > match.range.last }?.let { match.range.last..it } }
}
