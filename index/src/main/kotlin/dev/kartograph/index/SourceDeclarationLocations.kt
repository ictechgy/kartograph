package dev.kartograph.index

import dev.kartograph.core.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque

/** 확정된 파일의 package·owner·직접 member header가 유일할 때만 source 선언 줄을 보강한다. */
public object SourceDeclarationLocations {
    /**
     * 기존 compiler 줄은 보존하고, source에서 보강한 줄에는 별도 origin attribute를 붙인다.
     *
     * 파일은 최대 2 MiB를 한 번만 읽는다. 파일별 후보 index를 만든 뒤 정점은 key 조회만
     * 하므로 선언 수만큼 source 전체를 다시 훑지 않는다.
     */
    public fun enrich(graph: CodeGraph, projectRoot: Path, paths: Map<NodeId, String>): CodeGraph {
        val root = projectRoot.toRealPath()
        val localClassOwners = graph.enclosures.mapNotNullTo(mutableSetOf()) { enclosure ->
            enclosure.localClass.value.removePrefix("class:")
                .takeIf { it.length < enclosure.localClass.value.length }
        }
        val declarations = mutableMapOf<String, SourceDeclarations>()
        val nodes = graph.nodes.values.map { node ->
            val location = node.location ?: return@map node
            if (location.line != null || node.synthesized ||
                NodeAttribute.EXTERNAL_STUB in node.attributes ||
                NodeAttribute.FILE_FACADE in node.attributes ||
                NodeAttribute.PROPERTY_ACCESSOR in node.attributes
            ) return@map node
            val identity = identity(node) ?: return@map node
            if (hasLocalClassAncestor(identity.owner, localClassOwners)) return@map node
            if (identity.member != null) {
                val ownerId = JvmNodeId.classId(identity.owner)
                val owner = graph.nodes[ownerId] ?: return@map node
                if (owner.synthesized || NodeAttribute.FILE_FACADE in owner.attributes ||
                    NodeAttribute.EXTERNAL_STUB in owner.attributes
                ) return@map node
            }
            val relative = paths[node.id] ?: return@map node
            if (fileName(location.path) != fileName(relative)) return@map node
            val source = declarations.getOrPut(relative) { read(root, relative) }
            val expectedPackage = identity.owner.substringBeforeLast('/', "").replace('/', '.')
            if (source.packageName != expectedPackage) return@map node
            val key = declarationKey(node, identity) ?: return@map node
            val line = source.declarations[key].orEmpty().singleOrNull() ?: return@map node
            node.copy(
                location = SourceLocation(relative, line),
                attributes = node.attributes + NodeAttribute.SOURCE_DECLARATION_LOCATION,
            )
        }
        val enriched = CodeGraph(
            nodes, graph.edges, graph.externalCalls, graph.serviceProviders, graph.enclosures,
            graph.callbackArguments, graph.parameterUses, graph.lambdaEscapes,
        )
        return if (graph.compilerCallPositionsCaptured) {
            enriched.withCompilerCallPositions(graph.locatedCompilerReferences)
        } else enriched
    }

    private enum class DeclarationKind { TYPE, JAVA_FIELD, KOTLIN_PROPERTY, METHOD, CONSTRUCTOR }

    private data class DeclarationKey(
        val owners: List<String>,
        val kind: DeclarationKind,
        val name: String,
        val arity: Int? = null,
    )

    private data class RawDeclaration(
        val offset: Int,
        val kind: DeclarationKind,
        val name: String,
        val arity: Int? = null,
        val sourceOwner: String? = null,
    )

    private data class JvmIdentity(val owner: String, val member: String? = null, val arity: Int? = null)
    private data class DelimiterDepth(val braces: Int, val parentheses: Int)
    private data class SourceDeclarations(
        val packageName: String?,
        val declarations: Map<DeclarationKey, List<Int>>,
    )

    private val UNKNOWN = SourceDeclarations(null, emptyMap())

    private fun declarationKey(node: GraphNode, identity: JvmIdentity): DeclarationKey? {
        val owners = identity.owner.substringAfterLast('/').split('$').filter(String::isNotEmpty)
        return when (node.kind) {
            in TYPE_KINDS -> DeclarationKey(owners, DeclarationKind.TYPE, owners.lastOrNull() ?: return null)
            NodeKind.FIELD -> DeclarationKey(owners, DeclarationKind.JAVA_FIELD, identity.member ?: return null)
            NodeKind.PROPERTY -> DeclarationKey(owners, DeclarationKind.KOTLIN_PROPERTY, node.name)
            NodeKind.METHOD -> DeclarationKey(
                owners, DeclarationKind.METHOD, identity.member ?: return null, identity.arity ?: return null,
            )
            NodeKind.CONSTRUCTOR -> DeclarationKey(
                owners, DeclarationKind.CONSTRUCTOR, "<init>", identity.arity ?: return null,
            )
            else -> null
        }
    }

    private fun identity(node: GraphNode): JvmIdentity? {
        val value = node.id.value
        if (node.kind in TYPE_KINDS) {
            if (!value.startsWith("class:")) return null
            return JvmIdentity(value.removePrefix("class:"))
        }
        val prefix = when (node.kind) {
            NodeKind.FIELD, NodeKind.PROPERTY -> "field:"
            NodeKind.METHOD, NodeKind.CONSTRUCTOR -> "method:"
            else -> return null
        }
        if (!value.startsWith(prefix)) return null
        val raw = value.removePrefix(prefix)
        val separator = raw.indexOf('#').takeIf { it > 0 } ?: return null
        val owner = raw.substring(0, separator)
        val signature = raw.substring(separator + 1)
        return if (prefix == "field:") {
            JvmIdentity(owner, signature.substringBeforeLast(':', ""))
                .takeIf { it.member?.isNotEmpty() == true }
        } else {
            val open = signature.indexOf('(').takeIf { it > 0 } ?: return null
            val descriptor = signature.substring(open)
            JvmIdentity(owner, signature.substring(0, open), descriptorParameterCount(descriptor) ?: return null)
        }
    }

    /** enclosure가 local이라고 확정한 class 자신과 `$` 경계의 모든 하위 owner를 거른다. */
    private fun hasLocalClassAncestor(owner: String, localOwners: Set<String>): Boolean {
        var candidate = owner
        while (true) {
            if (candidate in localOwners) return true
            val separator = candidate.lastIndexOf('$')
            if (separator < 0) return false
            candidate = candidate.substring(0, separator)
        }
    }

    /** source path index가 디렉터리를 보강해도 원래 class SourceFile basename은 같아야 한다. */
    private fun fileName(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    /** source 파일 하나는 한 번만 bounded하게 읽고 유일성 대조용 header index만 보관한다. */
    private fun read(root: Path, relative: String): SourceDeclarations = try {
        val path = root.resolve(relative).normalize()
        if (!path.startsWith(root) || Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().startsWith(root)
        ) UNKNOWN else {
            val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) UNKNOWN else {
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                declarations(RouteSourceFile(relative, relative.endsWith(".java"), false, text))
            }
        }
    } catch (_: IOException) {
        UNKNOWN
    } catch (_: SecurityException) {
        UNKNOWN
    }

    private fun declarations(file: RouteSourceFile): SourceDeclarations {
        val raw = buildList {
            file.types.forEach { type ->
                add(RawDeclaration(type.start, DeclarationKind.TYPE, type.name))
            }
            file.functions.mapNotNullTo(this) { functionDeclaration(file, it) }
            if (file.isJava) {
                JAVA_FIELD.findAll(file.masked).forEach { match ->
                    add(RawDeclaration(match.groups[1]!!.range.first, DeclarationKind.JAVA_FIELD, match.groupValues[1]))
                }
                JAVA_CONSTRUCTOR.findAll(file.masked).mapNotNullTo(this) { constructorDeclaration(file, it, true) }
            } else {
                KOTLIN_PROPERTY.findAll(file.masked).forEach { match ->
                    add(RawDeclaration(
                        match.groups[1]!!.range.first,
                        DeclarationKind.KOTLIN_PROPERTY,
                        match.groupValues[1],
                    ))
                }
                KOTLIN_CONSTRUCTOR.findAll(file.masked).mapNotNullTo(this) {
                    constructorDeclaration(file, it, false)
                }
            }
        }
        val offsets = buildSet {
            raw.forEach { add(it.offset) }
            file.types.filter { it.bodyStart >= 0 }.forEach { add(it.bodyStart + 1) }
        }
        val owners = ownerScopes(file.types, offsets)
            ?: return SourceDeclarations(file.packageName, emptyMap())
        val functionDepths = functionDepths(file.functions, offsets)
            ?: return SourceDeclarations(file.packageName, emptyMap())
        val delimiterDepths = delimiterDepths(file.masked, offsets)
            ?: return SourceDeclarations(file.packageName, emptyMap())
        val lineStarts = lineStarts(file.source)
        val result = mutableMapOf<DeclarationKey, MutableList<Int>>()
        raw.forEach { declaration ->
            val scopes = owners[declaration.offset] ?: return@forEach
            val key = if (declaration.kind == DeclarationKind.TYPE) {
                DeclarationKey(scopes.map { it.name } + declaration.name, declaration.kind, declaration.name)
            } else {
                val owner = scopes.lastOrNull() ?: return@forEach
                if (functionDepths[declaration.offset] != 0 ||
                    delimiterDepths[declaration.offset] != delimiterDepths[owner.bodyStart + 1] ||
                    declaration.sourceOwner?.let { it != owner.name } == true
                ) return@forEach
                DeclarationKey(scopes.map { it.name }, declaration.kind, declaration.name, declaration.arity)
            }
            result.getOrPut(key) { mutableListOf() } += lineAt(lineStarts, declaration.offset)
        }
        return SourceDeclarations(file.packageName, result.mapValues { it.value.toList() })
    }

    private fun functionDeclaration(file: RouteSourceFile, function: RouteFunctionDecl): RawDeclaration? {
        val headerEnd = (if (function.bodyStart > function.start) function.bodyStart + 1 else function.end)
            .coerceIn(function.start + 1, file.masked.length)
        if (headerEnd - function.start > MAX_HEADER_CHARS) return null
        val header = file.masked.substring(function.start, headerEnd)
        val names = Regex("\\b${Regex.escape(function.name)}\\b\\s*\\(").findAll(header).toList()
        val match = names.singleOrNull() ?: return null
        val offset = function.start + match.range.first
        val open = file.masked.indexOf('(', offset + function.name.length)
        val close = balancedEnd(file.code, open)
            .takeIf { it > open && it - function.start <= MAX_HEADER_CHARS }
            ?: return null
        val arguments = callArguments(file.code, open, close)
        if (arguments.size != function.parameters.size) return null
        return RawDeclaration(offset, DeclarationKind.METHOD, function.name, arguments.size)
    }

    private fun constructorDeclaration(file: RouteSourceFile, match: MatchResult, java: Boolean): RawDeclaration? {
        val offset = if (java) match.groups[1]!!.range.first else match.range.first
        val name = if (java) match.groupValues[1] else "constructor"
        val open = file.masked.indexOf('(', offset + name.length)
        val close = balancedEnd(file.code, open).takeIf { it > open && it - offset <= MAX_HEADER_CHARS } ?: return null
        val tail = file.masked.substring(close + 1, (close + 1 + MAX_HEADER_CHARS).coerceAtMost(file.masked.length))
        val isDeclaration = if (java) JAVA_CONSTRUCTOR_TAIL.containsMatchIn(tail)
        else KOTLIN_CONSTRUCTOR_TAIL.containsMatchIn(tail)
        if (!isDeclaration) return null
        return RawDeclaration(
            offset,
            DeclarationKind.CONSTRUCTOR,
            "<init>",
            callArguments(file.code, open, close).size,
            name.takeIf { java },
        )
    }

    /** 모든 query offset의 owner stack을 type open/close event 한 번으로 만든다. */
    private fun ownerScopes(
        types: List<RouteTypeDecl>,
        offsets: Set<Int>,
    ): Map<Int, List<RouteTypeDecl>>? {
        val actions = buildList {
            types.filter { it.bodyStart >= 0 && it.bodyEnd > it.bodyStart }.forEach { type ->
                add(ScopeAction(type.bodyStart + 1, 0, type))
                add(ScopeAction(type.bodyEnd, 1, type))
            }
            offsets.forEach { add(ScopeAction(it, 2)) }
        }.sortedWith(compareBy(ScopeAction::position, ScopeAction::order))
        val active = ArrayDeque<RouteTypeDecl>()
        val result = mutableMapOf<Int, List<RouteTypeDecl>>()
        actions.forEach { action ->
            when (action.order) {
                0 -> {
                    val parent = active.peekLast()
                    if (parent != null && action.type!!.bodyEnd >= parent.bodyEnd) return null
                    active.addLast(action.type)
                }
                1 -> {
                    if (active.peekLast() != action.type) return null
                    active.removeLast()
                }
                else -> result[action.position] = active.toList()
            }
        }
        return result
    }

    private data class ScopeAction(
        val position: Int,
        val order: Int,
        val type: RouteTypeDecl? = null,
    )

    /** local 함수·함수 body 안 후보를 거르기 위한 depth를 한 번의 event sweep으로 만든다. */
    private fun functionDepths(functions: List<RouteFunctionDecl>, offsets: Set<Int>): Map<Int, Int>? {
        val actions = buildList {
            functions.filter { it.bodyStart >= 0 && it.end > it.bodyStart }.forEach { function ->
                add(DepthAction(function.bodyStart + 1, 0, 1))
                add(DepthAction(function.end, 1, -1))
            }
            offsets.forEach { add(DepthAction(it, 2, 0)) }
        }.sortedWith(compareBy(DepthAction::position, DepthAction::order))
        var depth = 0
        val result = mutableMapOf<Int, Int>()
        actions.forEach { action ->
            if (action.order == 2) result[action.position] = depth else {
                depth += action.delta
                if (depth < 0) return null
            }
        }
        return result
    }

    private data class DepthAction(val position: Int, val order: Int, val delta: Int)

    /** query offset의 brace·parenthesis depth를 source 한 번 순회해 기록한다. */
    private fun delimiterDepths(masked: String, offsets: Set<Int>): Map<Int, DelimiterDepth>? {
        var braces = 0
        var parentheses = 0
        var cursor = 0
        val result = mutableMapOf<Int, DelimiterDepth>()
        offsets.sorted().forEach { offset ->
            while (cursor < offset.coerceAtMost(masked.length)) {
                when (masked[cursor]) {
                    '{' -> braces++
                    '}' -> braces--
                    '(' -> parentheses++
                    ')' -> parentheses--
                }
                if (braces < 0 || parentheses < 0) return null
                cursor++
            }
            result[offset] = DelimiterDepth(braces, parentheses)
        }
        return result
    }

    private fun lineStarts(source: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        source.forEachIndexed { offset, character -> if (character == '\n') starts += offset + 1 }
        return starts.toIntArray()
    }

    private fun lineAt(starts: IntArray, offset: Int): Int {
        val found = starts.binarySearch(offset)
        return if (found >= 0) found + 1 else -found - 1
    }

    private fun descriptorParameterCount(descriptor: String): Int? {
        if (!descriptor.startsWith('(')) return null
        var count = 0
        var index = 1
        while (index < descriptor.length && descriptor[index] != ')') {
            while (descriptor.getOrNull(index) == '[') index++
            when (val element = descriptor.getOrNull(index) ?: return null) {
                in PRIMITIVE_DESCRIPTORS -> index++
                'L' -> {
                    index = descriptor.indexOf(';', index).takeIf { it >= 0 }?.plus(1) ?: return null
                }
                else -> return null
            }
            count++
        }
        return count.takeIf { descriptor.getOrNull(index) == ')' }
    }

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_HEADER_CHARS = 4 * 1024
    private const val PRIMITIVE_DESCRIPTORS = "BCDFIJSZ"
    private val TYPE_KINDS = setOf(
        NodeKind.CLASS,
        NodeKind.INTERFACE,
        NodeKind.OBJECT,
        NodeKind.ENUM,
        NodeKind.ANNOTATION_CLASS,
    )
    private val KOTLIN_PROPERTY = Regex("\\b(?:val|var)[ \\t]+([A-Za-z_]\\w*)\\b")
    private val KOTLIN_CONSTRUCTOR = Regex("\\bconstructor\\s*\\(")
    private val KOTLIN_CONSTRUCTOR_TAIL = Regex("^\\s*(?::|\\{)")
    private val JAVA_CONSTRUCTOR = Regex(
        "(?m)^[ \\t]*(?:(?:public|protected|private)[ \\t]+)?([A-Za-z_$][\\w$]*)[ \\t]*\\(",
    )
    private val JAVA_CONSTRUCTOR_TAIL = Regex("^\\s*(?:throws\\s+[\\w.$,\\s]+)?\\{")
    private val JAVA_FIELD = Regex(
        "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|transient|volatile)[ \\t]+)*" +
            "(?:[A-Za-z_$][\\w$]*(?:[ \\t]*\\.[ \\t]*[A-Za-z_$][\\w$]*)*" +
            "(?:[ \\t]*<[^;={}()\\n]*>)?(?:[ \\t]*\\[[ \\t]*\\])*)[ \\t]+" +
            "([A-Za-z_$][\\w$]*)[ \\t]*(?:\\[[ \\t]*\\])?[ \\t]*(?==|;)",
    )
}
