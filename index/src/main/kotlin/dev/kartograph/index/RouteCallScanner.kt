package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.core.RouteCallEvidence
import dev.kartograph.index.RouteUrlRules.ArgumentValue
import dev.kartograph.index.RouteUrlRules.CallArgument
import dev.kartograph.index.RouteUrlRules.ComposedRoute
import dev.kartograph.index.RouteUrlRules.JoinMode
import dev.kartograph.index.RouteUrlRules.UrlPart
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Kotlin/Java 소스에서 클라이언트 HTTP 호출을 isthmus http 도메인의 `route-call` 사실로 수확한다.
 *
 * 지원 표면은 증거가 있는 것으로 한정한다 — 사용자가 `http-wrappers` v1로 선언한 래퍼 호출,
 * `java.net.URL` + `HttpURLConnection`(또는 `openStream`·`readText`) 요청, Retrofit 동사 어노테이션.
 * 그 밖의 클라이언트(OkHttp·Ktor 등)는 지원한다고 주장하지 않고 `route-call-coverage:`로 센다.
 * 경로·동사를 증명하지 못한 호출은 버리지 않고 dynamic·`methodDynamic`과 limitation으로 남긴다.
 *
 * @param projectRoot 위치 경로의 기준이 되는 프로젝트 루트
 * @param sourceRoots 스캔할 source 루트들이다. 비어 있으면 프로젝트 전체다
 * @param wrappers `http-wrappers` 선언 전체다. `kotlin` 선언만 해석한다
 * @param includeTests 테스트 소스 세트도 스캔해 `testSource`로 표시할지 여부
 * @param service 문서의 기본 서비스 신원이다
 */
public class RouteCallScanner(
    private val projectRoot: Path,
    private val sourceRoots: List<Path> = emptyList(),
    wrappers: List<HttpWrapperDeclaration> = emptyList(),
    private val includeTests: Boolean = false,
    private val service: String? = null,
) {
    private val declarations = wrappers.filter { it.language == "kotlin" }

    /**
     * 소스를 스캔해 `target: "http"`, `roles: ["client"]` 문서를 만든다. 사실이 0건이어도 target을
     * 유지한다 — 호출 0건 클라이언트를 "스캔 안 함"과 구분하기 위한 계약이다.
     */
    public fun scan(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument {
        val root = projectRoot.toRealPath()
        val files = collectFiles(root)
        val stats = RouteScanStats()
        val wrappersByDeclaration = declarations.map { resolveWrapper(it, files) }
        val facts = mutableListOf<BridgeFact>()
        files.filter { includeTests || !it.isTest }.forEach { file ->
            RouteFileScan(file, wrappersByDeclaration, stats, facts, includeTests).run()
        }
        val ordered = facts.sortedWith(
            compareBy({ it.location.path }, { it.location.line }, { it.location.column }, { it.channel.orEmpty() }, { it.method.orEmpty() }),
        ).map { fact -> graph?.let { attachSnapshotSymbol(fact, it, projectRoot) } ?: fact }
        return BridgeFactsDocument(
            generatedAt = bridgeTimestamp(generatedAt?.let(Instant::parse) ?: Instant.now()),
            sourceModifiedAt = files.maxOfOrNull { Files.getLastModifiedTime(root.resolve(it.relative)).toInstant() }
                ?.let(::bridgeTimestamp),
            platform = "kotlin",
            target = "http",
            project = root.toString().replace('\\', '/'),
            facts = ordered,
            limitations = limitations(stats, wrappersByDeclaration, files).distinct().sorted(),
            roles = listOf("client"),
            testSources = if (includeTests) "included" else "excluded",
            service = service,
        )
    }

    /** 스캔 루트의 source를 모아 프로젝트 기준 상대 경로 순서로 정렬한다. 겹치는 루트는 한 번만 읽는다. */
    private fun collectFiles(root: Path): List<RouteSourceFile> {
        val paths = sortedMapOf<String, Path>()
        sourceRoots.ifEmpty { listOf(root) }.forEach { sourceRoot ->
            ProjectTraversal.walkSources(sourceRoot, includeTests = true) { path ->
                val real = path.toRealPath()
                require(real.startsWith(root)) { "source root is outside the project root" }
                if (!ProjectTraversal.isPruned(root, real)) paths[root.relativize(real).joinToString("/")] = real
            }
        }
        return paths.map { (relative, path) ->
            val isTest = isTestSource(relative)
            if (isTest && !includeTests) return@map RouteSourceFile(relative, relative.endsWith(".java"), true, "")
            RouteSourceFile(
                relative = relative,
                isJava = relative.endsWith(".java"),
                isTest = isTest,
                source = ProjectTraversal.readSourceLines(root, path, includeTests = true).joinToString("\n"),
            )
        }
    }

    /** 래퍼 선언의 owner·name을 실제 소스 선언과 맞춘다. 못 찾으면 명명 관례로 패키지를 추정해 호출만 찾는다. */
    private fun resolveWrapper(declaration: HttpWrapperDeclaration, files: List<RouteSourceFile>): ResolvedWrapper {
        val owner = declaration.owner
        val classMatch = files.firstNotNullOfOrNull { file ->
            file.types.firstOrNull { type -> typeFqn(file, type) == owner }?.let { file to it }
        }
        if (classMatch != null) {
            val (file, type) = classMatch
            val resolved = declaration.kind == "constructor" || file.functions.any { function ->
                function.name == declaration.name && file.enclosingTypes(function.start).lastOrNull() == type
            }
            return ResolvedWrapper(declaration, file.packageName, isMember = true, resolved = resolved)
        }
        if (declaration.kind == "function") {
            val topLevel = files.any { file ->
                (owner == file.packageName || owner == "${file.packageName}.${facadeName(file)}") &&
                    file.functions.any { it.name == declaration.name && file.enclosingTypes(it.start).isEmpty() }
            }
            val facade = owner.substringAfterLast('.').endsWith("Kt")
            if (topLevel) return ResolvedWrapper(declaration, if (facade) owner.substringBeforeLast('.') else owner, isMember = false, resolved = true)
        }
        return ResolvedWrapper(declaration, conventionalPackage(declaration), isMember = isConventionalClass(declaration), resolved = false)
    }

    private fun limitations(stats: RouteScanStats, wrappers: List<ResolvedWrapper>, files: List<RouteSourceFile>): List<String> = buildList {
        val unresolved = wrappers.filter { !it.resolved || it.calls == 0 }
        if (unresolved.isNotEmpty()) add(
            "http-wrapper-unresolved: ${unresolved.size} declared kotlin wrapper(s) matched no source declaration or produced no calls " +
                unresolved.map { "${it.declaration.owner}.${it.declaration.name}" }.distinct().sorted().toString(),
        )
        if (stats.undeclaredSinks > 0) add(
            "http-wrapper-undeclared: ${stats.undeclaredSinks} HTTP sink(s) pass enclosing-function parameters into a request path; declare the wrapper in http-wrappers to resolve its callers",
        )
        if (stats.ambiguousJoins > 0) add(
            "ambiguous-base-join: ${stats.ambiguousJoins} call(s) join an unresolved base URL with a relative path; their templates are dynamic",
        )
        val unmodeled = files.count { file ->
            (includeTests || !file.isTest) && file.imports.any { import -> UNMODELED_CLIENTS.any { import.path.startsWith(it) } } &&
                wrappers.none { wrapper -> declaresWrapper(file, wrapper) }
        }
        if (unmodeled > 0) add(
            "route-call-coverage: $unmodeled source file(s) use HTTP clients this scanner does not model (OkHttp, Ktor, Volley, java.net.http, Spring, Feign); their requests are not reported",
        )
    }

    /** 파일이 래퍼 구현 자체(소유 타입 또는 최상위 래퍼 함수)를 선언하는지 본다. */
    private fun declaresWrapper(file: RouteSourceFile, wrapper: ResolvedWrapper): Boolean =
        file.types.any { typeFqn(file, it) == wrapper.declaration.owner } ||
            (!wrapper.isMember && file.packageName == wrapper.ownerPackage && file.functions.any { it.name == wrapper.declaration.name })

    private fun isTestSource(relative: String): Boolean = relative.split('/').windowed(2).any { (parent, name) ->
        parent == "src" && (name == "test" || name.endsWith("Test") || TEST_SOURCE_SET.matches(name))
    }

    private fun conventionalPackage(declaration: HttpWrapperDeclaration): String {
        val segments = declaration.owner.split('.')
        return if (isConventionalClass(declaration)) segments.takeWhile { it.first().isLowerCase() }.joinToString(".")
        else declaration.owner
    }

    private fun isConventionalClass(declaration: HttpWrapperDeclaration): Boolean =
        declaration.kind == "constructor" || declaration.owner.substringAfterLast('.').first().isUpperCase() &&
            !declaration.owner.substringAfterLast('.').endsWith("Kt")

    private companion object {
        /** 이 스캐너가 모델링하지 않는 HTTP 클라이언트 import 접두사다. */
        val UNMODELED_CLIENTS = listOf(
            "okhttp3.", "io.ktor.client", "com.android.volley", "java.net.http.", "org.springframework.web.client",
            "org.springframework.web.reactive.function.client", "feign.",
        )

        /** `src/testDebug`·`src/androidTestRelease`·`src/testFixtures` 같은 변형 테스트 세트다. */
        val TEST_SOURCE_SET = Regex("^(?:test|androidTest)[A-Z].*")
    }
}

/** 소스 선언 사슬로 만든 타입 FQN이다(`pkg.Outer.Inner`). */
internal fun typeFqn(file: RouteSourceFile, type: RouteTypeDecl): String {
    val chain = file.types.filter { it !== type && it.bodyStart >= 0 && type.start in it.bodyStart..it.bodyEnd }
        .sortedBy { it.start }.map { it.name } + type.name
    return (listOf(file.packageName).filter { it.isNotEmpty() } + chain).joinToString(".")
}

/** Kotlin 파일 facade 클래스 이름이다(`Api.kt` → `ApiKt`). `@file:JvmName`은 따로 읽지 않는다. */
private fun facadeName(file: RouteSourceFile): String =
    file.relative.substringAfterLast('/').substringBeforeLast('.').replaceFirstChar { it.uppercase() } + "Kt"

/** 선언과 소스 해석 결과다. [calls]는 스캔 중 찾은 호출 수다. */
internal class ResolvedWrapper(
    val declaration: HttpWrapperDeclaration,
    val ownerPackage: String,
    val isMember: Boolean,
    val resolved: Boolean,
) {
    var calls: Int = 0
    val ownerSimple: String = declaration.owner.substringAfterLast('.')

    /** 동반 객체 멤버면 호출 한정자는 바깥 타입 이름이다(`Outer.jobs()`). */
    val callQualifier: String = if (ownerSimple == "Companion") declaration.owner.substringBeforeLast('.').substringAfterLast('.') else ownerSimple
}

/** 스캔 중 계수한 한계다. */
internal class RouteScanStats {
    var undeclaredSinks = 0
    var ambiguousJoins = 0
}

/**
 * 파일 하나의 호출 수확이다 — 래퍼 호출, URL 요청, Retrofit 어노테이션 순으로 사실을 낸다.
 */
private class RouteFileScan(
    private val file: RouteSourceFile,
    private val wrappers: List<ResolvedWrapper>,
    private val stats: RouteScanStats,
    private val facts: MutableList<BridgeFact>,
    private val includeTests: Boolean,
) {
    private val resolver = RoutePathResolver(file)

    fun run() {
        wrappers.forEach(::scanWrapper)
        if (seesJavaNetUrl()) URL_CALL.findAll(file.masked).forEach(::scanUrl)
        if (file.imports.any { it.path.startsWith("retrofit2.http.") }) RETROFIT.findAll(file.masked).forEach(::scanRetrofit)
    }

    // ---- 선언된 래퍼 ----

    private fun scanWrapper(wrapper: ResolvedWrapper) {
        wrapperCalls(wrapper).forEach { (start, open) ->
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@forEach
            val arguments = callArguments(file.code, open, close).let { values ->
                if (values.lastOrNull()?.isBlank() == true) values.dropLast(1) else values
            }.map(::callArgument)
            val decl = wrapper.declaration
            val bound = arguments.map { CallArgument(it.label, argumentValue(it.text, start)) }
            val method = RouteUrlRules.bindMethod(decl.methodArg, decl.defaultMethod, decl.methodEnum, bound)
            val pathText = RouteUrlRules.findArgumentIndex(decl.pathArg, bound)?.let { arguments[it].text }
            val parts = pathText?.let { resolver.parts(it, start) }
            val composed = parts?.let { RouteUrlRules.compose(it, JoinMode.Declared(decl.pathAnchor)) }
                ?: ComposedRoute(template = null, dynamic = true, pathAnchor = decl.pathAnchor)
            if (isParameterSink(composed, parts.orEmpty(), start)) return@forEach
            if (composed.limitation != null) stats.ambiguousJoins++
            wrapper.calls++
            emit(start, method, composed, pathText, decl.service, fallbackAnchor = decl.pathAnchor)
        }
    }

    /** 래퍼 호출의 (호출식 시작, 인자 `(`) 목록이다. 선언 자신과 상위 타입 호출은 뺀다. */
    private fun wrapperCalls(wrapper: ResolvedWrapper): List<Pair<Int, Int>> {
        val decl = wrapper.declaration
        val name = if (decl.kind == "constructor") wrapper.ownerSimple else decl.name
        val importAliases = if (decl.kind == "constructor") listOfNotNull(file.visibleNameOf(decl.owner))
        else file.imports.filter { !wrapper.isMember && it.path == "${wrapper.ownerPackage}.${decl.name}" }.mapNotNull { it.alias }
        val aliases = (listOf(name) + importAliases).distinct()
        val calls = mutableListOf<Pair<Int, Int>>()
        aliases.forEach { alias ->
            Regex("(?<![\\w.])((?:[A-Za-z_]\\w*\\s*(?:\\?|!!)?\\s*\\.\\s*)*)${Regex.escape(alias)}\\s*(?:<[^()\\n]*>)?\\s*\\(")
                .findAll(file.masked).forEach { match ->
                    if (isDeclarationSite(match.range.first)) return@forEach
                    val qualifier = match.groupValues[1].replace(QUALIFIER_NOISE, "")
                    if (acceptsCall(wrapper, alias, qualifier, match.range.first)) calls += match.range.first to match.range.last
                }
        }
        return calls.distinct()
    }

    /** 이름·한정자와 가시성으로 호출이 선언된 래퍼를 가리키는지 판정한다. */
    private fun acceptsCall(wrapper: ResolvedWrapper, alias: String, qualifier: String, offset: Int): Boolean {
        val decl = wrapper.declaration
        val owner = decl.owner
        return when {
            decl.kind == "constructor" -> constructorVisible(wrapper, alias, qualifier, offset)
            // 별칭 import로 찾은 이름은 그 import가 곧 가시성 근거다.
            !wrapper.isMember -> (qualifier.isEmpty() && (alias != decl.name || topLevelVisible(wrapper))) ||
                (qualifier == "${wrapper.ownerPackage}." && alias == decl.name)
            qualifier.isEmpty() || qualifier == "this." -> insideOwner(owner, offset)
            else -> {
                val receiver = qualifier.removeSuffix(".")
                receiver == wrapper.callQualifier && (file.visibleNameOf(owner.removeSuffix(".Companion")) != null || insideOwner(owner, offset)) ||
                    receiver == owner.removeSuffix(".Companion") ||
                    receiverTyped(receiver, wrapper)
            }
        }
    }

    private fun constructorVisible(wrapper: ResolvedWrapper, alias: String, qualifier: String, offset: Int): Boolean {
        val owner = wrapper.declaration.owner
        if (qualifier.isNotEmpty()) return "$qualifier${wrapper.ownerSimple}" == owner && alias == wrapper.ownerSimple
        return file.visibleNameOf(owner) == alias || insideOwner(owner.substringBeforeLast('.'), offset) ||
            insideOwner(owner, offset)
    }

    private fun topLevelVisible(wrapper: ResolvedWrapper): Boolean {
        val decl = wrapper.declaration
        // 별칭으로만 import한 이름은 원래 이름으로 보이지 않는다.
        return file.packageName == wrapper.ownerPackage || file.imports.any {
            (it.path == "${wrapper.ownerPackage}.${decl.name}" && it.alias == null) || it.path == "${wrapper.ownerPackage}.*"
        }
    }

    /** 호출이 [owner] 타입 몸체(중첩·동반 객체 포함) 안에 있는지 본다. */
    private fun insideOwner(owner: String, offset: Int): Boolean {
        val base = owner.removeSuffix(".Companion")
        return file.enclosingTypes(offset).any { typeFqn(file, it) == base || typeFqn(file, it) == owner }
    }

    /** 수신자 변수가 같은 파일에서 소유 타입으로 선언됐는지 본다(`client: ApiClient`, `val c = ApiClient(`). */
    private fun receiverTyped(receiver: String, wrapper: ResolvedWrapper): Boolean {
        if (!IDENTIFIER.matches(receiver)) return false
        val typeName = file.visibleNameOf(wrapper.declaration.owner) ?: return false
        val escapedReceiver = Regex.escape(receiver)
        val escapedType = Regex.escape(typeName)
        return Regex("\\b$escapedReceiver\\s*:\\s*$escapedType\\b|\\b(?:val|var)\\s+$escapedReceiver\\s*=\\s*$escapedType\\s*\\(|\\b$escapedType\\s+$escapedReceiver\\b")
            .containsMatchIn(file.masked)
    }

    /** `fun name(`·`class Name(`·`@Name(`·상위 타입 목록(`: Name(`)은 호출이 아니다. */
    private fun isDeclarationSite(start: Int): Boolean {
        val before = file.masked.substring((start - 96).coerceAtLeast(0), start)
        return DECLARATION_PREFIX.containsMatchIn(before)
    }

    private data class RawArgument(val label: String?, val text: String)

    private fun callArgument(text: String): RawArgument {
        if (file.isJava) return RawArgument(null, text)
        val match = NAMED_ARGUMENT.find(text) ?: return RawArgument(null, text)
        return RawArgument(match.groupValues[1], text.substring(match.range.last + 1).trim())
    }

    /** 동사 인자 값의 모양을 읽는다 — 리터럴·상수, `Enum.CASE`, `Enum.CASE.name`, import한 enum 상수. */
    private fun argumentValue(text: String, offset: Int): ArgumentValue {
        val trimmed = text.trim()
        resolver.literalValue(trimmed, offset)?.let { return ArgumentValue.Literal(it) }
        ENUM_NAME.matchEntire(trimmed)?.let { return ArgumentValue.Literal(it.groupValues[1]) }
        ENUM_CASE.matchEntire(trimmed)?.let { return ArgumentValue.EnumCase(it.groupValues[1]) }
        if (IDENTIFIER.matches(trimmed) && file.imports.any { it.path.endsWith(".$trimmed") && it.path.split('.').dropLast(1).lastOrNull()?.first()?.isUpperCase() == true }) {
            return ArgumentValue.EnumCase(trimmed)
        }
        return ArgumentValue.Opaque
    }

    // ---- java.net.URL ----

    private fun seesJavaNetUrl(): Boolean =
        file.imports.any { it.path == "java.net.URL" || it.path == "java.net.*" } || "java.net.URL" in file.masked

    private fun scanUrl(match: MatchResult) {
        if (isDeclarationSite(match.range.first)) return
        val open = match.range.last
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return
        val arguments = callArguments(file.code, open, close)
        if (arguments.size != 1 || arguments.single().isBlank()) return
        val start = match.range.first
        val parts = resolver.parts(arguments.single(), start)
        val composed = RouteUrlRules.compose(parts, JoinMode.Concat(null))
        val request = urlRequest(start, close)
        // URL을 여는 요청도, 다른 호출로 넘기는 흐름도 없으면 검증·파싱용 URL이다 — 호출이 아니다.
        if (request == null && !urlPassedOn(start, close)) return
        // 매개변수에서 온 경로는 요청을 다른 함수에서 열어도 싱크다 — 요청 확인보다 먼저 센다.
        if (isParameterSink(composed, parts, start) || request == null) return
        if (composed.limitation != null) stats.ambiguousJoins++
        emit(start, request, composed, arguments.single(), service = null, fallbackAnchor = "base")
    }

    /**
     * URL 식이 실제 요청으로 이어지는지와 그 동사를 정한다.
     *
     * @return 동사(`null`은 methodDynamic을 뜻하는 빈 문자열 대신 [METHOD_DYNAMIC]), 요청을 못 찾으면 null
     */
    private fun urlRequest(start: Int, close: Int): String? {
        val chained = REQUEST_CHAIN.find(file.masked.substring(close + 1).take(128))
        val (action, actionOffset) = if (chained != null) chained.groupValues[1] to close + 1 + chained.range.first
        else variableRequest(start, close) ?: return null
        if (action != "openConnection") return "GET"
        return connectionMethod(actionOffset) ?: METHOD_DYNAMIC
    }

    /** URL 식이 곧바로 호출 인자이거나, 담은 변수가 같은 함수의 다른 호출 인자로 넘어가는지 본다. */
    private fun urlPassedOn(start: Int, close: Int): Boolean {
        val before = file.masked.substring(0, start).trimEnd()
        val after = file.masked.substring(close + 1).trimStart()
        // `f(URL(x))`처럼 URL 값 자체가 인자일 때만이다 — `require(URL(x).protocol == …)`는 넘기지 않는다.
        if ((before.endsWith('(') || before.endsWith(',')) && (after.startsWith(',') || after.startsWith(')'))) return true
        val lineStart = file.masked.lastIndexOf('\n', start) + 1
        val name = ASSIGNED_NAME.find(file.masked.substring(lineStart, start))?.groupValues?.get(1) ?: return false
        val end = file.enclosingFunction(start)?.end ?: return false
        return Regex("[(,]\\s*${Regex.escape(name)}\\s*[,)]").containsMatchIn(file.masked.substring(close, end.coerceAtMost(file.masked.length)))
    }

    /** `val url = URL(...)` 뒤의 `url.openConnection()` 같은 요청을 같은 함수에서 찾는다. */
    private fun variableRequest(start: Int, close: Int): Pair<String, Int>? {
        val lineStart = file.masked.lastIndexOf('\n', start) + 1
        val name = ASSIGNED_NAME.find(file.masked.substring(lineStart, start))?.groupValues?.get(1) ?: return null
        val function = file.enclosingFunction(start) ?: return null
        val pattern = Regex("\\b${Regex.escape(name)}\\s*(?:\\?|!!)?\\s*\\.\\s*(openConnection|openStream|readText|readBytes)\\s*\\(")
        val found = pattern.find(file.masked.substring(0, function.end.coerceAtMost(file.masked.length)), close) ?: return null
        return found.groupValues[1] to found.range.first
    }

    /**
     * 연결 객체의 `requestMethod`/`setRequestMethod`와 `doOutput`으로 동사를 정한다. 조건부 대입이나
     * 서로 다른 값이면 증명하지 못한 것으로 null을 돌려준다. 대입이 없으면 라이브러리 기본값 GET이다.
     */
    private fun connectionMethod(actionOffset: Int): String? {
        val function = file.enclosingFunction(actionOffset)
        val statementStart = file.masked.lastIndexOf('\n', actionOffset) + 1
        val end = function?.end ?: file.masked.length
        val region = file.masked.substring(0, end.coerceAtMost(file.masked.length))
        val assignments = METHOD_ASSIGNMENT.findAll(region, actionOffset).toList()
        if (assignments.isEmpty()) {
            val output = DO_OUTPUT.findAll(region, actionOffset).toList()
            return when {
                output.isEmpty() -> "GET"
                output.all { unconditional(statementStart, it.range.first) } -> "POST"
                else -> null
            }
        }
        val values = assignments.map { assignment ->
            val valueStart = assignment.range.last + 1
            val valueEnd = if (assignment.value.trimEnd().endsWith("(")) balancedEnd(file.code, assignment.range.last) else file.statementEnd(valueStart)
            resolver.literalValue(file.code.substring(valueStart, valueEnd.coerceAtLeast(valueStart)).trim(), valueStart)
        }
        val verb = values.distinct().singleOrNull()?.takeIf { it in RouteUrlRules.VERBS } ?: return null
        return verb.takeIf { assignments.size > 1 || unconditional(statementStart, assignments.single().range.first) }
    }

    /** 대입이 연결 문장과 같은 블록이거나 그 문장에 이어진 `apply`/`with`/`run`/`also`/`use` 블록 안인지 본다. */
    private fun unconditional(statementStart: Int, offset: Int): Boolean {
        // `if (flag) connection.requestMethod = …`처럼 같은 줄 앞에 조건이 있으면 조건부다.
        val linePrefix = file.masked.substring(file.masked.lastIndexOf('\n', offset - 1) + 1, offset).trim()
        if (linePrefix.isNotEmpty() && !STATEMENT_RECEIVER.matches(linePrefix)) return false
        val opens = ArrayDeque<Int>()
        for (index in statementStart until offset) {
            when (file.masked[index]) {
                '{' -> opens.addLast(index)
                '}' -> if (opens.removeLastOrNull() == null) return false
            }
        }
        return when (opens.size) {
            0 -> true
            1 -> SCOPE_FUNCTION.containsMatchIn(file.masked.substring(statementStart, opens.single()))
            else -> false
        }
    }

    // ---- Retrofit ----

    private fun scanRetrofit(match: MatchResult) {
        val verbName = match.groupValues[1]
        val afterName = match.range.last + 1
        val openIndex = file.masked.substring(afterName).indexOfFirst { !it.isWhitespace() }.let { if (it < 0) -1 else afterName + it }
        val hasArgs = openIndex >= 0 && file.masked[openIndex] == '('
        val close = if (hasArgs) balancedEnd(file.code, openIndex) else -1
        val arguments = if (hasArgs && close > openIndex) callArguments(file.code, openIndex, close).map(::callArgument) else emptyList()
        val method = retrofitMethod(verbName, arguments, match.range.first)
        val pathText = if (verbName == "HTTP") arguments.firstOrNull { it.label == "path" }?.text ?: arguments.getOrNull(1)?.takeIf { it.label == null }?.text
        else arguments.firstOrNull { it.label == "value" }?.text ?: arguments.firstOrNull { it.label == null }?.text
        val signature = retrofitSignature(if (close > 0) close + 1 else afterName) ?: return
        val composed = if (signature.second || pathText == null) ComposedRoute(template = null, dynamic = true)
        else retrofitRoute(pathText, match.range.first)
        val qualifiedName = (listOf(file.packageName).filter { it.isNotEmpty() } + file.enclosingTypes(match.range.first).map { it.name } + signature.first).joinToString(".")
        emit(match.range.first, method, composed, pathText.takeUnless { signature.second }, service = null, fallbackAnchor = "base", symbolName = qualifiedName)
    }

    private fun retrofitMethod(verbName: String, arguments: List<RawArgument>, offset: Int): String {
        if (verbName != "HTTP") return verbName
        val expression = arguments.firstOrNull { it.label == "method" }?.text ?: arguments.firstOrNull { it.label == null }?.text
        return expression?.let { resolver.literalValue(it, offset) }?.takeIf { it in RouteUrlRules.VERBS } ?: METHOD_DYNAMIC
    }

    /** 어노테이션 뒤 첫 함수의 (이름, `@Url` 매개변수 여부)다. 함수가 없으면 null이다. */
    private fun retrofitSignature(from: Int): Pair<String, Boolean>? {
        val window = file.masked.substring(from, (from + 2_000).coerceAtMost(file.masked.length))
        val cleaned = RETROFIT_LEADING_ANNOTATIONS.find(window)?.let { from + it.range.last + 1 } ?: from
        val header = (if (file.isJava) JAVA_SIGNATURE else KOTLIN_SIGNATURE).find(file.masked, cleaned) ?: return null
        if (header.range.first - cleaned > 1_000) return null
        val open = header.range.last
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return null
        val usesUrl = callArguments(file.code, open, close).any { URL_PARAMETER.containsMatchIn(it) }
        return header.groupValues[1] to usesUrl
    }

    /** Retrofit 상대 경로는 RFC 3986 해석이다 — `/x`는 root, `x`는 base, `{name}`은 경로 매개변수다. */
    private fun retrofitRoute(pathText: String, offset: Int): ComposedRoute {
        val parts = resolver.parts(pathText, offset).flatMap { part ->
            if (part !is UrlPart.Literal) return@flatMap listOf(part)
            val expanded = mutableListOf<UrlPart>()
            var last = 0
            RETROFIT_PLACEHOLDER.findAll(part.text).forEach { placeholder ->
                expanded += UrlPart.Literal(part.text.substring(last, placeholder.range.first))
                expanded += UrlPart.Value(placeholder.value)
                last = placeholder.range.last + 1
            }
            expanded += UrlPart.Literal(part.text.substring(last))
            expanded
        }
        val first = (parts.firstOrNull() as? UrlPart.Literal)?.text.orEmpty()
        val anchor = if (first.startsWith('/') || first.contains("://")) "root" else "base"
        val rooted = if (anchor == "base") listOf(UrlPart.Literal("/")) + parts else parts
        return RouteUrlRules.compose(rooted, JoinMode.Declared(anchor))
    }

    // ---- 공통 ----

    /**
     * 경로 구조 전체가 감싸는 함수의 매개변수에서 오는지 본다. 그러면 그 함수가 싱크라 사실을 내지 않는다 —
     * 선언된 래퍼가 덮으면 조용히 건너뛰고, 아니면 `http-wrapper-undeclared:`로만 센다.
     */
    private fun isParameterSink(composed: ComposedRoute, parts: List<UrlPart>, offset: Int): Boolean {
        // 리터럴 경로 구조(`/`를 담은 리터럴)가 있으면 매개변수는 세그먼트 값일 뿐이다 — 싱크가 아니라 호출이다.
        if (!composed.dynamic || parts.any { it is UrlPart.Literal && '/' in it.text }) return false
        val function = file.enclosingFunction(offset) ?: return false
        val parameters = function.parameters.associateBy { it.name }
        val referenced = parts.filterIsInstance<UrlPart.Value>().mapNotNull { LEADING_IDENTIFIER.find(it.expression)?.value }
            .mapNotNull(parameters::get)
        if (referenced.isEmpty()) return false
        if (!coveredSink(function, referenced, offset)) stats.undeclaredSinks++
        return true
    }

    /** 싱크 함수가 선언된 래퍼 자신이거나, 래퍼 소유 타입 안이거나, 래퍼 타입 매개변수를 받는지 본다. */
    private fun coveredSink(function: RouteFunctionDecl, referenced: List<RouteParameter>, offset: Int): Boolean =
        wrappers.any { wrapper ->
            val decl = wrapper.declaration
            (decl.kind == "function" && function.name == decl.name) ||
                insideOwner(decl.owner, offset) ||
                (decl.kind == "constructor" && referenced.any { it.type == wrapper.ownerSimple })
        }

    private fun emit(
        start: Int,
        method: String?,
        composed: ComposedRoute,
        expression: String?,
        service: String?,
        fallbackAnchor: String,
        symbolName: String? = file.qualifiedName(start),
    ) {
        val methodDynamic = method == null || method == METHOD_DYNAMIC
        facts += BridgeFact(
            kind = "route-call",
            channel = if (composed.dynamic) dynamicChannel(expression) else composed.template,
            method = method.takeUnless { methodDynamic },
            dynamic = composed.dynamic,
            location = sourceLocation(file.relative, file.source, start),
            symbol = symbolName?.let { BridgeSymbol(it) },
            target = "http",
            channelPrefix = composed.channelPrefix.takeIf { composed.dynamic },
            route = RouteCallEvidence(
                pathAnchor = composed.pathAnchor ?: fallbackAnchor,
                methodDynamic = methodDynamic,
                authority = composed.authority,
                service = service,
                queryTailStripped = composed.queryTailStripped && !composed.dynamic,
                maskedSegments = composed.maskedSegments.takeIf { it > 0 && !composed.dynamic },
                testSource = includeTests && file.isTest,
            ),
        )
    }

    /** dynamic 원문은 리터럴이 없는 코드 식만 싣는다 — 문자열 안의 host·token이 새지 않게 한다. */
    private fun dynamicChannel(expression: String?): String? {
        val text = expression?.replace(CONTROL, " ")?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (text.isEmpty() || '"' in text || '\'' in text) return null
        return text.take(RouteUrlRules.MAX_TEMPLATE_LENGTH)
    }

    private companion object {
        /** 동사를 증명하지 못했음을 [emit]에 알리는 표식이다. */
        const val METHOD_DYNAMIC = "<dynamic>"

        val QUALIFIER_NOISE = Regex("\\s+|\\?|!!")
        val IDENTIFIER = Regex("[A-Za-z_]\\w*")
        val LEADING_IDENTIFIER = Regex("^[A-Za-z_]\\w*")
        val NAMED_ARGUMENT = Regex("^\\s*([A-Za-z_]\\w*)\\s*=(?!=)")
        val ENUM_CASE = Regex("(?:[A-Za-z_]\\w*\\.)*[A-Z]\\w*\\.([A-Za-z_]\\w*)")
        val ENUM_NAME = Regex("(?:[A-Za-z_]\\w*\\.)*[A-Z]\\w*\\.([A-Za-z_]\\w*)\\.name")
        val DECLARATION_PREFIX = Regex(
            "(?:\\b(?:fun|class|object|interface|typealias|constructor)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?|@|(?<!\\?):\\s*|\\bimport\\s+[\\w.]*)$",
        )
        val URL_CALL = Regex("(?<![\\w.])(?:new\\s+)?(?:java\\.net\\.)?URL\\s*\\(")
        val REQUEST_CHAIN = Regex("^\\s*(?:\\?|!!)?\\s*\\.\\s*(openConnection|openStream|readText|readBytes)\\s*\\(")
        val ASSIGNED_NAME = Regex("(?:\\b(?:val|var)\\s+|\\bURL\\s+)([A-Za-z_]\\w*)\\s*(?::\\s*[\\w.?]+\\s*)?=\\s*(?:new\\s+)?$")
        val METHOD_ASSIGNMENT = Regex("(?:\\brequestMethod\\s*=(?!=)|\\bsetRequestMethod\\s*\\()")
        val STATEMENT_RECEIVER = Regex("^[A-Za-z_]\\w*\\s*(?:\\?|!!)?\\s*\\.$")
        val DO_OUTPUT = Regex("\\bdoOutput\\s*=\\s*true\\b|\\bsetDoOutput\\s*\\(\\s*true\\s*\\)")
        val SCOPE_FUNCTION = Regex("\\b(?:apply|run|also|use|with\\s*\\([^)]*\\))\\s*\\{?\\s*$|\\b(?:apply|run|also|use)\\s*$")
        val RETROFIT = Regex("@(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|HTTP)\\b")
        val RETROFIT_LEADING_ANNOTATIONS = Regex("^(?:\\s*@[A-Za-z_][\\w.]*(?:\\s*\\([^()]*\\))?)*")
        val KOTLIN_SIGNATURE = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?([A-Za-z_]\\w*)\\s*\\(")
        val JAVA_SIGNATURE = Regex("([A-Za-z_]\\w*)\\s*\\(")
        val URL_PARAMETER = Regex("@(?:retrofit2\\.http\\.)?Url\\b")
        val RETROFIT_PLACEHOLDER = Regex("\\{[a-zA-Z][a-zA-Z0-9_-]*}")
        val CONTROL = Regex("\\p{C}")
    }
}
