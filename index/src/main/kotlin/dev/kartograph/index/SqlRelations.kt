package dev.kartograph.index

/**
 * SQL 어휘 하나다 — 인용된 식별자는 키워드가 아니다.
 * [offset]은 원문 위치로, .sq 같은 문서 전체를 스캔할 때 사실 위치로 쓴다.
 */
internal data class SqlToken(val text: String, val quoted: Boolean, val offset: Int)

/** 관계 이름 하나와 그것을 연 키워드 토큰 위치다. */
internal data class SqlRelation(val name: String, val keyword: Int)

/**
 * SQL 텍스트를 어휘로 나눈다 — 인용 식별자는 내용을 보존하고
 * 그 외엔 식별자 문자열과 단일 기호 토큰만 만든다.
 * `;`는 문장 경계로, `{}`·`$`·`?`·`:`·`@` 같은 플레이스홀더 기호는 미해석
 * 피연산자 계수를 위해 토큰으로 남긴다.
 */
internal fun lexSql(text: String): List<SqlToken> {
    val tokens = mutableListOf<SqlToken>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' || c == '`' || c == '[' -> {
                val end = if (c == '[') ']' else c
                var j = i + 1
                while (j < text.length && text[j] != end) j++
                tokens += SqlToken(text.substring(i + 1, j), quoted = true, offset = i)
                i = j + 1
            }
            isIdentStart(c) -> {
                var j = i + 1
                while (j < text.length && isIdentPart(text[j])) j++
                tokens += SqlToken(text.substring(i, j), quoted = false, offset = i)
                i = j
            }
            c == '-' && text.getOrNull(i + 1) == '-' -> {
                while (i < text.length && text[i] != '\n') i++
            }
            c == '/' && text.getOrNull(i + 1) == '*' -> {
                i += 2
                while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                i += 2
            }
            c == '\'' -> {
                // 문자열 리터럴은 이름이 아니다 — '' 와 \' 는 escape다.
                i += 1
                while (i < text.length) {
                    when {
                        text[i] == '\\' -> { i += 2; continue }
                        text[i] == '\'' && text.getOrNull(i + 1) == '\'' -> { i += 2; continue }
                        text[i] == '\'' -> { i += 1; break }
                        else -> i++
                    }
                }
            }
            else -> {
                if (c == '.' || c == ',' || c == '(' || c == ')' || c == ';' ||
                    c == '{' || c == '}' || c == '$' || c == '?' || c == ':' || c == '@'
                ) {
                    tokens += SqlToken(c.toString(), quoted = false, offset = i)
                }
                i++
            }
        }
    }
    return tokens
}

/**
 * SQL 문을 여는 강한 동사 표다 — 관계 키워드와 겹치는 update·truncate는
 * 뺀다(문장 머리 규칙이 따로 있다). WITH는 SELECT를 동반하므로 없다.
 */
internal fun looksLikeSql(text: String): Boolean {
    var head = true
    for (t in lexSql(text)) {
        if (!isNameToken(t)) continue
        if (isSqlVerb(t.text)) return true
        if (head) {
            head = false
            if (t.text.equals("update", true) || t.text.equals("truncate", true)) return true
        }
    }
    return false
}

/**
 * SQL 텍스트에서 관계 이름을 읽는다.
 * 한정 이름(`schema.table`)은 그대로 두고, 이름 자체에 점이 있는 인용
 * 식별자("a.b")는 한 세그먼트로 읽는다 — escape는 기록 시에 한다.
 * [SqlRelation.keyword]는 관계를 연 키워드 토큰의 원문 위치다.
 * 두 번째 반환은 관계 자리의 피연산자를 읽지 못했음을 뜻한다 —
 * `FROM {}` 같은 플레이스홀더를 사실 없이 조용히 넘기지 않기 위해서다.
 */
internal fun sqlRelations(text: String): Pair<List<SqlRelation>, Boolean> {
    val tokens = lexSql(text)
    val out = mutableListOf<SqlRelation>()
    // 같은 키워드의 피연산자 목록 안에서만 중복을 막는다(`FROM a, a`).
    // 다른 위치의 같은 이름은 별개의 사용 근거다 — `SELECT .. FROM t`와
    // `INSERT INTO t`는 각각 사실이어야 한다.
    val seen = mutableSetOf<String>()
    val consumed = BooleanArray(tokens.size) // 이름·별칭·수식어로 소비된 토큰
    var unresolved = false
    // `;`로 갈리는 각 문장의 머리 식별자 위치와 그 문장의 동사다 —
    // update·truncate는 문장 머리에서만 관계 키워드로 열고, `on`은
    // grant·revoke 문 안에서만 연다. 다중 문장 리터럴의 뒤 문장도
    // 같은 규칙을 받는다.
    val stmtHead = BooleanArray(tokens.size)
    val stmtVerb = arrayOfNulls<String>(tokens.size)
    run {
        var pending = true
        var verb: String? = null
        tokens.forEachIndexed { i, t ->
            if (!t.quoted && t.text == ";") {
                pending = true
                verb = null
                return@forEachIndexed
            }
            if (isNameToken(t) && pending) {
                stmtHead[i] = true
                verb = t.text.lowercase()
                pending = false
            }
            stmtVerb[i] = verb
        }
    }
    for (i in tokens.indices) {
        val tok = tokens[i]
        if (consumed[i] || tok.quoted || !isRelationKeyword(tok.text)) continue
        val word = tok.text.lowercase()
        val grantStmt = stmtVerb[i] == "grant" || stmtVerb[i] == "revoke"
        // 같은 문장(`;`로 갈리는 세그먼트) 안만 본다 — 뒤 세그먼트의
        // 단어를 앞 문장의 근거로 쓰지 않는다.
        fun segmentBefore(end: Int): Sequence<SqlToken> =
            tokens.subList(0, end).asReversed().asSequence().takeWhile { it.text != ";" }
        fun segmentAfterHas(start: Int, w: String): Boolean =
            tokens.subList(start, tokens.size).asSequence().takeWhile { it.text != ";" }
                .any { !it.quoted && it.text.equals(w, true) }
        val fires = when (word) {
            // 산문 속 "update the .."·upsert의 `DO UPDATE SET`을 막기 위해
            // update는 문장 머리이고 같은 문장에 SET이 있을 때만 연다.
            "update" -> stmtHead[i] && segmentAfterHas(i + 1, "set")
            // truncate는 항상 문장 머리 동사다 — 산문 중간의 "truncate"는 무시.
            "truncate" -> stmtHead[i]
            // into는 같은 문장에 INSERT·SELECT·MERGE·REPLACE가 앞선 문맥에서만
            // 연다 — "merged the branch into main" 같은 산문을 막는다.
            // 단, 문장이 "merge"로 시작하는 산문은 SQL `MERGE INTO`와 어휘가
            // 같아 구분 못 한다 — 남은 오탐 여지로 둔다.
            "into" -> segmentBefore(i).any {
                !it.quoted && it.text.lowercase() in setOf("insert", "select", "merge", "replace")
            }
            // table은 직전 식별자가 DDL 동사일 때만 키워드다 — 산문의
            // "the table"이나 다른 절의 단어는 읽지 않는다.
            "table" -> tableKeywordContext(tokens, i)
            // on은 `GRANT .. ON t`·`REVOKE .. ON t`의 관계 자리다 — 권한
            // 단어(SELECT 등)가 앞서야 "grant access on .." 같은 산문을
            // 막는다. `CREATE INDEX/TRIGGER .. ON t`의 on도 관계 자리다.
            "on" -> {
                val grantOn = grantStmt && segmentBefore(i).any { !it.quoted && isGrantPriv(it.text) }
                val createOn = stmtVerb[i] == "create" && segmentBefore(i).any {
                    // `rule`은 제외 — CREATE RULE의 ON은 이벤트
                    // 자리(`ON INSERT TO t`)라 관계가 아니다.
                    !it.quoted && it.text.lowercase() in setOf("index", "trigger", "policy")
                }
                grantOn || createOn
            }
            // grant·revoke의 FROM은 권한 주체 자리다 — 관계가 아니므로
            // from·join을 그 문장에서는 열지 않는다.
            "from", "join" -> !grantStmt
            else -> true
        }
        if (!fires) continue
        var j = i + 1
        // ONLY·IF NOT EXISTS 같은 수식어는 건너뛴다. `table`은 TRUNCATE 뒤의
        // 수식어일 때만 건너뛴다 — UPDATE table 같은 문에서 table이 진짜
        // 관계 이름일 수 있고, 억지로 건너뛰면 SET 같은 다음 단어가
        // 관계명으로 읽힌다.
        val headIsTruncate = word == "truncate"
        while (j < tokens.size && !tokens[j].quoted && isNameModifier(tokens[j].text, headIsTruncate)) {
            consumed[j] = true
            j++
        }
        if (word == "on" && grantStmt) {
            // GRANT/REVOKE ON은 객체 종류어가 낄 수 있다 — `ON TABLE t`의
            // table은 수식어고, `ON SEQUENCE`/`ON FUNCTION`/`ON ALL TABLES`
            // 같은 비테이블 객체는 관계가 아니라 조용히 삼킨다.
            val kind = tokens.getOrNull(j)?.takeUnless { it.quoted }?.text?.lowercase()
            when (kind) {
                "table", "tables", "view", "materialized" -> {
                    // 종류어 뒤의 이름이 관계다 — `ON FOREIGN TABLE`의
                    // foreign는 비테이블 목록으로 보낸다(서버·래퍼가 더 흔함).
                    var k = j
                    while (k < tokens.size &&
                        tokens[k].text.lowercase() in setOf("table", "tables", "view", "materialized")
                    ) {
                        consumed[k] = true
                        k++
                    }
                    j = k
                }
                "all", "sequence", "schema", "database", "domain", "type", "function",
                "procedure", "routine", "foreign", "server", "wrapper", "language",
                "large", "publication", "subscription", "statistics", "tablespace",
                "collation", "conversion", "extension", "aggregate", "operator",
                "policy", "cast", "fdw", "parser", "template", "dictionary",
                "configuration",
                -> {
                    // 비테이블 권한 객체 — 이름·한정자·인자 괄호까지 삼키고
                    // 사실은 내지 않는다(미해석도 아닌 정상 문법이다).
                    var k = j
                    while (k < tokens.size) {
                        val t = tokens[k]
                        if (!t.quoted && t.text == "(") {
                            val next = skipParens(tokens, k)
                            if (next == null) {
                                unresolved = true // 닫히지 않은 괄호.
                                break
                            }
                            k = next
                        } else if (isNameToken(t) || (!t.quoted && t.text == ".")) {
                            consumed[k] = true
                            k++
                        } else {
                            // 플레이스홀더 피연산자(`ON SEQUENCE {s}`)는
                            // 읽히지 않은 근거다 — 미해석으로 센다.
                            if (!t.quoted && t.text in setOf("{", "}", "$", "?", ":", "@")) {
                                unresolved = true
                            }
                            break
                        }
                    }
                    continue
                }
            }
        }
        if (j >= tokens.size) {
            unresolved = true // 이름이 없는 키워드 — "SELECT ... FROM" 꼴.
            continue
        }
        // GRANT/REVOKE의 ON은 형태 검증을 거친다 — name (, name)* 뒤에
        // TO·FROM·`;`·끝이 와야 한다. "grant select on the report"
        // 같은 산문은 이름이 쉼표 없이 이어져 형태가 성립하지 않으므로
        // 이름을 버퍼에 모았다가 형태가 맞을 때만 방출한다.
        val bufferedGrant = word == "on" && grantStmt
        val buf = mutableListOf<String>()
        var endPos = j
        // 쉼표로 이어지는 목록(`FROM a, b`)을 읽는다 — 괄호 피연산자는
        // 통째로 건너뛰고(안쪽 관계는 그 안의 키워드가 읽는다) 별칭은 삼킨다.
        while (j < tokens.size) {
            // 괄호 안의 토큰은 소비 표시를 하지 않는다 — 서브쿼리 안의
            // FROM 같은 키워드가 바깥 스캔에서 읽혀야 한다.
            val operandEnd = if (!tokens[j].quoted && tokens[j].text == "(") {
                val next = skipParens(tokens, j)
                if (next == null) {
                    unresolved = true // 닫히지 않은 괄호.
                    break
                }
                next
            } else {
                val read = readQualifiedName(tokens, j)
                if (read == null) {
                    // 이름 자리에 절 키워드가 오는 것(`DO UPDATE SET`,
                    // `ON TABLES TO`)은 정상 종료다 — 플레이스홀더 등
                    // 읽히지 않는 피연산자만 미해석으로 센다.
                    val clauseNext = tokens.getOrNull(j)
                        ?.let { isNameToken(it) && isClauseWord(it.text) } == true
                    if (!clauseNext) unresolved = true
                    break
                }
                val (name, next) = read
                if (bufferedGrant) buf += name
                else if (seen.add("${tok.offset} $name")) out += SqlRelation(name, tok.offset)
                for (c in j until next) consumed[c] = true
                next
            }
            // `AS alias` 또는 쉼표 직전 별칭(`FROM users u, ..`)을 건너뛴다.
            var k = operandEnd
            if (tokens.getOrNull(k)?.let { !it.quoted && it.text.equals("as", true) } == true &&
                tokens.getOrNull(k + 1)?.let(::isNameToken) == true
            ) {
                k += 2
            } else if (tokens.getOrNull(k)?.let(::isNameToken) == true &&
                tokens.getOrNull(k + 1)?.let { !it.quoted && it.text == "," } == true
            ) {
                k += 1
            }
            for (c in operandEnd until k) consumed[c] = true
            endPos = k
            if (tokens.getOrNull(k)?.let { !it.quoted && it.text == "," } == true) {
                j = k + 1
                continue
            }
            break
        }
        if (bufferedGrant) {
            // 피연산자 뒤가 GRANT 종결자가 아니면 산문이다 — 버퍼를 버린다.
            // `WITH GRANT OPTION`은 피연산자가 아니라 피부여자 뒤에 오고,
            // `)`는 GRANT가 중첩되지 않아 종결자가 아니다 — 둘 다 산문만 허용한다.
            val termOk = when (val t = tokens.getOrNull(endPos)) {
                null -> true
                else -> !t.quoted && t.text.lowercase() in setOf("to", "from", ";")
            }
            if (termOk) {
                for (name in buf) if (seen.add("${tok.offset} $name")) out += SqlRelation(name, tok.offset)
            }
        }
    }
    return out to unresolved
}

/** 뒤따르는 식별자가 관계 이름인 키워드다. `on`은 GRANT/REVOKE 문 안에서만 관계 키워드로 발화한다. */
private fun isRelationKeyword(word: String): Boolean =
    word.lowercase() in setOf("from", "join", "into", "update", "table", "truncate", "on")

private fun isSqlVerb(word: String): Boolean =
    word.lowercase() in setOf(
        "select", "insert", "delete", "create", "alter", "drop", "replace", "merge",
        "lock", "unlock", "rename", "describe", "desc", "analyze", "vacuum", "grant", "revoke",
    )

/** GRANT/REVOKE의 권한 단어인지 본다 — `ON`이 관계 자리임을 확정하는 근거다. */
private fun isGrantPriv(word: String): Boolean =
    word.lowercase() in setOf(
        "select", "insert", "update", "delete", "truncate", "references", "trigger",
        "execute", "usage", "create", "connect", "temporary", "temp", "maintain", "all",
    )

/** `table` 토큰이 관계 키워드로 발화하는 문맥인지 본다 — 직전 비인용 식별자가 DDL 동사일 때만이다. */
private fun tableKeywordContext(tokens: List<SqlToken>, i: Int): Boolean {
    for (k in i - 1 downTo 0) {
        if (!isNameToken(tokens[k])) continue
        return tokens[k].text.lowercase() in setOf(
            "alter", "drop", "create", "truncate", "rename", "lock", "unlock",
            "describe", "desc", "analyze", "vacuum",
        )
    }
    return false
}

/** 관계 키워드와 이름 사이에 올 수 있는 수식어다 — `table`은 TRUNCATE 뒤에서만 수식어다. */
private fun isNameModifier(word: String, afterTruncate: Boolean): Boolean =
    word.lowercase() in setOf("only", "if", "not", "exists") ||
        (afterTruncate && word.equals("table", true))

/** `(` 토큰부터 짝이 맞는 `)` 다음 위치를 돌려준다 — 닫히지 않으면 null. */
private fun skipParens(tokens: List<SqlToken>, start: Int): Int? {
    var depth = 0
    for (k in start until tokens.size) {
        val t = tokens[k]
        if (t.quoted) continue
        if (t.text == "(") depth++
        else if (t.text == ")") {
            depth--
            if (depth == 0) return k + 1
        }
    }
    return null
}

/**
 * 관계 이름 위치에 올 수 없는 SQL 절 키워드다 — `FROM {} WHERE` 템플릿의
 * 빈 플레이스홀더 뒤 토큰이 관계명으로 오독되지 않게 한다.
 * (`table`은 이름으로 읽어야 해서 제외한다 — `UPDATE table SET` 참조.)
 */
private fun isClauseWord(word: String): Boolean =
    word.lowercase() in setOf(
        "where", "set", "on", "group", "order", "by", "having", "limit", "offset",
        "union", "intersect", "except", "values", "returning", "as", "left", "right",
        "inner", "outer", "full", "cross", "natural", "lateral", "using", "and", "or",
        "not", "null", "select", "insert", "delete", "from", "join", "into", "update",
        "truncate", "with", "for", "in", "is", "case", "when", "then", "else", "end",
        "distinct", "asc", "desc", "if", "exists", "only", "between", "like", "to",
        "grant", "revoke", "option", "cascade", "restrict", "privileges",
    )

/** `ident(.ident)*` 한정 이름을 읽어 (이름, 다음 위치)를 돌려준다. */
private fun readQualifiedName(tokens: List<SqlToken>, start: Int): Pair<String, Int>? {
    val first = tokens.getOrNull(start) ?: return null
    if (first.quoted) {
        if (first.text.isEmpty()) return null
    } else if (!isNameToken(first) || isClauseWord(first.text)) {
        // 절 키워드(WHERE·SET·AS …)는 이름이 아니다 — `FROM {} WHERE`의
        // where 같은 토큰이 관계명으로 읽히지 않게 한다.
        return null
    }
    val name = StringBuilder(escapeSegment(first))
    var i = start + 1
    while (i + 1 < tokens.size && tokens[i].text == "." && !tokens[i].quoted) {
        val next = tokens[i + 1]
        if (!next.quoted && (!isNameToken(next) || isClauseWord(next.text))) break
        name.append('.').append(escapeSegment(next))
        i += 2
    }
    return name.toString() to i
}

/**
 * 인용 세그먼트의 `%`와 `.`을 escape한다 — `"a.b"` 같은 한 식별자가
 * 한정자로 오독되지 않게 하고, escape 문자 자체의 충돌을 막는다.
 * 비인용 세그먼트는 점을 담을 수 없어 `%`만 escape한다.
 */
private fun escapeSegment(tok: SqlToken): String =
    if (tok.quoted) tok.text.replace("%", "%25").replace(".", "%2E")
    else tok.text.replace("%", "%25")

/** 한정 이름의 각 세그먼트를 escape해 합친다 — 어노테이션에서 온 이름도 `.`가 한정자인 계약과 같게 맞춘다. */
internal fun escapeQualified(name: String): String =
    name.split('.').joinToString(".") { it.replace("%", "%25").replace(".", "%2E") }

/** 이름 문자열 그대로를 한 세그먼트로 escape한다 — `tableName = "a.b"` 같은 값은 한정자가 아니라 한 식별자다. */
internal fun escapeName(name: String): String =
    name.replace("%", "%25").replace(".", "%2E")

/** 비인용 토큰이 식별자인지 본다 — 기호·빈 문자열은 아니다. */
private fun isNameToken(tok: SqlToken): Boolean =
    !tok.quoted && tok.text.isNotEmpty() && isIdentStart(tok.text[0])

/** SQL 식별자 시작 문자인지 본다. */
private fun isIdentStart(c: Char): Boolean =
    c == '_' || c == '$' || c in 'a'..'z' || c in 'A'..'Z' || c.code >= 0x80

/** SQL 식별자의 이어지는 문자인지 본다. */
private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c in '0'..'9'
