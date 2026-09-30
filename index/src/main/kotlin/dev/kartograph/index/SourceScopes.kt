package dev.kartograph.index

/**
 * 소스 한 파일 안에서 값의 원천(지역 선언·매개변수·주 생성자 매개변수·속성과 그 대입·`return`)을 찾는 어휘 도구다.
 *
 * Retrofit base 해석([RetrofitInstanceResolver])과 Spring HTTP 클라이언트 해석([SpringClientResolver])이 같은 규칙으로
 * 선언을 따라가도록 한 벌로 둔다. 증명하지 못하면 null·빈 목록을 돌려준다 — 추측으로 다른 선언을 잇지 않는다.
 *
 * @param declarations 파일 밖 선언 색인이다
 */
internal class SourceScopes(val declarations: SourceDeclarations) {
    /** 감싸는 타입(안쪽부터)과 같은 파일 최상위에서 [name] 속성을 찾는다. */
    fun propertyInScope(file: RouteSourceFile, name: String, offset: Int): Pair<RouteSourceFile, SourceProperty>? {
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

    /** 가변 속성·필드에 대한 같은 파일의 대입(`name = …`, `this.name = …`) 우변 범위다. `null` 대입과 명명 인자는 뺀다. */
    fun assignments(file: RouteSourceFile, property: SourceProperty): List<IntRange> {
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
    fun assignsInOwnerScope(file: RouteSourceFile, property: SourceProperty, offset: Int): Boolean {
        val chain = file.enclosingTypes(offset)
        val owners = property.containers
        if (chain.size < owners.size || owners.indices.any { chain[it] !== owners[it] }) return false
        val inner = chain.drop(owners.size)
        return declarations.properties(file).none { other ->
            other !== property && other.name == property.name && other.containers.lastOrNull()?.let { owner -> inner.any { it === owner } } == true
        }
    }

    /** [offset]을 감싸는 함수가 [name] 매개변수나 그 앞의 지역 선언(초기식 없는 Java 선언 포함)을 가지는지 본다. */
    fun shadowedLocally(file: RouteSourceFile, name: String, offset: Int): Boolean {
        val function = file.enclosingFunction(offset) ?: return false
        if (parameter(file, name, offset) != null) return true
        val escaped = Regex.escape(name)
        val declaration = Regex("\\b(?:val|var)\\s+$escaped\\b|\\b(?!(?:return|throw|else|new|case)\\b)[A-Za-z_][\\w.]*(?:<[^;=()]*>)?\\s+$escaped\\s*[;=,)]")
        val bodyStart = function.bodyStart.coerceAtLeast(function.start)
        return declaration.findAll(file.masked.substring(0, offset), bodyStart).any { file.inScope(it.range.first, offset) }
    }

    fun isNullLiteral(file: RouteSourceFile, range: IntRange): Boolean = file.masked.substring(range).trim() == "null"

    /** 범위 안의 `return 식` 식 범위다. 레이블 반환(`return@x`)과 `return null`은 뺀다. */
    fun returnRanges(file: RouteSourceFile, body: IntRange): List<IntRange> =
        RETURN.findAll(file.masked.substring(0, (body.last + 1).coerceAtMost(file.masked.length)), body.first).mapNotNull { match ->
            val start = match.range.last + 1
            (start until file.statementEnd(start)).takeUnless { isNullLiteral(file, it) || file.masked.substring(it).isBlank() }
        }.toList()

    /** 블록(람다 본문) 안 마지막 문장의 범위다. */
    fun lastStatement(file: RouteSourceFile, body: IntRange): IntRange? {
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
     * 같은 함수에서 [offset] 앞에 선언된 지역 변수의 (val 여부, 초기식 범위)다. Kotlin `val`·`var`, Java `Retrofit x =`·`var x =`·
     * `String x =`를 본다.
     */
    fun localInitializer(file: RouteSourceFile, name: String, offset: Int): Pair<Boolean, IntRange>? {
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
    fun parameter(file: RouteSourceFile, name: String, offset: Int): Pair<RouteFunctionDecl, SourceParameter>? {
        val function = file.enclosingFunction(offset) ?: return null
        return functionParameters(file, function).firstOrNull { it.name == name }?.let { function to it }
    }

    /** 감싸는 타입들의 Kotlin 주 생성자 매개변수 중 [name]이다. 결과의 첫 값은 생성자 머리 원문(`@Inject` 판정용)이다. */
    fun constructorParameter(file: RouteSourceFile, name: String, offset: Int): Pair<String, SourceParameter>? {
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
    fun functionParameters(file: RouteSourceFile, function: RouteFunctionDecl): List<SourceParameter> {
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

    fun functionId(file: RouteSourceFile, function: RouteFunctionDecl): String =
        "kt:" + (listOf(declarations.containerFqn(file, function.start)).filter { it.isNotEmpty() } + function.name).joinToString(".")

    private companion object {
        val RETURN = Regex("\\breturn\\b(?!@)")
    }
}
