package dev.kartograph.index

/**
 * Spring 소스 reader가 쓰는 어휘 도구다. [RouteSourceFile]의 주석 제거·문자열 마스킹 뷰 위에서 동작한다.
 *
 * 컴파일러를 실행하지 않으므로 선언 경계는 괄호·중괄호 깊이와 선언 키워드로만 정한다. 모양을 증명하지 못하면
 * 선언을 만들지 않는다.
 */
internal object SpringSourceSyntax {
    /** 소스에서 찾은 어노테이션 토큰이다. [arguments]는 괄호 안 원문(주석 제거 뷰)이고, 괄호가 없으면 null이다. */
    data class AnnotationToken(val name: String, val useSite: String?, val offset: Int, val arguments: String?)

    /** 괄호(`(`·`[`)와 중괄호 깊이를 한 번에 센 표다. `depth[i]`는 i번째 글자 앞의 깊이다. */
    class Depths(masked: String) {
        val braces = IntArray(masked.length + 1)
        val parens = IntArray(masked.length + 1)

        init {
            var brace = 0
            var paren = 0
            masked.forEachIndexed { index, character ->
                braces[index] = brace
                parens[index] = paren
                when (character) {
                    '{' -> brace++
                    '}' -> brace--
                    '(', '[' -> paren++
                    ')', ']' -> paren--
                }
            }
            braces[masked.length] = brace
            parens[masked.length] = paren
        }
    }

    /**
     * [position] 앞에서 같은 깊이의 `;`·`{`·`}`를 거꾸로 찾아 선언 머리가 시작하는 위치를 돌려준다.
     * 괄호 안(어노테이션 인자 배열의 중괄호 등)은 건너뛴다.
     */
    fun segmentStart(masked: String, position: Int): Int {
        var paren = 0
        var index = position - 1
        while (index >= 0) {
            when (masked[index]) {
                ')', ']' -> paren++
                '(', '[' -> paren--
                ';', '{', '}' -> if (paren <= 0) return index + 1
            }
            index--
        }
        return 0
    }

    /**
     * [start]부터 [end] 앞까지 괄호 깊이 0의 어노테이션 토큰을 모은다.
     *
     * Kotlin은 문장 끝 표지가 없어 앞 선언의 어노테이션이 같은 구간에 섞일 수 있다. 그래서 깊이 0에서 선언 키워드
     * (`val`·`fun`·`class` 등)를 만나면 그때까지 모은 토큰을 버린다. `@file:` 대상과 Java `@interface`는 어노테이션이
     * 아니므로 모으지 않는다.
     *
     * @param resetOnDeclarations 거짓이면 선언 키워드에서 버리지 않는다 — 생성자 매개변수(`@get:AliasFor(…) val x`)처럼
     *   구간 안에 자기 선언 키워드가 오는 경우다
     */
    fun annotations(file: RouteSourceFile, start: Int, end: Int, resetOnDeclarations: Boolean = true): List<AnnotationToken> {
        val tokens = mutableListOf<Located>()
        var index = start
        var paren = 0
        while (index < end) {
            val character = file.masked[index]
            when {
                character == '(' || character == '[' -> paren++
                character == ')' || character == ']' -> paren--
                paren == 0 && character == '@' -> {
                    val token = annotationAt(file, index)
                    if (token != null) { tokens += token; index = token.end; continue }
                }
                paren == 0 && resetOnDeclarations && !file.isJava && startsDeclarationKeyword(file.masked, index) -> tokens.clear()
            }
            index++
        }
        return tokens.map { it.token }
    }

    private class Located(val token: AnnotationToken, val end: Int)

    private val ANNOTATION_NAME = Regex("\\G@(?:([A-Za-z]+):)?([A-Za-z_][\\w.]*)")

    private fun annotationAt(file: RouteSourceFile, at: Int): Located? {
        val match = ANNOTATION_NAME.find(file.masked, at) ?: return null
        val name = match.groupValues[2].trimEnd('.')
        if (name == "interface" || match.groupValues[1] == "file") return null
        var after = match.range.last + 1
        while (after < file.masked.length && file.masked[after].isWhitespace() && file.masked[after] != '\n') after++
        if (file.masked.getOrNull(after) != '(') return Located(AnnotationToken(name, match.groupValues[1].ifEmpty { null }, at, null), match.range.last + 1)
        val close = balancedEnd(file.code, after).takeIf { it > after } ?: return null
        val token = AnnotationToken(name, match.groupValues[1].ifEmpty { null }, at, file.code.substring(after + 1, close))
        return Located(token, close + 1)
    }

    private val DECLARATION_KEYWORD = Regex("\\G(?:val|var|fun|class|object|interface|init|constructor|typealias)\\b")

    private fun startsDeclarationKeyword(masked: String, index: Int): Boolean =
        (index == 0 || !masked[index - 1].isLetterOrDigit() && masked[index - 1] != '_' && masked[index - 1] != '@') &&
            DECLARATION_KEYWORD.find(masked, index) != null

    /**
     * 최상위 쉼표로 인자를 나눈다. `()`·`[]`·`{}` 안과 문자열 안의 쉼표는 나누지 않는다 — Java 배열 값
     * `{"/a", "/b"}`를 한 인자로 둔다.
     */
    fun splitTopLevel(text: String): List<String> {
        val values = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '"' -> { index = skipString(text, index); continue }
                character == '\'' -> { index = skipQuoted(text, index); continue }
                character in "([{" -> depth++
                character in ")]}" -> depth--
                character == ',' && depth == 0 -> { values += text.substring(start, index).trim(); start = index + 1 }
            }
            index++
        }
        values += text.substring(start).trim()
        return values.filter { it.isNotEmpty() }
    }

    private fun skipQuoted(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length && text[index] != '\'') index += if (text[index] == '\\') 2 else 1
        return index + 1
    }

    private val NAMED = Regex("^\\s*([A-Za-z_]\\w*)\\s*=(?!=)")

    /** `name = value`면 (이름, 값), 아니면 (null, 원문)이다. */
    fun namedArgument(argument: String): Pair<String?, String> {
        val match = NAMED.find(argument) ?: return null to argument.trim()
        return match.groupValues[1] to argument.substring(match.range.last + 1).trim()
    }

    /**
     * 배열 값의 원소 식들이다. Java `{a, b}`, Kotlin `[a, b]`·`arrayOf(a, b)`를 푼다. 배열이 아니면 식 하나다.
     */
    fun arrayElements(expression: String): List<String> {
        val text = expression.trim()
        val body = when {
            text.startsWith('{') && text.endsWith('}') -> text.substring(1, text.length - 1)
            text.startsWith('[') && text.endsWith(']') -> text.substring(1, text.length - 1)
            ARRAY_OF.matches(text) -> text.substring(text.indexOf('(') + 1, text.length - 1)
            else -> return listOf(text)
        }
        return splitTopLevel(body)
    }

    private val ARRAY_OF = Regex("^arrayOf\\s*(?:<[^>]*>)?\\s*\\(.*\\)$", RegexOption.DOT_MATCHES_ALL)
}
