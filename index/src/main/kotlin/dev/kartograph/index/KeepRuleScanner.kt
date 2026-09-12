package dev.kartograph.index

import dev.kartograph.core.KeepDeclarationKind
import dev.kartograph.core.KeepMemberCondition
import dev.kartograph.core.KeepMemberKind
import dev.kartograph.core.KeepRule
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.SourceLocation
import dev.kartograph.core.Visibility
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** ProGuard/R8 파일에서 class keep specification과 파일·줄 근거를 읽는다. */
public class KeepRuleScanner @JvmOverloads constructor(
    private val projectRoot: Path,
    private val retainClassMembers: Boolean = false,
) {
    /** 재귀 include까지 실제 읽은 입력이다. 빈 규칙 파일도 내용 신선도에 포함한다. */
    public val inputFiles: Set<Path> get() = observedFiles.toSet()
    private val observedFiles = linkedSetOf<Path>()

    /** 모든 파일을 입력 순서로 읽되 불완전하게 해석할 keep 문법에서는 실패한다. */
    public fun scan(ruleFiles: Iterable<Path>): List<KeepRule> {
        observedFiles.clear()
        val realProjectRoot = resolveProjectRoot()
        val visited = mutableSetOf<Path>()
        return buildList {
            ruleFiles.forEach { ruleFile ->
                val realRuleFile = resolveTopLevelRule(ruleFile)
                val scope = if (realRuleFile.startsWith(realProjectRoot)) {
                    RuleScope(realProjectRoot, external = false)
                } else {
                    RuleScope(requireNotNull(realRuleFile.parent), external = true)
                }
                addAll(scanFile(realRuleFile, scope, visited, mutableSetOf()))
            }
        }
    }

    private fun scanFile(
        ruleFile: Path,
        scope: RuleScope,
        visited: MutableSet<Path>,
        active: MutableSet<Path>,
    ): List<KeepRule> {
        if (!visited.add(ruleFile)) return emptyList()
        observedFiles.add(ruleFile)
        active.add(ruleFile)
        val sourcePath = scope.display(ruleFile)
        val lines = try {
            Files.readAllLines(ruleFile)
        } catch (error: IOException) {
            throw KeepRuleScanningException("keep rule file cannot be read", error)
        }
        var memberBlockDepth = 0
        var memberBlockStart: SourceLocation? = null
        var conditionalRule: KeepRule? = null
        var skippingConditionalRule = false
        val conditionalMembers = mutableListOf<KeepMemberCondition>()
        var plainRule: KeepRule? = null
        var skippingPlainRule = false
        var plainKeepAllMembers = false
        val plainKeptMembers = mutableListOf<KeepMemberCondition>()
        var conservativeMemberBlock = false
        return try {
            val parsedRules = buildList {
                lines.forEachIndexed { index, rawLine ->
                    val uncommented = rawLine.substringBefore('#')
                    // Member 모드에서는 조건부 owner 생존을 보수적으로 가정해 reflection member를 보존한다.
                    val memberHeader = retainClassMembers &&
                        Regex("^\\s*-keepclassmembers(?=[,\\s])").containsMatchIn(uncommented)
                    val ruleLine = when {
                        memberHeader -> {
                            val header = uncommented.replace(Regex("^(\\s*)-keepclassmembers(?=[,\\s])"), "$1-keep")
                            conservativeMemberBlock = '{' in header && '}' !in header
                            header
                        }
                        conservativeMemberBlock && uncommented.trim().startsWith('}') -> {
                            conservativeMemberBlock = false
                            uncommented
                        }
                        else -> uncommented
                    }
                    val location = SourceLocation(sourcePath, index + 1)
                    rejectContentAfterBlock(ruleLine, location)
                    val insideMemberBlock = memberBlockDepth > 0
                    if (retainClassMembers && insideMemberBlock && '}' in ruleLine && !ruleLine.trim().startsWith('}')) {
                        throw unsupported("member block closing must be on its own line", location)
                    }
                    if (!insideMemberBlock && ruleLine.trim() == "{") {
                        throw unsupported("member block opening must be on the keep rule line", location)
                    }
                    val openingBraces = ruleLine.count { character -> character == '{' }
                    val closingBraces = ruleLine.count { character -> character == '}' }
                    when {
                        insideMemberBlock && (conditionalRule != null || skippingConditionalRule) -> {
                            if (ruleLine.trim().startsWith('}')) {
                                if (conditionalRule != null && conditionalMembers.isEmpty()) {
                                    throw unsupported("conditional keep rule has no supported members", location)
                                }
                                conditionalRule?.let { rule ->
                                    add(rule.copy(memberConditions = conditionalMembers.toList()))
                                }
                                conditionalRule = null
                                skippingConditionalRule = false
                                conditionalMembers.clear()
                            } else if (!skippingConditionalRule) {
                                parseMemberCondition(ruleLine, location)?.let(conditionalMembers::add)
                            }
                        }
                        insideMemberBlock && (plainRule != null || skippingPlainRule) -> {
                            if (ruleLine.trim().startsWith('}')) {
                                if (plainRule != null && !plainKeepAllMembers && plainKeptMembers.isEmpty()) {
                                    throw unsupported("plain keep rule has no supported members", location)
                                }
                                plainRule?.let { rule ->
                                    add(
                                        rule.copy(
                                            keptMembers = plainKeptMembers.toList(),
                                            keepAllMembers = plainKeepAllMembers,
                                        ),
                                    )
                                }
                                plainRule = null
                                skippingPlainRule = false
                                plainKeepAllMembers = false
                                plainKeptMembers.clear()
                            } else if (!skippingPlainRule) {
                                val parsedMember = try {
                                    parsePlainMember(ruleLine, location)
                                } catch (problem: KeepRuleScanningException) {
                                    if (!conservativeMemberBlock) throw problem
                                    PlainMember(keepAll = true)
                                }
                                parsedMember?.let { member ->
                                    plainKeepAllMembers = plainKeepAllMembers || member.keepAll
                                    member.condition?.let(plainKeptMembers::add)
                                }
                            }
                        }
                        !insideMemberBlock && isConditionalKeepHeader(ruleLine) -> {
                            if (openingBraces != 1 || closingBraces !in 0..1) {
                                throw unsupported("unsupported conditional keep rule block", location)
                            }
                            if (closingBraces == 1) {
                                val header = ruleLine.substringBefore('{') + " {"
                                parseConditionalKeepHeader(header, location)?.let { rule ->
                                    val members = parseInlineConditionalMembers(ruleLine, location)
                                    if (members.isEmpty()) {
                                        throw unsupported("conditional keep rule has no supported members", location)
                                    }
                                    add(rule.copy(memberConditions = members))
                                }
                            } else {
                                conditionalRule = parseConditionalKeepHeader(ruleLine, location)
                                skippingConditionalRule = conditionalRule == null
                            }
                        }
                        !insideMemberBlock && isPlainKeepHeader(ruleLine) &&
                            openingBraces == 1 && closingBraces == 0 -> {
                            plainRule = parseLine(ruleLine, location, insideMemberBlock = false)
                            skippingPlainRule = plainRule == null
                        }
                        else -> {
                            val include = includeValue(ruleLine, insideMemberBlock, location)
                            if (include != null) {
                                val includedFile = resolveIncludedRule(include, ruleFile, scope, location)
                                if (includedFile in active) throw unsupported("cyclic keep rule include", location)
                                addAll(scanFile(includedFile, scope, visited, active))
                            } else {
                                val parsed = try {
                                    parseLine(ruleLine, location, insideMemberBlock)
                                } catch (problem: KeepRuleScanningException) {
                                    if (!memberHeader || '{' !in ruleLine || '}' !in ruleLine) throw problem
                                    parseLine(ruleLine.substringBefore('{') + "{ *; }", location, insideMemberBlock)
                                }
                                parsed?.let(::add)
                            }
                        }
                    }
                    if (memberBlockDepth == 0 && openingBraces > closingBraces) memberBlockStart = location
                    memberBlockDepth += openingBraces - closingBraces
                    if (memberBlockDepth < 0) throw unsupported("unbalanced keep rule braces", location)
                    if (memberBlockDepth == 0) memberBlockStart = null
                }
            }
            if (memberBlockDepth != 0) {
                throw unsupported("unbalanced keep rule braces", requireNotNull(memberBlockStart))
            }
            parsedRules
        } finally {
            active.remove(ruleFile)
        }
    }

    private fun isConditionalKeepHeader(rawLine: String): Boolean =
        rawLine.trim().split(WHITESPACE, limit = 2).first().substringBefore(',') == "-keepclasseswithmembers"

    private fun isPlainKeepHeader(rawLine: String): Boolean =
        rawLine.trim().split(WHITESPACE, limit = 2).first().substringBefore(',') == "-keep"

    private fun parseConditionalKeepHeader(rawLine: String, location: SourceLocation): KeepRule? {
        val line = rawLine.trim()
        val directive = line.split(WHITESPACE, limit = 2).first()
        if (directive.substringBefore(',') != "-keepclasseswithmembers") {
            throw unsupported("unsupported conditional keep rule", location)
        }
        val keepDirective = directive.replaceFirst("-keepclasseswithmembers", "-keep")
        return parseLine("$keepDirective${line.removePrefix(directive)}", location, insideMemberBlock = false)
    }

    private fun parseMemberCondition(rawLine: String, location: SourceLocation): KeepMemberCondition? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val tokens = line.removeSuffix(";").split(WHITESPACE)
        parseWildcardMember(tokens, location)?.let { condition -> return condition }
        return parseSignatureMember(line.removeSuffix(";"), location)
    }

    private fun parseInlineConditionalMembers(
        rawLine: String,
        location: SourceLocation,
    ): List<KeepMemberCondition> = rawLine.substringAfter('{').substringBeforeLast('}')
        .split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { specification ->
            parseMemberCondition("$specification;", location)
                ?: throw unsupported("unsupported inline member specification", location)
        }

    private fun parseWildcardMember(
        tokens: List<String>,
        location: SourceLocation,
    ): KeepMemberCondition? {
        val kind = tokens.lastOrNull()?.toMemberKind() ?: return null
        val requiredVisibilities = mutableSetOf<Visibility>()
        val forbiddenVisibilities = mutableSetOf<Visibility>()
        val requiredModifiers = mutableSetOf<JvmModifier>()
        val forbiddenModifiers = mutableSetOf<JvmModifier>()
        var annotationPattern: String? = null
        tokens.dropLast(1).forEach { qualifier ->
            val forbidden = qualifier.startsWith('!')
            val value = qualifier.removePrefix("!")
            when {
                qualifier.startsWith('@') && !forbidden && annotationPattern == null -> {
                    annotationPattern = qualifier.removePrefix("@").takeIf(CLASS_PATTERN::matches)
                        ?: throw unsupported("unsupported conditional member specification", location)
                }
                value in JVM_VISIBILITIES -> {
                    val target = if (forbidden) forbiddenVisibilities else requiredVisibilities
                    target += requireNotNull(JVM_VISIBILITIES[value])
                }
                value in JVM_MODIFIERS -> {
                    val target = if (forbidden) forbiddenModifiers else requiredModifiers
                    target += requireNotNull(JVM_MODIFIERS[value])
                }
                else -> throw unsupported("unsupported conditional member specification", location)
            }
        }
        return KeepMemberCondition(
            kind = kind,
            requiredAnnotationPattern = annotationPattern,
            requiredJvmVisibilities = requiredVisibilities,
            forbiddenJvmVisibilities = forbiddenVisibilities,
            requiredJvmModifiers = requiredModifiers,
            forbiddenJvmModifiers = forbiddenModifiers,
        )
    }

    private fun String.toMemberKind(): KeepMemberKind? = when (this) {
        "<methods>" -> KeepMemberKind.METHODS
        "<fields>" -> KeepMemberKind.FIELDS
        "<init>", "<init>(...)" -> KeepMemberKind.CONSTRUCTORS
        else -> null
    }

    private fun parseSignatureMember(line: String, location: SourceLocation): KeepMemberCondition {
        val method = METHOD_MEMBER.matchEntire(line)
        if (method != null) {
            val visibility = method.groupValues[1].toMemberVisibility()
            val returnType = method.groupValues[2]
            val name = method.groupValues[3]
            val parameters = method.groupValues[4]
            val constructor = name == "<init>"
            if (constructor && returnType.isNotEmpty()) {
                throw unsupported("unsupported conditional member specification", location)
            }
            return KeepMemberCondition(
                kind = if (constructor) KeepMemberKind.CONSTRUCTORS else KeepMemberKind.METHODS,
                requiredJvmVisibilities = visibility.required,
                forbiddenJvmVisibilities = visibility.forbidden,
                namePattern = if (constructor) "<init>" else name,
                jvmDescriptor = methodDescriptor(returnType, parameters, constructor, location),
            )
        }
        val field = FIELD_MEMBER.matchEntire(line)
            ?: throw unsupported("unsupported conditional member specification", location)
        val visibility = field.groupValues[1].toMemberVisibility()
        return KeepMemberCondition(
            kind = KeepMemberKind.FIELDS,
            requiredJvmVisibilities = visibility.required,
            forbiddenJvmVisibilities = visibility.forbidden,
            namePattern = field.groupValues[3],
            jvmDescriptor = field.groupValues[2].toJvmDescriptor(location),
        )
    }

    private fun methodDescriptor(
        returnType: String,
        parameters: String,
        constructor: Boolean,
        location: SourceLocation,
    ): String? {
        if (returnType == "***" || parameters == "...") return null
        val parameterDescriptor = if (parameters.isBlank()) "" else parameters.split(',')
            .joinToString("") { type -> type.trim().toJvmDescriptor(location) }
        val resultDescriptor = if (constructor) "V" else returnType.toJvmDescriptor(location)
        return "($parameterDescriptor)$resultDescriptor"
    }

    private fun String.toJvmDescriptor(location: SourceLocation): String {
        var type = this
        var dimensions = 0
        while (type.endsWith("[]")) {
            dimensions++
            type = type.removeSuffix("[]")
        }
        if ('*' in type || '?' in type) {
            throw unsupported("unsupported conditional member type wildcard", location)
        }
        val element = when (type) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> if (type.matches(CLASS_PATTERN)) "L${type.replace('.', '/')};" else {
                throw unsupported("unsupported conditional member specification", location)
            }
        }
        return "[".repeat(dimensions) + element
    }

    private fun String.toMemberVisibility(): MemberVisibility {
        if (isEmpty()) return MemberVisibility()
        val forbidden = startsWith('!')
        val visibility = requireNotNull(JVM_VISIBILITIES[removePrefix("!")])
        return if (forbidden) {
            MemberVisibility(forbidden = setOf(visibility))
        } else {
            MemberVisibility(required = setOf(visibility))
        }
    }

    private fun parsePlainMember(rawLine: String, location: SourceLocation): PlainMember? {
        val memberSpecification = rawLine.trim()
        if (memberSpecification.isEmpty()) return null
        if (memberSpecification == "*;") return PlainMember(keepAll = true)
        return PlainMember(condition = parseMemberCondition(memberSpecification, location))
    }

    private fun rejectContentAfterBlock(ruleLine: String, location: SourceLocation) {
        val closingBrace = ruleLine.lastIndexOf('}')
        if (closingBrace >= 0 && ruleLine.substring(closingBrace + 1).isNotBlank()) {
            throw unsupported("content after a member block is not supported", location)
        }
    }

    private fun includeValue(rawLine: String, insideMemberBlock: Boolean, location: SourceLocation): String? {
        if (insideMemberBlock) return null
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val tokens = line.split(WHITESPACE)
        return when {
            tokens.first() == "-include" && tokens.size == 2 -> tokens[1]
            tokens.first() == "-include" -> throw unsupported("unsupported include directive", location)
            tokens.size == 1 && tokens.first().startsWith('@') && tokens.first().length > 1 ->
                tokens.first().drop(1)
            line.startsWith('@') -> throw unsupported("unsupported keep rule", location)
            else -> null
        }
    }

    private fun parseLine(rawLine: String, location: SourceLocation, insideMemberBlock: Boolean): KeepRule? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        val tokens = line.split(WHITESPACE)
        val directive = tokens.first()
        if (insideMemberBlock) return null
        if (directive == "-if") {
            throw unsupported("conditional keep rules are not supported", location)
        }
        if (directive == "-basedirectory") {
            throw unsupported("base directory directives are not supported", location)
        }
        if (directive in INHERITANCE_KEYWORDS) {
            throw unsupported("multiline inheritance is not supported", location)
        }

        val baseDirective = directive.substringBefore(',')
        if (baseDirective != "-keep") {
            if (baseDirective in NON_RETENTION_KEEP_DIRECTIVES || baseDirective in IGNORED_DIRECTIVES) return null
            if (directive.startsWith("-keep")) {
                throw unsupported("unsupported keep rule", location)
            }
            if (directive.startsWith('-')) throw unsupported("unsupported directive", location)
            return null
        }

        val modifiers = directive.substringAfter(',', "")
            .split(',')
            .filter(String::isNotEmpty)
            .toSet()
        if ((modifiers - SUPPORTED_MODIFIERS).isNotEmpty()) {
            throw unsupported("unsupported keep rule", location)
        }
        val declarationIndex = tokens.indexOfFirst { token -> token.toDeclarationKind() != null }
        if (declarationIndex < 1) throw unsupported("unsupported keep rule", location)
        val classSpecModifiers = tokens.subList(1, declarationIndex)
        val annotationModifiers = classSpecModifiers.filter { modifier -> modifier.startsWith('@') }
        val accessModifiers = classSpecModifiers - annotationModifiers.toSet()
        val annotationPattern = annotationModifiers.singleOrNull()?.removePrefix("@")
        if (annotationModifiers.size > 1 || annotationPattern?.matches(CLASS_PATTERN) == false) {
            throw unsupported("unsupported keep rule", location)
        }
        if (accessModifiers.any { modifier -> modifier.removePrefix("!") !in JVM_ACCESS_FLAGS }) {
            throw unsupported("unsupported keep rule", location)
        }
        val requiredAccess = accessModifiers.filterNot { modifier -> modifier.startsWith('!') }
        val forbiddenAccess = accessModifiers.filter { modifier -> modifier.startsWith('!') }
            .map { modifier -> modifier.removePrefix("!") }
        val declarationKind = tokens[declarationIndex].toDeclarationKind()
        val classNamePattern = tokens.getOrNull(declarationIndex + 1)
        val trailingTokens = tokens.drop(declarationIndex + 2)
        val extendsPattern = parseExtendsPattern(trailingTokens, location)
        val inlineMembers = parseInlineMemberBlock(trailingTokens, location)
        if (declarationKind == null || classNamePattern == null || !CLASS_PATTERN.matches(classNamePattern)) {
            throw unsupported("unsupported keep rule", location)
        }
        if ("allowshrinking" in modifiers) return null
        return KeepRule(
            declarationKind = declarationKind,
            classNamePattern = classNamePattern,
            extendsPattern = extendsPattern,
            location = location,
            requiredJvmVisibilities = requiredAccess.mapNotNullTo(mutableSetOf(), JVM_VISIBILITIES::get),
            forbiddenJvmVisibilities = forbiddenAccess.mapNotNullTo(mutableSetOf(), JVM_VISIBILITIES::get),
            requiredJvmModifiers = requiredAccess.mapNotNullTo(mutableSetOf(), JVM_MODIFIERS::get),
            forbiddenJvmModifiers = forbiddenAccess.mapNotNullTo(mutableSetOf(), JVM_MODIFIERS::get),
            requiredAnnotationPattern = annotationPattern,
            keptMembers = inlineMembers?.conditions.orEmpty(),
            keepAllMembers = inlineMembers?.keepAll == true,
        )
    }

    private fun parseInlineMemberBlock(tokens: List<String>, location: SourceLocation): InlineMemberBlock? {
        val blockTokens = when {
            tokens.firstOrNull()?.startsWith('{') == true -> tokens
            tokens.firstOrNull() in INHERITANCE_KEYWORDS -> tokens.drop(2)
            else -> emptyList()
        }
        if (blockTokens.isEmpty() || blockTokens == listOf("{")) return null
        val block = blockTokens.joinToString(" ")
        if (!block.startsWith('{') || !block.endsWith('}')) return null
        val memberSpecifications = block.removePrefix("{").removeSuffix("}")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
        return InlineMemberBlock(
            keepAll = "*" in memberSpecifications,
            conditions = memberSpecifications.filterNot { specification -> specification == "*" }
                .map { specification ->
                    parseMemberCondition("$specification;", location)
                        ?: throw unsupported("unsupported inline member specification", location)
                },
        )
    }

    private fun parseExtendsPattern(tokens: List<String>, location: SourceLocation): String? = when {
        tokens.isEmpty() || tokens.first().startsWith('{') -> null
        tokens.first() in INHERITANCE_KEYWORDS &&
            tokens.getOrNull(1)?.matches(CLASS_PATTERN) == true &&
            (tokens.size == 2 || tokens.getOrNull(2)?.startsWith('{') == true) -> tokens[1]
        else -> throw unsupported("unsupported keep rule", location)
    }

    private fun String.toDeclarationKind(): KeepDeclarationKind? = when (this) {
        "class" -> KeepDeclarationKind.CLASS
        "interface" -> KeepDeclarationKind.INTERFACE
        "enum" -> KeepDeclarationKind.ENUM
        else -> null
    }

    private fun resolveProjectRoot(): Path = try {
        projectRoot.toRealPath()
    } catch (error: IOException) {
        throw KeepRuleScanningException("project root cannot be resolved", error)
    }

    private fun resolveTopLevelRule(ruleFile: Path): Path = try {
        ruleFile.toRealPath().also { realRuleFile ->
            if (!Files.isRegularFile(realRuleFile)) {
                throw KeepRuleScanningException("keep rule path is not a regular file")
            }
        }
    } catch (error: IOException) {
        throw KeepRuleScanningException("keep rule file cannot be resolved", error)
    }

    private fun resolveIncludedRule(
        value: String,
        includingFile: Path,
        scope: RuleScope,
        location: SourceLocation,
    ): Path {
        val includePath = try {
            Path.of(value.removeSurrounding("\""))
        } catch (error: InvalidPathException) {
            throw unsupported("invalid included keep rule path", location)
        }
        val candidate = if (includePath.isAbsolute) includePath.normalize() else {
            requireNotNull(includingFile.parent).resolve(includePath).normalize()
        }
        if (!candidate.startsWith(scope.root)) {
            throw unsupported("included keep rule file is outside the allowed root", location)
        }
        val realIncludedFile = try {
            candidate.toRealPath()
        } catch (error: IOException) {
            throw KeepRuleScanningException(
                "included keep rule file cannot be resolved at ${location.path}:${location.line}",
                error,
            )
        }
        if (!realIncludedFile.startsWith(scope.root)) {
            throw unsupported("included keep rule file is outside the allowed root", location)
        }
        if (!Files.isRegularFile(realIncludedFile)) {
            throw unsupported("included keep rule path is not a regular file", location)
        }
        return realIncludedFile
    }

    private data class RuleScope(val root: Path, val external: Boolean) {
        fun display(ruleFile: Path): String {
            val relativePath = root.relativize(ruleFile).toString().replace('\\', '/')
            return if (external) "external/$relativePath" else relativePath
        }
    }

    private data class MemberVisibility(
        val required: Set<Visibility> = emptySet(),
        val forbidden: Set<Visibility> = emptySet(),
    )

    private data class PlainMember(
        val keepAll: Boolean = false,
        val condition: KeepMemberCondition? = null,
    )

    private data class InlineMemberBlock(
        val keepAll: Boolean,
        val conditions: List<KeepMemberCondition>,
    )

    private fun unsupported(message: String, location: SourceLocation): KeepRuleScanningException =
        KeepRuleScanningException("$message at ${location.path}:${location.line}")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val CLASS_PATTERN = Regex("[A-Za-z_$*?][A-Za-z0-9_$*?.]*")
        val INHERITANCE_KEYWORDS = setOf("extends", "implements")
        val JVM_VISIBILITIES = mapOf(
            "public" to Visibility.PUBLIC,
            "protected" to Visibility.PROTECTED,
            "private" to Visibility.PRIVATE,
        )
        val JVM_MODIFIERS = mapOf(
            "final" to JvmModifier.FINAL,
            "abstract" to JvmModifier.ABSTRACT,
            "synthetic" to JvmModifier.SYNTHETIC,
            "native" to JvmModifier.NATIVE,
            "static" to JvmModifier.STATIC,
        )
        val JVM_ACCESS_FLAGS = JVM_VISIBILITIES.keys + JVM_MODIFIERS.keys
        val SUPPORTED_MODIFIERS = setOf("allowoptimization", "allowobfuscation", "allowshrinking")
        val METHOD_MEMBER = Regex("(?:(!?public|!?protected|!?private)\\s+)?(?:(\\S+)\\s+)?([^\\s(]+)\\(([^)]*)\\)")
        val FIELD_MEMBER = Regex("(?:(!?public|!?protected|!?private)\\s+)?(\\S+)\\s+([^\\s]+)")
        val NON_RETENTION_KEEP_DIRECTIVES = setOf(
            "-keepattributes",
            "-keepnames",
            "-keepclassmembers",
            "-keepclassmembernames",
            "-keepclasseswithmembernames",
            "-keeppackagenames",
        )
        val IGNORED_DIRECTIVES = setOf(
            "-adaptclassstrings",
            "-allowaccessmodification",
            "-assumenosideeffects",
            "-dontnote",
            "-dontobfuscate",
            "-dontoptimize",
            "-dontpreverify",
            "-dontwarn",
            "-flattenpackagehierarchy",
            "-ignorewarnings",
            "-libraryjars",
            "-optimizationpasses",
            "-optimizations",
            "-printconfiguration",
            "-printmapping",
            "-printseeds",
            "-printusage",
            "-repackageclasses",
            "-renamesourcefileattribute",
            "-verbose",
            "-whyareyoukeeping",
        )
    }
}

/** keep 파일을 안전하고 완전하게 읽지 못해 부분 보존 근거를 버릴 때 사용한다. */
public class KeepRuleScanningException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
