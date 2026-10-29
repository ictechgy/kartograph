package dev.kartograph.index

/**
 * route-call 스캐너가 파일 밖 선언(타입·함수·상수·속성·DI provider)을 찾는 프로젝트 전체 어휘 색인이다.
 *
 * 컴파일러 없이 [RouteSourceFile]의 어휘 모델 위에서 이름을 해석한다. 같은 이름 후보가 여럿이면 고르지 않고 null을 돌려준다 —
 * 추측으로 다른 선언을 잇지 않기 위해서다. Retrofit baseUrl 해석([RetrofitInstanceResolver])이 쓴다.
 *
 * @param files 스캔한 production source 파일이다
 */
internal class SourceDeclarations(val files: List<RouteSourceFile>) {
    /** 타입 FQN → (파일, 선언)이다. 같은 FQN이 여럿이면 첫 파일만 남긴다(경로 순). */
    private val types: Map<String, Pair<RouteSourceFile, RouteTypeDecl>> = buildMap {
        files.forEach { file -> file.types.forEach { type -> putIfAbsent(typeFqn(file, type), file to type) } }
    }

    /** 함수 FQN(`pkg.Type.name`, 최상위는 `pkg.name`) → 후보들이다. */
    private val functions: Map<String, List<Pair<RouteSourceFile, RouteFunctionDecl>>> = files.flatMap { file ->
        file.functions.map { function -> "${containerFqn(file, function.start)}.${function.name}".removePrefix(".") to (file to function) }
    }.groupBy({ it.first }, { it.second })

    /** 상수 FQN → 후보들이다. 동반 객체 상수는 `Outer.Companion.NAME`과 `Outer.NAME` 둘로 싣는다. */
    private val constants: Map<String, List<Pair<RouteSourceFile, RouteConstant>>> = files.flatMap { file ->
        file.constants.values.flatten().flatMap { constant ->
            val names = listOf(file.packageName).filter { it.isNotEmpty() } + constant.containers
            val direct = (names + constant.name).joinToString(".")
            val withoutCompanion = (names.filter { it != "Companion" } + constant.name).joinToString(".")
            listOf(direct, withoutCompanion).distinct().map { it to (file to constant) }
        }
    }.groupBy({ it.first }, { it.second })

    /** 파일별 속성 선언이다(처음 쓸 때 만든다). */
    private val propertiesByFile = java.util.IdentityHashMap<RouteSourceFile, List<SourceProperty>>()

    /** 프로젝트에서 `@Qualifier`로 선언한 어노테이션의 단순 이름이다. */
    val qualifierAnnotations: Set<String> = files.flatMap { file ->
        QUALIFIER_DECLARATION.findAll(file.masked).mapNotNull { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }.takeIf { QUALIFIER_MARKER.containsMatchIn(leadingAnnotations(file, match.range.first)) }
        }.toList()
    }.toSet()

    /** [offset]을 감싸는 타입 사슬의 FQN이다. 최상위면 패키지 이름이다. */
    fun containerFqn(file: RouteSourceFile, offset: Int): String =
        (listOf(file.packageName).filter { it.isNotEmpty() } + file.enclosingTypes(offset).map { it.name }).joinToString(".")

    /** [file]의 속성 선언 목록이다. */
    fun properties(file: RouteSourceFile): List<SourceProperty> = propertiesByFile.getOrPut(file) { collectProperties(file) }

    /**
     * [file]에서 쓴 타입 이름 [name](단순·한정)을 FQN으로 푼다. 파일 안 타입, 명시 import, 같은 패키지, `*` import 순이다.
     *
     * @return 색인에 있는 타입의 FQN. 찾지 못하거나 여럿이면 null이다
     */
    fun resolveType(file: RouteSourceFile, name: String): String? {
        val cleaned = name.split('.').filter { it != "INSTANCE" && it != "Companion" }.joinToString(".")
        if (cleaned.isEmpty()) return null
        if ('.' in cleaned) {
            val head = resolveType(file, cleaned.substringBefore('.'))?.let { "$it.${cleaned.substringAfter('.')}" }
            return listOfNotNull(head, cleaned).firstOrNull { it in types }
        }
        file.types.filter { it.name == cleaned }.map { typeFqn(file, it) }.distinct().singleOrNull()?.let { return it }
        file.imports.firstOrNull { (it.alias ?: it.path.substringAfterLast('.')) == cleaned && !it.path.endsWith("*") }
            ?.let { return it.path.takeIf { path -> path in types } }
        val candidates = (listOf(file.packageName) + file.imports.filter { it.path.endsWith(".*") }.map { it.path.removeSuffix(".*") })
            .map { if (it.isEmpty()) cleaned else "$it.$cleaned" }.filter { it in types }.distinct()
        return candidates.singleOrNull()
    }

    /** 타입 FQN의 선언 위치다. */
    fun type(fqn: String): Pair<RouteSourceFile, RouteTypeDecl>? = types[fqn]

    /**
     * 호출 `qualifier.name(...)`이 가리키는 함수를 찾는다. 한정자가 없으면 감싸는 타입의 멤버·같은 파일 최상위, 그다음 같은 패키지·
     * import한 최상위 함수 순이다.
     *
     * @return 하나로 정해진 함수, 아니면 null
     */
    fun findFunction(file: RouteSourceFile, qualifier: String, name: String, offset: Int): Pair<RouteSourceFile, RouteFunctionDecl>? {
        if (qualifier.isNotEmpty() && qualifier != "this") {
            val owner = resolveType(file, qualifier) ?: return null
            return (functions["$owner.$name"].orEmpty() + functions["$owner.Companion.$name"].orEmpty()).singleOrNull()
        }
        val enclosing = file.enclosingTypes(offset)
        val local = file.functions.filter { function ->
            function.name == name && file.enclosingTypes(function.start).let { owners -> owners.size <= enclosing.size && owners.indices.all { owners[it] === enclosing[it] } }
        }
        if (local.isNotEmpty()) return local.singleOrNull()?.let { file to it }
        val imported = file.imports.filter { (it.alias ?: it.path.substringAfterLast('.')) == name }.map { it.path }
        val topLevel = (imported + (if (file.packageName.isEmpty()) name else "${file.packageName}.$name")).distinct()
        return topLevel.flatMap { functions[it].orEmpty() }.filter { (owner, function) -> owner.enclosingTypes(function.start).isEmpty() }.singleOrNull()
    }

    /**
     * 참조 `qualifier.name`이 가리키는 파일 상수를 찾는다. 같은 파일(한정자 규칙은 [RoutePathResolver]와 같음), 명시 import,
     * 같은 패키지, 한정 타입의 멤버 순이다.
     */
    fun findConstant(file: RouteSourceFile, qualifier: String, name: String): Pair<RouteSourceFile, RouteConstant>? {
        val sameFile = file.constants[name].orEmpty().filter { constant ->
            qualifier.isEmpty() || constant.containers.lastOrNull() == qualifier.substringAfterLast('.') ||
                (constant.containers.lastOrNull() == "Companion" && constant.containers.dropLast(1).lastOrNull() == qualifier.substringAfterLast('.'))
        }
        if (sameFile.isNotEmpty()) return sameFile.singleOrNull()?.let { file to it }
        val candidates = if (qualifier.isEmpty()) {
            file.imports.filter { (it.alias ?: it.path.substringAfterLast('.')) == name }.map { it.path } +
                (if (file.packageName.isEmpty()) name else "${file.packageName}.$name")
        } else {
            listOfNotNull(resolveType(file, qualifier)?.let { "$it.$name" })
        }
        return candidates.distinct().flatMap { constants[it].orEmpty() }.distinct().singleOrNull()
    }

    /** 같은 패키지·import로 보이는 최상위 속성을 찾는다. */
    fun findTopLevelProperty(file: RouteSourceFile, name: String): Pair<RouteSourceFile, SourceProperty>? {
        val imported = file.imports.filter { (it.alias ?: it.path.substringAfterLast('.')) == name }.map { it.path.substringBeforeLast('.', "") }
        val packages = (imported + file.packageName).distinct()
        return files.filter { it.packageName in packages }.flatMap { owner ->
            properties(owner).filter { it.name == name && it.containers.isEmpty() }.map { owner to it }
        }.singleOrNull()
    }

    /** 타입(또는 그 동반 객체)의 멤버 속성을 찾는다. */
    fun findMemberProperty(file: RouteSourceFile, qualifier: String, name: String): Pair<RouteSourceFile, SourceProperty>? {
        val owner = resolveType(file, qualifier) ?: return null
        val (ownerFile, _) = types[owner] ?: return null
        return properties(ownerFile).filter { property ->
            property.name == name && property.ownerFqn(ownerFile).let { it == owner || it == "$owner.Companion" }
        }.singleOrNull()?.let { ownerFile to it }
    }

    /** `@Provides`(Dagger·Hilt)이고 `Retrofit`을 돌려주는 함수들이다. */
    val retrofitProviders: List<Pair<RouteSourceFile, RouteFunctionDecl>> by lazy { providers(RETROFIT_TYPE) }

    /** `@Provides`(Dagger·Hilt)이고 `OkHttpClient`를 돌려주는 함수들이다. 인터셉터 결합에서 client를 찾는 데 쓴다. */
    val okHttpClientProviders: List<Pair<RouteSourceFile, RouteFunctionDecl>> by lazy { providers(OKHTTP_CLIENT_TYPE) }

    /** [type]을 돌려주는 `@Provides` 함수들이다. */
    private fun providers(type: ProvidedType): List<Pair<RouteSourceFile, RouteFunctionDecl>> = files.flatMap { file ->
        file.functions.filter { function -> PROVIDES.containsMatchIn(functionAnnotations(file, function)) && returns(file, function, type) }
            .map { file to it }
    }

    /** 함수가 [type]을 돌려준다고 선언했거나(Kotlin `: T`, Java `T name(`) 반환 타입을 생략한 식 몸체가 그 타입의 빌더인지 본다. */
    private fun returns(file: RouteSourceFile, function: RouteFunctionDecl, type: ProvidedType): Boolean {
        val open = parameterListOpen(file, function) ?: return false
        if (file.isJava) return type.javaReturn.containsMatchIn(file.masked.substring(function.start, open))
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return false
        val header = file.masked.substring(close + 1, function.bodyStart.coerceAtLeast(close + 1))
        if (type.kotlinReturn.matches(header)) return true
        return file.masked.getOrNull(function.bodyStart) == '=' && header.isBlank() &&
            type.builder.containsMatchIn(file.masked.substring(function.bodyStart, function.end.coerceAtMost(file.masked.length)))
    }

    /** 선언 앞 어노테이션에서 DI 한정자(`@Named("x")`, 프로젝트 `@Qualifier` 어노테이션)를 모은다. */
    fun qualifiers(annotations: String): Set<String> = ANNOTATION.findAll(annotations).mapNotNull { match ->
        val simple = match.groupValues[1].substringAfterLast('.')
        val arguments = match.groupValues[2].trim().removeSurrounding("(", ")").trim()
        when {
            simple == "Named" -> "Named:" + (scriptString(arguments.substringAfter('=', arguments).trim()) ?: arguments)
            simple in qualifierAnnotations -> simple + arguments.replace(WHITESPACE, "").let { if (it.isEmpty()) "" else "($it)" }
            else -> null
        }
    }.toSet()

    /** Kotlin 속성·Java 필드를 모은다. 함수·람다 안의 지역 선언과 생성자 매개변수는 뺀다. */
    private fun collectProperties(file: RouteSourceFile): List<SourceProperty> =
        if (file.isJava) collectJavaFields(file) else collectKotlinProperties(file)

    private fun collectKotlinProperties(file: RouteSourceFile): List<SourceProperty> = KOTLIN_PROPERTY.findAll(file.masked).mapNotNull { match ->
        val start = match.range.first
        if (!declaredAtContainerLevel(file, start)) return@mapNotNull null
        val name = match.groupValues[3]
        var index = skipSpaces(file.masked, match.range.last + 1)
        var typeName: String? = null
        if (file.masked.getOrNull(index) == ':') {
            val typeEnd = kotlinTypeEnd(file.masked, index + 1)
            typeName = file.masked.substring(index + 1, typeEnd).trim().substringBefore('<').removeSuffix("?").substringAfterLast('.').trim()
            index = skipSpaces(file.masked, typeEnd)
        }
        val base = SourceProperty(name, start, file.enclosingTypes(start), mutable = match.groupValues[2] == "var", typeName = typeName,
            annotations = leadingAnnotations(file, start))
        when {
            file.masked.startsWith("=", index) && !file.masked.startsWith("==", index) ->
                base.copy(initializer = (index + 1) until file.statementEnd(index + 1))
            file.masked.startsWith("by", index) -> base.copy(lazyBody = lazyBody(file, index + 2))
            else -> base.copy(getterBody = getterBody(file, index))
        }
    }.toList()

    private fun collectJavaFields(file: RouteSourceFile): List<SourceProperty> = JAVA_FIELD.findAll(file.masked).mapNotNull { match ->
        val start = match.groups[4]!!.range.first
        if (match.groupValues[3] in JAVA_NON_TYPES || !declaredAtContainerLevel(file, start)) return@mapNotNull null
        val modifiers = match.groupValues[2]
        val initializerStart = match.range.last + 1
        SourceProperty(
            name = match.groupValues[4], start = start, containers = file.enclosingTypes(start),
            mutable = !Regex("\\bfinal\\b").containsMatchIn(modifiers),
            typeName = match.groupValues[3].substringBefore('<').substringAfterLast('.'),
            annotations = leadingAnnotations(file, match.range.first) + " " + file.code.substring(match.groups[1]!!.range),
            initializer = if (match.groupValues[5] == ";") null else initializerStart until file.statementEnd(initializerStart),
        )
    }.toList()

    /** 선언이 감싸는 타입 몸체(또는 파일) 바로 아래에 있는지 본다 — 함수·람다·생성자 괄호 안이면 거짓이다. */
    private fun declaredAtContainerLevel(file: RouteSourceFile, start: Int): Boolean {
        if (file.enclosingFunction(start) != null) return false
        val from = file.enclosingTypes(start).lastOrNull()?.bodyStart?.plus(1) ?: 0
        var depth = 0
        for (index in from until start) {
            when (file.masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        return depth == 0
    }

    /** `by lazy { … }`의 람다 본문 범위다. lazy가 아니면(다른 위임) null이다. */
    private fun lazyBody(file: RouteSourceFile, from: Int): IntRange? {
        val rest = file.masked.substring(from, (from + 200).coerceAtMost(file.masked.length))
        val match = LAZY_OPEN.find(rest) ?: return null
        val open = from + match.range.last
        val close = file.braceEnd(open).takeIf { it > open } ?: return null
        return (open + 1) until close
    }

    /** 다음 줄의 `get() = 식` 몸체 범위다. 블록 getter는 `get() { … }` 안쪽 범위다. */
    private fun getterBody(file: RouteSourceFile, from: Int): IntRange? {
        val match = GETTER.find(file.masked.substring(from, (from + 200).coerceAtMost(file.masked.length))) ?: return null
        val after = from + match.range.last + 1
        return if (match.value.trimEnd().endsWith("=")) after until file.statementEnd(after)
        else file.braceEnd(after - 1).takeIf { it > after }?.let { after until it }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val QUALIFIER_DECLARATION = Regex("\\bannotation\\s+class\\s+([A-Za-z_]\\w*)|@interface\\s+([A-Za-z_]\\w*)")
        private val QUALIFIER_MARKER = Regex("@(?:javax\\.inject\\.|jakarta\\.inject\\.)?Qualifier\\b")
        private val PROVIDES = Regex("@(?:dagger\\.)?Provides\\b")
        private val RETROFIT_TYPE = ProvidedType(
            kotlinReturn = Regex("^\\s*:\\s*(?:retrofit2\\s*\\.\\s*)?Retrofit\\s*$"),
            javaReturn = Regex("(?:^|[\\s>])(?:retrofit2\\.)?Retrofit\\s+[A-Za-z_]\\w*\\s*$"),
            builder = Regex("\\bRetrofit\\s*\\.\\s*Builder\\s*\\("),
        )
        private val OKHTTP_CLIENT_TYPE = ProvidedType(
            kotlinReturn = Regex("^\\s*:\\s*(?:okhttp3\\s*\\.\\s*)?OkHttpClient\\s*$"),
            javaReturn = Regex("(?:^|[\\s>])(?:okhttp3\\.)?OkHttpClient\\s+[A-Za-z_]\\w*\\s*$"),
            builder = Regex("\\bOkHttpClient\\s*\\.\\s*Builder\\s*\\("),
        )
        private val ANNOTATION = Regex(
            "@(?:(?:param|field|get|set|setparam|property|receiver|delegate)\\s*:\\s*)?([A-Za-z_][\\w.]*)(\\s*\\((?:[^()]|\\([^()]*\\))*\\))?",
        )
        private val KOTLIN_PROPERTY = Regex("\\b(lateinit\\s+)?(val|var)\\s+([A-Za-z_]\\w*)\\b")
        private val JAVA_FIELD = Regex(
            "(?m)^[ \\t]*((?:@[A-Za-z_][\\w.]*(?:\\s*\\([^()]*\\))?\\s+)*)((?:(?:public|protected|private|static|final|volatile|transient)\\s+)*)" +
                "([A-Za-z_][\\w.]*(?:<[^;=(){}]*>)?(?:\\[\\])*)\\s+([A-Za-z_]\\w*)\\s*(=(?!=)|;)",
        )
        private val JAVA_NON_TYPES = setOf("return", "new", "throw", "else", "case", "package", "import", "goto")
        private val LAZY_OPEN = Regex("^\\s*lazy\\s*(?:\\([^()]*\\))?\\s*\\{")
        private val GETTER = Regex("^\\s*get\\s*\\(\\s*\\)\\s*(?::\\s*[\\w.<>?]+\\s*)?(=|\\{)")
    }
}

/**
 * DI provider가 돌려주는 타입의 어휘 표지다.
 *
 * @property kotlinReturn Kotlin 반환 타입 표기(`: T`)다
 * @property javaReturn Java 메서드 머리의 반환 타입(`T name`)이다
 * @property builder 반환 타입을 생략한 식 몸체가 이 타입을 만든다고 볼 빌더 호출이다
 */
internal class ProvidedType(val kotlinReturn: Regex, val javaReturn: Regex, val builder: Regex)

/**
 * Kotlin 속성 또는 Java 필드 선언이다.
 *
 * @property start 선언 키워드(`val`·`var`) 또는 필드 이름 위치다
 * @property containers 감싸는 타입 사슬(바깥 → 안쪽)이다
 * @property mutable `var`·`lateinit var`·final 아닌 Java 필드면 참이다 — 다른 대입도 값의 원천이다
 * @property typeName 선언한 단순 타입 이름이다. 생략하면 null이다
 * @property annotations 선언 앞 어노테이션 원문이다(DI `@Inject`·한정자 판정용)
 * @property initializer `=` 뒤 초기식 범위다
 * @property lazyBody `by lazy { … }` 본문 범위다
 * @property getterBody `get() = …` 식 또는 블록 getter 본문 범위다
 */
internal data class SourceProperty(
    val name: String,
    val start: Int,
    val containers: List<RouteTypeDecl>,
    val mutable: Boolean,
    val typeName: String?,
    val annotations: String,
    val initializer: IntRange? = null,
    val lazyBody: IntRange? = null,
    val getterBody: IntRange? = null,
) {
    /** 선언을 감싸는 타입 사슬의 FQN이다(최상위면 패키지). */
    fun ownerFqn(file: RouteSourceFile): String =
        (listOf(file.packageName).filter { it.isNotEmpty() } + containers.map { it.name }).joinToString(".")

    /** 선언 범위의 끝이다 — 초기식·lazy·getter 중 있는 것의 끝, 없으면 선언 위치다. */
    val end: Int get() = listOfNotNull(initializer?.last, lazyBody?.last, getterBody?.last).maxOrNull()?.plus(1) ?: start
}

/**
 * 선언 [start] 앞의 어노테이션 원문이다 — 같은 줄의 앞부분과, 그 위로 이어지는 `@`로 시작하는 줄들이다. 문자열 인자
 * (`@Named("api")`)를 읽어야 하므로 주석만 지운 뷰에서 자른다.
 */
internal fun leadingAnnotations(file: RouteSourceFile, start: Int): String {
    var lineStart = file.code.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
    if (start == 0) lineStart = 0
    val parts = mutableListOf(file.code.substring(lineStart, start))
    while (lineStart > 0) {
        val previousStart = file.code.lastIndexOf('\n', lineStart - 2) + 1
        val line = file.code.substring(previousStart, lineStart - 1).trim()
        if (line.isNotEmpty() && !line.startsWith("@")) break
        parts += line
        lineStart = previousStart
    }
    return parts.reversed().joinToString(" ")
}

/**
 * 함수 선언의 어노테이션 원문이다. Kotlin은 `fun` 앞, Java 메서드 정규식은 같은 줄의 어노테이션까지 선언 시작에 포함하므로
 * 선언 시작부터 매개변수 목록 앞까지도 더한다.
 */
internal fun functionAnnotations(file: RouteSourceFile, function: RouteFunctionDecl): String =
    leadingAnnotations(file, function.start) + " " + file.code.substring(function.start, parameterListOpen(file, function) ?: function.start)

/** 함수 매개변수 목록 `(` 위치다. Java는 이름 바로 뒤, Kotlin은 `fun` 뒤 첫 `(`다. */
internal fun parameterListOpen(file: RouteSourceFile, function: RouteFunctionDecl): Int? {
    val open = if (file.isJava) Regex("\\b${Regex.escape(function.name)}\\s*\\(").find(file.masked, function.start)?.range?.last
    else file.masked.indexOf('(', function.start).takeIf { it >= 0 }
    return open
}

/** 공백·줄바꿈을 건너뛴 위치다. */
internal fun skipSpaces(text: String, from: Int): Int {
    var index = from
    while (index < text.length && text[index].isWhitespace()) index++
    return index
}

/** Kotlin 타입 표기의 끝이다 — 괄호 깊이 0에서 `=`·`by`·줄바꿈·`;`·`{`를 만나면 끝난다. */
private fun kotlinTypeEnd(text: String, from: Int): Int {
    var depth = 0
    var index = from
    while (index < text.length) {
        val character = text[index]
        when {
            character == '<' || character == '(' -> depth++
            character == '>' && text.getOrNull(index - 1) != '-' || character == ')' -> depth--
            depth <= 0 && (character == '=' || character == '\n' || character == ';' || character == '{') -> return index
            depth <= 0 && text.startsWith("by", index) && text.getOrNull(index - 1)?.isWhitespace() == true &&
                text.getOrNull(index + 2)?.isWhitespace() == true -> return index
        }
        index++
    }
    return index
}
