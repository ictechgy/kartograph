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

    /** 코어 RN 전역 이벤트 방출을 별도의 v2 문서로 내보낸다. */
    public fun scanReactNativeEvents(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument =
        ReactNativeEventScanner(projectRoot).scan(generatedAt, graph)

    /**
     * 프로젝트 상대 근거와 조인 불가능한 사실의 한계를 bridge-facts v1 문서로 만든다.
     * generatedAt은 추출 시각이며 최신 source 수정 시각은 sourceModifiedAt에 별도로 보존한다.
     */
    public fun scan(generatedAt: String? = null, graph: CodeGraph? = null, targetFilter: String? = null): BridgeFactsDocument {
        val facts = mutableListOf<BridgeFact>()
        val stats = ScanStats()
        val sources = mutableListOf<Path>()
        ProjectTraversal.walkSources(projectRoot) { sources.add(it) }
        sources.sorted().forEach { scanFile(it, facts, stats) }
        val ordered = facts.sortedWith(compareBy({ it.location.path }, { it.location.line }, { it.kind }, { it.method.orEmpty() }))
        val filtered = targetFilter?.let { target -> ordered.filter { it.target == target } } ?: ordered
        val attacher = graph?.let { SnapshotSymbolAttacher(it, projectRoot) }
        val withSymbols = filtered.map { attacher?.attach(it) ?: it }
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
            if (stats.unscannedConditions > 0) add(
                "unscanned-method-conditions: ${stats.unscannedConditions} inline handler scope(s) exceed leading direct braced literal method equality support",
            )
            if (stats.unresolvedWrites.isNotEmpty()) add(
                "unresolved-channel-writes: ${stats.unresolvedWrites.size} observed write site(s) invalidate channel receiver/name bindings: " +
                    stats.unresolvedWrites.sorted().take(8).joinToString() +
                    if (stats.unresolvedWrites.size > 8) " (additional sites omitted)" else "",
            )
            if (stats.unscannedBindingFiles.isNotEmpty()) add(
                "unscanned-channel-bindings: ${stats.unscannedBindingFiles.size} source file(s) contain unsupported declaration, parameter or qualified receiver binding forms: " +
                    stats.unscannedBindingFiles.sorted().take(8).joinToString(),
            )
            if (stats.incompatibleBindings > 0) add(
                "incompatible-channel-bindings: ${stats.incompatibleBindings} channel registration(s) use a binding of an incompatible value kind",
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
            generatedAt = bridgeTimestamp(generatedAt?.let(Instant::parse) ?: Instant.now()),
            sourceModifiedAt = sources.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() }?.let(::bridgeTimestamp),
            target = target,
            project = projectIdentity(projectRoot.toRealPath()),
            facts = withSymbols,
            limitations = limitations,
        )
    }

    /** 단순 lambda header의 모든 이름을 읽는다. 일부만 읽으면 shadowing을 놓치므로 복잡한 모양은 전체 미지원이다. */
    private fun lambdaParameterNames(header: String): List<String>? {
        val value = header.trim()
        if (value.isEmpty()) return emptyList()
        if (value.any { it in "()[]{}" }) return null
        val names = value.removeSuffix(",").split(',').map { part ->
            val name = part.substringBefore(':').trim()
            if (!IDENTIFIER.matches(name)) return null
            if (':' in part && (part.substringAfter(':').isBlank() || part.count { it == ':' } != 1)) return null
            name
        }.filterNot { it == "_" }
        return names.takeIf { it.distinct().size == it.size }
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val relative = projectRoot.toRealPath().relativize(path.toAbsolutePath().normalize())
            .joinToString("/")
        // FFI/JNI는 채널 조인 범위 밖의 interop다. 파일 수준으로만 관측해 한계 근거로 남긴다.
        val isJava = path.fileName.toString().endsWith(".java")
        val strippedSource = StringBuilder()
        val flutterChannels = mutableListOf<ChannelBinding>()
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
        var sourceBlockComment = false
        val sourceCode = sourceLines.map { line ->
            val stripped = stripComments(line, sourceBlockComment)
            sourceBlockComment = stripped.inBlockComment
            stripped.code
        }.joinToString("\n")
        val maskedSourceCode = maskStringContents(sourceCode)
        val guardedAssignments = mutableSetOf<Int>()
        val ordinaryBodies = mutableSetOf<Int>()
        CONTROL_CONDITION.findAll(maskedSourceCode).forEach { condition ->
            val open = maskedSourceCode.indexOf('(', condition.range.first)
            val end = balancedEnd(sourceCode, open)
            if (end >= 0) {
                val body = sourceCode.indexOfFirstNonWhitespace(end + 1)
                if (maskedSourceCode.getOrNull(body) == '{') ordinaryBodies += body else guardedAssignments += body
            }
        }
        CONTROL_ALTERNATIVE.findAll(maskedSourceCode).forEach { alternative ->
            val body = sourceCode.indexOfFirstNonWhitespace(alternative.range.last + 1)
            if (maskedSourceCode.getOrNull(body) == '{') ordinaryBodies += body else guardedAssignments += body
        }
        val companionBodies = COMPANION_BODY.findAll(maskedSourceCode).map { it.range.last }.toSet()
        val lineStarts = mutableListOf(0)
        val lineScopes = mutableListOf<List<Int>>(emptyList())
        val sourceScope = mutableListOf<Int>()
        val argumentDepth = IntArray(maskedSourceCode.length)
        val delimiterScopes = ArrayDeque<Int>()
        var delimiters = 0
        maskedSourceCode.forEachIndexed { index, character ->
            argumentDepth[index] = delimiters
            when (character) {
                '{' -> {
                    sourceScope += index
                    delimiterScopes.addLast(delimiters)
                    delimiters = 0
                }
                '}' -> {
                    if (sourceScope.isNotEmpty()) sourceScope.removeLast()
                    delimiters = if (delimiterScopes.isNotEmpty()) delimiterScopes.removeLast() else 0
                }
                '(', '[' -> delimiters++
                ')', ']' -> delimiters = (delimiters - 1).coerceAtLeast(0)
                '\n' -> {
                    lineStarts += index + 1
                    lineScopes += sourceScope.toList()
                }
            }
        }
        fun scopeAt(offset: Int): List<Int> {
            val line = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
            val scope = lineScopes[line].toMutableList()
            for (index in lineStarts[line] until offset) when (maskedSourceCode[index]) {
                '{' -> scope += index
                '}' -> if (scope.isNotEmpty()) scope.removeLast()
            }
            return scope
        }
        // implicit it와 해석하지 못한 함수 header는 이름을 추측해 이전 binding에 연결하지 않는다.
        val unscopedParameters = mutableSetOf("it", "field")
        val functionHeaders = FUNCTION_PARAMETERS.findAll(maskedSourceCode).toList()
        val functionOffsets = functionHeaders.map { it.range.first }.toSet()
        val unknownFunctionHeader = FUNCTION_KEYWORD.findAll(maskedSourceCode).any { it.range.first !in functionOffsets }
        fun functionHeader(match: MatchResult): FunctionHeader {
            val end = balancedEnd(sourceCode, match.range.last)
            if (end < 0) return FunctionHeader(end, unresolved = true)
            var index = sourceCode.indexOfFirstNonWhitespace(end + 1)
            if (maskedSourceCode.getOrNull(index) != ':') {
                val after = maskedSourceCode.getOrNull(index)
                return FunctionHeader(end, index.takeIf { after == '{' }, after == '=',
                    unresolved = after != null && after !in "{=;}" &&
                        DECLARATION_START.matchAt(maskedSourceCode, index) == null)
            }
            index++
            // 반환 타입 뒤의 확인한 본문만 사용한다. 본문 없는 함수 다음 property/선언의 lambda를 빌리지 않는다.
            while (index < maskedSourceCode.length) {
                when (maskedSourceCode[index]) {
                    '{' -> return FunctionHeader(end, index)
                    '=' -> return FunctionHeader(end, expression = true)
                    // 인용 식별자의 내부 예약어를 새 선언으로 해석하지 않는다.
                    '`' -> return FunctionHeader(end, unresolved = true)
                    ';', '}' -> return FunctionHeader(end)
                    '(' -> {
                        val closing = balancedEnd(sourceCode, index)
                        if (closing < 0) return FunctionHeader(end, unresolved = true)
                        index = closing + 1
                    }
                    '\n' -> {
                        val next = sourceCode.indexOfFirstNonWhitespace(index + 1)
                        var previous = index - 1
                        while (previous > end && maskedSourceCode[previous].isWhitespace()) previous--
                        val after = maskedSourceCode.getOrNull(next)
                        val before = maskedSourceCode.getOrNull(previous)
                        if (after != '{' && after != '=' && before != ':' && before != ',') {
                            // 읽지 못한 반환 타입을 본문 없는 선언으로 확정하면 매개변수 shadowing이 사라진다.
                            val bodyless = after == null || after in ";}" ||
                                DECLARATION_START.matchAt(maskedSourceCode, next) != null
                            return FunctionHeader(end, unresolved = !bodyless)
                        }
                        index++
                    }
                    else -> {
                        if (DECLARATION_START.matchAt(maskedSourceCode, index) != null) return FunctionHeader(end)
                        index++
                    }
                }
            }
            return FunctionHeader(end)
        }
        val functions = functionHeaders.map { it to functionHeader(it) }
        val localOffsets = LOCAL_DECLARATION.findAll(maskedSourceCode).map { it.range.first }.toSet()
        val unknownLocalDeclaration = LOCAL_KEYWORD.findAll(maskedSourceCode).any { it.range.first !in localOffsets }
        fun isClassLiteral(offset: Int): Boolean {
            var previous = offset - 1
            while (previous >= 0 && maskedSourceCode[previous].isWhitespace()) previous--
            return previous >= 0 && maskedSourceCode[previous] in ".:"
        }
        val classHeaders = (CLASS_DECLARATION.findAll(maskedSourceCode).toList() +
            ANONYMOUS_OBJECT.findAll(maskedSourceCode).toList()).filterNot {
                it.value.startsWith("class") && isClassLiteral(it.range.first)
            }
        val ownerOffsets = classHeaders.map { it.range.first }.toSet()
        val unknownOwnerHeader = OWNER_KEYWORD.findAll(maskedSourceCode).any { keyword ->
            if (keyword.range.first in ownerOffsets) false else {
                // Kotlin `Type::class`와 Java `Type.class`는 owner 선언이 아니다.
                val classLiteral = keyword.value == "class" && isClassLiteral(keyword.range.first)
                !classLiteral
            }
        }
        fun classHeader(start: Int): OwnerHeader {
            var index = start
            while (index < maskedSourceCode.length) {
                when (maskedSourceCode[index]) {
                    '(' -> {
                        // 슈퍼 생성자 인자 lambda의 `{`는 클래스 본문이 아니다.
                        val end = balancedEnd(sourceCode, index)
                        if (end < 0) return OwnerHeader(null, maskedSourceCode.length)
                        index = end + 1
                    }
                    '\n' -> {
                        val next = sourceCode.indexOfFirstNonWhitespace(index + 1)
                        var previous = index - 1
                        while (previous >= start && maskedSourceCode[previous].isWhitespace()) previous--
                        val nextCharacter = maskedSourceCode.getOrNull(next)
                        val previousCharacter = maskedSourceCode.getOrNull(previous)
                        val continuation = nextCharacter != null && nextCharacter in "{:,<" ||
                            previousCharacter != null && previousCharacter in ":," ||
                            DELEGATION_KEYWORD.matchAt(maskedSourceCode, next) != null ||
                            isJava && JAVA_HEADER_CONTINUATION.matchAt(maskedSourceCode, next) != null
                        // 본문 없는 선언 다음 문장/lambda의 중괄호를 class 소유권으로 빌리지 않는다.
                        if (!continuation) return OwnerHeader(null, index)
                        index++
                    }
                    '{' -> return OwnerHeader(index, index)
                    ';', '}' -> return OwnerHeader(null, index)
                    else -> index++
                }
            }
            return OwnerHeader(null, maskedSourceCode.length)
        }
        val ownerHeaders = classHeaders.map { it to classHeader(it.range.last + 1) }
        val classBodies = ownerHeaders.mapNotNull { it.second.body } + companionBodies
        val classScopes = classBodies.map { scopeAt(it + 1) }
        ordinaryBodies.addAll(classBodies)
        functions.forEach { (match, bounds) ->
            // catch는 함수 이름으로도 유효하다. 이름만으로 receiver 없는 제어 블록을 증명하지 않는다.
            if (!match.value.startsWith("fun")) return@forEach
            bounds.body?.let(ordinaryBodies::add)
        }
        ORDINARY_BODY.findAll(maskedSourceCode).forEach { ordinaryBodies += it.range.last }
        val unknownClassParameters = ownerHeaders.any { (declaration, bounds) ->
            val header = maskedSourceCode.substring(declaration.range.last + 1, bounds.end)
            val opening = header.indexOf('(')
            val colon = header.indexOf(':')
            header.trimStart().startsWith('<') || (opening >= 0 && (colon < 0 || opening < colon)) ||
                DELEGATION_KEYWORD.containsMatchIn(header)
        }
        val lambdaHeaders = LAMBDA_PARAMETERS.findAll(maskedSourceCode)
            .filter { it.range.first !in ordinaryBodies }
            .map { it to lambdaParameterNames(it.groupValues[1]) }.toList()
        val unknownLambdaDeclaration = lambdaHeaders.any { it.second == null }
        val multipleBindingStatements = maskedSourceCode.lineSequence().any { code ->
            ';' in code && (LOCAL_KEYWORD.containsMatchIn(code) || METHOD_CHANNEL.containsMatchIn(code))
        }
        val unknownAccessor = ACCESSOR_PARAMETERS.findAll(maskedSourceCode).any { accessor ->
            val end = balancedEnd(sourceCode, maskedSourceCode.indexOf('(', accessor.range.first))
            end >= 0 && maskedSourceCode.getOrNull(sourceCode.indexOfFirstNonWhitespace(end + 1)) == '{'
        }
        val bindingBarrier = unknownFunctionHeader || functions.any { it.second.unresolved } ||
            unknownOwnerHeader || unknownLocalDeclaration || unknownClassParameters ||
            unknownLambdaDeclaration || multipleBindingStatements || unknownAccessor ||
            FOR_PARAMETERS.containsMatchIn(maskedSourceCode) || CONSTRUCTOR_KEYWORD.containsMatchIn(maskedSourceCode) ||
            LABELLED_THIS.containsMatchIn(maskedSourceCode)
        functions.forEach { (match, bounds) ->
            val open = match.range.last
            val end = bounds.parametersEnd
            if (end < 0) return@forEach
            val names = PARAMETER_NAME.findAll(maskedSourceCode.substring(open + 1, end)).map { it.groupValues[1] }.toList()
            val body = bounds.body
            if (body != null) {
                val scope = scopeAt(body + 1)
                names.forEach { flutterChannels += ChannelBinding(it, scope, Channel(null, true)) }
            } else if (bounds.expression || bounds.unresolved) unscopedParameters += names
        }
        lambdaHeaders.forEach { (match, names) ->
            val scope = scopeAt(match.range.first + 1)
            names.orEmpty().forEach { flutterChannels += ChannelBinding(it, scope, Channel(null, true)) }
        }
        fun visibleBinding(name: String, scope: List<Int>, origin: List<Int>? = null): ChannelBinding? =
            flutterChannels.asReversed().filter { binding ->
                binding.name == name && binding.scope.size <= scope.size &&
                    binding.scope.indices.all { binding.scope[it] == scope[it] } &&
                    (origin == null || binding.originScope == origin)
            }.maxByOrNull { it.scope.size }
        fun ownerScope(scope: List<Int>): List<Int>? = classScopes.filter { owner ->
            owner.size <= scope.size && owner.indices.all { owner[it] == scope[it] }
        }.maxByOrNull { it.size }
        fun crossesReceiverBoundary(from: List<Int>, scope: List<Int>): Boolean =
            !isJava && scope.drop(from.size).any { it !in ordinaryBodies }
        fun channelFor(name: String, scope: List<Int>, kind: BindingKind, ownMember: Boolean = false): Channel? {
            // 표현식 몸체처럼 매개변수 범위를 증명하지 못한 모양은 파일 안의 동명 채널을 미확정으로 둔다.
            if (bindingBarrier) return Channel(null, true, BindingGap(unmodeledFile = relative))
            if (name in unscopedParameters) return Channel(null, true)
            val origin = if (ownMember) ownerScope(scope) ?: return Channel(null, true) else null
            val binding = visibleBinding(name, scope, origin) ?: return null
            // 타입을 모르는 lambda/DSL의 this와 암시적 멤버를 어휘적 class 소유로 확정하지 않는다.
            if (crossesReceiverBoundary(binding.scope, scope)) return Channel(null, true, BindingGap(unmodeledFile = relative))
            if (binding.kind == BindingKind.UNKNOWN) return binding.channel
            if (binding.kind != kind) return Channel(null, true, BindingGap(incompatible = true))
            return binding.channel
        }
        fun registerHandler(channel: Channel, handlerOffset: Int, lineNumber: Int, line: String) {
            val afterName = handlerOffset + "setMethodCallHandler".length
            val argumentStart = sourceCode.indexOfFirstNonWhitespace(afterName)
            // null은 등록이 아니라 해제다. 다음 줄의 인자도 같은 호출 경계에서 확인한다.
            if (sourceCode.getOrNull(argumentStart) == '(') {
                val end = balancedEnd(sourceCode, argumentStart)
                if (end >= 0 && sourceCode.substring(argumentStart + 1, end).trim() == "null") return
            }
            channel.bindingGap?.let { gap ->
                gap.unknownWrite?.let(stats.unresolvedWrites::add)
                gap.unmodeledFile?.let(stats.unscannedBindingFiles::add)
                if (gap.incompatible) stats.incompatibleBindings++
            }
            facts += fact("channel-register", channel.value, null, channel.dynamic, relative, lineNumber, line,
                "setMethodCallHandler", "flutter")
            val openingBrace = if (sourceCode.getOrNull(argumentStart) == '(') {
                sourceCode.indexOfFirstNonWhitespace(argumentStart + 1)
            } else argumentStart
            if (sourceCode.getOrNull(openingBrace) != '{') {
                stats.unscannedHandlers++
                return
            }
            // 기존 when 경로는 줄별 깊이를 사용한다. 다음 줄에서 여는 lambda는 그 줄까지의 깊이를 반영한다.
            val prefix = sourceCode.substring(lineStarts[lineNumber - 1], openingBrace + 1)
            val openingLine = lineStarts.binarySearch(openingBrace).let { if (it >= 0) it else -it - 2 } + 1
            handlerScopes.addLast(HandlerScope(channel, braceDepth + braceDelta(prefix), openingLine))
            val conditions = scanMethodChannelConditions(sourceCode, maskedSourceCode, openingBrace)
            stats.unscannedConditions += conditions.unsupported
            conditions.methods.forEach { method ->
                val zeroBased = lineStarts.binarySearch(method.offset).let { if (it >= 0) it else -it - 2 }
                val methodLine = zeroBased + 1
                facts += BridgeFact("method-handle", channel.value, method.name, channel.dynamic,
                    BridgeLocation(relative, methodLine,
                        sourceLines[zeroBased].substring(0, method.offset - lineStarts[zeroBased]).toByteArray(Charsets.UTF_8).size + 1),
                    target = "flutter")
            }
        }
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
            val channel = channelExpression?.let { expression ->
                val literal = expression.literalOrDynamic()
                if (!literal.dynamic) literal else channelFor(expression.trim(), pending.scope, BindingKind.STRING) ?: literal
            } ?: Channel(null, true)
            if (pending.variable != null) {
                if (pending.bindAllowed) {
                    flutterChannels += ChannelBinding(pending.variable, pending.scope, channel, BindingKind.CHANNEL, pending.originScope)
                } else {
                    // 중괄호 없는 조건/반복문의 생성 식은 분기 뒤 binding 값을 확정하지 못한다.
                    flutterChannels.filter { it.name == pending.variable && it.originScope == pending.originScope }.forEach { binding ->
                        binding.kind = BindingKind.UNKNOWN
                        binding.channel = Channel(null, true, BindingGap(unknownWrite = "$relative:$lineNumber"))
                    }
                }
                return
            }
            val handler = CHAINED_SUFFIX.find(completed.remainder)
            if (handler == null) {
                pendingChainedChannel = channel
                return
            }
            registerHandler(channel, lineStarts[lineNumber - 1] +
                code.indexOf("setMethodCallHandler", code.length - completed.remainder.length + handler.range.first), lineNumber, line)
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
                    registerHandler(channel, lineStarts[zeroBased] + code.indexOf("setMethodCallHandler", handler.range.first), lineNumber, line)
                }
                pendingChainedChannel = null
            }
            val maskedCode = maskStringContents(code)
            CHANNEL_WRITE.findAll(maskedCode).forEach { match ->
                val qualifier = match.groupValues[1].filterNot(Char::isWhitespace)
                val offset = lineStarts[zeroBased] + match.range.first
                if (argumentDepth[offset] > 0 || DECLARATION_PREFIX.containsMatchIn(maskedCode.substring(0, match.range.first))) return@forEach
                var previous = offset - 1
                while (previous >= 0 && maskedSourceCode[previous].isWhitespace()) previous--
                if (previous >= 0 && maskedSourceCode[previous] in ".:") return@forEach
                if (qualifier.isNotEmpty() && qualifier != "this.") return@forEach
                val name = match.groupValues[2]
                val scope = scopeAt(offset)
                val origin = if (qualifier == "this.") ownerScope(scope)?.takeUnless { crossesReceiverBoundary(it, scope) } else null
                if (qualifier == "this." && origin == null) {
                    // owner를 증명하지 못한 `this` 대입 뒤에 이전 리터럴을 확정하지 않는다.
                    val uncertain = visibleBinding(name, scope)
                    if (uncertain != null) flutterChannels.filter { it.name == name && it.originScope == uncertain.originScope }.forEach { binding ->
                        binding.kind = BindingKind.UNKNOWN
                        binding.channel = Channel(null, true, BindingGap(unmodeledFile = relative))
                    }
                    return@forEach
                }
                val target = visibleBinding(name, scope, origin) ?: return@forEach
                // 선언 신원을 공유하는 binding만 무효화한다. 다른 객체의 필드·동명 지역 변수는 바꾸지 않는다.
                flutterChannels.filter { it.name == name && it.originScope == target.originScope }.forEach { binding ->
                    binding.kind = BindingKind.UNKNOWN
                    binding.channel = Channel(null, true, BindingGap(unknownWrite = "$relative:$lineNumber"))
                }
            }
            LOCAL_DECLARATION.findAll(maskedCode).forEach { match ->
                flutterChannels += ChannelBinding(match.groupValues[1], scopeAt(lineStarts[zeroBased] + match.range.first), Channel(null, true))
            }
            STRING_LITERAL_ASSIGNMENT.findAll(code).forEach { match ->
                val scope = scopeAt(lineStarts[zeroBased] + match.range.first)
                // companion 내부 선언을 유지하고 같은 클래스에만 별도 가시성 alias를 둔다.
                val bindingScope = if (scope.lastOrNull() in companionBodies &&
                    CONST_PREFIX.containsMatchIn(code.substring(0, match.range.first))) scope.dropLast(1) else scope
                val tail = code.substring(match.range.last + 1).trimStart()
                val following = sourceCode.substring(lineStarts[zeroBased] + match.range.last + 1).trimStart()
                val complete = (tail.isEmpty() || tail.startsWith(';') || tail.startsWith('}')) &&
                    !ALIAS_CONTINUATION.containsMatchIn(following)
                val binding = ChannelBinding(match.groupValues[1], scope,
                    if (complete) match.groupValues[2].literalOrDynamic() else Channel(null, true, BindingGap(unmodeledFile = relative)),
                    if (complete) BindingKind.STRING else BindingKind.UNKNOWN)
                flutterChannels += binding
                if (bindingScope != scope) flutterChannels += binding.copy(scope = bindingScope)
            }
            val pending = pendingChannel
            if (pending != null) {
                pending.collector.consume("\n$code")?.let { completed ->
                    recordChannel(pending, completed, line, lineNumber, code)
                    pendingChannel = null
                }
            } else {
                val assignment = METHOD_CHANNEL.find(code)?.takeIf { match ->
                    var previous = match.range.first - 1
                    while (previous >= 0 && code[previous].isWhitespace()) previous--
                    previous < 0 || code[previous] != '.'
                }
                val call = assignment ?: METHOD_CHANNEL_CALL.find(code)
                call?.let { match ->
                    val channel = PendingChannel(assignment?.groupValues?.get(1), CallArguments(),
                        scopeAt(lineStarts[zeroBased] + match.range.first),
                        bindAllowed = lineStarts[zeroBased] + match.range.first !in guardedAssignments).let { pending ->
                            pending.copy(originScope = pending.variable?.let { visibleBinding(it, pending.scope)?.originScope } ?: pending.scope)
                        }
                    val remainder = code.substring(match.range.last + 1)
                    channel.collector.consume(remainder)?.let { completed ->
                        recordChannel(channel, completed, line, lineNumber, code)
                    } ?: run { pendingChannel = channel }
                }
            }
            SET_HANDLER.findAll(code).forEach { match ->
                val receiver = match.groupValues[1].filterNot(Char::isWhitespace)
                val scope = scopeAt(lineStarts[zeroBased] + match.range.first)
                val channel = when {
                    IDENTIFIER.matches(receiver) -> channelFor(receiver, scope, BindingKind.CHANNEL)
                    receiver.startsWith("this.") && IDENTIFIER.matches(receiver.removePrefix("this.")) ->
                        channelFor(receiver.removePrefix("this."), scope, BindingKind.CHANNEL, ownMember = true)
                    else -> Channel(null, true)
                } ?: Channel(null, true)
                registerHandler(channel, lineStarts[zeroBased] + code.indexOf("setMethodCallHandler", match.range.first), lineNumber, line)
            }

            val handler = handlerScopes.lastOrNull()?.takeIf { lineNumber >= it.openingLine }
            if (handler != null) {
                METHOD_WHEN.findAll(code).forEach { match ->
                    val openingBrace = code.indexOf('{', match.range.first)
                    val depth = braceDepth + braceDelta(code.substring(0, openingBrace + 1))
                    methodScopes.addLast(MethodScope(handler.channel, depth))
                    WHEN_METHOD.findAll(code.substring(openingBrace + 1)).forEach { methodMatch ->
                        val method = methodMatch.groupValues[1]
                        facts += fact("method-handle", handler.channel.value, method, handler.channel.dynamic, relative, lineNumber, line, method, "flutter")
                    }
                }
                val methodScope = methodScopes.lastOrNull()
                if (methodScope != null && braceDepth == methodScope.depth &&
                    !METHOD_WHEN.containsMatchIn(code) && !ANY_WHEN.containsMatchIn(code)
                ) {
                    WHEN_METHOD.findAll(code).forEach { match ->
                        val method = match.groupValues[1]
                        facts += fact(
                            "method-handle", methodScope.channel.value, method, methodScope.channel.dynamic,
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
            while (handlerScopes.lastOrNull()?.let { lineNumber >= it.openingLine && braceDepth < it.depth } == true) handlerScopes.removeLast()
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
                token.endsWith("ModuleDefinition") -> {
                    if (scope.definitionDepth == null) scope.expectDefinitionBrace = true
                }
                scope.expectDefinitionBrace -> {
                    // `ModuleDefinition` 뒤 `{`가 아닌 다른 호출이 오면 DSL 블록이 아니다.
                    scope.expectDefinitionBrace = false
                }
                scope.definitionDepth?.let { depth == it } == true -> when {
                    // `this.` 한정을 벗긴 뒤 이름으로 구분한다.
                    token.removePrefix("this.").startsWith("View") -> {
                        if (scope.firstView == null) scope.firstView = ViewSite(lineNumber, source)
                    }
                    else -> {
                        // `Name`·`Function` 계열 — 첫 인자가 JS 측 이름이다.
                        val call = PendingExpoCall(
                            CallArguments(),
                            isName = token.removePrefix("this.").startsWith("Name"),
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

    private data class Channel(val value: String?, val dynamic: Boolean, val bindingGap: BindingGap? = null)

    private data class BindingGap(val unknownWrite: String? = null, val unmodeledFile: String? = null, val incompatible: Boolean = false)

    private enum class BindingKind { STRING, CHANNEL, UNKNOWN }

    private class OwnerHeader(val body: Int?, val end: Int)

    private class FunctionHeader(
        val parametersEnd: Int,
        val body: Int? = null,
        val expression: Boolean = false,
        val unresolved: Boolean = false,
    )

    private data class ChannelBinding(
        val name: String,
        val scope: List<Int>,
        var channel: Channel,
        var kind: BindingKind = BindingKind.UNKNOWN,
        val originScope: List<Int> = scope,
    )

    private data class PendingChannel(
        val variable: String?,
        val collector: CallArguments,
        val scope: List<Int>,
        val originScope: List<Int> = scope,
        val bindAllowed: Boolean = true,
    )

    private data class CompletedCall(val arguments: List<String>, val remainder: String)

    private data class HandlerScope(val channel: Channel, val depth: Int, val openingLine: Int)

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
        var unscannedConditions: Int = 0,
        val unresolvedWrites: MutableSet<String> = mutableSetOf(),
        val unscannedBindingFiles: MutableSet<String> = mutableSetOf(),
        var incompatibleBindings: Int = 0,
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
        val METHOD_CHANNEL = Regex("(?<![\\w.])(?:\\b(?:val|var)\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*MethodChannel\\s*\\(")
        val STRING_LITERAL_ASSIGNMENT = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:\\\\.|[^\"])*\")")
        val COMPANION_BODY = Regex("\\bcompanion\\s+object(?:\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*\\{")
        val CONST_PREFIX = Regex("\\bconst\\s*$")
        val LOCAL_DECLARATION = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
        val LOCAL_KEYWORD = Regex("\\b(?:val|var)\\b")
        val CONSTRUCTOR_KEYWORD = Regex("\\bconstructor\\b")
        val DECLARATION_PREFIX = Regex("\\b(?:val|var)\\s*$")
        val CONTROL_CONDITION = Regex("\\b(?:if|while|for|when)\\s*\\(")
        val CONTROL_ALTERNATIVE = Regex("\\b(?:else|do)\\b")
        // init/finally도 receiver 함수 이름이 될 수 있으므로 확실한 keyword 본문만 추가한다.
        val ORDINARY_BODY = Regex("\\b(?:try|when)\\s*\\{")
        val JAVA_HEADER_CONTINUATION = Regex("(?:extends|implements|permits)\\b")
        // init/constructor/companion 같은 soft keyword는 타입 이름일 수 있어 선언 경계의 증거가 아니다.
        val DECLARATION_START = Regex("\\b(?:val|var|fun|class|object|interface|typealias)\\b")
        val FUNCTION_PARAMETERS = Regex("\\b(?:fun\\s+[A-Za-z_][A-Za-z0-9_]*|catch)\\s*\\(")
        val FOR_PARAMETERS = Regex("\\bfor\\s*\\(")
        val ACCESSOR_PARAMETERS = Regex("\\b(?:get|set)\\s*\\(")
        val LABELLED_THIS = Regex("\\bthis\\s*@")
        val FUNCTION_KEYWORD = Regex("\\bfun\\b")
        val PARAMETER_NAME = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*:")
        val LAMBDA_PARAMETERS = Regex("\\{([^{}]*?)->")
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val ALIAS_CONTINUATION = Regex("^(?:[.+*/%?:<>=!&|\\-]|get\\b|as\\b|is\\b|in\\b|@)")
        val CHANNEL_WRITE = Regex("(?<![\\w.])((?:[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\?\\.|\\.)\\s*)*)([A-Za-z_][A-Za-z0-9_]*)\\s*[+*/%\\-]?=(?!=|>)")
        val METHOD_CHANNEL_CALL = Regex("\\bMethodChannel\\s*\\(")
        val SET_HANDLER = Regex("(?<![\\w.])((?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)*[A-Za-z_][A-Za-z0-9_]*)\\s*(?:!!|\\?)?\\s*\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val WHEN_METHOD = Regex("\\\"([^\\\"]+)\\\"\\s*->")
        val METHOD_WHEN = Regex("\\bwhen\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\.method\\s*\\)\\s*\\{")
        val ANY_WHEN = Regex("\\bwhen\\s*\\(")
        val CHAINED_SUFFIX = Regex("^\\s*\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val REACT_MODULE = Regex("@ReactModule\\s*\\(\\s*name\\s*=\\s*\\\"([^\\\"]+)\\\"")
        val REACT_METHOD = Regex("@ReactMethod\\b")
        val FUNCTION = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val CLASS_DECLARATION = Regex("\\b(?:class|object)\\s+[A-Za-z_][A-Za-z0-9_]*")
        val OWNER_KEYWORD = Regex("\\b(?:class|object)\\b")
        val ANONYMOUS_OBJECT = Regex("\\bobject\\s*(?=[:{])")
        val DELEGATION_KEYWORD = Regex("\\bby\\b")
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
        // 제네릭 절은 lazy `[^{}]*?`로 읽는다 — `<List<String>>`처럼 `>`가 중첩돼도
        // 첫 유효 `>`+`(`에 고정되고, 인자 안의 `y > (z)` 같은 비교식에 끌려가지 않는다.
        // 뒤에 `(`가 없는 타입 참조는 버리고, 제네릭 절이 줄을 넘어가면 지원하지 않는다.
        // `this.`는 빌더 수신자의 명시 한정이라 허용하고 다른 수신자 한정은 거부한다.
        // `ModuleDefinition`은 최상위 함수라 `expo.modules.kotlin.modules.` FQN만 허용한다.
        val EXPO_DSL_TOKEN = Regex(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\{|\\}|" +
                "(?<![\\w.])(?:expo\\.modules\\.kotlin\\.modules\\.)?ModuleDefinition\\b|" +
                "(?<![\\w.])(?:this\\.)?Name\\s*\\(|" +
                "(?<![\\w.])(?:this\\.)?View\\s*(?:<[^{}]*?>)?\\s*\\(|" +
                "(?<![\\w.])(?:this\\.)?(?:Function|AsyncFunction)\\s*(?:<[^{}]*?>)?\\s*\\(",
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
