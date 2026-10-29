package dev.kartograph.index

import dev.kartograph.index.SpringSourceSyntax.AnnotationToken

/**
 * Kotlin/Java 소스에서 Spring 매핑에 필요한 선언을 읽는다.
 *
 * 두 번 돈다. 먼저 모든 파일의 타입 이름·상수·Kotlin 어노테이션 매개변수 순서를 색인하고, 그다음 색인으로
 * 타입 이름과 상수 참조를 풀어 [SpringType]을 만든다. 파일 밖 상수(`import static`, 다른 파일의 `object`·
 * `companion object`·Java 인터페이스 상수)도 이 색인으로 따라간다. 풀지 못한 값은 [SpringValue.Unresolved]다.
 */
internal class SpringSourceReader(private val files: List<RouteSourceFile>) {
    private val depthCache = HashMap<String, SpringSourceSyntax.Depths>()
    private val headers: List<TypeHeader> = files.flatMap { file -> file.types.mapNotNull { header(file, it) } }
    private val typesByName: Map<String, TypeHeader> = headers.associateBy { it.name }
    private val constants: Map<String, ConstantDefinition> = collectConstants()
    private val values = SpringSourceValues(this)

    /** 타입 하나의 머리 정보다. [kotlinParameters]는 Kotlin 어노테이션 class의 주 생성자 매개변수 이름 순서다. */
    internal class TypeHeader(
        val file: RouteSourceFile,
        val decl: RouteTypeDecl,
        val name: String,
        val internalName: String,
        val kind: SpringTypeKind,
        val isAbstract: Boolean,
        val supertypeNames: List<String>,
        val isJavaSuperclassSyntax: Boolean,
        val annotations: List<AnnotationToken>,
        val kotlinParameters: List<KotlinParameter>,
    )

    /** Kotlin 주 생성자 매개변수다(어노테이션 class 속성). */
    internal class KotlinParameter(val name: String, val annotations: List<AnnotationToken>, val defaultExpression: String?, val offset: Int)

    /** 상수 선언 하나다. [offset]은 초기식 문맥(감싸는 타입) 판정에 쓴다. */
    internal class ConstantDefinition(val file: RouteSourceFile, val expression: String, val offset: Int)

    /** 모든 타입을 모델로 만든다. */
    fun read(): List<SpringType> = headers.map { header ->
        SpringType(
            name = header.name,
            internalName = header.internalName,
            kind = header.kind,
            isAbstract = header.isAbstract,
            superclass = superclassOf(header),
            interfaces = interfacesOf(header),
            annotations = header.annotations.map { annotation(it, header.file) },
            methods = methodsOf(header),
            sourcePath = header.file.relative,
            isTest = header.file.isTest,
        )
    }

    // ---- 타입 머리 ----

    private fun header(file: RouteSourceFile, decl: RouteTypeDecl): TypeHeader? {
        val keyword = if (decl.start > 0 && file.masked[decl.start - 1] == '@') decl.start - 1 else decl.start
        val segment = SpringSourceSyntax.segmentStart(file.masked, keyword)
        val modifiers = topLevelWords(file.masked, segment, keyword)
        val chain = file.enclosingTypes(decl.start).filter { it !== decl }.map { it.name }
        val name = (listOf(file.packageName).filter(String::isNotEmpty) + chain + decl.name).joinToString(".")
        val internalName = (listOf(file.packageName.replace('.', '/')).filter(String::isNotEmpty) + (chain + decl.name).joinToString("$"))
            .joinToString("/")
        val kind = kindOf(file, decl, keyword, modifiers)
        val headerEnd = if (decl.bodyStart >= 0) decl.bodyStart else file.statementEnd(decl.start)
        val nameEnd = nameEnd(file, decl)
        return TypeHeader(file, decl, name, internalName, kind,
            isAbstract = kind != SpringTypeKind.CLASS || "abstract" in modifiers || "sealed" in modifiers,
            supertypeNames = supertypes(file, nameEnd, headerEnd), isJavaSuperclassSyntax = file.isJava,
            annotations = SpringSourceSyntax.annotations(file, segment, keyword),
            kotlinParameters = if (file.isJava) emptyList() else kotlinParameters(file, nameEnd, headerEnd))
    }

    private fun kindOf(file: RouteSourceFile, decl: RouteTypeDecl, keyword: Int, modifiers: Set<String>): SpringTypeKind {
        val word = file.masked.substring(decl.start).takeWhile { it.isLetter() }
        return when {
            keyword != decl.start || "annotation" in modifiers -> SpringTypeKind.ANNOTATION
            "enum" in modifiers -> SpringTypeKind.ENUM
            word == "interface" -> SpringTypeKind.INTERFACE
            else -> SpringTypeKind.CLASS
        }
    }

    /** 선언 이름 토큰 끝 위치다. `companion object`는 이름이 없어 키워드 끝이다. */
    private fun nameEnd(file: RouteSourceFile, decl: RouteTypeDecl): Int {
        val keywordEnd = decl.start + file.masked.substring(decl.start).takeWhile { it.isLetter() || it.isWhitespace() }.length
        if (decl.name == "Companion" && file.masked.startsWith("companion", decl.start)) return keywordEnd
        val nameAt = file.masked.indexOf(decl.name, decl.start)
        return if (nameAt < 0) keywordEnd else nameAt + decl.name.length
    }

    /** 괄호 밖 낱말 집합이다(수식어 판정용). */
    private fun topLevelWords(masked: String, start: Int, end: Int): Set<String> {
        val words = mutableSetOf<String>()
        var paren = 0
        var index = start
        while (index < end) {
            val character = masked[index]
            when {
                character == '(' -> paren++
                character == ')' -> paren--
                paren == 0 && character.isLetter() && (index == 0 || !masked[index - 1].isLetterOrDigit() && masked[index - 1] != '@' && masked[index - 1] != '.') -> {
                    val word = masked.substring(index).takeWhile { it.isLetterOrDigit() || it == '_' }
                    words += word
                    index += word.length
                    continue
                }
            }
            index++
        }
        return words
    }

    /** 머리의 상위 타입 이름들이다. Java는 `extends`·`implements`, Kotlin은 주 생성자 뒤 `:` 목록이다. */
    private fun supertypes(file: RouteSourceFile, nameEnd: Int, headerEnd: Int): List<String> {
        if (nameEnd >= headerEnd) return emptyList()
        val header = stripGenerics(file.masked.substring(nameEnd, headerEnd))
        val list = if (file.isJava) javaSupertypes(header) else kotlinSupertypes(header)
        return list.map { entry -> entry.substringBefore('(').substringBefore(" by ").trim() }.filter { REFERENCE.matches(it) }
    }

    private fun javaSupertypes(header: String): List<String> {
        val extends = Regex("\\bextends\\s+([^{]*?)(?=\\bimplements\\b|$)").find(header)?.groupValues?.get(1).orEmpty()
        val implements = Regex("\\bimplements\\s+([^{]*)").find(header)?.groupValues?.get(1).orEmpty()
        return (extends.split(',') + implements.split(',')).map(String::trim).filter(String::isNotEmpty)
    }

    private fun kotlinSupertypes(header: String): List<String> {
        var index = 0
        val text = header.trimEnd()
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> depth--
                ':' -> if (depth == 0) return SpringSourceSyntax.splitTopLevel(text.substring(index + 1))
            }
            index++
        }
        return emptyList()
    }

    /** 제네릭 `<…>`를 지운다. 람다 화살표 `->`의 `>`는 세지 않는다. */
    private fun stripGenerics(text: String): String = buildString {
        var depth = 0
        text.forEachIndexed { index, character ->
            when {
                character == '<' -> depth++
                character == '>' && text.getOrNull(index - 1) != '-' && depth > 0 -> depth--
                depth == 0 -> append(character)
            }
        }
    }

    /** Kotlin 주 생성자 매개변수다. 어노테이션 class 속성 이름·`@AliasFor`·기본값을 읽는 데 쓴다. */
    private fun kotlinParameters(file: RouteSourceFile, nameEnd: Int, headerEnd: Int): List<KotlinParameter> {
        val open = (nameEnd until headerEnd).firstOrNull { file.masked[it] == '(' } ?: return emptyList()
        if (file.masked.substring(nameEnd, open).any { it == ':' || it == '{' }) return emptyList()
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return emptyList()
        return parameterRanges(file, open, close).mapNotNull { (start, end) -> kotlinParameter(file, start, end) }
    }

    /** 괄호 안 최상위 쉼표로 나눈 (시작, 끝) 범위다. */
    private fun parameterRanges(file: RouteSourceFile, open: Int, close: Int): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = open + 1
        for (index in open + 1 until close) {
            when (file.masked[index]) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}' -> depth--
                '>' -> if (file.masked[index - 1] != '-') depth--
                ',' -> if (depth == 0) { ranges += start to index; start = index + 1 }
            }
        }
        ranges += start to close
        return ranges.filter { (from, to) -> file.masked.substring(from, to).isNotBlank() }
    }

    private fun kotlinParameter(file: RouteSourceFile, start: Int, end: Int): KotlinParameter? {
        val tokens = SpringSourceSyntax.annotations(file, start, end, resetOnDeclarations = false)
        val afterAnnotations = tokens.lastOrNull()?.let { token -> annotationEnd(file, token) } ?: start
        val text = file.code.substring(afterAnnotations, end)
        val match = KOTLIN_PARAMETER.find(text) ?: return null
        val equals = topLevelEquals(text)
        return KotlinParameter(match.groupValues[1], tokens, equals?.let { text.substring(it + 1).trim() }, afterAnnotations + match.range.first)
    }

    private fun annotationEnd(file: RouteSourceFile, token: AnnotationToken): Int {
        val nameEnd = token.offset + 1 + (token.useSite?.let { it.length + 1 } ?: 0) + token.name.length
        if (token.arguments == null) return nameEnd
        val open = file.masked.indexOf('(', nameEnd)
        return balancedEnd(file.code, open) + 1
    }

    private fun topLevelEquals(text: String): Int? {
        var depth = 0
        text.forEachIndexed { index, character ->
            when (character) {
                '(', '[', '{', '<' -> depth++
                ')', ']', '}', '>' -> depth--
                '=' -> if (depth == 0 && text.getOrNull(index + 1) != '=') return index
            }
        }
        return null
    }

    private fun superclassOf(header: TypeHeader): String? {
        if (header.kind != SpringTypeKind.CLASS) return null
        val resolved = header.supertypeNames.mapNotNull { resolveType(it, header.file, header.decl.start) }
        if (header.isJavaSuperclassSyntax) {
            val extends = Regex("\\bextends\\s+([\\w.]+)").find(stripGenerics(headerText(header)))?.groupValues?.get(1) ?: return null
            return resolveType(extends, header.file, header.decl.start)
        }
        return resolved.firstOrNull { typesByName[it]?.kind == SpringTypeKind.CLASS }
    }

    private fun interfacesOf(header: TypeHeader): List<String> {
        val all = header.supertypeNames.mapNotNull { resolveType(it, header.file, header.decl.start) }
        val superclass = superclassOf(header)
        return all.filter { it != superclass }
    }

    private fun headerText(header: TypeHeader): String {
        val end = if (header.decl.bodyStart >= 0) header.decl.bodyStart else header.file.statementEnd(header.decl.start)
        return header.file.masked.substring(nameEnd(header.file, header.decl), end)
    }

    // ---- 멤버 ----

    private fun methodsOf(header: TypeHeader): List<SpringMethod> {
        val methods = if (header.file.isJava) javaMethods(header) else kotlinFunctions(header)
        if (header.kind != SpringTypeKind.ANNOTATION || header.file.isJava) return methods
        return methods + header.kotlinParameters.map { parameter ->
            val annotations = parameter.annotations.map { annotation(it, header.file) }
            SpringMethod(parameter.name, null, 0, emptyList(), isAbstract = true, nameOffset = parameter.offset,
                aliases = aliasTargets(annotations, parameter.annotations, header.file),
                defaultValue = parameter.defaultExpression?.let { values.value(it, header.file, parameter.offset) })
        }
    }

    /** [header] 몸체 바로 아래 깊이에 있는 위치인지 본다. */
    private fun atMemberDepth(header: TypeHeader, depths: SpringSourceSyntax.Depths, position: Int): Boolean =
        header.decl.bodyStart >= 0 && position > header.decl.bodyStart && position < header.decl.bodyEnd &&
            depths.braces[position] == depths.braces[header.decl.bodyStart] + 1 && depths.parens[position] == depths.parens[header.decl.bodyStart]

    private fun kotlinFunctions(header: TypeHeader): List<SpringMethod> {
        val file = header.file
        val depths = depthsOf(file)
        return KOTLIN_FUN.findAll(file.masked).filter { atMemberDepth(header, depths, it.range.first) }.mapNotNull { match ->
            val open = file.masked.indexOf('(', match.range.last).takeIf { it >= 0 } ?: return@mapNotNull null
            val headerText = file.masked.substring(match.range.last + 1, open)
            if (!FUN_HEADER.matches(headerText)) return@mapNotNull null
            val name = IDENTIFIER_TAIL.find(headerText.trimEnd())?.value ?: return@mapNotNull null
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@mapNotNull null
            val segment = SpringSourceSyntax.segmentStart(file.masked, match.range.first)
            val modifiers = topLevelWords(file.masked, segment, match.range.first)
            val tokens = SpringSourceSyntax.annotations(file, segment, match.range.first)
            SpringMethod(name, null, parameterCount(file, open, close), tokens.map { annotation(it, file) },
                isAbstract = "abstract" in modifiers || (header.kind == SpringTypeKind.INTERFACE && !hasKotlinBody(file, close + 1)),
                nameOffset = match.range.last + 1 + headerText.lastIndexOf(name))
        }.toList()
    }

    /** 함수 머리 뒤에 블록·식 몸체가 있는지 본다. */
    private fun hasKotlinBody(file: RouteSourceFile, from: Int): Boolean {
        var index = from
        var depth = 0
        while (index < file.masked.length) {
            when (file.masked[index]) {
                '(', '<' -> depth++
                ')', '>' -> depth--
                '{' -> if (depth <= 0) return true
                '=' -> if (depth <= 0) return true
                '}', ';' -> if (depth <= 0) return false
                '\n' -> if (depth <= 0 && !file.masked.substring(index + 1).trimStart().let { it.startsWith("{") || it.startsWith("=") || it.startsWith(":") }) {
                    val before = file.masked.substring(from, index)
                    if (!before.trimEnd().endsWith(":")) return false
                }
            }
            index++
        }
        return false
    }

    private fun javaMethods(header: TypeHeader): List<SpringMethod> {
        val file = header.file
        val depths = depthsOf(file)
        val simpleName = header.decl.name
        return JAVA_CALL_SHAPE.findAll(file.masked).filter { atMemberDepth(header, depths, it.range.first) }.mapNotNull { match ->
            val name = match.groupValues[1]
            if (name in JAVA_KEYWORDS || name == simpleName || !precededByType(file.masked, match.range.first)) return@mapNotNull null
            val open = match.range.last
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@mapNotNull null
            val terminator = javaTerminator(file.masked, close + 1) ?: return@mapNotNull null
            val segment = SpringSourceSyntax.segmentStart(file.masked, match.range.first)
            val tokens = SpringSourceSyntax.annotations(file, segment, match.range.first)
            val modifiers = topLevelWords(file.masked, segment, match.range.first)
            val annotations = tokens.map { annotation(it, file) }
            val defaultExpression = if (terminator.second == "default") javaDefault(file, terminator.first) else null
            SpringMethod(name, null, parameterCount(file, open, close), annotations,
                isAbstract = terminator.second != "{" && "static" !in modifiers || "abstract" in modifiers,
                nameOffset = match.range.first, aliases = aliasTargets(annotations, tokens, file),
                defaultValue = defaultExpression?.let { values.value(it, file, match.range.first) })
        }.toList()
    }

    /** 이름 앞이 반환 타입 모양(식별자·`>`·`]`)이고 `new`·`return` 같은 식 문맥이 아닌지 본다. */
    private fun precededByType(masked: String, nameStart: Int): Boolean {
        var index = nameStart - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        if (index < 0) return false
        val character = masked[index]
        if (!(character.isLetterOrDigit() || character == '_' || character == '>' || character == ']')) return false
        val word = masked.substring(0, index + 1).takeLastWhile { it.isLetterOrDigit() || it == '_' }
        return word !in EXPRESSION_KEYWORDS
    }

    /** `)` 뒤의 `{`·`;`·`default` 종결자와 그 위치다. `throws` 절은 건너뛴다. 종결자가 아니면 null이다. */
    private fun javaTerminator(masked: String, from: Int): Pair<Int, String>? {
        var index = from
        while (index < masked.length && masked[index].isWhitespace()) index++
        if (masked.startsWith("throws", index)) {
            while (index < masked.length && masked[index] != '{' && masked[index] != ';') index++
        }
        return when {
            index >= masked.length -> null
            masked[index] == '{' -> index to "{"
            masked[index] == ';' -> index to ";"
            masked.startsWith("default", index) -> index + "default".length to "default"
            else -> null
        }
    }

    private fun javaDefault(file: RouteSourceFile, from: Int): String? {
        val end = file.masked.indexOf(';', from).takeIf { it > from } ?: return null
        return file.code.substring(from, end).trim()
    }

    /** 매개변수 수다. 제네릭 인자의 쉼표는 세지 않는다. */
    private fun parameterCount(file: RouteSourceFile, open: Int, close: Int): Int = parameterRanges(file, open, close).size

    private fun depthsOf(file: RouteSourceFile): SpringSourceSyntax.Depths =
        depthCache.getOrPut(file.relative) { SpringSourceSyntax.Depths(file.masked) }

    // ---- 어노테이션 ----

    /** 토큰을 모델 어노테이션으로 바꾼다. Kotlin 위치 인자는 대상 선언의 매개변수 순서로 이름을 붙인다. */
    fun annotation(token: AnnotationToken, file: RouteSourceFile): SpringAnnotation {
        val type = resolveType(token.name, file, token.offset) ?: token.name
        val arguments = token.arguments?.let(SpringSourceSyntax::splitTopLevel).orEmpty().map(SpringSourceSyntax::namedArgument)
        val positional = arguments.filter { it.first == null }.map { it.second }
        val named = arguments.mapNotNull { (name, value) -> name?.let { it to values.value(value, file, token.offset) } }.toMap()
        return SpringAnnotation(type, named + positionalAttributes(type, positional, file, token.offset), token.offset)
    }

    /** 위치 인자의 이름이다. Kotlin 어노테이션 class면 매개변수 순서, 그 밖(Java·프레임워크)은 `value` 가변 인자다. */
    private fun positionalAttributes(type: String, positional: List<String>, file: RouteSourceFile, offset: Int): Map<String, SpringValue> {
        if (positional.isEmpty()) return emptyMap()
        val parameters = typesByName[type]?.kotlinParameters.orEmpty()
        if (parameters.isNotEmpty()) {
            return positional.zip(parameters).associate { (value, parameter) -> parameter.name to values.value(value, file, offset) }
        }
        val joined = if (positional.size == 1) positional.single() else positional.joinToString(", ", "[", "]")
        return mapOf("value" to values.value(joined, file, offset))
    }

    /** `@AliasFor(annotation = X.class, attribute = "y")`를 대상으로 바꾼다. */
    private fun aliasTargets(annotations: List<SpringAnnotation>, tokens: List<AnnotationToken>, file: RouteSourceFile): List<SpringAliasTarget> =
        annotations.zip(tokens).filter { it.first.type == SpringAnnotations.ALIAS_FOR }.map { (annotation, token) ->
            val arguments = token.arguments?.let(SpringSourceSyntax::splitTopLevel).orEmpty().map(SpringSourceSyntax::namedArgument)
            val target = arguments.firstOrNull { it.first == "annotation" }?.second?.let { classReference(it, file, token.offset) }
            val attribute = listOf("attribute", "value").firstNotNullOfOrNull { key ->
                (annotation.attributes[key] as? SpringValue.Strings)?.values?.singleOrNull()
            }.orEmpty()
            SpringAliasTarget(target, attribute)
        }

    private fun classReference(expression: String, file: RouteSourceFile, offset: Int): String? {
        val name = expression.trim().removeSuffix("::class").removeSuffix(".class").trim()
        return resolveType(name, file, offset)
    }

    // ---- 이름 해석 ----

    /**
     * [file]의 [offset] 문맥에서 타입 이름을 점 이름으로 푼다 — 감싸는 타입의 중첩 타입, 명시 import(별칭 포함),
     * 같은 패키지, 와일드카드 import 순서다. 이미 패키지가 붙은 이름은 그대로다.
     *
     * @return 풀지 못하면 null
     */
    fun resolveType(name: String, file: RouteSourceFile, offset: Int): String? {
        val segments = name.split('.').map(String::trim)
        if (segments.size > 1 && segments.first().firstOrNull()?.isLowerCase() == true) return segments.joinToString(".")
        val first = resolveSimpleType(segments.first(), file, offset) ?: return null
        return (listOf(first) + segments.drop(1)).joinToString(".")
    }

    private fun resolveSimpleType(simple: String, file: RouteSourceFile, offset: Int): String? {
        val enclosing = file.enclosingTypes(offset).reversed().map { typeName(file, it) }
        enclosing.firstOrNull { it.substringAfterLast('.') == simple }?.let { return it }
        enclosing.map { "$it.$simple" }.firstOrNull(typesByName::containsKey)?.let { return it }
        file.imports.firstOrNull { it.alias == simple || (it.alias == null && it.path.substringAfterLast('.') == simple) }?.let { return it.path }
        val samePackage = listOf(file.packageName, simple).filter(String::isNotEmpty).joinToString(".")
        if (samePackage in typesByName) return samePackage
        return file.imports.filter { it.path.endsWith(".*") }.map { it.path.removeSuffix("*") + simple }
            .firstOrNull { it in typesByName || it in KNOWN_EXTERNAL_TYPES }
    }

    private fun typeName(file: RouteSourceFile, decl: RouteTypeDecl): String =
        (listOf(file.packageName).filter(String::isNotEmpty) + file.enclosingTypes(decl.start).filter { it !== decl }.map { it.name } + decl.name)
            .joinToString(".")

    /**
     * 상수 참조를 선언으로 푼다 — 감싸는 타입(과 그 companion)·상위 타입, 같은 패키지 최상위, 명시·와일드카드 import,
     * `Type.NAME`·`Type.Companion.NAME` 한정 참조 순서다.
     */
    fun resolveConstant(reference: String, file: RouteSourceFile, offset: Int): ConstantDefinition? {
        val name = reference.substringAfterLast('.')
        val qualifier = reference.substringBeforeLast('.', "")
        if (qualifier.isNotEmpty()) {
            val owner = resolveType(qualifier, file, offset) ?: qualifier
            return constantIn(owner, name) ?: constants["$qualifier#$name"]
        }
        return unqualifiedConstant(name, file, offset)
    }

    private fun unqualifiedConstant(name: String, file: RouteSourceFile, offset: Int): ConstantDefinition? {
        val enclosing = file.enclosingTypes(offset).reversed().map { typeName(file, it) }
        enclosing.firstNotNullOfOrNull { constantIn(it, name) }?.let { return it }
        enclosing.flatMap { owner -> typesByName[owner]?.supertypeNames.orEmpty().mapNotNull { resolveType(it, file, offset) } }
            .firstNotNullOfOrNull { constantIn(it, name) }?.let { return it }
        constants["${file.packageName}#$name"]?.let { return it }
        file.imports.firstOrNull { it.alias == name || (it.alias == null && it.path.substringAfterLast('.') == name) }?.let { import ->
            val original = import.path.substringAfterLast('.')
            return constants["${import.path.substringBeforeLast('.')}#$original"] ?: constantIn(import.path.substringBeforeLast('.'), original)
        }
        return file.imports.filter { it.path.endsWith(".*") }.firstNotNullOfOrNull { import ->
            val owner = import.path.removeSuffix(".*")
            constants["$owner#$name"] ?: constantIn(owner, name)
        }
    }

    private fun constantIn(owner: String, name: String): ConstantDefinition? =
        constants["$owner#$name"] ?: constants["$owner.Companion#$name"]

    /** 모든 파일의 상수(`static final String`·Java 인터페이스 `String`·Kotlin `const val`)를 `소유자#이름`으로 색인한다. */
    private fun collectConstants(): Map<String, ConstantDefinition> {
        val result = linkedMapOf<String, ConstantDefinition>()
        files.forEach { file ->
            val pattern = if (file.isJava) JAVA_CONSTANT else KOTLIN_CONSTANT
            pattern.findAll(file.masked).forEach { match -> addConstant(result, file, match) }
        }
        return result
    }

    private fun addConstant(result: MutableMap<String, ConstantDefinition>, file: RouteSourceFile, match: MatchResult) {
        val start = match.range.first
        val containers = file.enclosingTypes(start)
        if (file.isJava && match.groupValues[1].isBlank() && typesByName[typeNameOf(file, containers)]?.kind != SpringTypeKind.INTERFACE) return
        if (!file.isJava && match.groupValues[1].isBlank()) return
        if (depthsOf(file).parens[start] != 0 || isInsideFunctionBody(file, start, containers)) return
        val owner = if (containers.isEmpty()) file.packageName else typeNameOf(file, containers)
        val initializer = match.range.last + 1
        result.putIfAbsent("$owner#${match.groupValues[2]}", ConstantDefinition(file, file.code.substring(initializer, file.statementEnd(initializer)).trim(), start))
    }

    private fun typeNameOf(file: RouteSourceFile, containers: List<RouteTypeDecl>): String =
        (listOf(file.packageName).filter(String::isNotEmpty) + containers.map { it.name }).joinToString(".")

    /** 선언이 타입 몸체 바로 아래가 아니면(함수 안 지역 선언) 상수가 아니다. */
    private fun isInsideFunctionBody(file: RouteSourceFile, start: Int, containers: List<RouteTypeDecl>): Boolean {
        val depths = depthsOf(file)
        val innermost = containers.lastOrNull() ?: return depths.braces[start] != 0
        return depths.braces[start] != depths.braces[innermost.bodyStart] + 1
    }

    private companion object {
        val REFERENCE = Regex("[A-Za-z_][\\w.]*")
        val KOTLIN_FUN = Regex("\\bfun\\b")
        val FUN_HEADER = Regex("^\\s*(?:<[^()]*?>\\s*)?(?:[A-Za-z_][\\w.<>?, ]*\\.\\s*)?[A-Za-z_]\\w*\\s*$")
        val IDENTIFIER_TAIL = Regex("[A-Za-z_]\\w*$")
        val JAVA_CALL_SHAPE = Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
        val JAVA_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "synchronized", "return", "new", "else", "try", "do", "super", "this", "assert", "throw")
        val EXPRESSION_KEYWORDS = setOf("new", "return", "throw", "else", "case", "extends", "implements", "instanceof", "yield")
        val KOTLIN_PARAMETER = Regex("^\\s*(?:(?:val|var|private|public|internal|protected|override|vararg)\\s+)*([A-Za-z_]\\w*)\\s*:")
        // 첫 그룹이 비면 수식어 없는 선언이다 — Java는 인터페이스 상수, Kotlin은 const가 아닌 val이라 따로 거른다.
        val JAVA_CONSTANT = Regex("(?:\\b(static\\s+final|final\\s+static)\\s+|(?<=[;{}]|^)\\s*(?:public\\s+)?)String\\s+([A-Za-z_]\\w*)\\s*=(?!=)", RegexOption.MULTILINE)
        val KOTLIN_CONSTANT = Regex("\\b(const\\s+)?val\\s+([A-Za-z_]\\w*)\\s*(?::\\s*String\\s*)?=(?!=)")

        /** 와일드카드 import로만 보이는 프레임워크 타입이다. 명시 import는 이 목록 없이도 풀린다. */
        val KNOWN_EXTERNAL_TYPES: Set<String> = SpringAnnotations.MAPPING_VERBS.keys + SpringAnnotations.EXCHANGE_VERBS.keys + setOf(
            SpringAnnotations.REQUEST_MAPPING, SpringAnnotations.HTTP_EXCHANGE, SpringAnnotations.CONTROLLER,
            SpringAnnotations.REST_CONTROLLER, SpringAnnotations.ALIAS_FOR, "org.springframework.web.bind.annotation.RequestMethod",
            "org.springframework.boot.autoconfigure.SpringBootApplication", "org.springframework.boot.SpringBootConfiguration",
            "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
        )
    }
}
