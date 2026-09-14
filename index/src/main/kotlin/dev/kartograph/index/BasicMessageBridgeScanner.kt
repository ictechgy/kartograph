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
 * Kotlin/JVM source의 Flutter BasicMessageChannel 경계를 보수적으로 복원한다.
 *
 * 이 스캐너는 개발 소스용 opt-in producer다. Kotlin compiler plugin이나 bytecode를
 * 실행하지 않으므로, graph-file이 제공될 때만 실제 JVM node identity를 붙인다.
 * 이름을 추측해 Pigeon suffix를 만들거나 handler가 실행됐다고 해석하지 않는다.
 */
internal class BasicMessageBridgeScanner(private val projectRoot: Path) {
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
        val dynamicCount = withSymbols.count { it.dynamic }
        val unattributedCount = withSymbols.count { it.kind == "message-handle" && it.channel == null }
        val sourceOnlyCount = withSymbols.count { it.symbol == null }
        if (dynamicCount > 0) limitations += "dynamic-message-channel-names: $dynamicCount message fact(s) use a non-literal channel name"
        if (unattributedCount > 0) limitations += "unattributed-message-handles: $unattributedCount message handler(s) have no channel"
        if (stats.unsupportedSends > 0) limitations += "unscanned-message-sends: ${stats.unsupportedSends} Kotlin sender call(s) are outside the native receiver-only v2 contract"
        if (sourceOnlyCount > 0) limitations += "missing-handler-usrs: source scanning cannot resolve JVM identifiers for $sourceOnlyCount message fact(s); pass --graph-file with a matching compiler snapshot"
        if (stats.javaMessageSources > 0) {
            limitations += "java-source-basic-message-analysis: raw Java source is scanned lexically; Kotlin metadata and generated Pigeon identities require --graph-file"
        }
        val newest = files.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() } ?: Instant.EPOCH
        return BridgeFactsDocument(
            generatedAt = generatedAt ?: newest.toString(),
            project = projectRoot.toRealPath().toString().replace('\\', '/'),
            target = withSymbols.firstOrNull()?.let { "flutter" },
            facts = withSymbols,
            limitations = limitations.distinct().sorted(),
            transport = "basic-message-channel",
            version = 2,
        )
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val relative = projectRoot.toRealPath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val source = ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")
        val code = stripComments(source)
        val eventCode = maskStringContents(code)
        val events = mutableListOf<Event>()
        CONSTRUCTOR.findAll(eventCode).forEach { match ->
            val open = code.indexOf('(', match.range.first)
            val end = balancedEnd(code, open)
            if (open >= 0 && end > open) events += Event.Constructor(match.range.first, end, open, precedingBinding(code, match.range.first), callArguments(code, open, end))
        }
        if (path.fileName.toString().endsWith(".java") && events.any { it is Event.Constructor }) stats.javaMessageSources++
        STRING_ALIAS.findAll(eventCode).forEach { match ->
            val quote = code.indexOf('"', match.range.first)
            val end = quote.takeIf { it >= 0 }?.let { closingQuote(code, it) } ?: -1
            if (quote >= 0 && end > quote) events += Event.StringAlias(match.range.first, match.groupValues[1], code.substring(quote, end + 1))
        }
        ALIAS_ASSIGNMENT.findAll(eventCode).forEach { match -> events += Event.Alias(match.range.first, match.groupValues[1], match.groupValues[2]) }
        HANDLER.findAll(eventCode).forEach { match ->
            val receiver = match.groupValues[1].takeUnless { it.isBlank() }
            val open = code.indexOf('(', match.range.first)
            val end = balancedEnd(code, open)
            val isNull = open >= 0 && end > open && code.substring(open + 1, end).trim() == "null"
            events += Event.Handler(match.range.first, receiver, isNull)
        }
        SEND.findAll(eventCode).forEach { match -> events += Event.Send(match.range.first, match.groupValues[1]) }

        val bindings = mutableListOf<Binding>()
        events.sortedBy { it.offset }.forEach { event ->
            val scope = scopePath(eventCode, event.offset)
            when (event) {
                is Event.Constructor -> {
                    val expression = event.arguments.getOrNull(1)
                    val resolved = expression?.let { lookup(bindings, it.trim(), scope) ?: resolveChannel(it) }
                        ?: resolveChannel(null)
                    event.binding?.let { assignment ->
                        val existing = bindings.lastOrNull { it.name == assignment.name && isPrefix(it.scope, scope) }
                        if (existing != null && assignment.mutable) existing.channel = resolved
                        else bindings += Binding(assignment.name, scope, resolved)
                    }
                    event.directHandler = resolved
                }
                is Event.Alias -> {
                    lookup(bindings, event.source, scope)?.let { channel -> bindings += Binding(event.name, scope, channel) }
                }
                is Event.StringAlias -> bindings += Binding(event.name, scope, resolveChannel(event.expression))
                is Event.Handler -> if (!event.isNull) {
                    val channel = event.receiver?.let { lookup(bindings, it, scope) }
                        ?: previousChainedConstructor(events, event.offset, code)?.directHandler
                    val location = location(relative, code, event.offset)
                    facts += BridgeFact("message-handle", channel?.value, dynamic = channel?.dynamic ?: true,
                        location = location, target = "flutter", channelPrefix = channel?.prefix)
                }
                is Event.Send -> {
                    stats.unsupportedSends++
                }
            }
        }
    }

    private fun previousChainedConstructor(events: List<Event>, offset: Int, source: String): Event.Constructor? =
        events.filterIsInstance<Event.Constructor>().lastOrNull { constructor ->
            (constructor.end == offset && source.getOrNull(offset) == ')') ||
                (constructor.end < offset && source.substring(constructor.end + 1, offset).trim() == ".")
        }

    private fun precedingBinding(source: String, offset: Int): BindingSpec? {
        val prefix = source.substring(0, offset).takeLast(240)
        ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[2], match.groupValues[1] == "var") }
        JAVA_ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[1], true) }
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
        return BridgeLocation(path, line, offset - lineStart + 1)
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var block = false
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < source.length) {
            val c = source[index]
            val next = source.getOrNull(index + 1)
            when {
                block && c == '*' && next == '/' -> { out.append("  "); block = false; index += 2 }
                block -> { out.append(if (c == '\n') '\n' else ' '); index++ }
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

    /** Event regexes run against a string-masked view, so code-like text is not a fact. */
    private fun maskStringContents(source: String): String {
        val out = StringBuilder(source.length)
        var quote = false
        var escaped = false
        source.forEach { c ->
            when {
                !quote && c == '"' -> { quote = true; out.append(c) }
                quote && escaped -> { escaped = false; out.append(' ') }
                quote && c == '\\' -> { escaped = true; out.append(' ') }
                quote && c == '"' -> { quote = false; out.append(c) }
                quote && c == '\n' -> out.append('\n')
                quote -> out.append(' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun balancedEnd(source: String, open: Int): Int {
        if (open < 0 || source.getOrNull(open) != '(') return -1
        var depth = 0
        var quote = false
        var escaped = false
        for (index in open until source.length) {
            val c = source[index]
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; continue }
            when (c) { '"' -> quote = true; '(' -> depth++; ')' -> { depth--; if (depth == 0) return index } }
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
        var escaped = false
        for (index in start until end) {
            val c = source[index]
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; continue }
            when (c) { '"' -> quote = true; '(' -> depth++; ')' -> depth--; ',' -> if (depth == 0) { values += source.substring(start, index).trim(); start = index + 1 } }
        }
        values += source.substring(start, end).trim()
        return values
    }

    private fun resolveChannel(expression: String?): Channel = when {
        expression == null -> Channel(null, true, null)
        expression.isQuotedOrInterpolated() -> {
            val raw = expression.trim()
            val decoded = decodeLiteral(raw.substring(1, raw.length - 1).substringBefore('$'))
            if ('$' in raw && decoded.isNotEmpty()) Channel(raw, true, decoded) else Channel(decodeLiteral(raw.substring(1, raw.length - 1)), '$' in raw, null)
        }
        else -> Channel(expression.trim(), true, null)
    }

    private fun decodeLiteral(value: String): String = buildString {
        var escaped = false
        value.forEach { c ->
            if (escaped) { append(when (c) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '\\' -> '\\'; '"' -> '"'; else -> c }); escaped = false }
            else if (c == '\\') escaped = true else append(c)
        }
        if (escaped) append('\\')
    }

    private fun String.isQuotedOrInterpolated(): Boolean = trim().let { it.length >= 2 && it.first() == '"' && it.last() == '"' }

    private data class Channel(val value: String?, val dynamic: Boolean, val prefix: String?)
    private data class Binding(val name: String, val scope: List<Int>, var channel: Channel)
    private data class ScanStats(var unsupportedSends: Int = 0, var javaMessageSources: Int = 0)
    private data class BindingSpec(val name: String, val mutable: Boolean)
    private sealed class Event(open val offset: Int) {
        data class Constructor(override val offset: Int, val end: Int, val open: Int, val binding: BindingSpec?, val arguments: List<String>, var directHandler: Channel? = null) : Event(offset)
        data class StringAlias(override val offset: Int, val name: String, val expression: String) : Event(offset)
        data class Alias(override val offset: Int, val name: String, val source: String) : Event(offset)
        data class Handler(override val offset: Int, val receiver: String?, val isNull: Boolean) : Event(offset)
        data class Send(override val offset: Int, val receiver: String) : Event(offset)
    }

    private companion object {
        val CONSTRUCTOR = Regex("\\b(?:new\\s+)?BasicMessageChannel(?:\\s*<[^>\\n]*>)?\\s*\\(")
        val STRING_ALIAS = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"")
        val ASSIGNMENT = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val JAVA_ASSIGNMENT = Regex("\\b(?:[A-Za-z_][A-Za-z0-9_]*)(?:\\s*<[^>\\n]*>)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val ALIAS_ASSIGNMENT = Regex("(?m)\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)")
        val MUTATION_ASSIGNMENT = Regex("(?m)(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?=(?:BasicMessageChannel|new\\s+BasicMessageChannel))")
        val HANDLER = Regex("(?:\\b([A-Za-z_][A-Za-z0-9_]*)|\\))\\s*\\.\\s*setMessageHandler\\s*(?=\\(|\\{)")
        val SEND = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*send\\s*\\(")
    }
}

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
    val node = candidates.singleOrNull() ?: return fact
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
        val declaration = DECLARATION.find(text)
        val opening = declaration?.let { text.indexOf('{', it.range.first) } ?: -1
        if (declaration != null && opening >= 0) {
            val openingDepth = before + 1
            var after = before + braceDelta(text)
            var end = start
            if (after >= openingDepth) {
                var cursor = index + 1
                var current = after
                while (cursor < lines.size && current >= openingDepth) {
                    current += braceDelta(lines[cursor])
                    cursor++
                    end = cursor
                }
            }
            ranges += SourceDeclaration(declaration.groupValues[1], start, end)
        }
        depth = (before + braceDelta(text)).coerceAtLeast(0)
    }
    return ranges.filter { line in it.startLine..it.endLine }.minByOrNull { it.endLine - it.startLine }
}

private fun maskDeclarationStrings(source: String): String = buildString(source.length) {
    var quoted = false
    var escaped = false
    source.forEach { character ->
        when {
            !quoted && character == '"' -> { quoted = true; append(character) }
            quoted && escaped -> { escaped = false; append(' ') }
            quoted && character == '\\' -> { escaped = true; append(' ') }
            quoted && character == '"' -> { quoted = false; append(character) }
            quoted && character == '\n' -> append('\n')
            quoted -> append(' ')
            else -> append(character)
        }
    }
}

private fun maskDeclarationComments(source: String): String = buildString(source.length) {
    var block = false
    var line = false
    var quote = false
    var escaped = false
    var index = 0
    while (index < source.length) {
        val character = source[index]
        val next = source.getOrNull(index + 1)
        when {
            line && character == '\n' -> { line = false; append(character) }
            line -> append(' ')
            block && character == '*' && next == '/' -> { block = false; append("  "); index++ }
            block -> append(if (character == '\n') '\n' else ' ')
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

private val DECLARATION = Regex("\\b(?:fun\\s+|(?:public|private|protected|internal|override|static|final|suspend|inline|native)\\s+)*([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*(?:\\{|:)")
