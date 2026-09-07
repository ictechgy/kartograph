package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/** Kotlin/Java 소스의 정적 리터럴만 추출하고 동적 이름은 버리지 않는 브리지 스캐너다. */
public class BridgeFactScanner(private val projectRoot: Path) {
    /** 프로젝트 상대 근거와 조인 불가능한 사실의 한계를 bridge-facts v1 문서로 만든다. */
    public fun scan(generatedAt: String): BridgeFactsDocument {
        val facts = mutableListOf<BridgeFact>()
        val stats = ScanStats()
        Files.walk(projectRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension in SOURCE_EXTENSIONS }
                .filter { path -> !isPruned(path) }
                .sorted().forEach { scanFile(it, facts, stats) }
        }
        val ordered = facts.sortedWith(compareBy({ it.location.path }, { it.location.line }, { it.kind }, { it.method.orEmpty() }))
        val counts = ordered.groupingBy(BridgeFact::target).eachCount()
        val target = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
        val limitations = buildList {
            val dynamic = ordered.count { it.dynamic && it.kind == "channel-register" }
            if (dynamic > 0) add("dynamic-channel-names: $dynamic channel registration(s) use a non-literal name")
            val missingHandlerUsrs = ordered.count { it.kind == "method-handle" && it.symbol?.usr == null }
            if (missingHandlerUsrs > 0) add(
                "missing-handler-usrs: source scanning cannot resolve JVM identifiers for $missingHandlerUsrs method handler(s)",
            )
            val unattributed = ordered.count { it.kind == "method-handle" && it.channel == null }
            if (unattributed > 0) add(
                "unattributed-method-handles: $unattributed method handler(s) could not be assigned to a channel",
            )
            if (stats.unscannedHandlers > 0) add(
                "unscanned-method-handlers: ${stats.unscannedHandlers} handler callback(s) are not inline lambdas",
            )
            if (counts.size > 1) add(
                "mixed-targets: facts come from more than one bridge " +
                    counts.toSortedMap().entries.joinToString(prefix = "(", postfix = ")") { "${it.key} ${it.value}" },
            )
        }
        return BridgeFactsDocument(
            generatedAt = generatedAt,
            target = target,
            project = ".",
            facts = ordered,
            limitations = limitations,
        )
    }

    private fun scanFile(path: Path, facts: MutableList<BridgeFact>, stats: ScanStats) {
        val relative = projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
            .joinToString("/")
        val flutterChannels = mutableMapOf<String, Channel>()
        var pendingChannel: PendingChannel? = null
        val handlerScopes = ArrayDeque<HandlerScope>()
        val methodScopes = ArrayDeque<MethodScope>()
        var pendingReactModule: String? = null
        var reactScope: ReactScope? = null
        var pendingReactMethod = false
        var inBlockComment = false
        var braceDepth = 0
        fun recordChannel(
            pending: PendingChannel,
            completed: CompletedCall,
            line: String,
            lineNumber: Int,
            code: String,
        ) {
            val channel = completed.arguments.getOrNull(1)?.literalOrDynamic() ?: Channel(null, true)
            if (pending.variable != null) {
                flutterChannels[pending.variable] = channel
                return
            }
            val handler = CHAINED_SUFFIX.find(completed.remainder) ?: return
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
        path.readLines().forEachIndexed { zeroBased, line ->
            val lineNumber = zeroBased + 1
            val stripped = stripComments(line, inBlockComment)
            inBlockComment = stripped.inBlockComment
            val code = stripped.code
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

            braceDepth += braceDelta(code)
            while (methodScopes.lastOrNull()?.let { braceDepth < it.depth } == true) methodScopes.removeLast()
            while (handlerScopes.lastOrNull()?.let { braceDepth < it.depth } == true) handlerScopes.removeLast()
            reactScope?.let { scope -> if (braceDepth < scope.depth) reactScope = null }
        }
    }

    private fun isPruned(path: Path): Boolean = ProjectTraversal.isPrunedSource(projectRoot, path)

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
    ): BridgeFact = BridgeFact(
        kind, channel, method, dynamic,
        BridgeLocation(path, line, source.indexOf(token).coerceAtLeast(0) + 1),
        target = target,
    )

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

    private fun String.literalOrDynamic(): Channel {
        val trimmed = trim()
        return if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            Channel(trimmed.substring(1, trimmed.length - 1), false)
        } else {
            Channel(trimmed, true)
        }
    }

    private data class Channel(val value: String?, val dynamic: Boolean)

    private data class PendingChannel(val variable: String?, val collector: CallArguments)

    private data class CompletedCall(val arguments: List<String>, val remainder: String)

    private data class HandlerScope(val channel: Channel, val depth: Int)

    private data class MethodScope(val channel: Channel, val depth: Int)

    private data class ReactScope(val channel: String, val depth: Int)

    private data class StrippedLine(val code: String, val inBlockComment: Boolean)

    private data class ScanStats(var unscannedHandlers: Int = 0)

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
        val METHOD_CHANNEL_CALL = Regex("\\bMethodChannel\\s*\\(")
        val SET_HANDLER = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val WHEN_METHOD = Regex("\\\"([^\\\"]+)\\\"\\s*->")
        val METHOD_WHEN = Regex("\\bwhen\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\.method\\s*\\)\\s*\\{")
        val ANY_WHEN = Regex("\\bwhen\\s*\\(")
        val CHAINED_SUFFIX = Regex("\\.\\s*setMethodCallHandler\\s*(?:\\(|\\{)")
        val REACT_MODULE = Regex("@ReactModule\\s*\\(\\s*name\\s*=\\s*\\\"([^\\\"]+)\\\"")
        val REACT_METHOD = Regex("@ReactMethod\\b")
        val FUNCTION = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val CLASS_DECLARATION = Regex("\\b(?:class|object)\\s+[A-Za-z_][A-Za-z0-9_]*")
    }
}
