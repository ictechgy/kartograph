package dev.kartograph.index

/**
 * 재작성이 요청 경로·method를 보존하는지에 대한 증거다.
 *
 * @property pathPreserved 요청 URL의 authority(scheme·host·port)만 바꾸고 경로는 그대로 둠을 증명했다
 * @property methodPreserved 요청 method를 바꾸지 않음을 증명했다
 */
internal data class RewriteEffect(val pathPreserved: Boolean, val methodPreserved: Boolean) {
    /** 둘 다 적용될 때의 증거다(하나라도 증명하지 못하면 증명하지 못한다). */
    fun and(other: RewriteEffect): RewriteEffect = RewriteEffect(pathPreserved && other.pathPreserved, methodPreserved && other.methodPreserved)

    companion object {
        /** 경로·method 보존을 증명하지 못한 재작성이다. */
        val UNPROVEN: RewriteEffect = RewriteEffect(pathPreserved = false, methodPreserved = false)
    }
}

/**
 * 프로젝트의 URL 재작성 인터셉터를 찾아, 각 인터셉터가 어느 `addInterceptor`·`addNetworkInterceptor` 호출로 어느 OkHttp 빌더에
 * 붙는지 거꾸로 따라간다.
 *
 * 재작성 지점([RequestRewrites])은 `intercept` 함수 몸체, `Interceptor { … }` 람다, `addInterceptor { … }` 블록, 부착 호출의 인자
 * 괄호(Java 람다), Java `Interceptor x = …` 초기식 안에서만 센다(#123 규칙에 새 요청 생성·괄호 안 람다를 더함). 재작성을 가진
 * 개체(이름 있는 class·object와 그 하위 타입, 익명 객체, 람다)가 만들어지는 모든 곳에서 값이 흘러가는 곳을 따라간다 — 부착 호출의
 * 인자, 지역·속성 변수의 모든 참조, 함수 반환값의 모든 호출(`@Provides`면 같은 타입의 주입 지점), 이름 있는 타입의 모든 언급(생성·
 * 매개변수·필드 주입 타입). 흐름을 하나라도 증명하지 못하거나(목록·반복문·다른 함수 인자·리플렉션) 부착한 빌더의 원천을 증명하지
 * 못하면 [unboundReason]을 남긴다 — 그러면 호출자는 #123처럼 모든 base를 버린다.
 *
 * `authenticator`·`proxyAuthenticator`·`eventListener`·`eventListenerFactory` 부착은 재작성하지 않음을 증명하지 못하면
 * ([OkHttpClientResolver.attachmentClean]) 재작성으로 세고, 같은 방식으로 빌더의 원천을 증명한다.
 *
 * @param files 스캔한 production source 파일이다
 * @param clients OkHttpClient 해석기다(빌더 원천 증명과 부착 판정을 공유한다)
 */
internal class InterceptorRewriteIndex(private val files: List<RouteSourceFile>, private val clients: OkHttpClientResolver) {
    private val declarations = clients.declarations
    private val scopes = clients.scopes
    private var reason: String? = null
    private val siteList = mutableListOf<SourcePosition>()
    private val adderEffects = mutableMapOf<SourcePosition, RewriteEffect>()
    private var unclean = 0

    init {
        collectInterceptorRewrites()
        collectAttachments()
    }

    /** 인터셉터 안의 재작성 지점들이다(`url-rewrite-interceptors:`의 계수). */
    val sites: List<SourcePosition> get() = siteList

    /** URL을 바꾸는 인터셉터가 붙은 부착 호출 위치 → 그 인터셉터들의 경로·method 보존 증거다. */
    val rewritingAdders: Map<SourcePosition, RewriteEffect> get() = adderEffects

    /** 재작성하지 않음을 증명하지 못한 `Authenticator`·`EventListener` 부착 수다. */
    val uncleanAttachments: Int get() = unclean

    /** 재작성 인터셉터·부착 중 하나라도 결합을 증명하지 못한 이유다. null이면 모든 재작성이 원천을 아는 빌더에 붙었다. */
    val unboundReason: String? get() = reason

    /** 인스턴스별 판단이 필요한 재작성의 수다. 0이면 client를 몰라도 base를 버릴 이유가 없다. */
    val population: Int get() = siteList.size + unclean

    private fun fail(why: String) {
        if (reason == null) reason = why
    }

    // ---- 인터셉터 재작성 ----

    /** 재작성 개체다. 같은 개체의 여러 재작성 지점은 한 번만 따라간다. */
    private sealed interface Owner {
        /** 이름 있는 class·object다. */
        data class Named(val fqn: String) : Owner

        /** 식 하나로 만든 인터셉터(람다·익명 객체·Java 초기식)다. [start, end)가 그 식이다. */
        data class Creation(val file: String, val start: Int, val end: Int) : Owner

        /** 부착 호출에 직접 쓴 인터셉터(`addInterceptor { … }`, 괄호 안 람다)다. */
        data class Adder(val position: SourcePosition) : Owner
    }

    /** 재작성 지점을 감싸는 인터셉터 문맥이다. [owner]가 null이면 개체를 정하지 못했다. [chainParam]은 `Interceptor.Chain` 이름이다. */
    private data class Context(val range: IntRange, val owner: Owner?, val region: IntRange, val chainParam: String?)

    private fun collectInterceptorRewrites() {
        val owners = linkedMapOf<Owner, Pair<RouteSourceFile, Context>>()
        // `addInterceptor(chain -> …)`처럼 `Interceptor`를 import하지 않는 Java 람다도 있어 단어 경계 없이 거른다.
        files.filter { it.source.isNotEmpty() && "Interceptor" in it.masked }.forEach { file ->
            val contexts = contexts(file)
            (RequestRewrites.sites(file) + foreignProceeds(file, contexts)).distinct().sorted().forEach { site ->
                val context = contexts.filter { site in it.range }.maxByOrNull { it.range.first } ?: return@forEach
                siteList += SourcePosition(file.relative, site)
                val owner = context.owner ?: return@forEach fail("an interceptor rewrite is not inside a class, object or lambda")
                owners.putIfAbsent(owner, file to context)
            }
        }
        owners.forEach { (owner, located) ->
            val (file, context) = located
            // 개체가 자기 자신(`this`)을 값으로 넘기면(등록·반환) 생성 지점 밖으로 흐름이 새어 따라갈 수 없다.
            val body = if (owner is Owner.Named) declarations.type(owner.fqn)?.let { (typeFile, type) -> typeFile to (type.bodyStart..type.bodyEnd) } else file to context.region
            if (body == null || THIS_VALUE.containsMatchIn(body.first.masked.substring(body.second.first, (body.second.last + 1).coerceAtMost(body.first.masked.length)))) {
                return@forEach fail("a URL-rewriting interceptor passes itself (this) as a value, so its OkHttp client is not resolved")
            }
            var effect = if (owner is Owner.Named) namedEffect(owner.fqn) else effect(file, context.region, context.chainParam)
            val adders = when (owner) {
                is Owner.Adder -> setOf(owner.position)
                is Owner.Creation -> dest(file, owner.start, owner.end, emptySet(), SourceValueResolver.Budget())
                is Owner.Named -> namedDestinations(owner.fqn)?.let { (adders, subtyped) ->
                    // 하위 타입은 intercept를 다시 정의할 수 있어 상위 몸체의 보존 증거를 물려주지 않는다.
                    if (subtyped) effect = RewriteEffect.UNPROVEN
                    adders
                }
            } ?: return@forEach fail("a URL-rewriting interceptor is created or passed where its OkHttp client is not resolved")
            // 어디에도 붙지 않은 재작성 인터셉터는 보지 못한 경로(생성 코드·다른 모듈)로 붙었을 수 있다.
            if (adders.isEmpty()) return@forEach fail("a URL-rewriting interceptor is not attached to any OkHttpClient.Builder in the scanned sources")
            adders.forEach { adder ->
                val adderFile = files.first { it.relative == adder.file }
                if (!adderResolvable(adderFile, adder.offset)) fail("a URL-rewriting interceptor is added to an OkHttpClient.Builder whose origin is not resolved")
                adderEffects[adder] = adderEffects[adder]?.and(effect) ?: effect
            }
        }
    }

    /**
     * 인터셉터 문맥 안에서 원래 요청(`chain.request()`)이나 그 `newBuilder()` 사본이 아닌 요청을 넘기는 `chain.proceed(x)` 위치다.
     * 다른 곳에서 만든 요청(필드·생성자 인자·헬퍼 함수 결과)은 어휘 재작성 호출 없이도 요청이 가는 곳을 바꾼다.
     */
    private fun foreignProceeds(file: RouteSourceFile, contexts: List<Context>): List<Int> = PROCEED.findAll(file.masked).mapNotNull { match ->
        val context = contexts.filter { match.range.first in it.range }.maxByOrNull { it.range.first } ?: return@mapNotNull null
        val open = match.range.last
        val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@mapNotNull match.range.first
        val argument = singleArgument(file, open..close) ?: return@mapNotNull match.range.first
        match.range.first.takeUnless { fromOriginalRequest(file, argument.first, argument.last + 1, context.region, match.groupValues[1], 0) }
    }.toList()

    /** [file]의 인터셉터 문맥들이다. */
    private fun contexts(file: RouteSourceFile): List<Context> = buildList {
        val masked = file.masked
        file.functions.filter { it.name == "intercept" && it.bodyStart >= 0 }.forEach { function ->
            val range = function.bodyStart..function.end.coerceAtMost(masked.length - 1)
            val owner = memberOwner(file, function.start)
            val region = (owner as? Owner.Named)?.let { declarations.type(it.fqn)?.second }?.let { it.bodyStart..it.bodyEnd } ?: range
            add(Context(range, owner, (owner as? Owner.Creation)?.let { it.start until it.end } ?: region, function.parameters.firstOrNull()?.name))
        }
        INTERCEPTOR_BLOCK.findAll(masked).forEach { match ->
            val open = match.range.last
            val close = file.braceEnd(open).takeIf { it > open } ?: return@forEach
            val owner = if (match.groupValues[1] == "Interceptor") Owner.Creation(file.relative, match.range.first, close + 1)
            else Owner.Adder(SourcePosition(file.relative, match.range.first))
            add(Context(open..close, owner, open..close, LAMBDA_PARAMETER.find(masked, open)?.takeIf { it.range.first == open }?.groupValues?.get(1) ?: "it"))
        }
        ADDER_PARENTHESES.findAll(masked).forEach { match ->
            val open = match.range.last
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@forEach
            val parameter = JAVA_LAMBDA_PARAMETER.find(masked, open + 1)?.takeIf { it.range.first == open + 1 }?.groupValues?.get(1)
            add(Context(open..close, Owner.Adder(SourcePosition(file.relative, match.range.first)), open..close, parameter))
        }
        if (file.isJava) JAVA_INTERCEPTOR_DECLARATION.findAll(masked).forEach { match ->
            val start = skipSpaces(masked, match.range.last + 1)
            val end = file.statementEnd(start)
            val parameter = JAVA_LAMBDA_PARAMETER.find(masked, start)?.takeIf { it.range.first == start }?.groupValues?.get(1)
            add(Context(start until end, Owner.Creation(file.relative, start, end), start until end, parameter))
        }
    }

    /**
     * `intercept` 함수 [functionStart]를 가진 개체다 — 감싸는 `{`가 이름 있는 타입의 몸체면 그 타입, Kotlin `object : …`·Java
     * `new X(…) {`의 몸체면 그 식이다. 그 밖(최상위·지역 함수)은 null이다.
     */
    private fun memberOwner(file: RouteSourceFile, functionStart: Int): Owner? {
        val open = enclosingOpener(file.masked, functionStart).takeIf { it >= 0 && file.masked[it] == '{' } ?: return null
        file.types.firstOrNull { it.bodyStart == open }?.let { return Owner.Named(typeFqn(file, it)) }
        val close = file.braceEnd(open).takeIf { it > open } ?: return null
        val head = file.masked.substring((open - 300).coerceAtLeast(0), open)
        ANONYMOUS_OBJECT.find(head)?.let { return Owner.Creation(file.relative, open - head.length + it.range.first, close + 1) }
        ANONYMOUS_CLASS.find(head)?.let { return Owner.Creation(file.relative, open - head.length + it.range.first, close + 1) }
        return null
    }

    /** 이름 있는 재작성 타입의 효과다. 하위 타입이 상속해 쓰는 경우까지 증명하지 않으므로 몸체 하나로만 본다. */
    private fun namedEffect(fqn: String): RewriteEffect {
        val (file, type) = declarations.type(fqn) ?: return RewriteEffect.UNPROVEN
        if (type.bodyStart < 0) return RewriteEffect.UNPROVEN
        val intercept = file.functions.firstOrNull { it.name == "intercept" && it.start in type.bodyStart..type.bodyEnd }
        return effect(file, type.bodyStart..type.bodyEnd, intercept?.parameters?.firstOrNull()?.name)
    }

    /**
     * [region] 안의 재작성이 경로·method를 보존하는지다. 경로 보존은 새 요청을 만들지 않고, 경로 변경 메서드가 없고, 모든
     * `url(x)`의 `x`가 원래 요청 URL의 authority·query만 바꾼 사본(`chain.request().url.newBuilder().host(h).build()`)일 때만이다.
     */
    private fun effect(file: RouteSourceFile, region: IntRange, chainParam: String?): RewriteEffect {
        if (RequestRewrites.buildsFreshRequest(file, region)) return RewriteEffect.UNPROVEN
        // 다음 단계로 넘기는 요청이 모두 원래 요청(또는 그 newBuilder 사본)이어야 한다 — 다른 곳에서 온 요청은 URL을 모른다.
        if (!proceedsOriginal(file, region, chainParam)) return RewriteEffect.UNPROVEN
        var path = true
        var method = true
        RequestRewrites.builders(file, region).forEach { from ->
            val calls = RequestRewrites.calls(file, from)
            if (calls.any { it.name in RequestRewrites.METHOD_METHODS }) method = false
            if (calls.any { it.name in RequestRewrites.PATH_METHODS }) path = false
            if (calls.any { it.name == "url" && !authorityOnlyUrl(file, it.arguments, region, chainParam, 0) }) path = false
        }
        return RewriteEffect(path, method)
    }

    /** [region]의 모든 `chain.proceed(x)`의 `x`가 원래 요청이거나 그 `newBuilder()` 사본인지 본다. proceed가 없으면 증명하지 못한다. */
    private fun proceedsOriginal(file: RouteSourceFile, region: IntRange, chainParam: String?): Boolean {
        if (chainParam == null) return false
        val proceed = Regex("(?<![\\w.])${Regex.escape(chainParam)}\\s*\\.\\s*proceed\\s*\\(")
        val calls = proceed.findAll(file.masked.substring(0, (region.last + 1).coerceAtMost(file.masked.length)), region.first).toList()
        return calls.isNotEmpty() && calls.all { match ->
            val open = match.range.last
            val close = balancedEnd(file.code, open).takeIf { it > open } ?: return@all false
            val argument = singleArgument(file, open..close) ?: return@all false
            fromOriginalRequest(file, argument.first, argument.last + 1, region, chainParam, 0)
        }
    }

    /** 식이 원래 요청, 또는 원래 요청의 `newBuilder()…build()` 사본(지역 `val` 포함)인지 본다. 사본의 URL 변경은 [effect]가 따로 본다. */
    private fun fromOriginalRequest(file: RouteSourceFile, from: Int, end: Int, region: IntRange, chainParam: String, depth: Int): Boolean {
        if (depth > 4) return false
        val segments = chainSegments(file, from, end) ?: return false
        if (originalRequest(file, segments, from, region, chainParam, depth)) return true
        segments.singleOrNull()?.takeIf { it.isPlain }?.let { single ->
            val (isVal, range) = scopes.localInitializer(file, single.name, from) ?: return false
            return isVal && range.first in region && fromOriginalRequest(file, skipSpaces(file.masked, range.first), range.last + 1, region, chainParam, depth + 1)
        }
        val newBuilder = segments.indexOfFirst { it.name == "newBuilder" && emptyCall(file, it) }
        return newBuilder >= 1 && originalRequest(file, segments.subList(0, newBuilder), from, region, chainParam, depth)
    }

    /** 인자 괄호 [arguments]의 식이 원래 요청 URL에서 authority·query만 바꾼 사본인지 본다. */
    private fun authorityOnlyUrl(file: RouteSourceFile, arguments: IntRange, region: IntRange, chainParam: String?, depth: Int): Boolean {
        if (chainParam == null || depth > 4) return false
        val argument = singleArgument(file, arguments) ?: return false
        return authorityOnlyUrlExpression(file, argument.first, argument.last + 1, region, chainParam, depth)
    }

    private fun authorityOnlyUrlExpression(file: RouteSourceFile, from: Int, end: Int, region: IntRange, chainParam: String, depth: Int): Boolean {
        if (depth > 4) return false
        val segments = chainSegments(file, from, end) ?: return false
        segments.singleOrNull()?.takeIf { it.isPlain }?.let { single ->
            val (isVal, range) = scopes.localInitializer(file, single.name, from) ?: return false
            return isVal && range.first in region && authorityOnlyUrlExpression(file, skipSpaces(file.masked, range.first), range.last + 1, region, chainParam, depth + 1)
        }
        val newBuilder = segments.indexOfFirst { it.name == "newBuilder" && emptyCall(file, it) }
        if (newBuilder < 2) return false
        val accessor = segments[newBuilder - 1]
        if (accessor.name != "url" || accessor.lambda != null || accessor.arguments != null && !emptyCall(file, accessor)) return false
        if (!originalRequest(file, segments.subList(0, newBuilder - 1), from, region, chainParam, depth)) return false
        val rest = segments.drop(newBuilder + 1)
        return rest.isNotEmpty() && rest.last().name == "build" && rest.all { it.name in AUTHORITY_URL_METHODS && it.lambda == null }
    }

    /** 수신 사슬이 인터셉터가 받은 원래 요청(`chain.request()`, 그것을 담은 지역 `val`, `chain.request().let { r -> … }`)인지 본다. */
    private fun originalRequest(file: RouteSourceFile, receiver: List<ChainSegment>, offset: Int, region: IntRange, chainParam: String, depth: Int): Boolean {
        if (receiver.size == 2 && receiver[0].isPlain && receiver[0].name == chainParam && receiver[1].name == "request" && emptyCall(file, receiver[1])) return true
        val single = receiver.singleOrNull()?.takeIf { it.isPlain } ?: return false
        scopes.localInitializer(file, single.name, offset)?.let { (isVal, range) ->
            val segments = chainSegments(file, skipSpaces(file.masked, range.first), range.last + 1) ?: return false
            return isVal && range.first in region && depth < 4 && originalRequest(file, segments, range.first, region, chainParam, depth + 1)
        }
        val let = Regex("(?<![\\w.])${Regex.escape(chainParam)}\\s*\\.\\s*request\\s*\\(\\s*\\)\\s*\\.\\s*let\\s*\\{" +
            if (single.name == "it") "(?!\\s*[A-Za-z_]\\w*\\s*(?:,[^-]*)?->)" else "\\s*${Regex.escape(single.name)}\\s*->")
        return let.findAll(file.masked.substring(0, offset), region.first).any { match ->
            val open = file.masked.indexOf('{', match.range.first)
            file.braceEnd(open) > offset && !rebound(file, single.name, open, offset)
        }
    }

    /**
     * `let` 블록 [open]과 사용 위치 [offset] 사이에서 이름 [name]이 다시 묶이는지 본다 — 같은 이름의 람다 매개변수·지역 선언, `it`이면
     * 사용 위치를 감싸는 안쪽 람다 블록이다.
     */
    private fun rebound(file: RouteSourceFile, name: String, open: Int, offset: Int): Boolean {
        val between = file.masked.substring(open + 1, offset)
        if (name == "it") {
            var depth = 0
            between.forEach { character -> if (character == '{') depth++ else if (character == '}') depth-- }
            return depth > 0
        }
        val escaped = Regex.escape(name)
        return Regex("[{(,]\\s*$escaped\\s*(?:[,:)]|->)|\\b(?:val|var)\\s+$escaped\\b").containsMatchIn(between)
    }

    private fun emptyCall(file: RouteSourceFile, segment: ChainSegment): Boolean =
        segment.lambda == null && segment.arguments?.let { file.masked.substring(it.first + 1, it.last).isBlank() } == true

    /**
     * 이름 있는 재작성 타입 [fqn](과 그 하위 타입)의 값이 닿는 부착 호출들이다. 프로젝트의 모든 언급을 분류하고 하나라도 흐름을
     * 증명하지 못하면 null이다.
     */
    private fun namedDestinations(fqn: String): Pair<Set<SourcePosition>, Boolean>? {
        val types = linkedSetOf(fqn)
        val queue = ArrayDeque(listOf(fqn))
        val result = mutableSetOf<SourcePosition>()
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            val simple = type.substringAfterLast('.')
            val isObject = declarations.type(type)?.let { (file, decl) -> file.masked.startsWith("object", decl.start) } == true
            val flow = types.map { it.substringAfterLast('.') }.toSet()
            for (file in files) {
                if (file.source.isEmpty() || simple !in file.masked) continue
                for (match in Regex("(?<![\\w])${Regex.escape(simple)}(?![\\w])").findAll(file.masked)) {
                    val start = qualifiedStart(file.masked, match.range.first)
                    if (!mentions(file, start, match.range.last + 1, type)) continue
                    val outcome = classifyMention(file, start, match.range.last + 1, isObject, flow) ?: return null
                    outcome.subtype?.let { if (types.add(it)) queue += it }
                    result += outcome.adders
                }
            }
        }
        return result to (types.size > 1)
    }

    /** 언급 하나의 분류 결과다. [subtype]은 이 언급이 상위 타입 목록일 때의 하위 타입이다. */
    private data class MentionOutcome(val adders: Set<SourcePosition> = emptySet(), val subtype: String? = null)

    /** [start, end)의 이름이 타입 [fqn]을 가리키는지 본다. import·package 줄과 같은 이름의 다른 타입은 아니다. */
    private fun mentions(file: RouteSourceFile, start: Int, end: Int, fqn: String): Boolean {
        val lineStart = file.masked.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
        val line = file.masked.substring(lineStart, start).trimStart()
        if (line.startsWith("import") || line.startsWith("package")) return false
        val dotted = file.masked.substring(start, end).replace(WHITESPACE, "")
        val resolved = declarations.resolveType(file, dotted)
        if (resolved != null) return resolved == fqn
        val simple = dotted.substringAfterLast('.')
        // 같은 단순 이름의 다른 타입을 명시 import한 파일은 그 타입을 쓴다.
        return file.imports.none { (it.alias ?: it.path.substringAfterLast('.')) == simple && it.path != fqn && !it.path.endsWith("*") }
    }

    /**
     * 재작성 타입 언급 하나를 분류한다. 선언 자신·상위 타입 목록(하위 타입 추가)·반환 타입은 흐름이 아니고, 생성·object 참조는
     * 그 식의 흐름을, 매개변수·속성·필드·지역 변수의 타입은 그 변수 참조들의 흐름을 따른다. 그 밖(`::class`·캐스트·제네릭
     * 인자 등)은 증명하지 못해 null이다.
     */
    private fun classifyMention(file: RouteSourceFile, start: Int, end: Int, isObject: Boolean, flow: Set<String>): MentionOutcome? {
        val masked = file.masked
        val before = masked.substring((start - 300).coerceAtLeast(0), start).trimEnd()
        if (DECLARATION_KEYWORD.containsMatchIn(before)) return MentionOutcome()
        val after = skipSpaces(masked, end)
        if (masked.startsWith("::", after) || before.endsWith("::")) return null
        val header = file.types.firstOrNull { type -> type.start < start && start < headerEnd(file, type) }
        if (header != null && parenthesisDepth(masked, header.start, start) == 0) return MentionOutcome(subtype = typeFqn(file, header))
        if (header != null && before.endsWith(":") && !file.isJava) return constructorParameter(file, header, start, flow)?.let { MentionOutcome(it) }
        var cursor = start - 1
        while (cursor >= 0 && masked[cursor].isWhitespace()) cursor--
        val isNew = cursor >= 2 && masked.startsWith("new", cursor - 2) && (cursor < 3 || !(masked[cursor - 3].isLetterOrDigit() || masked[cursor - 3] == '_'))
        val newStart = if (isNew) cursor - 2 else start
        if (masked.getOrNull(after) == '(' && !before.endsWith(":")) {
            // Java 생성자 선언(`T(…) {`)은 흐름이 아니다. `new` 없는 그 밖의 Java 호출 모양은 증명하지 못한다.
            if (file.isJava && !isNew) return if (JAVA_METHOD_TYPE_TAIL.containsMatchIn(masked.substring(after))) MentionOutcome() else null
            val close = balancedEnd(file.code, after).takeIf { it > after } ?: return null
            var exprEnd = close + 1
            val brace = skipSpaces(masked, exprEnd)
            if (isNew && masked.getOrNull(brace) == '{') exprEnd = file.braceEnd(brace).takeIf { it > brace }?.plus(1) ?: return null
            return dest(file, newStart, exprEnd, flow, SourceValueResolver.Budget())?.let { MentionOutcome(it) }
        }
        if (!file.isJava && before.endsWith(":")) return kotlinTypePosition(file, start, before, flow)?.let { MentionOutcome(it) }
        if (file.isJava) javaTypePosition(file, start, end, flow)?.let { return MentionOutcome(it) }
        if (isObject) {
            val instanceEnd = Regex("\\G\\s*\\.\\s*INSTANCE\\b").find(masked, end)?.range?.last?.plus(1) ?: end
            return dest(file, start, instanceEnd, flow, SourceValueResolver.Budget())?.let { MentionOutcome(it) }
        }
        return null
    }

    /** Kotlin `name: T` 타입 표기다 — 반환 타입이면 흐름이 아니고, `val`/`var`면 변수, 아니면 함수 매개변수다. */
    private fun kotlinTypePosition(file: RouteSourceFile, start: Int, before: String, flow: Set<String>): Set<SourcePosition>? {
        val beforeColon = before.dropLast(1).trimEnd()
        if (beforeColon.endsWith(")")) return emptySet()
        val name = IDENTIFIER_TAIL.find(beforeColon)?.value ?: return null
        val nameStart = file.masked.lastIndexOf(name, start)
        val declaration = beforeColon.dropLast(name.length).trimEnd()
        if (VAL_VAR_TAIL.containsMatchIn(declaration)) return variable(file, nameStart, name, flow)
        return parameter(file, start, name, flow)
    }

    /** Java 타입 위치 `T name` 다 — 메서드 반환 타입이면 흐름이 아니고, 매개변수·지역 변수·필드면 그 참조들이다. */
    private fun javaTypePosition(file: RouteSourceFile, start: Int, end: Int, flow: Set<String>): Set<SourcePosition>? {
        val masked = file.masked
        var index = skipSpaces(masked, end)
        if (masked.getOrNull(index) == '<') index = skipSpaces(masked, balancedAngle(masked, index)?.plus(1) ?: return null)
        while (masked.startsWith("[]", index)) index = skipSpaces(masked, index + 2)
        val name = Regex("\\G[A-Za-z_]\\w*").find(masked, index)?.value ?: return null
        val afterName = skipSpaces(masked, index + name.length)
        return when (masked.getOrNull(afterName)) {
            '(' -> emptySet()
            '=', ';', ',', ')' -> if (file.functions.any { function -> parameterRange(file, function)?.let { start in it } == true }) parameter(file, start, name, flow)
            else variable(file, index, name, flow)
            else -> null
        }
    }

    /** 함수 매개변수 [name]의 참조들의 흐름이다. 몸체 없는 함수(`@Binds`·추상)는 증명하지 못한다. */
    private fun parameter(file: RouteSourceFile, typePosition: Int, name: String, flow: Set<String>): Set<SourcePosition>? {
        val function = file.functions.firstOrNull { parameterRange(file, it)?.let { range -> typePosition in range } == true } ?: return null
        val close = parameterRange(file, function)!!.last
        if (function.bodyStart < 0 || function.bodyStart <= close || function.end <= function.bodyStart) return null
        return references(file, name, (close + 1)..function.end.coerceAtMost(file.masked.length - 1), null, flow, property = false)
    }

    /** Kotlin 주 생성자 매개변수다. 공개 `val`·`var` 속성이면 프로젝트 전체, 아니면 타입 몸체 안의 참조만 본다. */
    private fun constructorParameter(file: RouteSourceFile, type: RouteTypeDecl, typePosition: Int, flow: Set<String>): Set<SourcePosition>? {
        val before = file.masked.substring(type.start, typePosition).trimEnd().dropLast(1).trimEnd()
        val name = IDENTIFIER_TAIL.find(before)?.value ?: return null
        val parameterStart = maxOf(before.lastIndexOf('('), before.lastIndexOf(','))
        val modifiers = before.substring(parameterStart + 1, before.length - name.length)
        val nameStart = type.start + before.length - name.length
        val isProperty = VAL_VAR_TAIL.containsMatchIn(modifiers.trimEnd())
        if (isProperty && !Regex("\\bprivate\\b").containsMatchIn(modifiers)) return projectReferences(name, nameStart, file, flow)
        val end = if (type.bodyStart >= 0) type.bodyEnd else headerEnd(file, type)
        return references(file, name, (typePosition + 1)..end.coerceAtMost(file.masked.length - 1), nameStart, flow, property = true)
    }

    /** 변수 선언(지역 변수·속성·필드) [nameStart]의 모든 참조의 흐름이다. */
    private fun variable(file: RouteSourceFile, nameStart: Int, name: String, flow: Set<String>): Set<SourcePosition>? {
        val function = file.enclosingFunction(nameStart)
        if (function != null && nameStart > function.bodyStart) {
            return references(file, name, (nameStart + name.length)..function.end.coerceAtMost(file.masked.length - 1), nameStart, flow, property = false)
        }
        val lineStart = file.masked.lastIndexOf('\n', (nameStart - 1).coerceAtLeast(0)) + 1
        if (Regex("\\bprivate\\b").containsMatchIn(file.masked.substring(lineStart, nameStart))) {
            return references(file, name, file.masked.indices, nameStart, flow, property = true)
        }
        return projectReferences(name, nameStart, file, flow)
    }

    /** 공개 속성 [name]의 프로젝트 전체 참조의 흐름이다. 같은 이름의 지역 선언이 가린 참조는 뺀다. */
    private fun projectReferences(name: String, declarationStart: Int, declarationFile: RouteSourceFile, flow: Set<String>): Set<SourcePosition>? {
        val result = mutableSetOf<SourcePosition>()
        for (file in files) {
            if (file.source.isEmpty() || name !in file.masked) continue
            result += references(file, name, file.masked.indices, declarationStart.takeIf { file === declarationFile }, flow, property = true) ?: return null
        }
        return result
    }

    /**
     * [range] 안 [name] 참조마다의 흐름이다. 대입의 왼쪽·명명 인자 이름·다른 선언(`name: T`)은 값을 읽지 않으므로 뺀다.
     * 한정자 없는 참조를 같은 이름의 지역 선언·매개변수가 가리면 이 변수가 아니다.
     */
    private fun references(
        file: RouteSourceFile,
        name: String,
        range: IntRange,
        declarationStart: Int?,
        flow: Set<String>,
        property: Boolean,
    ): Set<SourcePosition>? {
        val masked = file.masked
        val result = mutableSetOf<SourcePosition>()
        val pattern = Regex("(?<![\\w])${Regex.escape(name)}(?![\\w])")
        for (match in pattern.findAll(masked.substring(0, (range.last + 1).coerceAtMost(masked.length)), range.first.coerceAtLeast(0))) {
            if (match.range.first == declarationStart) continue
            val after = skipSpaces(masked, match.range.last + 1)
            if (masked.getOrNull(after) == '=' && masked.getOrNull(after + 1) != '=') continue
            if (masked.getOrNull(after) == ':' && masked.getOrNull(after + 1) != ':') continue
            val start = qualifiedStart(masked, match.range.first)
            if (property && start == match.range.first && scopes.shadowedLocally(file, name, match.range.first)) continue
            result += dest(file, start, match.range.last + 1, flow, SourceValueResolver.Budget()) ?: return null
        }
        return result
    }

    /**
     * 식 [start, end)의 값이 흘러가는 부착 호출들이다. 부착 호출의 인자·끝 람다면 그 호출, 변수 초기식이면 그 변수의 참조들,
     * `return`·식 몸체면 그 함수의 호출들이다. 멤버 접근·캐스트·다른 호출의 인자·그 밖의 문장은 증명하지 못해 null이다.
     */
    private fun dest(file: RouteSourceFile, start: Int, end: Int, flow: Set<String>, budget: SourceValueResolver.Budget): Set<SourcePosition>? {
        if (!budget.enter(file, start)) return null
        try {
            val masked = file.masked
            val after = skipSpaces(masked, end)
            if (masked.getOrNull(after) == '.' || masked.startsWith("?.", after) || masked.startsWith("!!", after) || masked.getOrNull(after) == '[' ||
                masked.startsWith("::", after) || AS_CAST.find(masked, after)?.range?.first == after) return null
            val opener = enclosingOpener(masked, start)
            if (opener >= 0) {
                val call = identifierBefore(masked, opener)
                if (call != null && call.second in OkHttpClientResolver.ADDERS && masked[opener] != '[') return setOf(SourcePosition(file.relative, call.first))
                if (masked[opener] != '{') return null
            }
            return statementDest(file, start, end, opener, flow, budget)
        } finally {
            budget.leave(file, start)
        }
    }

    /** 블록 안 문장 수준의 식 [start, end)의 흐름이다(변수 초기식·`return`·식 몸체·lazy 본문의 마지막 식). */
    private fun statementDest(file: RouteSourceFile, start: Int, end: Int, opener: Int, flow: Set<String>, budget: SourceValueResolver.Budget): Set<SourcePosition>? {
        val masked = file.masked
        if (masked.substring(end, file.statementEnd(start).coerceAtLeast(end)).isNotBlank()) return null
        val function = file.enclosingFunction(start)
        if (function != null && masked.getOrNull(function.bodyStart) == '=' && skipSpaces(masked, function.bodyStart + 1) == start) {
            return functionReturn(file, function, flow, budget)
        }
        val tailStart = (start - 400).coerceAtLeast(if (opener >= 0) opener + 1 else 0)
        val tail = masked.substring(tailStart, start)
        if (RETURN_TAIL.containsMatchIn(tail)) return function?.let { functionReturn(file, it, flow, budget) }
        (KOTLIN_DECLARATION_TAIL.find(tail) ?: JAVA_DECLARATION_TAIL.find(tail)?.takeIf { file.isJava && it.groupValues[1] !in JAVA_NON_TYPES })?.let { match ->
            val group = match.groups[2]!!
            return variable(file, tailStart + group.range.first, group.value, flow)
        }
        declarations.properties(file).firstOrNull { property -> property.lazyBody?.let { start in it } == true }?.let { property ->
            val last = scopes.lastStatement(file, property.lazyBody!!) ?: return null
            if (skipSpaces(masked, last.first) == start) return variable(file, masked.indexOf(property.name, property.start), property.name, flow)
        }
        return null
    }

    /**
     * 함수 반환값의 흐름이다 — 모든 호출의 흐름이다. `@Provides`면 DI가 같은 타입의 주입 지점으로 넘기므로 선언한 반환 타입이
     * 재작성 타입([flow])일 때만 받는다(그 주입 지점은 타입 언급으로 따로 따라간다). 메서드 참조는 증명하지 못한다.
     */
    private fun functionReturn(file: RouteSourceFile, function: RouteFunctionDecl, flow: Set<String>, budget: SourceValueResolver.Budget): Set<SourcePosition>? {
        if (PROVIDES.containsMatchIn(functionAnnotations(file, function))) {
            val type = declaredReturnType(file, function) ?: return null
            if (type.substringAfterLast('.') !in flow) return null
        }
        val result = mutableSetOf<SourcePosition>()
        val pattern = Regex("(?<![\\w])${Regex.escape(function.name)}(?![\\w])")
        for (candidate in files) {
            if (candidate.source.isEmpty() || function.name !in candidate.masked) continue
            val declared = candidate.functions.filter { it.name == function.name }.mapNotNull { declaration ->
                parameterListOpen(candidate, declaration)?.let { open -> candidate.masked.lastIndexOf(function.name, open) }
            }.toSet()
            for (match in pattern.findAll(candidate.masked)) {
                if (match.range.first in declared) continue
                // 메서드 참조(`::name`, `Type::name`)는 값을 어디로 넘기는지 모른다. 호출이 아닌 그 밖의 같은 이름은 다른 식별자다.
                if (candidate.masked.substring((match.range.first - 3).coerceAtLeast(0), match.range.first).trimEnd().endsWith("::")) return null
                val open = skipSpaces(candidate.masked, match.range.last + 1)
                if (candidate.masked.getOrNull(open) != '(') continue
                val close = balancedEnd(candidate.code, open).takeIf { it > open } ?: return null
                result += dest(candidate, qualifiedStart(candidate.masked, match.range.first), close + 1, flow, budget) ?: return null
            }
        }
        return result
    }

    /** 함수가 선언한 반환 타입 표기다. 없으면 null이다. */
    private fun declaredReturnType(file: RouteSourceFile, function: RouteFunctionDecl): String? {
        val range = parameterRange(file, function) ?: return null
        if (file.isJava) return IDENTIFIER_TAIL.find(file.masked.substring(function.start, range.first).trimEnd().removeSuffix(function.name).trimEnd())?.value
        val header = file.masked.substring(range.last + 1, function.bodyStart.coerceAtLeast(range.last + 1))
        return Regex("^\\s*:\\s*([A-Za-z_][\\w.]*)\\s*$").find(header)?.groupValues?.get(1)
    }

    /**
     * 부착 호출 [offset]이 원천을 증명할 수 있는 OkHttp 빌더에 걸렸는지 본다 — 수신 사슬이 있으면 그 사슬, `this`·`it`·수신
     * 객체 없는 호출이면 감싸는 `apply`·`also`·`run` 블록의 수신 사슬이다. 조건문·반복문 블록은 건너 올라간다.
     */
    private fun adderResolvable(file: RouteSourceFile, offset: Int): Boolean {
        val masked = file.masked
        var index = offset - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        if (index >= 0 && masked[index] == '.') {
            val start = receiverStart(file, index) ?: return false
            val segments = chainSegments(file, start, if (masked.getOrNull(index - 1) == '?') index - 1 else index) ?: return false
            val implicit = segments.singleOrNull()?.takeIf { it.isPlain && (it.name == "this" || it.name == "it") }
            if (implicit == null) return clients.builderResolvable(file, segments, offset)
        }
        return enclosingBuilderBlock(file, offset)
    }

    private fun enclosingBuilderBlock(file: RouteSourceFile, offset: Int): Boolean {
        val masked = file.masked
        var position = offset
        repeat(MAX_BLOCK_DEPTH) {
            val open = enclosingOpener(masked, position).takeIf { it >= 0 && masked[it] == '{' } ?: return false
            val block = identifierBefore(masked, open)
            if (block != null && block.second in setOf("apply", "also", "run")) {
                var dot = block.first - 1
                while (dot >= 0 && masked[dot].isWhitespace()) dot--
                if (dot < 0 || masked[dot] != '.') return false
                val start = receiverStart(file, dot) ?: return false
                val segments = chainSegments(file, start, if (masked.getOrNull(dot - 1) == '?') dot - 1 else dot) ?: return false
                return clients.builderResolvable(file, segments, offset)
            }
            if (!CONTROL_BLOCK_TAIL.containsMatchIn(masked.substring((open - 200).coerceAtLeast(0), open)) && !controlParentheses(masked, open)) return false
            position = open
        }
        return false
    }

    /** `{` 바로 앞이 `if (…)`·`for (…)`·`while (…)`·`catch (…)`의 괄호인지 본다. */
    private fun controlParentheses(masked: String, open: Int): Boolean {
        var index = open - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        if (index < 0 || masked[index] != ')') return false
        val paren = matchingOpenParenthesis(masked, index) ?: return false
        return identifierBefore(masked, paren)?.second in setOf("if", "for", "while", "catch", "synchronized")
    }

    // ---- Authenticator·EventListener ----

    private fun collectAttachments() {
        files.filter { file -> file.source.isNotEmpty() && (file.imports.any { it.path.startsWith("okhttp3") } || "okhttp3." in file.masked) }.forEach { file ->
            ATTACHMENT_CALL.findAll(file.masked).forEach { match ->
                val before = file.masked.substring((match.range.first - 20).coerceAtLeast(0), match.range.first)
                if (Regex("\\bfun\\s+$").containsMatchIn(before) || file.functions.any { it.name == match.value.trim() && it.start <= match.range.first &&
                        parameterListOpen(file, it)?.let { open -> match.range.first < open } == true }) return@forEach
                val shape = clients.callShape(file, match.range.last)
                if (shape != null && clients.attachmentClean(file, shape.first, shape.second, authenticator = "uthenticator" in match.value)) return@forEach
                unclean++
                if (!adderResolvable(file, match.range.first)) fail("an Authenticator or EventListener that may rewrite requests is attached to an OkHttpClient.Builder whose origin is not resolved")
            }
        }
    }

    private companion object {
        const val MAX_BLOCK_DEPTH = 16
        val WHITESPACE = Regex("\\s+")
        val PROCEED = Regex("(?<![\\w.])([A-Za-z_]\\w*)\\s*\\.\\s*proceed\\s*\\(")
        /** 멤버 접근(`this.x`)·레이블(`this@X`)이 아닌 값으로 쓴 `this`다. */
        val THIS_VALUE = Regex("(?<![\\w.@])this(?!\\s*(?:[.@\\w]|\\?\\.))")
        /** 인터셉터 람다·등록 블록의 여는 괄호다(`Interceptor { … }`, `addInterceptor { … }`). */
        val INTERCEPTOR_BLOCK = Regex("\\b(Interceptor|addInterceptor|addNetworkInterceptor)\\s*\\{")
        val ADDER_PARENTHESES = Regex("\\b(?:addInterceptor|addNetworkInterceptor)\\s*\\(")
        val JAVA_INTERCEPTOR_DECLARATION = Regex("\\bInterceptor\\s+[A-Za-z_]\\w*\\s*=(?!=)")
        val LAMBDA_PARAMETER = Regex("\\{\\s*([A-Za-z_]\\w*)\\s*->")
        val JAVA_LAMBDA_PARAMETER = Regex("\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?\\s*->")
        val ANONYMOUS_OBJECT = Regex("\\bobject\\s*:\\s*[^{};=]*$")
        val ANONYMOUS_CLASS = Regex("\\bnew\\s+[\\w.<>]+\\s*\\([^()]*\\)\\s*$")
        val DECLARATION_KEYWORD = Regex("\\b(?:class|interface|object)$")
        val IDENTIFIER_TAIL = Regex("[A-Za-z_]\\w*$")
        val VAL_VAR_TAIL = Regex("\\b(?:val|var)$")
        val AS_CAST = Regex("\\bas\\b\\??")
        val RETURN_TAIL = Regex("(?:^|[;\\n{])\\s*return\\s*$")
        val KOTLIN_DECLARATION_TAIL = Regex(
            "(?:^|[;\\n{(])\\s*(?:@[\\w.]+(?:\\([^()]*\\))?\\s*)*(?:(?:private|internal|public|protected|override|lateinit|open|final|const)\\s+)*" +
                "(val|var)\\s+([A-Za-z_]\\w*)\\s*(?::[^=;{}\\n]*)?=\\s*$",
        )
        val JAVA_DECLARATION_TAIL = Regex(
            "(?:^|[;\\n{(])\\s*(?:@[\\w.]+(?:\\([^()]*\\))?\\s*)*(?:(?:private|protected|public|static|final|transient|volatile)\\s+)*" +
                "([A-Za-z_][\\w.]*)(?:<[^;=(){}]*>)?(?:\\[\\])*\\s+([A-Za-z_]\\w*)\\s*=\\s*$",
        )
        val JAVA_NON_TYPES = setOf("return", "new", "throw", "else", "case")
        /** Java 메서드 선언 `T name(…) {`의 괄호 뒤다(반환 타입 언급을 생성으로 읽지 않기 위해). */
        val JAVA_METHOD_TYPE_TAIL = Regex("^\\([^()]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{")
        val CONTROL_BLOCK_TAIL = Regex("(?:\\belse|\\btry|\\bdo|->|\\bfinally)\\s*$")
        val ATTACHMENT_CALL = Regex("(?<![\\w])(?:authenticator|proxyAuthenticator|eventListener|eventListenerFactory)(?=\\s*[({])")
        val PROVIDES = Regex("@(?:dagger\\.)?Provides\\b")
        /** 경로·method와 무관한 `HttpUrl.Builder` 메서드다(authority·userinfo·query·fragment). */
        val AUTHORITY_URL_METHODS = setOf(
            "host", "scheme", "port", "username", "password", "encodedUsername", "encodedPassword", "query", "encodedQuery",
            "addQueryParameter", "addEncodedQueryParameter", "setQueryParameter", "setEncodedQueryParameter", "removeAllQueryParameters",
            "removeAllEncodedQueryParameters", "fragment", "encodedFragment", "build",
        )
    }
}

/** [start] 앞에서 짝이 맞지 않은 가장 안쪽 여는 괄호(`(`·`[`·`{`) 위치다. 없으면 -1이다. */
internal fun enclosingOpener(masked: String, start: Int): Int {
    var depth = 0
    for (index in start - 1 downTo 0) {
        when (masked[index]) {
            ')', ']', '}' -> depth++
            '(', '[', '{' -> { if (depth == 0) return index; depth-- }
        }
    }
    return -1
}

/** 괄호 [open] 바로 앞 식별자의 (시작 위치, 이름)이다. */
private fun identifierBefore(masked: String, open: Int): Pair<Int, String>? {
    var end = open - 1
    while (end >= 0 && masked[end].isWhitespace()) end--
    var start = end
    while (start >= 0 && (masked[start].isLetterOrDigit() || masked[start] == '_')) start--
    if (start == end) return null
    return (start + 1) to masked.substring(start + 1, end + 1)
}

/** 이름 [nameStart] 앞의 `a.b.` 한정자까지 포함한 시작 위치다. */
private fun qualifiedStart(masked: String, nameStart: Int): Int {
    var start = nameStart
    while (true) {
        var index = start - 1
        while (index >= 0 && masked[index].isWhitespace()) index--
        if (index < 0 || masked[index] != '.') return start
        index--
        while (index >= 0 && masked[index].isWhitespace()) index--
        var identifier = index
        while (identifier >= 0 && (masked[identifier].isLetterOrDigit() || masked[identifier] == '_')) identifier--
        if (identifier == index) return start
        start = identifier + 1
    }
}

/** 타입 머리(선언 시작부터 몸체 `{` 또는 문장 끝까지)의 끝이다. */
private fun headerEnd(file: RouteSourceFile, type: RouteTypeDecl): Int = if (type.bodyStart >= 0) type.bodyStart else file.statementEnd(type.start)

/** [from]부터 [to]까지 열린 `(` 깊이다. */
private fun parenthesisDepth(masked: String, from: Int, to: Int): Int {
    var depth = 0
    for (index in from until to) {
        when (masked[index]) {
            '(' -> depth++
            ')' -> depth--
        }
    }
    return depth
}

/** 함수 매개변수 괄호의 (`(`, `)`) 범위다. */
private fun parameterRange(file: RouteSourceFile, function: RouteFunctionDecl): IntRange? {
    val open = parameterListOpen(file, function) ?: return null
    val close = balancedEnd(file.code, open).takeIf { it > open } ?: return null
    return open..close
}

/** `<`에 짝이 맞는 `>` 위치다. */
private fun balancedAngle(masked: String, open: Int): Int? {
    var depth = 0
    for (index in open until masked.length) {
        when (masked[index]) {
            '<' -> depth++
            '>' -> { depth--; if (depth == 0) return index }
            ';', '{', '}', '=' -> return null
        }
    }
    return null
}

/** 닫는 `)` [close]에 짝이 맞는 `(` 위치다. */
private fun matchingOpenParenthesis(masked: String, close: Int): Int? {
    var depth = 0
    for (index in close downTo 0) {
        when (masked[index]) {
            ')' -> depth++
            '(' -> { depth--; if (depth == 0) return index }
        }
    }
    return null
}
