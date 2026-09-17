package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.CodeGraph
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Kotlin/Java 소스의 정적 리터럴만 추출하고 동적 이름은 버리지 않는 브리지 스캐너다. */
public class BridgeFactScanner(private val projectRoot: Path) {
    /** Flutter BasicMessageChannel 전용 bridge-facts v2를 opt-in으로 생성한다. */
    public fun scanMessages(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument =
        ChannelBridgeScanner(projectRoot, BASIC_MESSAGE_CHANNEL_SPEC).scan(generatedAt, graph)

    /** Flutter EventChannel 전용 bridge-facts v2를 opt-in으로 생성한다. */
    public fun scanEvents(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument =
        ChannelBridgeScanner(projectRoot, EVENT_CHANNEL_SPEC).scan(generatedAt, graph)

    /**
     * 프로젝트 상대 근거와 조인 불가능한 사실의 한계를 bridge-facts v1 문서로 만든다.
     * generatedAt을 생략하면 최신 source 수정 시각을 snapshot 시각으로 사용한다(빈 입력은 Unix epoch).
     */
    public fun scan(generatedAt: String? = null, graph: CodeGraph? = null, targetFilter: String? = null): BridgeFactsDocument {
        val facts = mutableListOf<BridgeFact>()
        val stats = ScanStats()
        val sources = mutableListOf<Path>()
        ProjectTraversal.walkSources(projectRoot) { sources.add(it) }
        sources.sorted().forEach { scanFile(it, facts, stats) }
        val ordered = facts.sortedWith(compareBy({ it.location.path }, { it.location.line }, { it.kind }, { it.method.orEmpty() }))
        val filtered = targetFilter?.let { target -> ordered.filter { it.target == target } } ?: ordered
        val withSymbols = filtered.map { graph?.let { snapshot -> attachSnapshotSymbol(it, snapshot, projectRoot) } ?: it }
        val counts = filtered.groupingBy(BridgeFact::target).eachCount()
        val target = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        val limitations = buildList {
            if (stats.jniInteropSources > 0) add(
                "unscanned-ffi-interop: ${stats.jniInteropSources} Kotlin/Java source file(s) declare JNI/native interop outside channel join coverage",
            )
            val dynamic = filtered.count { it.dynamic && it.kind == "channel-register" }
            if (dynamic > 0) add("dynamic-channel-names: $dynamic channel registration(s) use a non-literal name")
            val missingHandlerUsrs = withSymbols.count { it.kind == "method-handle" && it.symbol?.usr == null }
            if (missingHandlerUsrs > 0) add(
                "missing-handler-usrs: source scanning cannot resolve JVM identifiers for $missingHandlerUsrs method handler(s)",
            )
            val unattributed = filtered.count { it.kind == "method-handle" && it.channel == null }
            if (unattributed > 0) add(
                "unattributed-method-handles: $unattributed method handler(s) could not be assigned to a channel",
            )
            if (stats.unscannedHandlers > 0) add(
                "unscanned-method-handlers: ${stats.unscannedHandlers} handler callback(s) are not inline lambdas",
            )
            val dynamicExpo = filtered.count { it.mechanism == "expo" && it.dynamic }
            if (dynamicExpo > 0) add(
                "dynamic-expo-names: $dynamicExpo Expo boundary fact(s) use a non-literal module name",
            )
            if (stats.expoJavaSources > 0) add(
                "unscanned-expo-java: ${stats.expoJavaSources} Java source file(s) import Expo Modules; the Kotlin DSL cannot be scanned there",
            )
            if (counts.size > 1) add(
                "mixed-targets: facts come from more than one bridge " +
                    counts.toSortedMap().entries.joinToString(prefix = "(", postfix = ")") { "${it.key} ${it.value}" },
            )
            val omitted = ordered.size - filtered.size
            if (targetFilter != null && omitted > 0) add("target-filter: omitted $omitted fact(s) outside --target $targetFilter")
        }
        return BridgeFactsDocument(
            generatedAt = generatedAt ?: (sources.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() }
                ?: Instant.EPOCH).toString(),
            target = target,
            project = projectRoot.toRealPath().toString().replace('\\', '/'),
            facts = withSymbols,
            limitations = limitations,
        )
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val relative = projectRoot.toRealPath().relativize(path.toAbsolutePath().normalize())
            .joinToString("/")
        // FFI/JNI는 채널 조인 범위 밖의 interop다. 파일 수준으로만 관측해 한계 근거로 남긴다.
        val isJava = path.fileName.toString().endsWith(".java")
        val strippedSource = StringBuilder()
        val flutterChannels = mutableMapOf<String, Channel>()
        var pendingChainedChannel: Channel? = null
        var pendingChannel: PendingChannel? = null
        val handlerScopes = ArrayDeque<HandlerScope>()
        val methodScopes = ArrayDeque<MethodScope>()
        var pendingReactModule: String? = null
        var reactScope: ReactScope? = null
        var pendingReactMethod = false
        var expoScope: ExpoScope? = null
        var previousCode = ""
        var pendingExpoClass: PendingExpoClass? = null
        var inBlockComment = false
        var braceDepth = 0
        // Expo 게이트는 주석·문자열 내용을 제거한 전체 파일 뷰에서 한 번 판정한다 —
        // 문자열 리터럴 안의 import 텍스트로 게이트가 켜지지 않게 하기 위함이다.
        val sourceLines = ProjectTraversal.readSourceLines(projectRoot, path)
        var gateBlockComment = false
        val gateView = buildString {
            sourceLines.forEach { line ->
                val stripped = stripComments(line, gateBlockComment)
                gateBlockComment = stripped.inBlockComment
                append(maskStringContents(stripped.code)).append('\n')
            }
        }
        val hasExpoModuleImport = EXPO_MODULE_GATE.containsMatchIn(gateView)
        fun recordChannel(
            pending: PendingChannel,
            completed: CompletedCall,
            line: String,
            lineNumber: Int,
            code: String,
        ) {
            val channelExpression = completed.arguments.getOrNull(1)
            val channel = channelExpression?.let { flutterChannels[it.trim()] ?: it.literalOrDynamic() } ?: Channel(null, true)
            if (pending.variable != null) {
                flutterChannels[pending.variable] = channel
                return
            }
            val handler = CHAINED_SUFFIX.find(completed.remainder)
            if (handler == null) {
                pendingChainedChannel = channel
                return
            }
            facts += fact(
                "channel-register", channel.value, null, channel.dynamic,
                relative, lineNumber, line, "setMethodCallHandler", "flutter",
            )
            val openingBrace = code.indexOf('{', code.length - completed.remainder.length + handler.range.first)
            if (openingBrace >= 0) {
                handlerScopes.addLast(
                    HandlerScope(channel, braceDepth + braceDelta(code.substring(0, openingBrace + 1))),
                )
            } else {
                stats.unscannedHandlers++
            }
        }
        sourceLines.forEachIndexed { zeroBased, line ->
            val lineNumber = zeroBased + 1
            val stripped = stripComments(line, inBlockComment)
            inBlockComment = stripped.inBlockComment
            val code = stripped.code
            strippedSource.append(code).append('\n')
            pendingChainedChannel?.let { channel ->
                val handler = CHAINED_SUFFIX.find(code)
                if (handler != null) {
                    facts += fact("channel-register", channel.value, null, channel.dynamic, relative, lineNumber, line,
                        "setMethodCallHandler", "flutter")
                    val openingBrace = code.indexOf('{', handler.range.first)
                    if (openingBrace >= 0) handlerScopes.addLast(HandlerScope(channel, braceDepth + braceDelta(code.substring(0, openingBrace + 1))))
                    else stats.unscannedHandlers++
                }
                pendingChainedChannel = null
            }
            STRING_LITERAL_ASSIGNMENT.findAll(code).forEach { match ->
                flutterChannels[match.groupValues[1]] = match.groupValues[2].literalOrDynamic()
            }
            val pending = pendingChannel
            if (pending != null) {
                pending.collector.consume("\n$code")?.let { completed ->
                    recordChannel(pending, completed, line, lineNumber, code)
                    pendingChannel = null
                }
            } else {
                val assignment = METHOD_CHANNEL.find(code)
                val call = assignment ?: METHOD_CHANNEL_CALL.find(code)
                call?.let { match ->
                    val channel = PendingChannel(assignment?.groupValues?.get(1), CallArguments())
                    val remainder = code.substring(match.range.last + 1)
                    channel.collector.consume(remainder)?.let { completed ->
                        recordChannel(channel, completed, line, lineNumber, code)
                    } ?: run { pendingChannel = channel }
                }
            }
            SET_HANDLER.findAll(code).forEach { match ->
                val channel = flutterChannels[match.groupValues[1]] ?: Channel(null, true)
                facts += fact("channel-register", channel.value, null, channel.dynamic, relative, lineNumber, line, "setMethodCallHandler", "flutter")
                val openingBrace = code.indexOf('{', match.range.first)
                if (openingBrace >= 0) {
                    handlerScopes.addLast(HandlerScope(channel, braceDepth + braceDelta(code.substring(0, openingBrace + 1))))
                } else {
                    stats.unscannedHandlers++
                }
            }

            val handler = handlerScopes.lastOrNull()
            if (handler != null) {
                METHOD_WHEN.findAll(code).forEach { match ->
                    val openingBrace = code.indexOf('{', match.range.first)
                    val depth = braceDepth + braceDelta(code.substring(0, openingBrace + 1))
                    methodScopes.addLast(MethodScope(handler.channel, depth))
                    WHEN_METHOD.findAll(code.substring(openingBrace + 1)).forEach { methodMatch ->
                        val method = methodMatch.groupValues[1]
                        facts += fact("method-handle", handler.channel.value, method, false, relative, lineNumber, line, method, "flutter")
                    }
                }
                val methodScope = methodScopes.lastOrNull()
                if (methodScope != null && braceDepth == methodScope.depth &&
                    !METHOD_WHEN.containsMatchIn(code) && !ANY_WHEN.containsMatchIn(code)
                ) {
                    WHEN_METHOD.findAll(code).forEach { match ->
                        val method = match.groupValues[1]
                        facts += fact(
                            "method-handle", methodScope.channel.value, method, false,
                            relative, lineNumber, line, method, "flutter",
                        )
                    }
                }
            }

            REACT_MODULE.find(code)?.let { match ->
                pendingReactModule = match.groupValues[1]
                facts += fact(
                    "module-export", pendingReactModule, null, false,
                    relative, lineNumber, line, match.value, "react-native",
                )
            }
            val classMatch = CLASS_DECLARATION.find(code)
            if (pendingReactModule != null && classMatch != null) {
                val openingBrace = code.indexOf('{', classMatch.range.first)
                if (openingBrace >= 0) {
                    reactScope = ReactScope(
                        requireNotNull(pendingReactModule),
                        braceDepth + braceDelta(code.substring(0, openingBrace + 1)),
                    )
                    pendingReactModule = null
                }
            }
            REACT_METHOD.find(code)?.let { annotation ->
                pendingReactMethod = true
                FUNCTION.find(code, annotation.range.last + 1)?.let { function ->
                    reactScope?.let { scope ->
                        facts += fact(
                            "method-handle", scope.channel, function.groupValues[1], false,
                            relative, lineNumber, line, function.groupValues[1], "react-native",
                        )
                    }
                    pendingReactMethod = false
                }
            }
            if (pendingReactMethod && !REACT_METHOD.containsMatchIn(code)) {
                FUNCTION.find(code)?.let { function ->
                    reactScope?.let { scope ->
                        facts += fact(
                            "method-handle", scope.channel, function.groupValues[1], false,
                            relative, lineNumber, line, function.groupValues[1], "react-native",
                        )
                    }
                    pendingReactMethod = false
                }
            }

            // Expo Modules — `Module()` 서브클래스의 `ModuleDefinition { }` DSL만 스캔한다.
            if (!isJava) {
                pendingExpoClass?.let { pending ->
                    val openingBrace = code.indexOf('{')
                    if (openingBrace >= 0) {
                        expoScope = ExpoScope(
                            pending.className, pending.line, pending.source,
                            braceDepth + braceDelta(code.substring(0, openingBrace + 1)),
                        )
                        pendingExpoClass = null
                    } else if (CLASS_DECLARATION.containsMatchIn(code) || FUNCTION.containsMatchIn(code)) {
                        // 다음 선언이 먼저 나오면 비정상 입력으로 버린다.
                        pendingExpoClass = null
                    }
                }
                if (expoScope == null && pendingExpoClass == null) {
                    // import가 있어도 FQN 슈퍼타입을 둘 다 시도한다 — 괄호 없는
                    // `else null ?: fqn`은 `else (null ?: fqn)`로 파싱돼 FQN을 건너뛴다.
                    val expoClass = (if (hasExpoModuleImport) EXPO_MODULE_CLASS.find(code) else null)
                        ?: EXPO_MODULE_CLASS_FQN.find(code)
                    if (expoClass != null &&
                        !ABSTRACT_MODIFIER.containsMatchIn(code.substring(0, expoClass.range.first)) &&
                        !ABSTRACT_AT_END.containsMatchIn(previousCode)
                    ) {
                        val className = expoClass.groupValues[1]
                        val openingBrace = code.indexOf('{', expoClass.range.last + 1)
                        if (openingBrace >= 0) {
                            expoScope = ExpoScope(
                                className, lineNumber, line,
                                braceDepth + braceDelta(code.substring(0, openingBrace + 1)),
                            )
                        } else {
                            pendingExpoClass = PendingExpoClass(className, lineNumber, line)
                        }
                    }
                }
                expoScope?.let { scope -> scanExpoDslLine(code, braceDepth, lineNumber, line, scope) }
            }

            braceDepth += braceDelta(code)
            expoScope?.let { scope ->
                if (braceDepth < scope.depth) {
                    emitExpoModule(scope, facts, relative)
                    expoScope = null
                }
            }
            while (methodScopes.lastOrNull()?.let { braceDepth < it.depth } == true) methodScopes.removeLast()
            while (handlerScopes.lastOrNull()?.let { braceDepth < it.depth } == true) handlerScopes.removeLast()
            reactScope?.let { scope -> if (braceDepth < scope.depth) reactScope = null }
            if (code.isNotBlank()) previousCode = code
        }
        // 파일이 클래스 선언 중간에 끝나도 그동안 모은 DSL 증거는 버리지 않는다.
        expoScope?.let { emitExpoModule(it, facts, relative) }
        // JNI 표식은 문자열이 마스킹된 전체 뷰에서 판정한다 — 문자열 안의
        // "System.loadLibrary(...)" 같은 텍스트를 선언으로 오인하지 않고,
        // 여러 줄 `native` 시그니처도 잡기 위함이다.
        val maskedSource = maskStringContents(strippedSource.toString())
        if (JNI_INTEROP_PATTERN.containsMatchIn(maskedSource) ||
            (isJava && JAVA_NATIVE_METHOD_PATTERN.containsMatchIn(maskedSource))
        ) stats.jniInteropSources++
        // Java에서는 receiver DSL을 쓸 수 없어 Expo 모듈 선언이 사실상 나타나지 않는다.
        // import만 보인 경우를 한계로 남긴다.
        if (isJava && hasExpoModuleImport) stats.expoJavaSources++
    }

    /**
     * Expo `ModuleDefinition { … }` 본문의 최상위 문장만 읽는다.
     *
     * `Name`·`View`·`Function` 계열은 흔한 이름이라 `ModuleDefinition` 바로 뒤의 `{`로 열리는
     * 블록의 직접 깊이에서만 인정한다. `Function("f") { … }` 처럼 더 깊은 클로저 안의 동명
     * 호출은 세지 않는다. 문자열 리터럴은 토큰으로 소비해 안의 `{`가 깊이를 바꾸지 않게 한다.
     * 호출 인자가 줄을 넘어가면 괄호가 닫힐 때까지 다음 줄을 이어 읽는다.
     */
    private fun scanExpoDslLine(code: String, depthAtStart: Int, lineNumber: Int, source: String, scope: ExpoScope) {
        var depth = depthAtStart
        var index = 0
        scope.pendingCall?.let { pending ->
            val completed = pending.collector.consume("\n$code") ?: return
            pending.finish(completed.arguments.firstOrNull()?.literalOrDynamic(), scope)
            scope.pendingCall = null
            index = code.length - completed.remainder.length
        }
        while (index < code.length) {
            val match = EXPO_DSL_TOKEN.find(code, index) ?: break
            index = match.range.last + 1
            val token = match.value
            when {
                token == "{" -> {
                    depth++
                    if (scope.expectDefinitionBrace) {
                        scope.expectDefinitionBrace = false
                        scope.sawDefinitionBlock = true
                        scope.definitionDepth = depth
                    }
                }
                token == "}" -> {
                    if (scope.expectDefinitionBrace) scope.expectDefinitionBrace = false
                    depth--
                    if (scope.definitionDepth?.let { depth < it } == true) scope.definitionDepth = null
                }
                token.first() == '"' || token.first() == '\'' -> Unit
                token == "ModuleDefinition" -> {
                    if (scope.definitionDepth == null) scope.expectDefinitionBrace = true
                }
                scope.expectDefinitionBrace -> {
                    // `ModuleDefinition` 뒤 `{`가 아닌 다른 호출이 오면 DSL 블록이 아니다.
                    scope.expectDefinitionBrace = false
                }
                scope.definitionDepth?.let { depth == it } == true -> when {
                    token.startsWith("View") -> {
                        if (scope.firstView == null) scope.firstView = ViewSite(lineNumber, source)
                    }
                    else -> {
                        // `Name`·`Function` 계열 — 첫 인자가 JS 측 이름이다.
                        val call = PendingExpoCall(
                            CallArguments(), isName = token.startsWith("Name"),
                            line = lineNumber, source = source, token = token,
                        )
                        val completed = call.collector.consume(code.substring(index))
                        if (completed == null) {
                            scope.pendingCall = call
                            return
                        }
                        call.finish(completed.arguments.firstOrNull()?.literalOrDynamic(), scope)
                        index = code.length - completed.remainder.length
                    }
                }
            }
        }
    }

    /**
     * 닫힌 Expo 클래스 스코프를 이름 경계·메서드 사실로 내린다.
     *
     * 모듈 이름은 `Name(…)`의 마지막 호출이 이기고 없으면 Kotlin `javaClass.simpleName`과
     * 같은 클래스명 폴백이다. 정의 블록이 스캔 범위를 벗어나면 이름을 추측하지 않고
     * dynamic으로 남긴다. `View`가 하나라도 있으면 JS `requireNativeViewManager(모듈이름)`의
     * 대상이 되므로 component-export를 첫 `View` 자리에 하나만 낸다.
     */
    private fun emitExpoModule(scope: ExpoScope, facts: MutableList<BridgeFact>, relative: String) {
        val name = scope.resolvedName()
        facts += fact(
            "module-export", name.value, null, name.dynamic,
            relative, scope.classLine, scope.classSource, scope.className, "react-native",
            mechanism = "expo",
        )
        scope.firstView?.let { view ->
            facts += fact(
                "component-export", name.value, null, name.dynamic,
                relative, view.line, view.source, "View(", "react-native",
                mechanism = "expo",
            )
        }
        scope.methods.forEach { method ->
            facts += fact(
                "method-handle", name.value, method.method, name.dynamic || method.dynamic,
                relative, method.line, method.source, method.token, "react-native",
            )
        }
    }

    private fun fact(
        kind: String,
        channel: String?,
        method: String?,
        dynamic: Boolean,
        path: String,
        line: Int,
        source: String,
        token: String,
        target: String,
        mechanism: String? = null,
    ): BridgeFact = BridgeFact(
        kind, channel, method, dynamic,
        BridgeLocation(path, line, tokenColumn(source, token)),
        target = target,
        mechanism = mechanism,
    )

    /**
     * 토큰의 바이트 컬럼을 찾는다. 첫 등장이 다른 식별자 안쪽이면
     * (`AsyncFunction(` 안의 `Function(` 같은 경우) 다음 후보로 넘어간다.
     * `.`는 허용한다 — `channel.setMethodCallHandler`처럼 멤버 호출이 정상 형태다.
     */
    private fun tokenColumn(source: String, token: String): Int {
        var from = 0
        while (true) {
            val at = source.indexOf(token, from)
            if (at < 0) return 1
            val before = source.getOrNull(at - 1)
            if (before == null || (!before.isLetterOrDigit() && before != '_')) {
                return source.substring(0, at).toByteArray(Charsets.UTF_8).size + 1
            }
            from = at + 1
        }
    }

    private fun stripComments(line: String, startsInBlockComment: Boolean): StrippedLine {
        val result = StringBuilder(line.length)
        var index = 0
        var inBlockComment = startsInBlockComment
        var quote: Char? = null
        var escaped = false
        while (index < line.length) {
            val character = line[index]
            val next = line.getOrNull(index + 1)
            when {
                inBlockComment && character == '*' && next == '/' -> {
                    result.append("  ")
                    inBlockComment = false
                    index += 2
                }
                inBlockComment -> {
                    result.append(' ')
                    index++
                }
                quote != null -> {
                    result.append(character)
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == quote -> quote = null
                    }
                    index++
                }
                character == '"' || character == '\'' -> {
                    quote = character
                    result.append(character)
                    index++
                }
                character == '/' && next == '/' -> {
                    result.append(" ".repeat(line.length - index))
                    break
                }
                character == '/' && next == '*' -> {
                    result.append("  ")
                    inBlockComment = true
                    index += 2
                }
                else -> {
                    result.append(character)
                    index++
                }
            }
        }
        return StrippedLine(result.toString(), inBlockComment)
    }

    private fun braceDelta(code: String): Int {
        var delta = 0
        var quote: Char? = null
        var escaped = false
        code.forEach { character ->
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
            } else {
                when (character) {
                    '"', '\'' -> quote = character
                    '{' -> delta++
                    '}' -> delta--
                }
            }
        }
        return delta
    }

    // 문자열 리터럴 해석은 ChannelBridgeScanner의 것을 공유한다 — 이스케이프·raw string·
    // 보간 판정이 Flutter 채널 경로와 Expo DSL 경로 사이에서 어긋나지 않게 하기 위함이다.
    private fun String.literalOrDynamic(): Channel {
        val (value, dynamic) = literalOrDynamicChannel(this)
        return Channel(value, dynamic)
    }

    private data class Channel(val value: String?, val dynamic: Boolean)

    private data class PendingChannel(val variable: String?, val collector: CallArguments)

    private data class CompletedCall(val arguments: List<String>, val remainder: String)

    private data class HandlerScope(val channel: Channel, val depth: Int)

    private data class MethodScope(val channel: Channel, val depth: Int)

    private data class ReactScope(val channel: String, val depth: Int)

    private data class PendingExpoClass(val className: String, val line: Int, val source: String)

    private data class ExpoScope(
        val className: String,
        val classLine: Int,
        val classSource: String,
        val depth: Int,
        var sawDefinitionBlock: Boolean = false,
        var expectDefinitionBrace: Boolean = false,
        var definitionDepth: Int? = null,
        var nameLiteral: String? = null,
        var nameIsDynamic: Boolean = false,
        var firstView: ViewSite? = null,
        var pendingCall: PendingExpoCall? = null,
        val methods: MutableList<PendingExpoMethod> = mutableListOf(),
    ) {
        /**
         * `Name(…)` → 클래스명 폴백 순으로 모듈 이름을 결정한다.
         * 정의 블록이 스캔 범위 밖이면 이름을 추측하지 않고 클래스명을 dynamic 근거로 남긴다
         * (`channel`은 비워둘 수 없으므로 표현식·클래스명 원문을 실는다).
         */
        fun resolvedName(): Channel = when {
            !sawDefinitionBlock -> Channel(className, true)
            nameLiteral != null -> Channel(nameLiteral, nameIsDynamic)
            else -> Channel(className, false)
        }
    }

    private data class ViewSite(val line: Int, val source: String)

    private data class PendingExpoCall(
        val collector: CallArguments,
        val isName: Boolean,
        val line: Int,
        val source: String,
        val token: String,
    ) {
        /** 첫 인자를 모듈명 후보 또는 메서드 사실로 기록한다. 인자가 비면 기록하지 않는다. */
        fun finish(argument: Channel?, scope: ExpoScope) {
            val name = argument ?: return
            if (name.value.isNullOrEmpty()) return
            if (isName) {
                scope.nameLiteral = name.value
                scope.nameIsDynamic = name.dynamic
            } else {
                scope.methods += PendingExpoMethod(
                    method = name.value, dynamic = name.dynamic,
                    line = line, source = source, token = token,
                )
            }
        }
    }

    private data class PendingExpoMethod(
        val method: String?,
        val dynamic: Boolean,
        val line: Int,
        val source: String,
        val token: String,
    )

    private data class StrippedLine(val code: String, val inBlockComment: Boolean)

    private data class ScanStats(
        var unscannedHandlers: Int = 0,
        var jniInteropSources: Int = 0,
        var expoJavaSources: Int = 0,
    )

    private class CallArguments {
        private val arguments = mutableListOf<String>()
        private val current = StringBuilder()
        private var depth = 1
        private var inString = false
        private var escaped = false

        fun consume(text: String): CompletedCall? {
            text.forEachIndexed { index, character ->
                if (inString) {
                    current.append(character)
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> inString = false
                    }
                    return@forEachIndexed
                }
                when (character) {
                    '"' -> {
                        inString = true
                        current.append(character)
                    }
                    '(' -> {
                        depth++
                        current.append(character)
                    }
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            arguments += current.toString().trim()
                            return CompletedCall(arguments.toList(), text.substring(index + 1))
                        }
                        current.append(character)
                    }
                    ',' -> if (depth == 1) {
                        arguments += current.toString().trim()
                        current.clear()
                    } else {
                        current.append(character)
                    }
                    else -> current.append(character)
                }
            }
            return null
        }
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val METHOD_CHANNEL = Regex("(?:\\b(?:val|var)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*MethodChannel\\s*\\(")
        val STRING_LITERAL_ASSIGNMENT = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:\\\\.|[^\"])*\")")
        val METHOD_CHANNEL_CALL = Regex("\\bMethodChannel\\s*\\(")
        val SET_HANDLER = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:!!|\\?)?\\s*\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val WHEN_METHOD = Regex("\\\"([^\\\"]+)\\\"\\s*->")
        val METHOD_WHEN = Regex("\\bwhen\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\.method\\s*\\)\\s*\\{")
        val ANY_WHEN = Regex("\\bwhen\\s*\\(")
        val CHAINED_SUFFIX = Regex("\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val REACT_MODULE = Regex("@ReactModule\\s*\\(\\s*name\\s*=\\s*\\\"([^\\\"]+)\\\"")
        val REACT_METHOD = Regex("@ReactMethod\\b")
        val FUNCTION = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val CLASS_DECLARATION = Regex("\\b(?:class|object)\\s+[A-Za-z_][A-Za-z0-9_]*")
        // `expo.modules.kotlin.modules` 패키지 자체에 선언된 서브클래스도 import 없이 Module을 본다.
        val EXPO_MODULE_GATE = Regex(
            "\\bimport\\s+expo\\.modules\\.kotlin\\.modules\\.(?:Module|\\*)\\b" +
                "|\\bpackage\\s+expo\\.modules\\.kotlin\\.modules\\b",
        )
        // `Foo.Module()` 같은 중첩 타입이나 인자 위치의 `Module()`은 Expo 모듈이 아니다.
        // 생성자 괄호 안의 `=`(기본 인자)는 슈퍼타입 목록이 아니므로 괄호째로 건너뛴다.
        val EXPO_MODULE_CLASS = Regex(
            "\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\([^;{}]*\\))?[^={;]*:[^={;]*(?<![\\w.])Module\\s*\\(",
        )
        val EXPO_MODULE_CLASS_FQN = Regex(
            "\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\([^;{}]*\\))?[^={;]*:[^={;]*" +
                "(?<![\\w.])expo\\.modules\\.kotlin\\.modules\\.Module\\s*\\(",
        )
        val ABSTRACT_MODIFIER = Regex("\\babstract\\b")
        // `abstract`가 class 줄이 아니라 직전 줄에 오는 형태(`abstract\nclass X`)를 잡는다.
        val ABSTRACT_AT_END = Regex("\\babstract\\s*$")
        // 문자열은 통째로 소비해 안의 `{`가 깊이를 오염시키지 않게 한다.
        val EXPO_DSL_TOKEN = Regex(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\{|\\}|" +
                "(?<![\\w.])ModuleDefinition\\b|" +
                "(?<![\\w.])(?:Name|View)\\s*\\(|" +
                "(?<![\\w.])(?:Function|AsyncFunction)\\s*(?:<[^>]*>)?\\s*\\(",
        )
    }
}

// Kotlin `external fun`, System.loadLibrary, JNI export(Java_패키지_클래스_메서드) 표식.
// Java의 `native`는 Kotlin에서 예약어가 아니라 식별자가 될 수 있어 .java에만 적용한다.
// v1·v2 브리지 스캐너가 같은 표식 한 벌을 공유한다.
internal val JNI_INTEROP_PATTERN =
    Regex("\\bSystem\\.loadLibrary\\s*\\(|\\bexternal\\s+fun\\b|\\bJava_[A-Za-z_][A-Za-z0-9_]*_[A-Za-z0-9_]+")
// 제네릭(`List<? extends Foo>`)과 qualified 타입에 공백·`.`이 들어가고,
// 반환 타입과 이름이 여러 줄로 갈라질 수 있어 `\s`를 허용한다.
internal val JAVA_NATIVE_METHOD_PATTERN =
    Regex("\\bnative\\s+[A-Za-z_][A-Za-z0-9_$.<>?\\[\\],\\s]*?\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\(")
