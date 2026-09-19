package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.BridgeLocation
import dev.kartograph.core.CodeGraph
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 명시적인 RN RCTDeviceEventEmitter 요청 뒤 emit 호출만 인식한다. */
internal class ReactNativeEventScanner(private val projectRoot: Path) {
    fun scan(generatedAt: String?, graph: CodeGraph?): BridgeFactsDocument {
        val root = projectRoot.toRealPath()
        val files = mutableListOf<Path>()
        ProjectTraversal.walkSources(root) { files.add(it) }
        val facts = files.sorted().flatMap { path -> scanFile(root, path) }
            .map { fact -> graph?.let { attachSnapshotSymbol(fact, it, root) } ?: fact }
        val missing = facts.count { it.symbol?.usr == null }
        val dynamic = facts.count { it.dynamic }
        return BridgeFactsDocument(
            version = 2, transport = "react-native-event", project = root.toString(),
            target = if (facts.isEmpty()) null else "react-native", facts = facts,
            generatedAt = generatedAt ?: (files.maxOfOrNull { Files.getLastModifiedTime(it).toInstant() } ?: Instant.EPOCH).toString(),
            limitations = buildList {
                add("rn-event-scan-scope: only getJSModule(RCTDeviceEventEmitter).emit calls are scanned; Expo, codegen, wrappers and emitter variables are not resolved")
                if (missing > 0) add("missing-event-usrs: " + missing + " native event emissions lack JVM identities")
                if (dynamic > 0) add("dynamic-event-names: " + dynamic + " native event emissions have non-literal names")
            },
        )
    }

    private fun scanFile(root: Path, path: Path): List<BridgeFact> {
        val source = ProjectTraversal.readSourceLines(root, path).joinToString("\n")
        val comments = maskDeclarationComments(source)
        val code = maskStringContents(comments)
        val importsReact = Regex("\\bimport\\s+com\\.facebook\\.react\\.").containsMatchIn(code)
        return EMIT.findAll(code).filter { importsReact || it.value.contains("com.facebook.react.modules.core.") }.map { match ->
            val start = match.range.last + 1
            var end = start
            var depth = 0
            var malformed = false
            while (end < code.length) {
                val character = code[end]
                if (depth == 0 && (character == ',' || character == ')')) break
                if (depth == 0 && character in "]}") { malformed = true; break }
                if (character in "([{") depth++
                if (character in ")]}") depth--
                end++
            }
            val expression = comments.substring(start, end).trim().ifEmpty { "<missing>" }
            val (name, dynamic) = literalOrDynamicChannel(expression)
            val unsafe = name.isEmpty() || name.codePoints().anyMatch(::unsafeCharacter)
            val unresolved = dynamic || unsafe || malformed || end == code.length
            val channel = if (unresolved) expression.map { if (unsafeCharacter(it.code)) ' ' else it }.joinToString("") else name
            val offset = match.groups[1]!!.range.first
            val lineStart = source.lastIndexOf('\n', offset - 1) + 1
            BridgeFact(kind = "event-emit", channel = channel, dynamic = unresolved, target = "react-native",
                location = BridgeLocation(root.relativize(path).toString().replace('\\', '/'),
                    source.substring(0, offset).count { it == '\n' } + 1,
                    source.substring(lineStart, offset).toByteArray(Charsets.UTF_8).size + 1))
        }.toList()
    }

    private fun unsafeCharacter(code: Int): Boolean = code in 0..31 || code in 127..159 ||
        code == 0x2028 || code == 0x2029 || code in 0xD800..0xDFFF

    private companion object {
        val EMIT = Regex("\\.\\s*getJSModule\\s*\\(\\s*(?:com\\.facebook\\.react\\.modules\\.core\\.)?" +
            "(?:DeviceEventManagerModule\\.)?RCTDeviceEventEmitter\\s*(?:::\\s*class\\s*\\.\\s*java|\\.\\s*class)" +
            "\\s*\\)\\s*(?:\\?\\.|\\.)\\s*(emit)\\s*\\(")
    }
}
