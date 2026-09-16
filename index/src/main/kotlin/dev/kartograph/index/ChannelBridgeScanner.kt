package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.qualifiedName
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Kotlin/JVM source의 Flutter 보조 채널(BasicMessageChannel·EventChannel) 경계를 보수적으로 복원한다.
 *
 * 이 스캐너는 개발 소스용 opt-in producer다. Kotlin compiler plugin이나 bytecode를
 * 실행하지 않으므로, graph-file이 제공될 때만 실제 JVM node identity를 붙인다.
 * 이름을 추측해 Pigeon suffix를 만들거나 handler가 실행됐다고 해석하지 않는다.
 * transport별 차이는 [ChannelScanSpec]으로만 주입된다.
 */
internal class ChannelBridgeScanner(private val projectRoot: Path, private val spec: ChannelScanSpec) {

    private val CONSTRUCTOR = Regex("\\b(?:new\\s+)?${spec.constructorName}(?:\\s*<[^>\\n]*>)?\\s*\\(")
    // 수신자의 `?.` safe-call과 `!!` non-null assert를 모두 허용한다 — Android 플러그인은
    // nullable 필드에 `channel!!.setXxx` 형태를 흔히 쓴다.
    private val HANDLER = Regex("(?:\\b([A-Za-z_][A-Za-z0-9_]*)|\\))\\s*(?:!!|\\?)?\\s*\\.\\s*${spec.handlerMethod}\\s*(?=\\(|\\{)")
    private val MUTATION_ASSIGNMENT = Regex("(?m)(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?=(?:${spec.constructorName}|new\\s+${spec.constructorName}))")
    private val SEND = spec.senderMethod?.let { Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(?:!!|\\?)?\\s*\\.\\s*$it\\s*\\(") }
    fun scan(generatedAt: String? = null, graph: CodeGraph? = null): BridgeFactsDocument {
        val facts = mutableListOf<BridgeFact>()
        val limitations = mutableListOf<String>()
        val files = mutableListOf<Path>()
        ProjectTraversal.walkSources(projectRoot) { files.add(it) }
        val stats = ScanStats()
        files.sorted().forEach { scanFile(it, facts, stats) }

        val withSymbols = facts.map { fact ->
            graph?.let { attachSnapshotSymbol(fact, it, projectRoot) } ?: fact
        }.sortedWith(compareBy({ it.location.path }, { it.location.line }, { it.location.column }, { it.kind }, { it.channel.orEmpty() }))
        val noun = spec.factKind.substringBefore('-')
        val dynamicCount = withSymbols.count { it.dynamic }
        val unattributedCount = withSymbols.count { it.kind == spec.factKind && it.channel == null }
        val sourceOnlyCount = withSymbols.count { it.symbol == null }
        if (dynamicCount > 0) limitations += "${spec.dynamicLimitation}: $dynamicCount $noun fact(s) use a non-literal channel name"
        if (unattributedCount > 0) limitations += "${spec.unattributedLimitation}: $unattributedCount $noun handler(s) have no channel"
        if (stats.unsupportedSends > 0 && spec.unscannedSendLimitation != null) {
            limitations += "${spec.unscannedSendLimitation}: ${stats.unsupportedSends} Kotlin sender call(s) are outside the native receiver-only v2 contract"
        }
        if (sourceOnlyCount > 0) limitations += "missing-handler-usrs: source scanning cannot resolve JVM identifiers for $sourceOnlyCount $noun fact(s); pass --graph-file with a matching compiler snapshot"
        if (stats.javaSources > 0) {
            limitations += "${spec.javaLimitation}: raw Java source is scanned lexically; Kotlin metadata and generator-produced channel identities require --graph-file"
        }
        if (stats.jniInteropSources > 0) {
            limitations += "unscanned-ffi-interop: ${stats.jniInteropSources} Kotlin/Java source file(s) declare JNI/native interop outside channel join coverage"
        }
        val newest = files.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() } ?: Instant.EPOCH
        return BridgeFactsDocument(
            generatedAt = generatedAt ?: newest.toString(),
            project = projectRoot.toRealPath().toString().replace('\\', '/'),
            target = withSymbols.firstOrNull()?.let { "flutter" },
            facts = withSymbols,
            limitations = limitations.distinct().sorted(),
            transport = spec.transport,
            version = 2,
        )
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val relative = projectRoot.toRealPath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val source = ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")
        val code = stripComments(source)
        val eventCode = maskStringContents(code)
        // 채널 계약 밖의 JNI/FFI interop은 파일 수준 한계로만 관측한다.
        // 문자열 리터럴 안의 표식은 마스킹된 뷰로 제외한다 — 로그 메시지의
        // "System.loadLibrary(...)" 같은 텍스트를 선언으로 오인하지 않기 위해서다.
        if (JNI_INTEROP_PATTERN.containsMatchIn(eventCode) ||
            (path.fileName.toString().endsWith(".java") && JAVA_NATIVE_METHOD_PATTERN.containsMatchIn(eventCode))
        ) stats.jniInteropSources++
        val events = mutableListOf<Event>()
        CONSTRUCTOR.findAll(eventCode).forEach { match ->
            val open = code.indexOf('(', match.range.first)
            val end = balancedEnd(code, open)
            if (open >= 0 && end > open) events += Event.Constructor(match.range.first, end, open, precedingBinding(code, match.range.first), callArguments(code, open, end))
        }
        if (path.fileName.toString().endsWith(".java") && events.any { it is Event.Constructor }) stats.javaSources++
        STRING_ALIAS.findAll(eventCode).forEach { match ->
            val quote = code.indexOf('"', match.range.first)
            val end = quote.takeIf { it >= 0 }?.let { closingQuote(code, it) } ?: -1
            if (quote >= 0 && end > quote) {
                val following = code.substring(end + 1)
                val lineTail = following.substringBefore('\n').trimStart()
                val complete = (lineTail.isEmpty() || lineTail.startsWith(';') || lineTail.startsWith('}')) &&
                    !ALIAS_CONTINUATION.containsMatchIn(following.trimStart())
                events += Event.StringAlias(match.range.first, match.groupValues[1],
                    if (complete) code.substring(quote, end + 1) else match.groupValues[1])
            }
        }
        ALIAS_ASSIGNMENT.findAll(eventCode).forEach { match -> events += Event.Alias(match.range.first, match.groupValues[1], match.groupValues[2]) }
        HANDLER.findAll(eventCode).forEach { match ->
            val receiver = match.groupValues[1].takeUnless { it.isBlank() }
            val callTail = code.substring(match.range.last + 1).dropWhile(Char::isWhitespace)
            val open = if (callTail.startsWith("(")) code.indexOf('(', match.range.last + 1) else -1
            val end = balancedEnd(code, open)
            val isNull = open >= 0 && end > open && code.substring(open + 1, end).trim() == "null"
            val handlerOffset = eventCode.indexOf(spec.handlerMethod, match.range.first).takeIf { it >= 0 } ?: match.range.first
            events += Event.Handler(handlerOffset, receiver, isNull)
        }
        SEND?.findAll(eventCode)?.forEach { match -> events += Event.Send(match.range.first, match.groupValues[1]) }

        // 실행 순서·분기·외부 setter를 분석하지 않으므로 mutable 이름을 literal로 확정하지 않는다.
        // 파일 안의 동명 shadow도 보수적으로 취급하며 실제 위치와 동적 근거는 유지한다.
        val mutableNames = MUTABLE_DECLARATION.findAll(eventCode).map { it.groupValues[1] }.toMutableSet()
        events.filterIsInstance<Event.Constructor>().mapNotNull { it.binding }
            .filter { it.mutable }.forEach { mutableNames += it.name }
        val bindings = mutableListOf<Binding>()
        fun channelFor(name: String, scope: List<Int>): Channel? =
            if (name in mutableNames) Channel(name, true, null) else lookup(bindings, name, scope)
        events.sortedBy { it.offset }.forEach { event ->
            val scope = scopePath(eventCode, event.offset)
            when (event) {
                is Event.Constructor -> {
                    val expression = event.arguments.firstNamed("name") ?: event.arguments.getOrNull(1)
                    val resolved = expression?.let { channelFor(it.trim(), scope) ?: resolveChannel(it) }
                        ?: resolveChannel(null)
                    event.binding?.let { assignment ->
                        val existing = bindings.lastOrNull { it.name == assignment.name && isPrefix(it.scope, scope) }
                        if (existing != null && assignment.mutable) existing.channel = resolved
                        else bindings += Binding(assignment.name, scope, resolved)
                    }
                    event.directHandler = resolved
                }
                is Event.Alias -> {
                    channelFor(event.source, scope)?.let { channel -> bindings += Binding(event.name, scope, channel) }
                }
                is Event.StringAlias -> bindings += Binding(event.name, scope, resolveChannel(event.expression))
                is Event.Handler -> if (!event.isNull) {
                    val channel = event.receiver?.let { channelFor(it, scope) }
                        ?: previousChainedConstructor(events, event.offset, code)?.directHandler
                    val location = location(relative, source, event.offset)
                    facts += BridgeFact(spec.factKind, channel?.value, dynamic = channel?.dynamic ?: true,
                        location = location, target = "flutter", channelPrefix = channel?.prefix)
                }
                is Event.Send -> {
                    if (lookup(bindings, event.receiver, scope) != null ||
                        previousChainedConstructor(events, event.offset, code)?.directHandler != null
                    ) stats.unsupportedSends++
                }
            }
        }
    }

    private fun previousChainedConstructor(events: List<Event>, offset: Int, source: String): Event.Constructor? =
        events.filterIsInstance<Event.Constructor>().lastOrNull { constructor ->
            (constructor.end == offset && source.getOrNull(offset) == ')') ||
                (constructor.end < offset && isChainedSeparator(source, constructor.end + 1, offset))
        }

    /** `)`와 메서드 이름 사이가 `.`·`!!.`·`?.`처럼 연쇄 호출 접미사만인지 확인한다. */
    private fun isChainedSeparator(source: String, from: Int, to: Int): Boolean =
        source.substring(from, to).replace("!!", "").replace("?", "").trim() == "."

    private fun precedingBinding(source: String, offset: Int): BindingSpec? {
        val prefix = source.substring(0, offset).takeLast(240)
        ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[2], match.groupValues[1] == "var") }
        JAVA_ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[2], match.groupValues[1].isEmpty()) }
        MUTATION_ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[1], true) }
        return null
    }

    private fun lookup(bindings: List<Binding>, name: String, scope: List<Int>): Channel? =
        bindings.asReversed().firstOrNull { it.name == name && isPrefix(it.scope, scope) }?.channel

    private fun isPrefix(prefix: List<Int>, value: List<Int>): Boolean =
        prefix.size <= value.size && prefix.indices.all { prefix[it] == value[it] }

    private fun location(path: String, source: String, offset: Int): BridgeLocation {
        val line = source.substring(0, offset.coerceIn(0, source.length)).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
        val column = source.substring(lineStart, offset.coerceIn(lineStart, source.length))
            .toByteArray(Charsets.UTF_8).size + 1
        return BridgeLocation(path, line, column)
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var block = false
        var quote: Char? = null
        var rawQuote = false
        var escaped = false
        var index = 0
        while (index < source.length) {
            val c = source[index]
            val next = source.getOrNull(index + 1)
            if (block) {
                if (c == '*' && next == '/') { out.append("  "); block = false; index += 2 }
                else { out.append(if (c == '\n') '\n' else ' '); index++ }
                continue
            }
            if (rawQuote && source.startsWith("\"\"\"", index)) {
                out.append("\"\"\""); rawQuote = false; index += 3; continue
            }
            if (rawQuote) { out.append(source[index]); index++; continue }
            if (quote == null && source.startsWith("\"\"\"", index)) {
                out.append("\"\"\""); rawQuote = true; index += 3; continue
            }
            when {
                quote != null -> {
                    out.append(c)
                    when { escaped -> escaped = false; c == '\\' -> escaped = true; c == quote -> quote = null }
                    index++
                }
                c == '"' -> { quote = c; out.append(c); index++ }
                c == '/' && next == '*' -> { out.append("  "); block = true; index += 2 }
                c == '/' && next == '/' -> {
                    while (index < source.length && source[index] != '\n') { out.append(' '); index++ }
                }
                else -> { out.append(c); index++ }
            }
        }
        return out.toString()
    }

    private fun scopePath(source: String, end: Int): List<Int> {
        val stack = mutableListOf(0)
        var nextScope = 1
        var quote = false
        var escaped = false
        source.take(end).forEach { c ->
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; return@forEach }
            when (c) {
                '"' -> quote = true
                '{' -> stack += nextScope++
                '}' -> if (stack.size > 1) stack.removeLast()
            }
        }
        return stack.toList()
    }

    private fun balancedEnd(source: String, open: Int): Int {
        if (open < 0 || source.getOrNull(open) != '(') return -1
        var depth = 0
        var quote = false
        var rawQuote = false
        var escaped = false
        var index = open
        while (index < source.length) {
            if (rawQuote) {
                if (source.startsWith("\"\"\"", index)) { rawQuote = false; index += 3 } else index++
                continue
            }
            if (!quote && source.startsWith("\"\"\"", index)) { rawQuote = true; index += 3; continue }
            val c = source[index]
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; index++; continue }
            when (c) { '"' -> quote = true; '(' -> depth++; ')' -> { depth--; if (depth == 0) return index } }
            index++
        }
        return -1
    }

    private fun closingQuote(source: String, opening: Int): Int {
        var escaped = false
        for (index in opening + 1 until source.length) {
            when (val c = source[index]) {
                '\\' -> escaped = !escaped
                '"' -> if (!escaped) return index
                else -> escaped = false
            }
        }
        return -1
    }

    private fun callArguments(source: String, open: Int, end: Int): List<String> {
        if (end <= open + 1) return emptyList()
        val values = mutableListOf<String>()
        var start = open + 1
        var depth = 0
        var quote = false
        var rawQuote = false
        var escaped = false
        var index = start
        while (index < end) {
            if (rawQuote) {
                if (source.startsWith("\"\"\"", index)) { rawQuote = false; index += 3 } else index++
                continue
            }
            if (!quote && source.startsWith("\"\"\"", index)) { rawQuote = true; index += 3; continue }
            val c = source[index]
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; index++; continue }
            when (c) { '"' -> quote = true; '(' -> depth++; ')' -> depth--; ',' -> if (depth == 0) { values += source.substring(start, index).trim(); start = index + 1 } }
            index++
        }
        values += source.substring(start, end).trim()
        return values
    }

    private fun resolveChannel(expression: String?): Channel = when {
        expression == null -> Channel(null, true, null)
        expression.isQuotedOrInterpolated() -> {
            val raw = expression.trim()
            val triple = raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"")
            val body = if (triple) raw.substring(3, raw.length - 3) else raw.substring(1, raw.length - 1)
            val interpolation = interpolationIndex(body, triple)
            if (interpolation >= 0) {
                val prefix = if (triple) decodeRawLiteral(body.substring(0, interpolation)) else decodeLiteral(body.substring(0, interpolation))
                Channel(raw, true, prefix.takeIf { it.isNotEmpty() })
            } else Channel(if (triple) decodeRawLiteral(body) else decodeLiteral(body), false, null)
        }
        else -> Channel(expression.trim(), true, null)
    }

    private fun List<String>.firstNamed(name: String): String? = firstOrNull { argument ->
        argument.substringBefore('=', "").trim() == name
    }?.substringAfter('=', "")?.trim()

    private fun interpolationIndex(value: String, raw: Boolean): Int {
        var index = 0
        while (index < value.length) {
            if (!raw && value[index] == '\\') { index += 2; continue }
            if (value[index] == '$') {
                if (value.isDollarLiteral(index)) { index += 6; continue }
                val next = value.getOrNull(index + 1)
                if (next == '{' || next == '_' || next?.isLetter() == true) return index
            }
            index++
        }
        return -1
    }

    private fun String.isDollarLiteral(index: Int): Boolean =
        index + 5 < length && this[index] == '$' && this[index + 1] == '{' && this[index + 2] == '\'' &&
            this[index + 3] == '$' && this[index + 4] == '\'' && this[index + 5] == '}'

    private fun decodeRawLiteral(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value.isDollarLiteral(index)) { append('$'); index += 6 } else { append(value[index]); index++ }
        }
    }

    private fun decodeLiteral(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value.isDollarLiteral(index)) { append('$'); index += 6; continue }
            when (val c = value[index]) {
                '\\' -> when (val escaped = value.getOrNull(index + 1)) {
                    null -> { append('\\'); index++ }
                    'u' -> {
                        val digits = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                        if (digits.length == 4 && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                            append(digits.toInt(16).toChar()); index += 6
                        } else { append('u'); index += 2 }
                    }
                    else -> { append(when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '\\' -> '\\'; '"' -> '"'; '\'' -> '\''; else -> escaped }); index += 2 }
                }
                else -> { append(c); index++ }
            }
        }
    }

    private fun String.isQuotedOrInterpolated(): Boolean = trim().let {
        if (it.startsWith("\"\"\"")) it.length >= 6 && it.indexOf("\"\"\"", 3) == it.length - 3
        else it.length >= 2 && it.first() == '"' && closingQuote(it, 0) == it.lastIndex
    }

    private data class Channel(val value: String?, val dynamic: Boolean, val prefix: String?)
    private data class Binding(val name: String, val scope: List<Int>, var channel: Channel)
    private data class ScanStats(var unsupportedSends: Int = 0, var javaSources: Int = 0, var jniInteropSources: Int = 0)
    private data class BindingSpec(val name: String, val mutable: Boolean)
    private sealed class Event(open val offset: Int) {
        data class Constructor(override val offset: Int, val end: Int, val open: Int, val binding: BindingSpec?, val arguments: List<String>, var directHandler: Channel? = null) : Event(offset)
        data class StringAlias(override val offset: Int, val name: String, val expression: String) : Event(offset)
        data class Alias(override val offset: Int, val name: String, val source: String) : Event(offset)
        data class Handler(override val offset: Int, val receiver: String?, val isNull: Boolean) : Event(offset)
        data class Send(override val offset: Int, val receiver: String) : Event(offset)
    }

    private companion object {
        val STRING_ALIAS = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"")
        val ASSIGNMENT = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val JAVA_ASSIGNMENT = Regex("\\b(?:(final)\\s+)?[A-Za-z_][A-Za-z0-9_]*(?:\\s*<[^>\\n]*>)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val MUTABLE_DECLARATION = Regex("\\bvar\\s+([A-Za-z_][A-Za-z0-9_]*)\\b")
        val ALIAS_CONTINUATION = Regex("^(?:[.+*/%?:<>=!&|\\-]|get\\b|@)")
        val ALIAS_ASSIGNMENT = Regex("(?m)\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)")
    }
}

/**
 * 문자열 리터럴 내용을 공백으로 마스킹한 뷰를 만든다 — 이벤트 regex와 JNI 표식
 * 판정이 코드처럼 생긴 문자열 텍스트를 사실·선언으로 오인하지 않기 위해서다.
 * v1·v2 브리지 스캐너가 공유한다.
 */
internal fun maskStringContents(source: String): String {
    val out = StringBuilder(source.length)
    var quote = false
    var rawQuote = false
    var escaped = false
    var index = 0
    while (index < source.length) {
        if (!quote && source.startsWith("\"\"\"", index)) {
            rawQuote = true; quote = true; out.append("   "); index += 3; continue
        }
        if (rawQuote && source.startsWith("\"\"\"", index)) {
            rawQuote = false; quote = false; out.append("   "); index += 3; continue
        }
        val c = source[index]
        when {
            rawQuote && c == '\n' -> out.append('\n')
            rawQuote -> out.append(' ')
            !quote && c == '"' -> { quote = true; out.append(c) }
            quote && escaped -> { escaped = false; out.append(' ') }
            quote && c == '\\' -> { escaped = true; out.append(' ') }
            quote && c == '"' -> { quote = false; out.append(c) }
            quote && c == '\n' -> out.append('\n')
            quote -> out.append(' ')
            else -> out.append(c)
        }
        index++
    }
    return out.toString()
}

/** transport별 표면 차이 — 생성자·등록 메서드·fact kind·한계 라벨만 다르고 스캔 의미는 같다. */
internal data class ChannelScanSpec(
    val constructorName: String,
    val handlerMethod: String,
    val senderMethod: String?,
    val factKind: String,
    val transport: String,
    val dynamicLimitation: String,
    val unattributedLimitation: String,
    val unscannedSendLimitation: String?,
    val javaLimitation: String,
)

internal val BASIC_MESSAGE_CHANNEL_SPEC = ChannelScanSpec(
    constructorName = "BasicMessageChannel",
    handlerMethod = "setMessageHandler",
    senderMethod = "send",
    factKind = "message-handle",
    transport = "basic-message-channel",
    dynamicLimitation = "dynamic-message-channel-names",
    unattributedLimitation = "unattributed-message-handles",
    unscannedSendLimitation = "unscanned-message-sends",
    javaLimitation = "java-source-basic-message-analysis",
)

internal val EVENT_CHANNEL_SPEC = ChannelScanSpec(
    constructorName = "EventChannel",
    handlerMethod = "setStreamHandler",
    senderMethod = null,
    factKind = "stream-handle",
    transport = "event-channel",
    dynamicLimitation = "dynamic-event-channel-names",
    unattributedLimitation = "unattributed-stream-handles",
    unscannedSendLimitation = null,
    javaLimitation = "java-source-event-channel-analysis",
)

/** Snapshot의 실제 source location이 한 정점으로만 해석될 때만 JVM identity를 붙인다. */
internal fun attachSnapshotSymbol(fact: BridgeFact, graph: CodeGraph, projectRoot: Path): BridgeFact {
    val declaration = enclosingDeclaration(projectRoot, fact.location.path, fact.location.line) ?: return fact
    val candidates = graph.nodes.values.filter { node ->
        val location = node.location ?: return@filter false
        location.path.replace('\\', '/') == fact.location.path &&
            location.line in declaration.startLine..declaration.endLine &&
            node.name == declaration.name &&
            node.kind.name.lowercase() in setOf("function", "method")
    }
    val preceding = candidates.filter { (it.location?.line ?: Int.MAX_VALUE) <= fact.location.line }
    val node = preceding.singleOrNull() ?: candidates.singleOrNull()
        ?: return fact
    return fact.copy(symbol = BridgeSymbol(node.qualifiedName, node.id.value))
}

private data class SourceDeclaration(val name: String, val startLine: Int, val endLine: Int)

/** Current source supplies the only safe body range; graph lines alone do not. */
private fun enclosingDeclaration(projectRoot: Path, relativePath: String, line: Int): SourceDeclaration? {
    val source = try {
        ProjectTraversal.readSourceLines(projectRoot, projectRoot.resolve(relativePath)).joinToString("\n")
    } catch (_: Exception) {
        return null
    }
    val masked = maskDeclarationStrings(maskDeclarationComments(source))
    val ranges = mutableListOf<SourceDeclaration>()
    var depth = 0
    val lines = masked.split('\n')
    lines.forEachIndexed { index, text ->
        val start = index + 1
        val before = depth
        val kotlinHeader = KOTLIN_HEADER_START.find(text)
        val kotlinOpening = kotlinHeader?.let { findKotlinOpening(lines, index, it.range.last) }
        val javaDeclaration = if (kotlinHeader == null) JAVA_DECLARATION.find(text) else null
        val declarationName = kotlinHeader?.groupValues?.get(1) ?: javaDeclaration?.groupValues?.get(1)
        val openingLine = kotlinOpening ?: javaDeclaration?.let { index }
        if (declarationName != null && openingLine != null) {
            val openingDepth = before + 1
            var after = before
            var end = start
            var cursor = index
            var current = before
            while (cursor <= openingLine) {
                current += braceDelta(lines[cursor])
                cursor++
            }
            if (current >= openingDepth) {
                while (cursor < lines.size && current >= openingDepth) {
                    current += braceDelta(lines[cursor])
                    cursor++
                    end = cursor
                }
            }
            if (end == start) end = openingLine + 1
            ranges += SourceDeclaration(declarationName, start, end)
        }
        depth = (before + braceDelta(text)).coerceAtLeast(0)
    }
    return ranges.filter { line in it.startLine..it.endLine }.minByOrNull { it.endLine - it.startLine }
}

private fun findKotlinOpening(lines: List<String>, declarationLine: Int, afterOpen: Int): Int? {
    var parentheses = 1
    for (lineIndex in declarationLine until minOf(lines.size, declarationLine + 128)) {
        val text = lines[lineIndex].let { if (lineIndex == declarationLine) it.substring(afterOpen + 1) else it }
        for (character in text) {
            when {
                parentheses > 0 && character == '(' -> parentheses++
                parentheses > 0 && character == ')' -> parentheses--
                parentheses == 0 && character == '{' -> return lineIndex
            }
        }
    }
    return null
}

private fun maskDeclarationStrings(source: String): String = buildString(source.length) {
    var quoted = false
    var rawQuoted = false
    var escaped = false
    var index = 0
    while (index < source.length) {
        if (rawQuoted && source.startsWith("\"\"\"", index)) { rawQuoted = false; quoted = false; append("   "); index += 3; continue }
        if (!quoted && source.startsWith("\"\"\"", index)) { rawQuoted = true; quoted = true; append("   "); index += 3; continue }
        val character = source[index]
        when {
            rawQuoted && character == '\n' -> append('\n')
            rawQuoted -> append(' ')
            !quoted && character == '"' -> { quoted = true; append(character) }
            quoted && escaped -> { escaped = false; append(' ') }
            quoted && character == '\\' -> { escaped = true; append(' ') }
            quoted && character == '"' -> { quoted = false; append(character) }
            quoted && character == '\n' -> append('\n')
            quoted -> append(' ')
            else -> append(character)
        }
        index++
    }
}

private fun maskDeclarationComments(source: String): String = buildString(source.length) {
    var block = false
    var line = false
    var quote = false
    var rawQuote = false
    var escaped = false
    var index = 0
    while (index < source.length) {
        val character = source[index]
        val next = source.getOrNull(index + 1)
        if (line) {
            if (character == '\n') { line = false; append(character) } else append(' ')
            index++
            continue
        }
        if (block) {
            if (character == '*' && next == '/') { block = false; append("  "); index += 2 }
            else { append(if (character == '\n') '\n' else ' '); index++ }
            continue
        }
        if (rawQuote && source.startsWith("\"\"\"", index)) { rawQuote = false; quote = false; append("\"\"\""); index += 3; continue }
        if (!quote && source.startsWith("\"\"\"", index)) { rawQuote = true; quote = true; append("\"\"\""); index += 3; continue }
        if (rawQuote) { append(source[index]); index++; continue }
        when {
            !quote && character == '/' && next == '/' -> { line = true; append("  "); index++ }
            !quote && character == '/' && next == '*' -> { block = true; append("  "); index++ }
            quote && character == '\\' && !escaped -> { escaped = true; append(character) }
            quote && escaped -> { escaped = false; append(character) }
            character == '"' -> { quote = !quote; append(character) }
            else -> { escaped = false; append(character) }
        }
        index++
    }
}

private fun braceDelta(text: String): Int = text.count { it == '{' } - text.count { it == '}' }

private val KOTLIN_HEADER_START = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
private val JAVA_DECLARATION = Regex("\\b(?:public\\s+|private\\s+|protected\\s+|static\\s+|final\\s+|synchronized\\s+|native\\s+|abstract\\s+)*(?:[A-Za-z_][A-Za-z0-9_<>.?\\[\\]]*\\s+)([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*\\{")
