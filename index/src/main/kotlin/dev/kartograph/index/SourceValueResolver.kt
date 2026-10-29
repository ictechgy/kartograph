package dev.kartograph.index

/**
 * 특정 타입(`Retrofit`·`OkHttpClient`) 값을 내는 식의 원천을 따라가는 공통 골격이다.
 *
 * 식별자 참조를 지역 `val`·매개변수·속성(초기식·lazy·getter·가변이면 모든 대입)으로, 함수 호출을 식 몸체·`return`으로, 주입
 * 지점을 한정자가 같은 Dagger/Hilt `@Provides` provider 하나로, Koin `get()`을 한정자가 같은 `single`·`factory` 정의 하나로 잇는다.
 * 하위 클래스는 사슬 식 자체의 해석([expression])과 값을 모를 때([unknown])·원천이 여럿일 때([combine])의 표현만 정한다 —
 * Retrofit base 해석([RetrofitInstanceResolver])과 인터셉터 결합의 client 해석([OkHttpClientResolver])이 같은 DI 규칙을 쓰게
 * 하기 위해서다. 증명하지 못한 원천은 추측으로 잇지 않고 [unknown]이다.
 *
 * @param files 스캔한 production source 파일이다
 * @param declarations 파일 밖 선언 색인이다(해석기끼리 공유한다)
 */
internal abstract class SourceValueResolver<T>(files: List<RouteSourceFile>, val declarations: SourceDeclarations) {
    /** 값 원천을 찾는 공유 어휘 도구다. */
    val scopes: SourceScopes = SourceScopes(declarations)

    /** 주입 지점·Koin 타입 인자와 비교할 단순 타입 이름이다(`Retrofit`, `OkHttpClient`). */
    protected abstract val typeName: String

    /** 이 타입을 돌려주는 `@Provides` 함수들이다. */
    protected abstract val providers: List<Pair<RouteSourceFile, RouteFunctionDecl>>

    /** 타입 인자 없는 Koin 정의가 이 타입을 만든다고 볼 빌더 호출이다. */
    protected abstract val koinBuilder: Regex

    /** 값을 모르는 결과다. [ref]는 값을 공급하는 선언의 생산자 id다(모르면 null). */
    protected abstract fun unknown(ref: String?): T

    /** 원천이 여럿인 값(가변 속성의 대입들, 여러 `return`)을 합친다. [values]는 비어 있지 않다. */
    protected abstract fun combine(values: List<T>): T

    /** 이 타입 값을 내는 식 [start, end)를 푼다. */
    protected abstract fun expression(file: RouteSourceFile, start: Int, end: Int, ref: String?, budget: Budget): T

    /** Koin `single`·`factory` 정의 중 이 타입을 만드는 것들이다. */
    private val koinDefinitions: List<KoinDefinition> by lazy { collectKoinDefinitions(files) }

    /** 재귀 한도와 순환 방지다 — 서로를 참조하는 속성·함수가 무한히 따라가지 않게 한다. */
    class Budget {
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

    /** 식별자 참조 `qualifier.name`을 지역 `val`·매개변수·속성으로 따라간다. */
    protected fun reference(file: RouteSourceFile, qualifier: String, name: String, offset: Int, ref: String?, budget: Budget): T {
        if (qualifier.isEmpty()) {
            scopes.localInitializer(file, name, offset)?.let { (isVal, range) ->
                return if (isVal) expression(file, range.first, range.last + 1, ref, budget) else unknown(ref)
            }
            scopes.parameter(file, name, offset)?.let { (function, parameter) -> return injectedParameter(file, function, parameter, budget) }
        }
        val property = if (qualifier.isEmpty() || qualifier == "this") scopes.propertyInScope(file, name, offset) ?: declarations.findTopLevelProperty(file, name)
        else declarations.findMemberProperty(file, qualifier, name)
        if (property != null) return propertyValues(property.first, property.second, budget)
        if (qualifier.isEmpty() || qualifier == "this") {
            scopes.constructorParameter(file, name, offset)?.let { (header, parameter) -> return injectedConstructorParameter(header, parameter, budget) }
        }
        return unknown(null)
    }

    /** 함수 호출 `qualifier.name(…)`을 선언의 몸체로 따라간다. 선언을 하나로 정하지 못하면 모른다. */
    protected fun functionCall(file: RouteSourceFile, qualifier: String, name: String, offset: Int, budget: Budget): T {
        val (owner, function) = declarations.findFunction(file, qualifier, name, offset) ?: return unknown(null)
        return functionBody(owner, function, scopes.functionId(owner, function), budget)
    }

    /** 속성·필드 값의 원천(초기식, lazy 본문의 마지막 식, getter, 가변이면 모든 대입)을 푼다. */
    private fun propertyValues(file: RouteSourceFile, property: SourceProperty, budget: Budget): T {
        val ref = "kt:" + (listOf(property.ownerFqn(file)).filter { it.isNotEmpty() } + property.name).joinToString(".")
        val sources = mutableListOf<IntRange>()
        property.initializer?.takeUnless { scopes.isNullLiteral(file, it) }?.let(sources::add)
        property.lazyBody?.let { scopes.lastStatement(file, it) }?.let(sources::add)
        property.getterBody?.let { body -> sources += if (file.masked.substring(body).contains(RETURN)) scopes.returnRanges(file, body) else listOf(body) }
        if (property.mutable || sources.isEmpty() && property.lazyBody == null && property.getterBody == null) sources += scopes.assignments(file, property)
        if (sources.isEmpty()) {
            return if (INJECT.containsMatchIn(property.annotations)) injected(declarations.qualifiers(property.annotations), budget) else unknown(ref)
        }
        return combine(sources.map { expression(file, it.first, it.last + 1, ref, budget) })
    }

    /** 함수 몸체의 값: 식 몸체면 그 식, 블록이면 모든 `return` 식이다. */
    protected fun functionBody(file: RouteSourceFile, function: RouteFunctionDecl, ref: String, budget: Budget): T {
        val bodyStart = function.bodyStart
        if (bodyStart < 0 || bodyStart >= file.masked.length) return unknown(ref)
        if (file.masked[bodyStart] == '=') return expression(file, bodyStart + 1, function.end, ref, budget)
        val returns = scopes.returnRanges(file, (bodyStart + 1) until function.end.coerceAtMost(file.masked.length))
        if (returns.isEmpty()) return unknown(ref)
        return combine(returns.map { expression(file, it.first, it.last + 1, ref, budget) })
    }

    /**
     * 함수 매개변수로 받은 값이다. `@Provides` provider의 매개변수와 `@Inject` 생성자(Java)의 매개변수만 DI 그래프에서 풀고,
     * 그 밖의 매개변수는 호출자마다 다를 수 있어 모른다.
     */
    private fun injectedParameter(file: RouteSourceFile, function: RouteFunctionDecl, parameter: SourceParameter, budget: Budget): T {
        if (parameter.type != typeName) return unknown(null)
        val annotations = functionAnnotations(file, function)
        if (!PROVIDES.containsMatchIn(annotations) && !INJECT.containsMatchIn(annotations)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** Kotlin 주 생성자 매개변수로 받은 값이다. `@Inject constructor`일 때만 DI로 푼다. */
    private fun injectedConstructorParameter(header: String, parameter: SourceParameter, budget: Budget): T {
        if (parameter.type != typeName || !INJECT.containsMatchIn(header)) return unknown(null)
        return injected(declarations.qualifiers(parameter.annotations), budget)
    }

    /** 한정자가 같은 `@Provides` provider가 하나일 때만 그 몸체를 푼다. 없거나 여럿이면 모른다. */
    private fun injected(qualifiers: Set<String>, budget: Budget): T {
        val provider = providers.filter { (file, function) ->
            declarations.qualifiers(functionAnnotations(file, function)) == qualifiers
        }.singleOrNull() ?: return unknown(null)
        return functionBody(provider.first, provider.second, scopes.functionId(provider.first, provider.second), budget)
    }

    /** Koin `get()`·`get<T>()`·`get(named("x"))`을 한정자가 같은 정의 하나로 푼다. */
    protected fun koin(file: RouteSourceFile, segment: ChainSegment, budget: Budget): T {
        val typeArgument = segment.typeArguments?.trim()?.substringAfterLast('.')
        if (typeArgument != null && typeArgument != typeName) return unknown(null)
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
                val body = scopes.lastStatement(file, (open + 1) until close) ?: return@mapNotNull null
                val typeArgument = match.groupValues[2].trim().substringAfterLast('.')
                val statement = file.masked.substring(body)
                val builds = typeArgument == typeName || typeArgument.isEmpty() && koinBuilder.containsMatchIn(statement)
                if (!builds) return@mapNotNull null
                val qualifier = match.groups[3]?.let { koinQualifier(file.code.substring(it.range.first + 1, it.range.last)) }
                val ref = (scopes.declarationId(file, match.range.first) ?: "kt:${file.packageName}") + (qualifier?.let { "#$it" } ?: "")
                KoinDefinition(file, body, qualifier, ref)
            }.toList()
        }

    /** Koin 한정자 인자(`named("x")`, `qualifier = named("x")`)를 `named:x`로 정규화한다. 없으면 null이다. */
    private fun koinQualifier(arguments: String): String? {
        val match = KOIN_NAMED.find(arguments) ?: return null
        return "named:" + (scriptString(match.groupValues[1]) ?: match.groupValues[1].trim())
    }

    /** Koin 정의 하나다. [body]는 정의 람다의 마지막 식 범위다. */
    private data class KoinDefinition(val file: RouteSourceFile, val body: IntRange, val qualifier: String?, val ref: String)

    protected companion object {
        const val MAX_DEPTH = 16
        val PROVIDES = Regex("@(?:dagger\\.)?Provides\\b")
        val INJECT = Regex("@(?:javax\\.inject\\.|jakarta\\.inject\\.)?Inject\\b")
        val RETURN = Regex("\\breturn\\b(?!@)")
        val KOIN_DEFINITION = Regex("(?<![\\w.])(single|factory|scoped)\\s*(?:<\\s*([\\w.]+)\\s*>)?\\s*(\\((?:[^()]|\\([^()]*\\))*\\))?\\s*\\{")
        val KOIN_NAMED = Regex("\\bnamed\\s*\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\)")
    }
}
