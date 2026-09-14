package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.BridgeSymbol
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.NodeKind
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
        files.sorted().forEach { scanFile(it, facts) }

        val withSymbols = facts.map { fact ->
            graph?.let { attachSymbol(fact, it) } ?: fact
        }.sortedWith(compareBy({ it.location.path }, { it.location.line }, { it.location.column }, { it.kind }, { it.channel.orEmpty() }))
        val dynamicCount = withSymbols.count { it.dynamic }
        val unattributedCount = withSymbols.count { it.kind == "message-handle" && it.channel == null }
        val sourceOnlyCount = withSymbols.count { it.symbol == null }
        if (dynamicCount > 0) limitations += "dynamic-message-channel-names: $dynamicCount message fact(s) use a non-literal channel name"
        if (unattributedCount > 0) limitations += "unattributed-message-handles: $unattributedCount message handler(s) have no channel"
        if (sourceOnlyCount > 0) limitations += "missing-handler-usrs: source scanning cannot resolve JVM identifiers for $sourceOnlyCount message fact(s); pass --graph-file with a matching compiler snapshot"
        if (files.any { it.fileName.toString().endsWith(".java") }) {
            limitations += "java-source-basic-message-analysis: raw Java source is scanned lexically; Kotlin metadata and generated Pigeon identities require --graph-file"
        }
        val newest = files.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() } ?: Instant.EPOCH
        return BridgeFactsDocument(
            generatedAt = generatedAt ?: newest.toString(),
            project = ".",
            target = withSymbols.firstOrNull()?.let { "flutter" },
            facts = withSymbols,
            limitations = limitations.distinct().sorted(),
            transport = "basic-message-channel",
            version = 2,
        )
    }

    private fun attachSymbol(fact: BridgeFact, graph: CodeGraph): BridgeFact {
        val candidates = graph.nodes.values.filter { node ->
            val location = node.location ?: return@filter false
            location.path.replace('\\', '/') == fact.location.path && location.line == fact.location.line
        }
        // A source line can contain multiple declarations (or a generated synthetic
        // node). An ambiguous match is evidence we cannot safely attach.
        val node = candidates.singleOrNull() ?: run {
            // JVM snapshots generally locate the enclosing Kotlin function at its
            // declaration line, while the bridge call is inside its body. Use the
            // nearest unique function/method declaration before the observed line;
            // unrelated fields/classes are deliberately excluded.
            val enclosing = graph.nodes.values.filter { candidate ->
                val location = candidate.location ?: return@filter false
                val line = location.line ?: return@filter false
                location.path.replace('\\', '/') == fact.location.path &&
                    line <= fact.location.line &&
                    candidate.kind in setOf(NodeKind.FUNCTION, NodeKind.METHOD)
            }.groupBy { it.location!!.line!! }.maxByOrNull { it.key }?.value.orEmpty()
            enclosing.singleOrNull()
        } ?: return fact
        return fact.copy(symbol = BridgeSymbol(node.qualifiedName, node.id.value))
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>) {
        val relative = projectRoot.toRealPath().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val source = ProjectTraversal.readSourceLines(projectRoot, path).joinToString("\n")
        val code = stripComments(source)
        val events = mutableListOf<Event>()
        CONSTRUCTOR.findAll(code).forEach { match ->
            val open = code.indexOf('(', match.range.first)
            val end = balancedEnd(code, open)
            if (open >= 0 && end > open) events += Event.Constructor(match.range.first, end, open, precedingBinding(code, match.range.first), callArguments(code, open, end))
        }
        ALIAS_ASSIGNMENT.findAll(code).forEach { match -> events += Event.Alias(match.range.first, match.groupValues[1], match.groupValues[2]) }
        HANDLER.findAll(code).forEach { match ->
            val receiver = match.groupValues[1].takeUnless { it.isBlank() }
            val open = code.indexOf('(', match.range.first)
            val hasBody = code.getOrNull(match.range.last + 1) == '{'
            val isNull = open >= 0 && balancedEnd(code, open) > open && code.substring(open + 1, balancedEnd(code, open)).trim() == "null"
            events += Event.Handler(match.range.first, receiver, hasBody, isNull, code.lastIndexOf(')', match.range.first))
        }
        SEND.findAll(code).forEach { match -> events += Event.Send(match.range.first, match.groupValues[1], code.lastIndexOf(')', match.range.first)) }

        val bindings = mutableListOf<Binding>()
        var lastOffset = 0
        events.sortedBy { it.offset }.forEach { event ->
            val depth = braceDepth(code, event.offset)
            bindings.removeIf { it.depth > depth }
            when (event) {
                is Event.Constructor -> {
                    val resolved = resolveChannel(event.arguments.firstOrNull { it.isQuotedOrInterpolated() })
                    event.binding?.let { assignment ->
                        val existing = bindings.lastOrNull { it.name == assignment.name && it.depth <= depth }
                        if (existing != null && assignment.mutable) existing.channel = resolved
                        else bindings += Binding(assignment.name, depth, resolved)
                    }
                    event.directHandler = resolved
                }
                is Event.Alias -> {
                    lookup(bindings, event.source, depth)?.let { channel ->
                        val existing = bindings.lastOrNull { it.name == event.name && it.depth <= depth }
                        if (existing != null) existing.channel = channel else bindings += Binding(event.name, depth, channel)
                    }
                }
                is Event.Handler -> if (!event.isNull) {
                    val channel = event.receiver?.let { lookup(bindings, it, depth) }
                        ?: previousChainedConstructor(events, event.offset, code)?.directHandler
                    val location = location(relative, code, event.offset)
                    facts += BridgeFact("message-handle", channel?.value, dynamic = channel?.dynamic ?: true,
                        location = location, target = "flutter", channelPrefix = channel?.prefix)
                }
                is Event.Send -> {
                    val channel = lookup(bindings, event.receiver, depth)
                        ?: previousChainedConstructor(events, event.offset, code)?.directHandler
                    val location = location(relative, code, event.offset)
                    facts += BridgeFact("message-send", channel?.value, dynamic = channel?.dynamic ?: true,
                        location = location, target = "flutter", channelPrefix = channel?.prefix)
                }
            }
            lastOffset = event.offset
        }
        // Keep the local variable to make the event pass explicit for future parser
        // extensions and to prevent accidental source-order changes during refactors.
        if (lastOffset < 0) error("unreachable source event offset")
    }

    private fun previousChainedConstructor(events: List<Event>, offset: Int, source: String): Event.Constructor? =
        events.filterIsInstance<Event.Constructor>().lastOrNull { constructor ->
            constructor.end < offset && source.substring(constructor.end + 1, offset).trim() == "."
        }

    private fun precedingBinding(source: String, offset: Int): BindingSpec? {
        val prefix = source.substring(0, offset).takeLast(240)
        ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[2], match.groupValues[1] == "var") }
        JAVA_ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[1], true) }
        MUTATION_ASSIGNMENT.find(prefix)?.let { match -> return BindingSpec(match.groupValues[1], true) }
        return null
    }

    private fun lookup(bindings: List<Binding>, name: String, depth: Int): Channel? =
        bindings.asReversed().firstOrNull { it.name == name && it.depth <= depth }?.channel

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

    private fun braceDepth(source: String, end: Int): Int {
        var depth = 0
        var quote = false
        var escaped = false
        source.take(end).forEach { c ->
            if (quote) { when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quote = false }; return@forEach }
            when (c) { '"' -> quote = true; '{' -> depth++; '}' -> depth-- }
        }
        return depth.coerceAtLeast(0)
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
    private data class Binding(val name: String, val depth: Int, var channel: Channel)
    private data class BindingSpec(val name: String, val mutable: Boolean)
    private sealed class Event(open val offset: Int) {
        data class Constructor(override val offset: Int, val end: Int, val open: Int, val binding: BindingSpec?, val arguments: List<String>, var directHandler: Channel? = null) : Event(offset)
        data class Alias(override val offset: Int, val name: String, val source: String) : Event(offset)
        data class Handler(override val offset: Int, val receiver: String?, val hasBody: Boolean, val isNull: Boolean, val previousClose: Int) : Event(offset)
        data class Send(override val offset: Int, val receiver: String, val previousClose: Int) : Event(offset)
    }

    private companion object {
        val CONSTRUCTOR = Regex("\\b(?:new\\s+)?BasicMessageChannel(?:\\s*<[^>\\n]*>)?\\s*\\(")
        val ASSIGNMENT = Regex("\\b(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val JAVA_ASSIGNMENT = Regex("\\b(?:[A-Za-z_][A-Za-z0-9_]*)(?:\\s*<[^>\\n]*>)?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
        val ALIAS_ASSIGNMENT = Regex("(?m)\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)")
        val MUTATION_ASSIGNMENT = Regex("(?m)(?<![.\\w])([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?=(?:BasicMessageChannel|new\\s+BasicMessageChannel))")
        val HANDLER = Regex("(?:\\b([A-Za-z_][A-Za-z0-9_]*)|\\))\\s*\\.\\s*setMessageHandler\\s*(?=\\(|\\{)")
        val SEND = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*send\\s*\\(")
    }
}
