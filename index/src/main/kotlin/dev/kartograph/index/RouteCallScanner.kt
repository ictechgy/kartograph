package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.HttpWrapperDeclaration
import dev.kartograph.core.RouteCallEvidence
import dev.kartograph.core.TestSourceSets
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
            (includeTests || !file.isTest) && file.imports.any(::importsUnmodeledClient) &&
                wrappers.none { wrapper -> declaresWrapper(file, wrapper) }
        }
        if (unmodeled > 0) add(
            "route-call-coverage: $unmodeled source file(s) use HTTP clients this scanner does not model (OkHttp, Ktor, Volley, java.net.http, Spring, Feign); their requests are not reported",
        )
    }

    /**
     * import가 모델링하지 않은 클라이언트의 요청 API인지 본다. OkHttp의 본문·미디어 타입·헤더 값 타입만 쓰는 파일(Retrofit
     * 서비스 인터페이스의 `RequestBody`·`ResponseBody`)은 요청을 보내지 않으므로 세지 않는다.
     */
    private fun importsUnmodeledClient(import: RouteImport): Boolean {
        // 값 타입 자신과 그 중첩 타입·동반 객체 확장(`RequestBody.Companion.create`, `MultipartBody.Part`)을 모두 뺀다.
        val okhttpType = import.path.takeIf { it.startsWith("okhttp3.") }?.removePrefix("okhttp3.")?.substringBefore('.')
        return okhttpType !in OKHTTP_VALUE_TYPES && UNMODELED_CLIENTS.any { import.path.startsWith(it) }
    }

    /** 파일이 래퍼 구현 자체(소유 타입 또는 최상위 래퍼 함수)를 선언하는지 본다. */
    private fun declaresWrapper(file: RouteSourceFile, wrapper: ResolvedWrapper): Boolean =
        file.types.any { typeFqn(file, it) == wrapper.declaration.owner } ||
            (!wrapper.isMember && file.packageName == wrapper.ownerPackage && file.functions.any { it.name == wrapper.declaration.name })

    private fun isTestSource(relative: String): Boolean = TestSourceSets.isTestSourcePath(relative)

    // 코덱이 owner 모양을 검증하지만 API로 직접 넘긴 선언도 스캔 전체를 죽이지 않게 빈 조각을 견딘다.
    private fun conventionalPackage(declaration: HttpWrapperDeclaration): String {
        val segments = declaration.owner.split('.')
        return if (isConventionalClass(declaration)) segments.takeWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")
        else declaration.owner
    }

    private fun isConventionalClass(declaration: HttpWrapperDeclaration): Boolean {
        val simple = declaration.owner.substringAfterLast('.')
        return declaration.kind == "constructor" || simple.firstOrNull()?.isUpperCase() == true && !simple.endsWith("Kt")
    }

    private companion object {
        /** 이 스캐너가 모델링하지 않는 HTTP 클라이언트 import 접두사다. */
        val UNMODELED_CLIENTS = listOf(
            "okhttp3.", "io.ktor.client", "com.android.volley", "java.net.http.", "org.springframework.web.client",
            "org.springframework.web.reactive.function.client", "feign.",
        )

        /** 요청을 만들거나 보내지 않는 OkHttp 값 타입(단순 이름)이다. 이것만 import한 파일은 모델링하지 않은 호출의 근거가 아니다. */
        val OKHTTP_VALUE_TYPES = setOf("RequestBody", "ResponseBody", "MediaType", "MultipartBody", "FormBody", "Headers")
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
        val importsRetrofit = file.imports.any { it.path.startsWith("retrofit2.http.") }
        // 짧은 이름(`@GET`)은 Retrofit import가 있을 때만, 완전한 이름(`@retrofit2.http.GET`)은 언제나 Retrofit이다.
        RETROFIT.findAll(file.masked).filter { importsRetrofit || it.groups[1] != null }.forEach(::scanRetrofit)
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
        // 빈 이름·식별자가 아닌 이름은 호출 regex를 "모든 `(`"로 넓힌다 — 호출을 찾지 않고 미해석으로 남긴다.
        if (!IDENTIFIER.matches(name)) return emptyList()
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
        if (IDENTIFIER.matches(trimmed) && file.imports.any { it.path.endsWith(".$trimmed") && it.path.split('.').dropLast(1).lastOrNull()?.firstOrNull()?.isUpperCase() == true }) {
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
     * @return 동사(증명하지 못하면 [METHOD_DYNAMIC]), 요청을 못 찾으면 null
     */
    private fun urlRequest(start: Int, close: Int): String? {
        val chained = REQUEST_CHAIN.find(file.masked.substring(close + 1).take(128))
        val (action, actionOffset) = if (chained != null) chained.groupValues[1] to close + 1 + chained.groups[1]!!.range.first
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
        val name = assignedName(start) ?: return false
        val end = file.enclosingFunction(start)?.end ?: return false
        return Regex("[(,]\\s*${Regex.escape(name)}\\s*[,)]").containsMatchIn(file.masked.substring(close, end.coerceAtMost(file.masked.length)))
    }

    /** `val url = URL(...)` 뒤의 `url.openConnection()` 같은 요청을 같은 함수에서 찾는다. 반환 위치는 동작 이름이다. */
    private fun variableRequest(start: Int, close: Int): Pair<String, Int>? {
        val name = assignedName(start) ?: return null
        val function = file.enclosingFunction(start) ?: return null
        val pattern = Regex("\\b${Regex.escape(name)}\\s*(?:\\?|!!)?\\s*\\.\\s*(openConnection|openStream|readText|readBytes)\\s*\\(")
        val found = pattern.find(file.masked.substring(0, function.end.coerceAtMost(file.masked.length)), close) ?: return null
        return found.groupValues[1] to found.groups[1]!!.range.first
    }

    /** [offset]의 식을 대입받는 변수 이름이다(`val url =`, `URL url =`). 줄바꿈 뒤로 이어진 대입도 따라간다. */
    private fun assignedName(offset: Int): String? =
        ASSIGNED_NAME.find(file.masked.substring(statementStart(offset), offset))?.groupValues?.get(1)

    /**
     * [offset]을 담은 문장이 시작하는 위치다. 앞 줄이 대입 `=`로 끝나면(`val url =` 줄바꿈 뒤 식) 그 줄부터다.
     */
    private fun statementStart(offset: Int): Int {
        var lineStart = file.masked.lastIndexOf('\n', offset - 1) + 1
        while (lineStart > 0) {
            val previous = file.masked.substring(0, lineStart).trimEnd()
            if (!previous.endsWith('=') || previous.length >= 2 && previous[previous.length - 2] in "=!<>") break
            lineStart = file.masked.lastIndexOf('\n', previous.length - 1) + 1
        }
        return lineStart
    }

    /**
     * 연결 객체의 `requestMethod`/`setRequestMethod`와 `doOutput`으로 동사를 정한다. 다른 객체의 대입은
     * 무시하고, 이 연결의 대입이 하나라도 조건부이거나 값이 서로 다르면 증명하지 못한 것(null)이다.
     * 이 연결의 대입이 없으면 라이브러리 기본값 GET이다.
     */
    private fun connectionMethod(actionOffset: Int): String? {
        val statement = statementStart(actionOffset)
        val connection = CONNECTION_NAME.find(file.masked.substring(statement, actionOffset))?.groupValues?.get(1)
        val end = file.enclosingFunction(actionOffset)?.end ?: file.masked.length
        val region = file.masked.substring(0, end.coerceAtMost(file.masked.length))
        fun ours(match: MatchResult) = belongsToConnection(match.range.first, connection)
        fun proven(match: MatchResult) = unconditional(statement, actionOffset, match.range.first, connection)
        val assignments = METHOD_ASSIGNMENT.findAll(region, actionOffset).filter(::ours).toList()
        if (assignments.isEmpty()) {
            val output = DO_OUTPUT.findAll(region, actionOffset).filter(::ours).toList()
            return when {
                output.isEmpty() -> "GET"
                output.all(::proven) -> "POST"
                else -> null
            }
        }
        val values = assignments.map { assignment ->
            val valueStart = assignment.range.last + 1
            val valueEnd = if (assignment.value.trimEnd().endsWith("(")) balancedEnd(file.code, assignment.range.last) else file.statementEnd(valueStart)
            resolver.literalValue(file.code.substring(valueStart, valueEnd.coerceAtLeast(valueStart)).trim(), valueStart)
        }
        val verb = values.distinct().singleOrNull()?.takeIf { it in RouteUrlRules.VERBS } ?: return null
        return verb.takeIf { assignments.all(::proven) }
    }

    /** 대입의 수신자가 다른 이름의 객체면(`other.requestMethod`) 이 연결의 대입이 아니다. */
    private fun belongsToConnection(offset: Int, connection: String?): Boolean {
        val receiver = RECEIVER_BEFORE.find(file.masked.substring((offset - 128).coerceAtLeast(0), offset))?.groupValues?.get(1)
            ?: return true
        return receiver == connection || receiver == "it" || receiver == "this"
    }

    /**
     * 대입이 연결 문장과 같은 블록에 있거나, 연결에 곧바로 이어진 범위 함수 블록 안에 있는지 본다.
     *
     * 범위 함수는 연결 문장에 사슬로 붙은 것(`….openConnection().apply {`)이나 연결 변수에 대한 독립 문장
     * (`c.apply {`·`with(c) {`)만 인정한다. 같은 줄의 조건(`if (x) c.requestMethod = …`)이나 연결을
     * 감싸지 않는 중첩 `if`/`when` 블록은 조건부다.
     */
    private fun unconditional(statement: Int, actionOffset: Int, offset: Int, connection: String?): Boolean {
        val opens = ArrayDeque<Int>()
        for (index in statement until offset) {
            when (file.masked[index]) {
                '{' -> opens.addLast(index)
                '}' -> if (opens.removeLastOrNull() == null) return false
            }
        }
        val localStart = maxOf(file.masked.lastIndexOf('\n', offset - 1), file.masked.lastIndexOf(';', offset - 1), opens.lastOrNull() ?: -1) + 1
        val local = file.masked.substring(localStart, offset).replace(QUALIFIER_NOISE, "")
        val ownReceiver = connection != null && local == "$connection."
        return when (opens.size) {
            0 -> ownReceiver
            1 -> (ownReceiver || local in SCOPE_RECEIVERS) && scopedOnConnection(statement, actionOffset, opens.single(), connection)
            else -> false
        }
    }

    /** [brace] 블록이 연결에 대한 범위 함수 블록인지 본다. */
    private fun scopedOnConnection(statement: Int, actionOffset: Int, brace: Int, connection: String?): Boolean {
        if (brace > actionOffset && CHAINED_SCOPE.matches(file.masked.substring(actionOffset, brace))) return true
        if (connection == null) return false
        val header = file.masked.substring(statement, brace)
        val segment = header.substring(maxOf(header.lastIndexOf('\n'), header.lastIndexOf(';')) + 1)
        val name = Regex.escape(connection)
        return Regex("^\\s*(?:$name\\s*(?:\\?|!!)?\\s*\\.\\s*(?:apply|run|also|use|let)|with\\s*\\(\\s*$name\\s*\\))\\s*$").matches(segment)
    }

    // ---- Retrofit ----

    private fun scanRetrofit(match: MatchResult) {
        val verbName = match.groupValues[2]
        val afterName = match.range.last + 1
        val openIndex = file.masked.substring(afterName).indexOfFirst { !it.isWhitespace() }.let { if (it < 0) -1 else afterName + it }
        val hasArgs = openIndex >= 0 && file.masked[openIndex] == '('
        val close = if (hasArgs) balancedEnd(file.code, openIndex) else -1
        // Java 어노테이션도 `name = value` 명명 요소를 쓴다 — 메서드 호출과 달리 Java에서도 이름을 읽는다.
        val arguments = if (hasArgs && close > openIndex) callArguments(file.code, openIndex, close).map(::annotationArgument) else emptyList()
        val method = retrofitMethod(verbName, arguments, match.range.first)
        val pathText = if (verbName == "HTTP") arguments.firstOrNull { it.label == "path" }?.text ?: arguments.getOrNull(1)?.takeIf { it.label == null }?.text
        else arguments.firstOrNull { it.label == "value" }?.text ?: arguments.firstOrNull { it.label == null }?.text
        val signature = retrofitSignature(if (close > 0) close + 1 else afterName) ?: return
        val composed = if (signature.usesUrl || pathText == null) ComposedRoute(template = null, dynamic = true)
        else retrofitRoute(pathText, match.range.first, signature.encodedPathNames)
        val qualifiedName = (listOf(file.packageName).filter { it.isNotEmpty() } + file.enclosingTypes(match.range.first).map { it.name } + signature.name).joinToString(".")
        emit(match.range.first, method, composed, pathText.takeUnless { signature.usesUrl }, service = null, fallbackAnchor = "base", symbolName = qualifiedName)
    }

    /** 어노테이션 인자 하나다. Java·Kotlin 모두 `name = value`면 명명 요소다. */
    private fun annotationArgument(text: String): RawArgument {
        val match = NAMED_ARGUMENT.find(text) ?: return RawArgument(null, text)
        return RawArgument(match.groupValues[1], text.substring(match.range.last + 1).trim())
    }

    private fun retrofitMethod(verbName: String, arguments: List<RawArgument>, offset: Int): String {
        if (verbName != "HTTP") return verbName
        val expression = arguments.firstOrNull { it.label == "method" }?.text ?: arguments.firstOrNull { it.label == null }?.text
        return expression?.let { resolver.literalValue(it, offset) }?.takeIf { it in RouteUrlRules.VERBS } ?: METHOD_DYNAMIC
    }

    /**
     * 어노테이션 뒤 첫 서비스 메서드다.
     *
     * @property usesUrl `@Url` 매개변수가 있어 URL 전체가 실행 시점 값이다
     * @property encodedPathNames `@Path(encoded = true)`인 매개변수 이름이다. 값의 `/`가 인코딩되지 않아 여러 세그먼트가 될 수 있다
     */
    private data class RetrofitSignature(val name: String, val usesUrl: Boolean, val encodedPathNames: Set<String>)

    /** 어노테이션 뒤 첫 함수의 이름과 매개변수 어노테이션이다. 함수가 없으면 null이다. */
    private fun retrofitSignature(from: Int): RetrofitSignature? {
        val window = file.masked.substring(from, (from + 2_000).coerceAtMost(file.masked.length))
        val cleaned = RETROFIT_LEADING_ANNOTATIONS.find(window)?.let { from + it.range.last + 1 } ?: from
        val header = (if (file.isJava) JAVA_SIGNATURE else KOTLIN_SIGNATURE).find(file.masked, cleaned) ?: return null
        if (header.range.first - cleaned > 1_000) return null
        val open = header.range.last
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return null
        val usesUrl = callArguments(file.code, open, close).any { URL_PARAMETER.containsMatchIn(it) }
        return RetrofitSignature(header.groupValues[1], usesUrl, encodedPathNames(open, close))
    }

    /** 매개변수 목록 `(`…`)`에서 `@Path(… encoded = true)`의 이름을 모은다. 이름을 증명하지 못한 인코딩 매개변수는 `*`다. */
    private fun encodedPathNames(open: Int, close: Int): Set<String> = PATH_PARAMETER.findAll(file.masked.substring(open, close)).mapNotNull { match ->
        val annotationOpen = open + match.range.last
        val annotationClose = balancedEnd(file.code, annotationOpen).takeIf { it > annotationOpen } ?: return@mapNotNull null
        val arguments = callArguments(file.code, annotationOpen, annotationClose).map(::annotationArgument)
        val encoded = arguments.firstOrNull { it.label == "encoded" }?.text?.trim() == "true" ||
            arguments.getOrNull(1)?.takeIf { it.label == null }?.text?.trim() == "true"
        if (!encoded) return@mapNotNull null
        val name = arguments.firstOrNull { it.label == "value" }?.text ?: arguments.firstOrNull { it.label == null }?.text
        name?.let { resolver.literalValue(it, annotationOpen) } ?: ANY_PATH_NAME
    }.toSet()

    /**
     * Retrofit 상대 경로는 RFC 3986 해석이다 — `/x`는 root, `x`는 base, `{name}`은 경로 매개변수이고 점 세그먼트는
     * OkHttp가 지운다. 어노테이션 값은 컴파일 타임 상수이므로 풀지 못한 조각(파일 밖 상수)은 값이 아니라 경로 구조를
     * 모르는 것이다 — query 뒤가 아니면 dynamic이다. `@Path(encoded = true)` 값은 `/`를 담아 세그먼트 경계를 넘을 수
     * 있으므로 그 자리부터 dynamic이다.
     */
    private fun retrofitRoute(pathText: String, offset: Int, encodedNames: Set<String>): ComposedRoute {
        val resolved = resolver.parts(pathText, offset)
        val known = resolved.takeWhile { it is UrlPart.Literal }.joinToString("") { (it as UrlPart.Literal).text }
        // 풀지 못한 조각이 query·fragment 뒤에 있으면 경로는 이미 확정됐다.
        val unknownInPath = resolved.any { it !is UrlPart.Literal } && '?' !in known && '#' !in known
        // 첫 조각부터 모르면 상대·절대 여부도 모른다 — 앵커와 접두사를 싣지 않는다.
        if (unknownInPath && known.isEmpty()) return ComposedRoute(template = null, dynamic = true)
        val anchor = if (known.startsWith('/') || ABSOLUTE_URL.containsMatchIn(known)) "root" else "base"
        val parts = mutableListOf<UrlPart>()
        if (anchor == "base") parts += UrlPart.Literal("/")
        var last = 0
        var multiSegment = false
        for (placeholder in RETROFIT_PLACEHOLDER.findAll(known)) {
            parts += UrlPart.Literal(known.substring(last, placeholder.range.first))
            val name = placeholder.value.removeSurrounding("{", "}")
            if (name in encodedNames || ANY_PATH_NAME in encodedNames) { multiSegment = true; break }
            parts += UrlPart.Value(placeholder.value)
            last = placeholder.range.last + 1
        }
        if (!multiSegment) parts += UrlPart.Literal(known.substring(last))
        val composed = RouteUrlRules.compose(parts, JoinMode.Declared(anchor, resolveDotSegments = true))
        if (!unknownInPath && !multiSegment) return composed
        // 모르는 자리 앞까지만 증명됐다 — 그 접두사를 channelPrefix로 싣는다.
        return ComposedRoute(
            template = null, dynamic = true, channelPrefix = composed.template ?: composed.channelPrefix, pathAnchor = anchor,
            authority = composed.authority,
        )
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
        val CONNECTION_NAME = Regex("^\\s*(?:(?:val|var)\\s+|[A-Za-z_][\\w.<>]*\\s+)([A-Za-z_]\\w*)\\s*(?::\\s*[\\w.?<>]+\\s*)?=(?!=)")
        val RECEIVER_BEFORE = Regex("([A-Za-z_]\\w*)\\s*(?:\\?|!!)?\\s*\\.\\s*$")
        val CHAINED_SCOPE = Regex("^openConnection\\s*\\(\\s*\\)(?:\\s*as\\??\\s*[\\w.]+)?\\s*\\)?\\s*(?:\\?|!!)?\\s*\\.\\s*(?:apply|run|also|use|let)\\s*$")
        val SCOPE_RECEIVERS = setOf("", "this.", "it.")
        val DO_OUTPUT = Regex("\\bdoOutput\\s*=\\s*true\\b|\\bsetDoOutput\\s*\\(\\s*true\\s*\\)")
        val RETROFIT = Regex("@(retrofit2\\s*\\.\\s*http\\s*\\.\\s*)?(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|HTTP)\\b")
        val RETROFIT_LEADING_ANNOTATIONS = Regex("^(?:\\s*@[A-Za-z_][\\w.]*(?:\\s*\\([^()]*\\))?)*")
        val KOTLIN_SIGNATURE = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?([A-Za-z_]\\w*)\\s*\\(")
        val JAVA_SIGNATURE = Regex("([A-Za-z_]\\w*)\\s*\\(")
        val URL_PARAMETER = Regex("@(?:retrofit2\\s*\\.\\s*http\\s*\\.\\s*)?Url\\b")
        val RETROFIT_PLACEHOLDER = Regex("\\{[a-zA-Z][a-zA-Z0-9_-]*}")
        val PATH_PARAMETER = Regex("@(?:retrofit2\\s*\\.\\s*http\\s*\\.\\s*)?Path\\s*\\(")
        /** 이름을 증명하지 못한 `@Path(encoded = true)`다 — 모든 자리를 여러 세그먼트일 수 있다고 본다. */
        const val ANY_PATH_NAME = "*"
        val ABSOLUTE_URL = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        val CONTROL = Regex("\\p{C}")
    }
}
