package dev.kartograph.index

import dev.kartograph.core.HttpWrapperArgument

/**
 * isthmus http 도메인의 호출 측 조립 규칙(`url-compose`)과 정규 경로 템플릿 문법(`http-template`)이다.
 *
 * 소스 파싱과 분리한 순수 규칙이라 공유 적합성 벡터를 그대로 실행해 검증할 수 있다. 규칙이 벡터와
 * 다르면 벡터가 정본이다(isthmus `docs/HTTP-WRAPPERS.md`). 모든 호출 측 생산자가 같은 규칙을 써야
 * 같은 API를 부르는 iOS·Android 호출이 같은 키로 조인된다.
 */
public object RouteUrlRules {
    /** 계약의 route-call 동사 집합이다. `ANY`는 route-decl 전용이라 없다. */
    public val VERBS: Set<String> = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")

    /** 정규 템플릿과 dynamic 원문의 길이 상한이다. */
    public const val MAX_TEMPLATE_LENGTH: Int = 2_048

    /** 경로 식을 이루는 조각이다. 소스 파서가 리터럴·보간·증명된 query 꼬리로 나눠 넘긴다. */
    public sealed interface UrlPart {
        /** 디코드된 문자열 리터럴 조각이다. */
        public data class Literal(val text: String) : UrlPart

        /** 값을 모르는 보간·연결 조각이다. [expression]은 표시용 원문이다. */
        public data class Value(val expression: String) : UrlPart

        /** 초기식이 `?`로 시작하거나 빈 값임을 증명한 지역 변수 보간이다(`compose.suffix`). */
        public data class QueryTail(val expression: String) : UrlPart
    }

    /**
     * base와 경로를 잇는 방식이다(`compose.base-join`).
     *
     * [Declared]는 `http-wrappers` 선언의 `pathAnchor`를 따른다. [PathOnly]는 조각 전체가 경로라고
     * 가정하고 앞 보간을 base 식으로 해석하지 않는다(보간 규칙만 적용할 때).
     */
    public sealed interface JoinMode {
        /**
         * 선언된 래퍼 경로다. 앞의 미해석 보간은 단순 연결 base로 본다.
         *
         * @property resolveDotSegments 참이면 RFC 3986 §5.2.4처럼 `.`·`..` 세그먼트를 지운다. 생산자가 앵커를 직접 정하는
         *   RFC 3986 해석 클라이언트(Retrofit)가 쓴다 — OkHttp `HttpUrl`이 상대·절대 경로 모두 점 세그먼트를 지우기 때문이다
         */
        public data class Declared(val anchor: String, val resolveDotSegments: Boolean = false) : JoinMode

        /** 조각 전체가 경로다. 앞의 미해석 보간은 dynamic이다. */
        public data object PathOnly : JoinMode

        /**
         * RFC 3986 상대 해석(Retrofit·Ktor)이다. `/x`는 root, `x`는 base다. 점 세그먼트는 지우지 않는다 — 전송 전에 지우는지는
         * 클라이언트·엔진마다 다르므로 확인한 생산자만 [Declared.resolveDotSegments]로 켠다.
         */
        public data object Rfc3986 : JoinMode

        /** 슬래시 결합(axios 등)이다. 항상 base다. */
        public data object SlashJoin : JoinMode

        /** 단순 문자열 연결이다. [base]가 리터럴이면 연결 결과를 쓰고, null이면 미상 base다. */
        public data class Concat(val base: String?) : JoinMode
    }

    /**
     * 조립 결과다.
     *
     * @property template dynamic이 아니면 정규 경로 템플릿이다
     * @property dynamic 템플릿을 증명하지 못했다는 표시다
     * @property channelPrefix dynamic일 때 증명된 리터럴 접두사 템플릿이다(마스킹 적용)
     * @property pathAnchor `root` 또는 `base`. dynamic이면 조립 중 확정된 값이거나 null이다
     * @property authority 전체 URL 리터럴에서 뗀 소문자 `host[:port]`다
     * @property queryTailStripped query·fragment 꼬리를 떼어 냈다는 증거다
     * @property maskedSegments `{}`로 가린 리터럴 세그먼트 수다
     * @property limitation 이 호출이 만든 호출 측 limitation 접두사다(`ambiguous-base-join:`)
     */
    public data class ComposedRoute(
        val template: String?,
        val dynamic: Boolean,
        val channelPrefix: String? = null,
        val pathAnchor: String? = null,
        val authority: String? = null,
        val queryTailStripped: Boolean = false,
        val maskedSegments: Int = 0,
        val limitation: String? = null,
    )

    /**
     * 경로 조각을 정규 템플릿으로 조립한다.
     *
     * 순서는 base 결합 → query·fragment 꼬리 → suffix 꼬리 → 세그먼트 보간 → 정규화 → 마스킹이다.
     * 증명하지 못한 단계는 결과를 dynamic으로 내리고 그 앞까지의 템플릿을 channelPrefix로 남긴다.
     */
    public fun compose(parts: List<UrlPart>, mode: JoinMode): ComposedRoute {
        val merged = mergeLiterals(parts)
        if (merged.isEmpty()) return ComposedRoute(template = null, dynamic = true)
        val located = locate(merged, mode) ?: return ComposedRoute(
            template = null, dynamic = true, limitation = ambiguousJoinLimitation(merged, mode),
        )
        val dotSegments = (mode as? JoinMode.Declared)?.resolveDotSegments == true
        return assemble(located, dotSegments)
    }

    /** base 결합으로 앵커·authority와 `/`로 시작하는 경로 조각을 정한 중간 결과다. */
    private data class Located(val parts: List<UrlPart>, val anchor: String, val authority: String?)

    /** 연속한 리터럴을 합쳐 경계 판정을 단순하게 만든다. */
    private fun mergeLiterals(parts: List<UrlPart>): List<UrlPart> {
        val result = mutableListOf<UrlPart>()
        parts.forEach { part ->
            val last = result.lastOrNull()
            if (part is UrlPart.Literal && last is UrlPart.Literal) {
                result[result.lastIndex] = UrlPart.Literal(last.text + part.text)
            } else if (!(part is UrlPart.Literal && part.text.isEmpty())) {
                result += part
            }
        }
        return result
    }

    /**
     * base 결합을 적용한다. null은 증명할 수 없는 결합(dynamic)이다.
     */
    private fun locate(parts: List<UrlPart>, mode: JoinMode): Located? {
        val first = parts.first()
        if (mode is JoinMode.Concat && mode.base != null) {
            return locate(mergeLiterals(listOf(UrlPart.Literal(mode.base)) + parts), JoinMode.Concat(null))
        }
        if (first is UrlPart.Literal && SCHEME.containsMatchIn(first.text)) return locateAbsolute(parts)
        // `//host/path`는 network-path 참조다 — `//` 뒤는 경로 세그먼트가 아니라 authority(userinfo 포함)다.
        if (first is UrlPart.Literal && first.text.startsWith("//")) return locateAbsolute(parts)
        // `"${'$'}scheme//user@host/x"`처럼 앞 보간이 scheme이고 뒤가 `//`면 같은 authority 규칙을 쓴다.
        val second = parts.getOrNull(1)
        if (first !is UrlPart.Literal && second is UrlPart.Literal && second.text.startsWith("//")) return locateAbsolute(parts.drop(1))
        if (first !is UrlPart.Literal) return locateAfterBase(parts.drop(1), mode)
        val rooted = first.text.startsWith('/')
        val anchor = when (mode) {
            is JoinMode.Declared -> mode.anchor
            JoinMode.PathOnly -> "root"
            JoinMode.Rfc3986 -> if (rooted) "root" else "base"
            JoinMode.SlashJoin -> "base"
            is JoinMode.Concat -> if (rooted) "base" else return null
        }
        return Located(rootedParts(parts), anchor, null)
    }

    /** 앞의 미해석 base 식 뒤를 단순 연결로 본다 — `/`로 시작하면 base, 아니면 증명 불가다. */
    private fun locateAfterBase(rest: List<UrlPart>, mode: JoinMode): Located? {
        if (mode == JoinMode.PathOnly) return null
        val next = rest.firstOrNull() as? UrlPart.Literal ?: return null
        if (mode == JoinMode.SlashJoin) return Located(rootedParts(rest), "base", null)
        return if (next.text.startsWith('/')) Located(rest, "base", null) else null
    }

    /** 첫 조각이 `/`로 시작하지 않으면 `/`를 붙여 템플릿 문법의 루트를 맞춘다. */
    private fun rootedParts(parts: List<UrlPart>): List<UrlPart> {
        val first = parts.first() as UrlPart.Literal
        if (first.text.startsWith('/')) return parts
        return listOf(UrlPart.Literal("/" + first.text)) + parts.drop(1)
    }

    /**
     * 전체 URL 리터럴(`scheme://…`)이나 network-path 참조(`//…`)에서 scheme·userinfo를 떼고 host를
     * authority로 옮긴다(`compose.strip`). host가 보간으로 끊기면 host가 동적이라 base 앵커다.
     */
    private fun locateAbsolute(parts: List<UrlPart>): Located {
        val literal = (parts.first() as UrlPart.Literal).text
        val afterScheme = if (literal.startsWith("//")) literal.substring(2) else literal.substring(literal.indexOf("://") + 3)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (authorityEnd < 0 && parts.size > 1) return locateAfterDynamicHost(parts.drop(1))
        val rawAuthority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
        val remainder = if (authorityEnd < 0) "" else afterScheme.substring(authorityEnd)
        val pathText = if (remainder.startsWith('/')) remainder else "/$remainder"
        val authority = rawAuthority.substringAfterLast('@').lowercase().takeIf(AUTHORITY::matches)
        return Located(mergeLiterals(listOf(UrlPart.Literal(pathText)) + parts.drop(1)), "root", authority)
    }

    /**
     * host에 보간이 섞였다 — host를 모르므로 base 앵커다. host 뒤 첫 `/`·`?`·`#`부터가 경로이며,
     * 끝까지 없으면 루트 경로다.
     */
    private fun locateAfterDynamicHost(rest: List<UrlPart>): Located {
        rest.forEachIndexed { index, part ->
            val start = (part as? UrlPart.Literal)?.text?.indexOfFirst { it == '/' || it == '?' || it == '#' } ?: -1
            if (start >= 0) {
                val head = (part as UrlPart.Literal).text.substring(start)
                val path = if (head.startsWith('/')) head else "/$head"
                return Located(mergeLiterals(listOf(UrlPart.Literal(path)) + rest.drop(index + 1)), "base", null)
            }
        }
        return Located(listOf(UrlPart.Literal("/")), "base", null)
    }

    /** 단순 연결의 미상 base 뒤 상대 경로만 `ambiguous-base-join:`으로 센다. */
    private fun ambiguousJoinLimitation(parts: List<UrlPart>, mode: JoinMode): String? {
        val first = parts.first()
        val relativeLiteral = first is UrlPart.Literal && mode is JoinMode.Concat
        val relativeAfterBase = first !is UrlPart.Literal && mode != JoinMode.PathOnly &&
            (parts.getOrNull(1) as? UrlPart.Literal)?.text?.startsWith('/') == false
        return if (relativeLiteral || relativeAfterBase) AMBIGUOUS_BASE_JOIN else null
    }

    /**
     * query 꼬리·suffix·보간 규칙을 적용하고 정규화·마스킹한다.
     *
     * @param dotSegments 참이면 마스킹 전에 점 세그먼트를 지운다. `..`가 미상 base 위로 올라가면 dynamic이다
     */
    private fun assemble(located: Located, dotSegments: Boolean = false): ComposedRoute {
        val (parts, stripped) = stripQueryTail(located.parts)
        val template = StringBuilder()
        parts.forEachIndexed { index, part ->
            val offending = when (part) {
                is UrlPart.Literal -> { template.append(normalizePath(part.text)); false }
                // 마지막 query 꼬리는 stripQueryTail이 이미 뗐다 — 남은 것은 중간 꼬리다.
                is UrlPart.QueryTail -> true
                is UrlPart.Value -> !fillsWholeSegment(parts, index)
            }
            if (offending) return dynamicWithPrefix(template.toString(), located, dotSegments)
            if (part is UrlPart.Value) template.append("{}")
        }
        val resolved = if (dotSegments) {
            removeDotSegments(template.toString(), located.anchor)
                ?: return ComposedRoute(template = null, dynamic = true, pathAnchor = located.anchor, authority = located.authority)
        } else template.toString()
        val (masked, count) = mask(resolved, located.authority)
        if (validateTemplate(masked) != null) return dynamicWithPrefix("", located)
        return ComposedRoute(
            template = masked,
            dynamic = false,
            pathAnchor = located.anchor,
            authority = located.authority,
            queryTailStripped = stripped,
            maskedSegments = count,
        )
    }

    /**
     * 리터럴의 첫 `?`·`#`부터 끝까지와 그 뒤 조각을 떼고, 마지막 조각이 증명된 query 꼬리면 뗀다.
     */
    private fun stripQueryTail(parts: List<UrlPart>): Pair<List<UrlPart>, Boolean> {
        val kept = mutableListOf<UrlPart>()
        for (part in parts) {
            if (part is UrlPart.Literal) {
                val cut = part.text.indexOfFirst { it == '?' || it == '#' }
                if (cut >= 0) {
                    part.text.substring(0, cut).takeIf { it.isNotEmpty() }?.let { kept += UrlPart.Literal(it) }
                    return kept to true
                }
            }
            kept += part
        }
        if (kept.lastOrNull() is UrlPart.QueryTail) return kept.dropLast(1) to true
        return kept to false
    }

    /** 보간이 세그먼트 전체를 채우는지 본다 — 앞이 `/`로 끝나고 뒤가 없거나 `/`로 시작해야 한다. */
    private fun fillsWholeSegment(parts: List<UrlPart>, index: Int): Boolean {
        val before = parts.getOrNull(index - 1) as? UrlPart.Literal ?: return false
        val after = parts.getOrNull(index + 1)
        return before.text.endsWith('/') && (after == null || (after is UrlPart.Literal && after.text.startsWith('/')))
    }

    /**
     * dynamic 결과에 `/`로 시작하는 증명된 접두사를 마스킹해 싣는다. 점 세그먼트를 지우는 결합이면 완결된 세그먼트만
     * 지우고, 뒤 보간과 이어지는 마지막 조각이 점뿐이면(`/a/..${'$'}{x}`) 접두사를 증명하지 못한 것으로 본다.
     */
    private fun dynamicWithPrefix(prefix: String, located: Located, dotSegments: Boolean = false): ComposedRoute {
        val resolved = if (dotSegments && prefix.startsWith('/')) resolvePrefixDots(prefix, located.anchor) else prefix
        val masked = resolved?.takeIf { it.startsWith('/') }?.let { mask(it, located.authority).first }
            ?.takeIf { validateTemplate(it) == null }
        return ComposedRoute(
            template = null, dynamic = true, channelPrefix = masked, pathAnchor = located.anchor,
            authority = located.authority,
        )
    }

    /**
     * 경로 템플릿의 점 세그먼트를 RFC 3986 §5.2.4대로 지운다. OkHttp `HttpUrl`의 경로 해석(`push`·`pop`)과 같은 결과다 —
     * 끝의 `.`·`..`는 끝 슬래시를 남기고, root 위의 `..`는 root에 머문다.
     *
     * @param template `/`로 시작하는 템플릿이다. `{}` 세그먼트는 점 세그먼트가 아니다(Retrofit은 `.`·`..` 값을 거부한다)
     * @param anchor `base`면 템플릿이 미상 base 경로 뒤에 붙는다
     * @return 지운 템플릿. `base`에서 `..`가 템플릿 앞(미상 base 경로)으로 올라가면 결과 경로를 알 수 없어 null이다
     */
    public fun removeDotSegments(template: String, anchor: String): String? {
        val segments = template.removePrefix("/").split('/')
        val output = ArrayList<String>()
        segments.forEachIndexed { index, segment ->
            val last = index == segments.lastIndex
            when (segment) {
                "." -> if (last) output += ""
                ".." -> {
                    if (output.isNotEmpty()) output.removeAt(output.lastIndex) else if (anchor == "base") return null
                    if (last) output += ""
                }
                else -> output += segment
            }
        }
        return "/" + output.joinToString("/")
    }

    /** dynamic 접두사의 완결된 세그먼트만 점을 지운다. 뒤 보간과 이어지는 마지막 조각이 점뿐이면 null이다. */
    private fun resolvePrefixDots(prefix: String, anchor: String): String? {
        val tail = prefix.substringAfterLast('/')
        if (tail.isNotEmpty() && tail.all { it == '.' }) return null
        val complete = removeDotSegments(prefix.substring(0, prefix.length - tail.length), anchor) ?: return null
        return complete + tail
    }

    /**
     * 리터럴 경로를 정규 표기로 바꾼다(`template.normalize`, `compose.normalize`).
     *
     * 퍼센트 인코딩은 대문자 hex로 쓰고 unreserved 문자는 디코드한다. 비ASCII·금지 문자·리터럴
     * 중괄호는 UTF-8 퍼센트 인코딩한다. 형식이 깨진 `%`는 `%25`로 인코딩한다. 중복·끝 슬래시와
     * 대소문자는 보존한다.
     */
    public fun normalizePath(path: String): String = buildString {
        var index = 0
        while (index < path.length) {
            val character = path[index]
            val hex = path.substring(index + 1, (index + 3).coerceAtMost(path.length))
            if (character == '%' && hex.length == 2 && hex.all(::isHexDigit)) {
                val decoded = hex.toInt(16).toChar()
                if (decoded in UNRESERVED_SET) append(decoded) else append('%').append(hex.uppercase())
                index += 3
                continue
            }
            if (character == '/' || (character.code < 128 && character in PCHAR_SET)) {
                append(character)
            } else {
                val end = if (Character.isHighSurrogate(character) && index + 1 < path.length) index + 2 else index + 1
                path.substring(index, end).toByteArray(Charsets.UTF_8).forEach { byte ->
                    append('%').append(HEX_DIGITS[(byte.toInt() shr 4) and 0xF]).append(HEX_DIGITS[byte.toInt() and 0xF])
                }
                index = end
                continue
            }
            index++
        }
    }

    /**
     * 정규 템플릿의 고엔트로피·웹훅 세그먼트를 `{}`로 가린다(`compose.mask`).
     *
     * 퍼센트 디코드한 리터럴 세그먼트가 16자 이상이고 ASCII 글자와 숫자를 모두 담으면 가린다.
     * 알려진 웹훅 host는 경로 세그먼트를 모두(`hooks.slack.com`) 또는 `/api/webhooks` 뒤를
     * (`discord.com`·`discordapp.com`) 가린다. 빈 세그먼트와 이미 `{}`인 세그먼트는 세지 않는다.
     *
     * @return 가린 템플릿과 가린 세그먼트 수
     */
    public fun mask(template: String, authority: String?): Pair<String, Int> {
        val segments = template.split('/')
        val host = authority?.substringBefore(':')
        val discordWebhook = host in DISCORD_HOSTS && segments.getOrNull(1) == "api" && segments.getOrNull(2) == "webhooks"
        var count = 0
        val masked = segments.mapIndexed { index, segment ->
            val forced = host == SLACK_HOST || (discordWebhook && index >= 3)
            val shouldMask = index > 0 && segment.isNotEmpty() && segment != "{}" &&
                (forced || isHighEntropy(percentDecode(segment)))
            if (shouldMask) { count++; "{}" } else segment
        }
        return masked.joinToString("/") to count
    }

    /**
     * 정규 경로 템플릿 문법을 검사한다(`template.grammar`).
     *
     * @return 적합하면 null, 아니면 계약의 거부 사유 코드(`not-rooted`·`too-long`·`invalid-character`·
     *   `malformed-percent`·`lowercase-percent-hex`·`encoded-unreserved`·`stray-brace`·
     *   `multiple-parameters`·`catch-all-partial`·`catch-all-not-last`)
     */
    public fun validateTemplate(template: String): String? {
        if (!template.startsWith('/')) return "not-rooted"
        if (template.length > MAX_TEMPLATE_LENGTH) return "too-long"
        val segments = template.substring(1).split('/')
        segments.forEachIndexed { index, segment ->
            if (segment == "{**}") {
                if (index != segments.lastIndex) return "catch-all-not-last"
                return@forEachIndexed
            }
            if ("{**}" in segment) return "catch-all-partial"
            validateSegment(segment)?.let { return it }
        }
        return null
    }

    /** 세그먼트 하나의 pchar·퍼센트·중괄호 규칙을 검사한다. */
    private fun validateSegment(segment: String): String? {
        var parameters = 0
        var index = 0
        while (index < segment.length) {
            val character = segment[index]
            when {
                character == '%' -> {
                    val hex = segment.substring(index + 1, (index + 3).coerceAtMost(segment.length))
                    if (hex.length < 2 || !hex.all(::isHexDigit)) return "malformed-percent"
                    if (hex != hex.uppercase()) return "lowercase-percent-hex"
                    if (hex.toInt(16).toChar() in UNRESERVED_SET) return "encoded-unreserved"
                    index += 3
                    continue
                }
                character == '{' -> {
                    if (segment.getOrNull(index + 1) != '}') return "stray-brace"
                    parameters++
                    if (parameters > 1) return "multiple-parameters"
                    index += 2
                    continue
                }
                character == '}' -> return "stray-brace"
                character.code >= 128 || character !in PCHAR_SET -> return "invalid-character"
            }
            index++
        }
        return null
    }

    /**
     * 래퍼 호출의 인자 하나를 찾는다(`wrapper.method`의 인자 찾기).
     *
     * `label`이 있으면 같은 이름의 인자를 먼저 찾는다. 없으면 `index` 위치의 인자를 쓰되 그 인자가
     * 이름을 달고 있으면 쓰지 않는다 — 다른 매개변수에 붙은 이름 인자를 위치로 오인하지 않기 위해서다.
     */
    public fun findArgument(spec: HttpWrapperArgument?, arguments: List<CallArgument>): CallArgument? =
        findArgumentIndex(spec, arguments)?.let(arguments::get)

    /** [findArgument]와 같은 규칙으로 찾은 인자의 위치다. 같은 값의 인자가 여럿이어도 위치를 헷갈리지 않는다. */
    public fun findArgumentIndex(spec: HttpWrapperArgument?, arguments: List<CallArgument>): Int? {
        if (spec == null) return null
        spec.label?.let { label -> arguments.indexOfFirst { it.label == label }.takeIf { it >= 0 }?.let { return it } }
        return spec.index?.takeIf { it < arguments.size && arguments[it].label == null }
    }

    /**
     * 래퍼 호출의 동사를 정한다(`wrapper.method`).
     *
     * @return 확정한 동사, 증명하지 못하면 null(`methodDynamic: true`)
     */
    public fun bindMethod(
        spec: HttpWrapperArgument?,
        defaultMethod: String?,
        methodEnum: Map<String, String>,
        arguments: List<CallArgument>,
    ): String? {
        val argument = findArgument(spec, arguments) ?: return defaultMethod
        return when (val value = argument.value) {
            // 문자열 리터럴은 계약 동사와 정확히 같을 때만 동사다 — "get"은 동사가 아니다.
            is ArgumentValue.Literal -> value.text.takeIf { it in VERBS }
            is ArgumentValue.EnumCase -> methodEnum[value.name]
            ArgumentValue.Opaque -> null
        }
    }

    /** 호출 인자 하나다. [label]은 Kotlin 이름 붙은 인자의 이름이다. */
    public data class CallArgument(val label: String?, val value: ArgumentValue)

    /** 동사 인자로 쓰일 수 있는 값의 모양이다. */
    public sealed interface ArgumentValue {
        /** 디코드된 문자열 리터럴(또는 같은 파일 상수의 값)이다. */
        public data class Literal(val text: String) : ArgumentValue

        /** enum case·상수 이름이다(`HttpMethod.GET`의 `GET`). */
        public data class EnumCase(val name: String) : ArgumentValue

        /** 값을 증명할 수 없는 식이다. */
        public data object Opaque : ArgumentValue
    }

    private fun isHighEntropy(segment: String): Boolean =
        segment.length >= 16 && segment.any { it in 'a'..'z' || it in 'A'..'Z' } && segment.any { it in '0'..'9' }

    /** 마스킹 판정용 퍼센트 디코드다 — 바이트 단위로 풀고 깨진 시퀀스는 그대로 둔다. */
    private fun percentDecode(segment: String): String {
        if ('%' !in segment) return segment
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < segment.length) {
            val hex = segment.substring(index + 1, (index + 3).coerceAtMost(segment.length))
            if (segment[index] == '%' && hex.length == 2 && hex.all(::isHexDigit)) {
                bytes.write(hex.toInt(16)); index += 3
            } else {
                bytes.write(segment[index].toString().toByteArray(Charsets.UTF_8)); index++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun isHexDigit(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    private const val AMBIGUOUS_BASE_JOIN = "ambiguous-base-join:"
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val SLACK_HOST = "hooks.slack.com"
    private val DISCORD_HOSTS = setOf("discord.com", "discordapp.com")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    // isthmus 소비자의 authority 문법과 같다 — 맞지 않는 host는 싣지 않는다(입력 오류 방지).
    private val AUTHORITY = Regex(
        "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*|\\[[0-9a-f:.]+])(?::[0-9]{1,5})?$",
    )
    private val UNRESERVED_SET: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()
    private val PCHAR_SET: Set<Char> = UNRESERVED_SET + "!$&'()*+,;=:@".toSet()
}
