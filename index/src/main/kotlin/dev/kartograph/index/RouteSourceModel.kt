package dev.kartograph.index

import dev.kartograph.index.RouteUrlRules.UrlPart

/**
 * route-call 스캐너가 쓰는 Kotlin/Java 소스 한 파일의 어휘 모델이다.
 *
 * 컴파일러를 실행하지 않고 주석 제거 뷰와 문자열 마스킹 뷰(길이·오프셋 보존) 위에서 패키지·import·
 * 타입/함수 범위·상수를 복원한다. 증명하지 못한 구조는 추측으로 채우지 않고 호출자에게 null로 알린다.
 *
 * @property relative 프로젝트 기준 상대 경로(`/` 구분)
 * @property isJava `.java` 파일이면 참이다 — 문자열 보간과 명명 인자가 없다
 * @property isTest 테스트 소스 세트 파일이면 참이다
 * @property source 줄 단위로 읽어 `\n`으로 이은 원문
 */
internal class RouteSourceFile(
    val relative: String,
    val isJava: Boolean,
    val isTest: Boolean,
    val source: String,
    private val maximumParameterChars: Int? = null,
) {
    /** 주석만 공백으로 지운 뷰다. 인자 원문은 이 뷰에서 읽는다. */
    val code: String = stripComments(source)

    /** 문자열 내용까지 공백으로 가린 뷰다. 구조 regex는 이 뷰에서만 찾는다. */
    val masked: String = maskStringContents(code)

    /** 선언된 패키지다. 없으면 빈 문자열이다. */
    val packageName: String = PACKAGE.find(masked)?.let { match ->
        code.substring(match.groups[1]!!.range).replace(WHITESPACE, "")
    }.orEmpty()

    /** import 목록이다(별칭 포함). */
    val imports: List<RouteImport> = IMPORT.findAll(masked).map { match ->
        RouteImport(match.groupValues[1].replace(WHITESPACE, ""), match.groupValues[2].ifEmpty { null })
    }.toList()

    /** bounded parameter scan이 완성되지 않으면 호출자는 파일 전체의 선언 위치 추측을 중단한다. */
    var parameterScanIncomplete: Boolean = false
        private set

    /** 타입 선언(class·interface·object·companion)이다. */
    val types: List<RouteTypeDecl> = collectTypes()

    /** 함수 선언이다. */
    val functions: List<RouteFunctionDecl> = if (isJava) collectJavaMethods() else collectKotlinFunctions()

    /** 이름 → 파일 안 상수 선언들이다. 같은 이름이 여러 컨테이너에 있을 수 있다. */
    val constants: Map<String, List<RouteConstant>> = collectConstants().groupBy { it.name }

    /** [offset]을 감싸는 가장 안쪽 함수다. */
    fun enclosingFunction(offset: Int): RouteFunctionDecl? =
        functions.filter { offset in it.start..it.end }.maxByOrNull { it.start }

    /** [offset]을 감싸는 타입 사슬이다(바깥 → 안쪽). */
    fun enclosingTypes(offset: Int): List<RouteTypeDecl> =
        types.filter { it.bodyStart >= 0 && offset in it.bodyStart..it.bodyEnd }.sortedBy { it.start }

    /** 소스 기준 선언 이름(`pkg.Outer.Inner.function`)이다. 감싸는 선언이 없으면 null이다. */
    fun qualifiedName(offset: Int): String? {
        val function = enclosingFunction(offset)
        val names = enclosingTypes(offset).map { it.name } + listOfNotNull(function?.name)
        if (names.isEmpty()) return null
        return (listOf(packageName).filter { it.isNotEmpty() } + names).joinToString(".")
    }

    /** 파일이 [fqn] 선언을 이름 그대로 볼 수 있으면 그 이름(별칭 포함)을 돌려준다. */
    fun visibleNameOf(fqn: String): String? {
        val simple = fqn.substringAfterLast('.')
        val parent = fqn.substringBeforeLast('.', "")
        imports.firstOrNull { it.path == fqn }?.let { return it.alias ?: simple }
        if (parent == packageName || imports.any { it.path == "$parent.*" }) return simple
        return null
    }

    /**
     * [offset]에서 쓴 식별자 [name]이 같은 함수의 매개변수나 지역 선언에 가려지는지 본다.
     * 가려지면 파일 상수로 치환하지 않는다.
     */
    fun isShadowed(name: String, offset: Int): Boolean {
        val function = enclosingFunction(offset) ?: return false
        return function.parameters.any { it.name == name } || localDeclaration(name, offset) != null
    }

    /**
     * 같은 함수 안에서 [offset]보다 앞서고 그 위치까지 블록이 열려 있는 `val`·`var` 선언을 찾는다.
     *
     * @return 선언 정보, 없으면 null
     */
    fun localDeclaration(name: String, offset: Int): RouteLocal? {
        val function = enclosingFunction(offset) ?: return null
        val pattern = Regex("\\b(val|var)\\s+${Regex.escape(name)}\\b\\s*(?::[^=\\n]+)?=(?!=)")
        val match = pattern.findAll(masked.substring(0, offset.coerceAtMost(masked.length)), function.bodyStart.coerceAtLeast(function.start))
            .lastOrNull { inScope(it.range.first, offset) } ?: return null
        val initializerStart = match.range.last + 1
        return RouteLocal(match.groupValues[1] == "val", code.substring(initializerStart, statementEnd(initializerStart)).trim())
    }

    /** 선언 위치의 블록이 사용 위치까지 닫히지 않았는지 본다. */
    fun inScope(declaration: Int, use: Int): Boolean {
        var depth = 0
        for (index in declaration until use.coerceAtMost(masked.length)) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> { depth--; if (depth < 0) return false }
            }
        }
        return true
    }

    /**
     * 초기식·인자 식의 끝을 찾는다. 괄호 깊이 0의 줄바꿈에서 앞 줄이 연산자로 끝나지 않고 다음 줄이
     * 연산자·멤버 접근으로 시작하지 않으면 식이 끝난다.
     */
    fun statementEnd(start: Int): Int {
        var depth = 0
        var index = start
        while (index < masked.length) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth == 0) return index; depth-- }
                ';' -> if (depth == 0) return index
                '\n' -> if (depth == 0 && !continuesAfter(index)) return index
            }
            index++
        }
        return masked.length
    }

    /** 줄바꿈 뒤에도 식이 이어지는지 본다. */
    private fun continuesAfter(newline: Int): Boolean {
        val before = masked.substring(0, newline).trimEnd()
        if (before.isEmpty() || before.last() in CONTINUATION_END) return true
        val after = masked.substring(newline + 1).trimStart()
        return after.startsWith("?.") || after.startsWith("?:") || (after.isNotEmpty() && after.first() in CONTINUATION_START)
    }

    private fun collectTypes(): List<RouteTypeDecl> {
        val result = mutableListOf<RouteTypeDecl>()
        TYPE_DECL.findAll(masked).forEach { match ->
            val name = match.groupValues[2].ifEmpty { "Companion" }
            val (bodyStart, headerEnd) = typeHeader(match.range.last + 1)
            result += RouteTypeDecl(name, match.range.first, bodyStart, if (bodyStart >= 0) braceEnd(bodyStart) else -1, headerEnd)
        }
        return result
    }

    /** 타입 머리 뒤 몸체 `{` 위치를 찾는다 — 괄호 밖 줄바꿈에서 이어지지 않으면 몸체 없는 선언이다. */
    private fun typeHeader(from: Int): Pair<Int, Int> {
        var depth = 0
        var index = from
        while (index < masked.length) {
            when (masked[index]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) return index to index
                ';', '}', '=' -> if (depth <= 0) return -1 to index
                '\n' -> if (depth <= 0 && !typeHeaderContinues(index)) return -1 to index
            }
            index++
        }
        return -1 to masked.length
    }

    private fun typeHeaderContinues(newline: Int): Boolean {
        var before = newline - 1
        while (before >= 0 && masked[before].isWhitespace()) before--
        var after = newline + 1
        while (after < masked.length && masked[after].isWhitespace()) after++
        return masked.getOrNull(before) in setOf(':', ',') || masked.getOrNull(after) in setOf('{', ':', ',') ||
            masked.startsWith("where", after) || (maximumParameterChars != null && !isJava &&
                (masked.startsWith("constructor", after) || masked.getOrNull(after) == '@' && constructorAfterAnnotations(after)))
    }

    /** sibling annotation을 타입 header로 삼지 않고 bounded annotation chain 뒤 constructor만 확인한다. */
    private fun constructorAfterAnnotations(from: Int): Boolean {
        val limit = minOf(masked.length, from + requireNotNull(maximumParameterChars))
        var cursor = from
        while (cursor < limit && masked[cursor] == '@') {
            cursor++
            if (masked.getOrNull(cursor) == '[') { parameterScanIncomplete = true; return false }
            val nameStart = cursor
            while (cursor < limit && (Character.isJavaIdentifierPart(masked[cursor]) || masked[cursor] in setOf('.', ':'))) cursor++
            if (cursor == nameStart) { parameterScanIncomplete = true; return false }
            while (cursor < limit && masked[cursor].isWhitespace()) cursor++
            if (masked.getOrNull(cursor) == '(') {
                val end = balancedEnd(code, cursor, limit)
                if (end < 0) { parameterScanIncomplete = true; return false }
                cursor = end + 1
            }
            while (cursor < limit && masked[cursor].isWhitespace()) cursor++
        }
        if (cursor >= limit) { parameterScanIncomplete = true; return false }
        return masked.startsWith("constructor", cursor) &&
            masked.getOrNull(cursor + "constructor".length)?.let(Character::isJavaIdentifierPart) != true
    }

    private fun collectKotlinFunctions(): List<RouteFunctionDecl> = KOTLIN_FUN.findAll(masked).mapNotNull { match ->
        val open = masked.indexOf('(', match.range.last).takeIf { it >= 0 } ?: return@mapNotNull null
        // `fun interface`처럼 함수 머리가 아닌 `fun`은 이름과 `(` 사이 모양으로 걸러낸다.
        val header = masked.substring(match.range.last + 1, open)
        if (!FUN_HEADER.matches(header)) return@mapNotNull null
        val name = IDENTIFIER_TAIL.find(header.trimEnd())?.value ?: return@mapNotNull null
        val close = parameterEnd(open).takeIf { it > open } ?: return@mapNotNull null
        val parameters = callArguments(code, open, close).mapNotNull(::kotlinParameter)
        val (bodyStart, end) = kotlinBody(close + 1)
        RouteFunctionDecl(name, match.range.first, bodyStart, end, parameters)
    }.toList()

    /** 함수 머리 뒤 블록 몸체나 식 몸체의 범위다. 몸체가 없으면 머리 끝까지다. */
    private fun kotlinBody(from: Int): Pair<Int, Int> {
        var index = from
        var depth = 0
        while (index < masked.length) {
            when (masked[index]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) return index to braceEnd(index).let { if (it < 0) masked.length else it }
                '=' -> if (depth <= 0 && masked.getOrNull(index + 1) != '=') return index to expressionBodyEnd(index + 1)
                '\n' -> if (depth <= 0 && !typeHeaderContinues(index) && masked.substring(index + 1).trimStart().let {
                        !it.startsWith('=') && !it.startsWith('{')
                    }) return index to index
                '}', ';' -> if (depth <= 0) return index to index
            }
            index++
        }
        return index to index
    }

    /** 식 몸체는 괄호 깊이 0에서 다음 선언이 시작하거나 감싸는 블록이 닫힐 때 끝난다. */
    private fun expressionBodyEnd(from: Int): Int {
        var depth = 0
        var index = from
        while (index < masked.length) {
            when (masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> { if (depth == 0) return index; depth-- }
                '\n' -> if (depth == 0 && DECLARATION_START.containsMatchIn(masked.substring(index + 1).take(64))) return index
            }
            index++
        }
        return masked.length
    }

    private fun collectJavaMethods(): List<RouteFunctionDecl> = JAVA_METHOD.findAll(masked).mapNotNull { match ->
        val name = match.groupValues[1]
        if (name in JAVA_KEYWORDS) return@mapNotNull null
        val open = masked.indexOf('(', match.range.first + match.value.indexOf(name))
        val close = parameterEnd(open).takeIf { it > open } ?: return@mapNotNull null
        val bodyStart = masked.indexOf('{', close).takeIf { it >= 0 } ?: return@mapNotNull null
        val parameters = callArguments(code, open, close).mapNotNull(::javaParameter)
        RouteFunctionDecl(name, match.range.first, bodyStart, braceEnd(bodyStart).coerceAtLeast(bodyStart), parameters)
    }.toList()

    private fun kotlinParameter(text: String): RouteParameter? {
        val cleaned = ANNOTATION.replace(text, " ").replace(PARAMETER_MODIFIERS, " ").trim()
        val match = KOTLIN_PARAMETER.find(cleaned) ?: return null
        return RouteParameter(match.groupValues[1], simpleType(match.groupValues[2]))
    }

    private fun parameterEnd(open: Int): Int {
        val end = balancedEnd(code, open, maximumParameterChars?.let { (open + it).coerceAtMost(code.length) } ?: code.length)
        if (maximumParameterChars != null && end <= open) parameterScanIncomplete = true
        return end
    }

    private fun javaParameter(text: String): RouteParameter? {
        val cleaned = ANNOTATION.replace(text, " ").replace("final ", " ").trim()
        val name = IDENTIFIER_TAIL.find(cleaned)?.value ?: return null
        return RouteParameter(name, simpleType(cleaned.removeSuffix(name).trim()))
    }

    /** 제네릭·nullable·패키지를 뗀 단순 타입 이름이다. */
    private fun simpleType(type: String): String =
        type.substringBefore('<').substringBefore('=').trim().removeSuffix("?").substringAfterLast('.').trim()

    private fun collectConstants(): List<RouteConstant> {
        val matches = if (isJava) {
            // 인터페이스 필드는 수식어 없이도 static final이다. 두 패턴이 같은 선언을 잡으면 이름 위치로 한 번만 센다.
            (JAVA_CONSTANT.findAll(masked) + JAVA_INTERFACE_CONSTANT.findAll(masked).filter(::declaredInInterface))
                .distinctBy { it.groups[2]!!.range.first }
        } else KOTLIN_CONSTANT.findAll(masked)
        return matches.mapNotNull { match ->
            val start = match.range.first
            if (enclosingFunction(start) != null) return@mapNotNull null
            val isConst = isJava || match.groupValues[1].isNotBlank()
            val containers = enclosingTypes(start)
            // const가 아닌 val은 최상위 선언만 상수로 본다 — 클래스 속성은 재정의·인스턴스 값일 수 있다.
            if (!isConst && containers.isNotEmpty()) return@mapNotNull null
            val initializerStart = match.range.last + 1
            RouteConstant(
                name = match.groupValues[2],
                containers = containers.map { it.name },
                expression = code.substring(initializerStart, statementEnd(initializerStart)).trim(),
            )
        }.toList()
    }

    /** 선언을 감싸는 가장 안쪽 타입이 Java 인터페이스(또는 `@interface`)인지 본다. */
    private fun declaredInInterface(match: MatchResult): Boolean =
        enclosingTypes(match.range.first).lastOrNull()?.let { masked.startsWith("interface", it.start) } == true

    /** `{` 위치부터 짝이 맞는 `}`의 위치다 — 마스킹 뷰라 문자열 안 중괄호를 세지 않는다. */
    fun braceEnd(open: Int): Int {
        var depth = 0
        for (index in open until masked.length) {
            when (masked[index]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return index }
            }
        }
        return -1
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PACKAGE = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.\\s]*?)\\s*;?\\s*$")
        val IMPORT = Regex("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*(?:\\s*\\.\\s*\\*)?)(?:\\s+as\\s+([A-Za-z_]\\w*))?\\s*;?")
        val TYPE_DECL = Regex("\\b(?:(class|interface|object)\\s+([A-Za-z_]\\w*)|companion\\s+object(?!\\s+[A-Za-z_]))")
        val KOTLIN_FUN = Regex("\\bfun\\b")
        val FUN_HEADER = Regex("^\\s*(?:<[^()]*?>\\s*)?(?:[A-Za-z_][\\w.<>?, ]*\\.\\s*)?[A-Za-z_]\\w*\\s*$")
        val IDENTIFIER_TAIL = Regex("[A-Za-z_]\\w*$")
        val JAVA_METHOD = Regex(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native)\\s+)*" +
                "[A-Za-z_][\\w.<>\\[\\],? ]*\\s+([A-Za-z_]\\w*)\\s*\\([^;{]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{",
        )
        val JAVA_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do")
        val ANNOTATION = Regex("@[A-Za-z_][\\w.]*(?::[A-Za-z_]\\w*)?(?:\\s*\\([^)]*\\))?")
        val PARAMETER_MODIFIERS = Regex("\\b(?:vararg|noinline|crossinline|val|var|private|public|internal|protected|override)\\b")
        val KOTLIN_PARAMETER = Regex("^([A-Za-z_]\\w*)\\s*:\\s*([^=]+)")
        val KOTLIN_CONSTANT = Regex("\\b(const\\s+)?val\\s+([A-Za-z_]\\w*)\\s*(?::\\s*String\\s*)?=(?!=)")
        // Java는 const 수식어가 없다 — 빈 첫 그룹으로 그룹 번호를 Kotlin 패턴과 맞춘다.
        val JAVA_CONSTANT = Regex("\\b()static\\s+final\\s+String\\s+([A-Za-z_]\\w*)\\s*=(?!=)")
        // 인터페이스 몸체의 `String NAME = …`(수식어 생략 가능)이다. 인터페이스 안인지는 [declaredInInterface]가 거른다.
        val JAVA_INTERFACE_CONSTANT = Regex("(?m)^[ \\t]*()(?:(?:public|static|final)\\s+)*String\\s+([A-Za-z_]\\w*)\\s*=(?!=)")
        val DECLARATION_START = Regex(
            "^\\s*(?:@|fun\\b|val\\b|var\\b|class\\b|object\\b|interface\\b|init\\b|constructor\\b|companion\\b|" +
                "private\\b|public\\b|internal\\b|protected\\b|override\\b|enum\\b|data\\b|sealed\\b|abstract\\b|open\\b|" +
                "const\\b|lateinit\\b|suspend\\b|inline\\b|operator\\b|typealias\\b)",
        )
        const val CONTINUATION_END = "+-*/.(,=?:&|%<>!"
        const val CONTINUATION_START = ".+-*/?:&|%)]"
    }
}

/** import 한 줄이다. [path]가 `*`로 끝나면 패키지 전체 import다. */
internal data class RouteImport(val path: String, val alias: String?)

/** 타입 선언의 머리 시작과 몸체 범위다. 몸체가 없으면 [bodyStart]가 -1이다. */
internal data class RouteTypeDecl(val name: String, val start: Int, val bodyStart: Int, val bodyEnd: Int, val headerEnd: Int = bodyStart)

/** 함수 선언의 범위와 매개변수다. [end]는 블록·식 몸체의 끝이다. */
internal data class RouteFunctionDecl(
    val name: String,
    val start: Int,
    val bodyStart: Int,
    val end: Int,
    val parameters: List<RouteParameter>,
)

/** 함수 매개변수 이름과 단순 타입 이름이다. */
internal data class RouteParameter(val name: String, val type: String)

/** 파일 수준 상수다. [containers]는 감싸는 타입 이름 사슬이다. */
internal data class RouteConstant(val name: String, val containers: List<String>, val expression: String)

/** 지역 선언이다. [isVal]이 거짓(`var`)이면 값을 증명하지 않는다. */
internal data class RouteLocal(val isVal: Boolean, val initializer: String)

/**
 * 경로 식을 [UrlPart] 조각으로 푸는 해석기다.
 *
 * 리터럴·Kotlin 템플릿·`+` 연결·같은 파일 상수·같은 함수의 `val`을 따라간다. query 꼬리임을 증명한
 * 지역 `val`은 [UrlPart.QueryTail]로 표시한다. 따라갈 수 없는 식은 [UrlPart.Value]로 남긴다.
 */
internal class RoutePathResolver(private val file: RouteSourceFile) {
    /**
     * [expression]을 [offset] 위치 문맥에서 조각으로 푼다.
     *
     * @param expression 인자·초기식 원문
     * @param offset 식이 쓰인 소스 위치(가림·지역 선언 판정용)
     */
    fun parts(expression: String, offset: Int, depth: Int = 0): List<UrlPart> {
        val trimmed = unwrapParentheses(expression.trim())
        if (depth > MAX_DEPTH || trimmed.isEmpty()) return listOf(UrlPart.Value(trimmed))
        val terms = splitConcatenation(trimmed)
        if (terms.size > 1) return terms.flatMap { parts(it, offset, depth + 1) }
        stringLiteralBody(trimmed)?.let { (body, raw) -> return templateParts(body, raw, offset, depth) }
        return reference(trimmed, offset, depth) ?: listOf(UrlPart.Value(trimmed))
    }

    /** 문자열 리터럴 인자 값을 푼다 — 보간이 있거나 리터럴이 아니면 null이다. */
    fun literalValue(expression: String, offset: Int): String? {
        val resolved = parts(expression, offset)
        return resolved.singleOrNull()?.let { it as? UrlPart.Literal }?.text
            ?: if (resolved.isNotEmpty() && resolved.all { it is UrlPart.Literal }) {
                resolved.joinToString("") { (it as UrlPart.Literal).text }
            } else null
    }

    /** 식별자·`Owner.NAME` 참조를 상수·지역 `val`로 따라간다. */
    private fun reference(expression: String, offset: Int, depth: Int): List<UrlPart>? {
        if (!REFERENCE.matches(expression)) return null
        val name = expression.substringAfterLast('.')
        val qualifier = expression.substringBeforeLast('.', "")
        if (qualifier.isEmpty()) {
            file.localDeclaration(name, offset)?.let { local -> return localParts(expression, local, offset, depth) }
            if (file.isShadowed(name, offset)) return null
        }
        val constant = constantFor(name, qualifier) ?: return null
        return parts(constant.expression, offset, depth + 1).takeIf { resolved -> resolved.none { it is UrlPart.QueryTail } }
    }

    /** 같은 함수 `val`은 query 꼬리 증명을 먼저 하고, 아니면 초기식을 따라간다. `var`는 값이 바뀔 수 있다. */
    private fun localParts(expression: String, local: RouteLocal, offset: Int, depth: Int): List<UrlPart> {
        if (!local.isVal) return listOf(UrlPart.Value(expression))
        if (provesQueryTail(local.initializer, offset)) return listOf(UrlPart.QueryTail(expression))
        val resolved = parts(local.initializer, offset, depth + 1)
        val opaque = resolved.size == 1 && resolved.single() is UrlPart.Value
        return if (opaque) listOf(UrlPart.Value(expression)) else resolved
    }

    /** 이름과 한정자로 파일 상수를 고른다 — 후보가 여럿이면 추측하지 않는다. */
    private fun constantFor(name: String, qualifier: String): RouteConstant? {
        val candidates = file.constants[name].orEmpty()
        val filtered = if (qualifier.isEmpty()) candidates
        else candidates.filter { constant ->
            val owner = qualifier.substringAfterLast('.')
            constant.containers.lastOrNull() == owner ||
                (constant.containers.lastOrNull() == "Companion" && constant.containers.dropLast(1).lastOrNull() == owner)
        }
        return filtered.singleOrNull()
    }

    /**
     * 초기식의 비어 있지 않은 값이 모두 `?`로 시작함을 증명한다(`compose.suffix`).
     *
     * 받는 모양: `"?..."` 리터럴, `"?" + ...` 연결, `prefix = "?"`인 `joinToString`, `let { "?..." }`
     * 람다, 두 가지가 모두 증명되는 `if … else …`. `?.` 사슬은 `.orEmpty()`·`?: ""` 대체값이 있어야 한다.
     */
    fun provesQueryTail(initializer: String, offset: Int): Boolean {
        val text = initializer.trim()
        val (core, fallback) = splitEmptyFallback(text)
        if (isEmptyLiteral(core)) return true
        if ("?." in core && !fallback) return false
        return startsWithQuestionMark(core, offset) || provesConditional(core, offset)
    }

    private fun splitEmptyFallback(text: String): Pair<String, Boolean> {
        ORDER_EMPTY_SUFFIXES.forEach { suffix ->
            if (text.endsWith(suffix)) return text.removeSuffix(suffix).trim() to true
        }
        val elvis = Regex("\\?:\\s*\"\"$").find(text)
        return if (elvis != null) text.substring(0, elvis.range.first).trim() to true else text to false
    }

    private fun startsWithQuestionMark(core: String, offset: Int): Boolean {
        val first = splitConcatenation(unwrapParentheses(core)).first()
        stringLiteralBody(first)?.let { (body, _) -> return body.startsWith('?') }
        JOIN_TO_STRING.find(core)?.let { match ->
            val open = match.range.last
            val close = balancedEnd(core, open)
            if (close == core.lastIndex) {
                val prefix = callArguments(core, open, close).firstNamed("prefix") ?: return false
                return literalValue(prefix, offset)?.startsWith('?') == true
            }
        }
        val lambda = LAMBDA_OPEN.findAll(core).lastOrNull { templateExpressionEnd(core, it.range.last) == core.lastIndex }
        if (lambda != null) {
            val inside = core.substring(lambda.range.last + 1, core.length - 1).trim()
            val body = LAMBDA_PARAMETER.find(inside)?.let { inside.substring(it.range.last + 1) } ?: inside
            return body.isNotBlank() && startsWithQuestionMark(body.trim(), offset)
        }
        return false
    }

    /** `if (c) A else B`의 두 가지가 모두 `?` 시작이거나 빈 문자열인지 본다. */
    private fun provesConditional(core: String, offset: Int): Boolean {
        if (!core.startsWith("if")) return false
        val open = core.indexOf('(')
        val close = balancedEnd(core, open).takeIf { open >= 0 && it > open } ?: return false
        val branches = core.substring(close + 1).split(ELSE, limit = 2)
        if (branches.size != 2) return false
        return branches.all { branch ->
            val value = branch.trim().removeSurrounding("{", "}").trim()
            isEmptyLiteral(value) || startsWithQuestionMark(value, offset)
        }
    }

    private fun isEmptyLiteral(text: String): Boolean = text == "\"\"" || text == "\"\"\"\"\"\""

    /** Kotlin 템플릿 본문을 리터럴·보간 조각으로 나눈다. Java 문자열에는 보간이 없다. */
    private fun templateParts(body: String, raw: Boolean, offset: Int, depth: Int): List<UrlPart> {
        if (file.isJava) return listOf(UrlPart.Literal(decodeLiteral(body)))
        val result = mutableListOf<UrlPart>()
        val text = StringBuilder()
        var index = 0
        fun flush() { if (text.isNotEmpty()) { result += UrlPart.Literal(text.toString()); text.clear() } }
        while (index < body.length) {
            val character = body[index]
            when {
                body.isDollarLiteral(index) -> { text.append('$'); index += 6 }
                !raw && character == '\\' -> {
                    val width = if (body.getOrNull(index + 1) == 'u') 6 else 2
                    text.append(decodeLiteral(body.substring(index, (index + width).coerceAtMost(body.length))))
                    index += width
                }
                character == '$' && body.getOrNull(index + 1) == '{' -> {
                    val close = templateExpressionEnd(body, index + 1)
                    flush()
                    result += interpolation(body.substring(index + 2, close), offset, depth)
                    index = close + 1
                }
                character == '$' && body.getOrNull(index + 1)?.let { it == '_' || it.isLetter() } == true -> {
                    val name = IDENTIFIER_HEAD.find(body, index + 1)!!.value
                    flush()
                    result += interpolation(name, offset, depth)
                    index += 1 + name.length
                }
                else -> { text.append(character); index++ }
            }
        }
        flush()
        return result
    }

    /** 보간 식 하나를 따라간다 — 참조가 아니면 값 조각이다. */
    private fun interpolation(expression: String, offset: Int, depth: Int): List<UrlPart> {
        val trimmed = expression.trim()
        return reference(trimmed, offset, depth + 1) ?: listOf(UrlPart.Value(trimmed))
    }

    private companion object {
        const val MAX_DEPTH = 8
        val REFERENCE = Regex("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*")
        val IDENTIFIER_HEAD = Regex("\\G[A-Za-z_]\\w*")
        val JOIN_TO_STRING = Regex("\\bjoinToString\\s*\\(")
        val LAMBDA_OPEN = Regex("\\.\\s*(?:let|run)\\s*\\{")
        val LAMBDA_PARAMETER = Regex("^[A-Za-z_]\\w*\\s*->")
        val ELSE = Regex("\\belse\\b")
        val ORDER_EMPTY_SUFFIXES = listOf(".orEmpty()", "?.orEmpty()")
    }
}

/** 문자열 리터럴 전체면 (본문, raw 여부)를 돌려준다. 템플릿 안의 중첩 문자열도 건너뛴다. */
internal fun stringLiteralBody(expression: String): Pair<String, Boolean>? {
    val text = expression.trim()
    if (!text.startsWith('"')) return null
    val end = skipString(text, 0)
    if (end != text.length) return null
    return if (text.startsWith("\"\"\"")) text.substring(3, text.length - 3) to true
    else text.substring(1, text.length - 1) to false
}

/**
 * [start]의 `"`에서 시작하는 문자열 리터럴 끝 다음 위치를 돌려준다. Kotlin `${…}` 안의 중첩
 * 문자열과 괄호를 따라가며, 닫히지 않으면 텍스트 길이를 돌려준다.
 */
internal fun skipString(text: String, start: Int): Int {
    val raw = text.startsWith("\"\"\"", start)
    var index = start + if (raw) 3 else 1
    while (index < text.length) {
        when {
            raw && text.startsWith("\"\"\"", index) -> {
                var end = index + 3
                while (end < text.length && text[end] == '"') end++ // 닫는 따옴표 뒤의 따옴표는 본문에 속한다.
                return end
            }
            !raw && text[index] == '\\' -> index += 2
            !raw && text[index] == '"' -> return index + 1
            text[index] == '$' && text.getOrNull(index + 1) == '{' -> index = templateExpressionEnd(text, index + 1) + 1
            else -> index++
        }
    }
    return text.length
}

/** `${`의 `{` 위치부터 짝이 맞는 `}` 위치다. 중첩 문자열·중괄호를 따라간다. */
internal fun templateExpressionEnd(text: String, open: Int): Int {
    var depth = 0
    var index = open
    while (index < text.length) {
        when (text[index]) {
            '"' -> { index = skipString(text, index); continue }
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return index }
        }
        index++
    }
    return text.length - 1
}

/** 최상위 `+`로 연결된 항을 나눈다. 문자열·괄호 안의 `+`와 `++`·`+=`는 나누지 않는다. */
internal fun splitConcatenation(expression: String): List<String> {
    val terms = mutableListOf<String>()
    var depth = 0
    var start = 0
    var index = 0
    while (index < expression.length) {
        val character = expression[index]
        when {
            character == '"' -> { index = skipString(expression, index); continue }
            character == '\'' -> { index = skipCharLiteral(expression, index); continue }
            character in "([{" -> depth++
            character in ")]}" -> depth--
            character == '+' && depth == 0 && isBinaryPlus(expression, index) -> {
                terms += expression.substring(start, index).trim()
                start = index + 1
            }
        }
        index++
    }
    terms += expression.substring(start).trim()
    return terms.filter { it.isNotEmpty() }.ifEmpty { listOf(expression.trim()) }
}

private fun skipCharLiteral(text: String, start: Int): Int {
    var index = start + 1
    while (index < text.length && text[index] != '\'') index += if (text[index] == '\\') 2 else 1
    return index + 1
}

private fun isBinaryPlus(text: String, index: Int): Boolean {
    if (text.getOrNull(index + 1) == '+' || text.getOrNull(index + 1) == '=' || text.getOrNull(index - 1) == '+') return false
    val before = text.substring(0, index).trimEnd()
    return before.isNotEmpty() && before.last() !in "(=,+-*/%!<>&|?:"
}

/** 식 전체를 감싼 괄호를 벗긴다. */
internal fun unwrapParentheses(expression: String): String {
    var text = expression
    while (text.startsWith('(') && balancedEnd(text, 0) == text.lastIndex) text = text.substring(1, text.length - 1).trim()
    return text
}
