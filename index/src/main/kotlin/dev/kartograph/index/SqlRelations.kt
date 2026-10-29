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
internal fun looksLikeSql(text: String, strict: Boolean = false): Boolean {
    var head = true
    for (t in lexSql(text)) {
        if (!isNameToken(t)) continue
        if (isSqlVerb(t.text) && (!strict || t.text == t.text.uppercase())) return true
        if (head) {
            head = false
            val headVerb = t.text.equals("update", true) || t.text.equals("truncate", true)
            if (headVerb && (!strict || t.text == t.text.uppercase())) return true
        }
    }
    return false
}

/**
 * SQL 텍스트에서 관계 이름을 읽는다.
 * 한정 이름(`schema.table`)은 그대로 두고, 이름 자체에 점이 있는 인용
 * 식별자("a.b")는 한 세그먼트로 읽는다 — escape는 기록 시에 한다.
 * [SqlRelation.keyword]는 관계를 연 키워드 토큰의 원문 위치다.
 * 두 번째 반환은 관계 자리의 피연산자를 읽지 못한 횟수다 —
 * `FROM {}` 같은 플레이스홀더를 사실 없이 조용히 넘기지 않기 위해서다.
 * 같은 문장의 CTE 이름, 함수 인자 안의 FROM, `IS [NOT] DISTINCT FROM`의 피연산자는
 * 관계가 아니다(persistence 생산자 공통 규칙, [SqlRelationContext]).
 */
internal fun sqlRelations(text: String, strict: Boolean = false): Pair<List<SqlRelation>, Int> {
    val tokens = lexSql(text)
    val context = SqlRelationContext(tokens)
    val out = mutableListOf<SqlRelation>()
    // 같은 키워드의 피연산자 목록 안에서만 중복을 막는다(`FROM a, a`).
    // 다른 위치의 같은 이름은 별개의 사용 근거다 — `SELECT .. FROM t`와
    // `INSERT INTO t`는 각각 사실이어야 한다.
    val seen = mutableSetOf<String>()
    val consumed = BooleanArray(tokens.size) // 이름·별칭·수식어로 소비된 토큰
    var unresolved = 0
    // strict 모드는 게이트 없이 스캔하는 문자열 리터럴용이다 — 관계 키워드와
    // 동사가 모두 대문자일 때만 발화해 "Select an option from the menu" 같은
    // 산문이 관계 사실을 만들지 않게 한다.
    // `this.text`를 명시한다 — 무한정 `text`는 외부 파라미터(sql 문장)로 해석된다.
    fun SqlToken.upperOk(): Boolean = !strict || this.text == this.text.uppercase()
    // `;`로 갈리는 각 문장의 머리 식별자 위치와 그 문장의 동사다 —
    // update·truncate는 문장 머리에서만 관계 키워드로 열고, `on`은
    // grant·revoke 문 안에서만 연다. 다중 문장 리터럴의 뒤 문장도
    // 같은 규칙을 받는다. 문장 머리의 `ident :`는 SQLDelight 라벨이다 —
    // 라벨은 머리를 차지하지 않고 다음 식별자가 머리가 된다.
    val stmtHead = BooleanArray(tokens.size)
    val stmtVerb = arrayOfNulls<String>(tokens.size)
    run {
        var pending = true
        var verb: String? = null
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (!t.quoted && t.text == ";") {
                pending = true
                verb = null
                i++
                continue
            }
            if (isNameToken(t) && pending) {
                if (tokens.getOrNull(i + 1)?.let { !it.quoted && it.text == ":" } == true &&
                    tokens.getOrNull(i + 2)?.let { !it.quoted && it.text == ":" } != true
                ) {
                    i += 2 // 라벨은 머리가 아니다 — 계속 pending 상태로 둔다.
                    continue
                }
                stmtHead[i] = true
                verb = t.text.lowercase().takeIf { t.upperOk() }
                pending = false
            }
            stmtVerb[i] = verb
            i++
        }
    }
    for (i in tokens.indices) {
        val tok = tokens[i]
        if (consumed[i] || tok.quoted || !isRelationKeyword(tok.text) || !tok.upperOk()) continue
        val word = tok.text.lowercase()
        val grantStmt = stmtVerb[i] == "grant" || stmtVerb[i] == "revoke"
        // 같은 문장(`;`로 갈리는 세그먼트) 안만 본다 — 뒤 세그먼트의
        // 단어를 앞 문장의 근거로 쓰지 않는다.
        fun segmentBefore(end: Int): Sequence<SqlToken> =
            tokens.subList(0, end).asReversed().asSequence().takeWhile { it.text != ";" }
        fun segmentAfterHas(start: Int, w: String): Boolean =
            tokens.subList(start, tokens.size).asSequence().takeWhile { it.text != ";" }
                .any { !it.quoted && it.text.equals(w, true) && it.upperOk() }
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
                !it.quoted && it.upperOk() &&
                    it.text.lowercase() in setOf("insert", "select", "merge", "replace")
            }
            // table은 직전 식별자가 DDL 동사일 때만 키워드다 — 산문의
            // "the table"이나 다른 절의 단어는 읽지 않는다.
            "table" -> tableKeywordContext(tokens, i, strict)
            // on은 `GRANT .. ON t`·`REVOKE .. ON t`의 관계 자리다 — 권한
            // 단어(SELECT 등)가 앞서야 "grant access on .." 같은 산문을
            // 막는다. `CREATE INDEX/TRIGGER .. ON t`의 on도 관계 자리다.
            "on" -> {
                val grantOn = grantStmt && segmentBefore(i).any {
                    !it.quoted && it.upperOk() && isGrantPriv(it.text)
                }
                val createOn = stmtVerb[i] == "create" && segmentBefore(i).any {
                    // `rule`은 제외 — CREATE RULE의 ON은 이벤트
                    // 자리(`ON INSERT TO t`)라 관계가 아니다.
                    !it.quoted && it.upperOk() &&
                        it.text.lowercase() in setOf("index", "trigger", "policy")
                }
                grantOn || createOn
            }
            // grant·revoke의 FROM은 권한 주체 자리다 — 관계가 아니므로
            // from·join을 그 문장에서는 열지 않는다. 함수 인자 안의 FROM(R1)과
            // `IS [NOT] DISTINCT FROM`(R3)도 관계 자리가 아니라 미해석으로도 세지 않는다.
            "from", "join" -> !grantStmt && !(word == "from" && context.suppressesFrom(i))
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
                                unresolved++ // 닫히지 않은 괄호.
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
                                unresolved++
                            }
                            break
                        }
                    }
                    continue
                }
            }
        }
        if (j >= tokens.size) {
            unresolved++ // 이름이 없는 키워드 — "SELECT ... FROM" 꼴.
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
                    unresolved++ // 닫히지 않은 괄호.
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
                    if (!clauseNext) unresolved++
                    break
                }
                val (name, next) = read
                for (c in j until next) consumed[c] = true
                if (word in setOf("from", "join") &&
                    tokens.getOrNull(next)?.let { !it.quoted && it.text == "(" } == true
                ) {
                    // 함수의 내부 관계를 추측하지 않고 미해석 근거를 남긴다.
                    unresolved++
                    skipParens(tokens, next) ?: break
                } else if (context.isCteReference(j, next)) {
                    // 같은 문장의 CTE 이름은 관계가 아니다(R2) — 소비 표시만 남기고 내지 않는다.
                    next
                } else {
                    if (bufferedGrant) buf += name
                    else if (seen.add("${tok.offset}\u0000$name")) out += SqlRelation(name, tok.offset)
                    next
                }
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

/**
 * 계열 공통 규칙 세 가지가 관계 키워드마다 묻는 문장·괄호 문맥이다.
 *
 * - R1: `(` 바로 앞이 함수 이름이고 그룹이 SELECT·WITH·VALUES로 시작하지 않으면 그 깊이의 FROM은
 *   관계를 열지 않는다(`EXTRACT(YEAR FROM created_at)`·`TRIM(BOTH ' ' FROM name)`). 안쪽 하위 질의는 연다.
 * - R2: 문장(`;` 구간)마다 `WITH [RECURSIVE] name [(cols)] AS [NOT] [MATERIALIZED] (`와 `, name …`의
 *   이름을 모아, 같은 문장의 한정자 없는 피연산자가 그 이름이면 관계로 내지 않는다. 다른 문장에는 영향이 없다.
 * - R3: FROM 바로 앞이 비인용 `distinct`이고 그 앞이 `is` 또는 `is not`이면 비교 연산자의 일부다.
 *
 * persistence 생산자 7곳이 같은 SQL을 같게 읽어야 isthmus 조인이 생산자 언어에 따라 갈리지 않는다 —
 * 규칙의 정본은 isthmus GRAPH-EXCHANGE의 "SQL 텍스트의 관계 이름" 절과 공유 사례
 * `conformance/sql-relations.json`이다. 키워드마다 토큰을 되감지 않도록 문장 번호·괄호 짝·CTE 이름·
 * 함수 인자 문맥을 토큰 열을 세 번 훑어 미리 계산한다(재귀 없이 선형).
 * 세 규칙은 관계를 빼기만 하므로 strict(게이트 없는 리터럴)에서도 판정 단어의 대소문자를 가리지 않는다 —
 * strict 게이트는 산문이 관계 자리를 여는 것을 막는 장치이고, 계열 도구도 같게 판정한다.
 */
private class SqlRelationContext(private val tokens: List<SqlToken>) {
    /** 토큰마다 속한 문장(`;`로 갈리는 구간) 번호다. */
    private val statements = IntArray(tokens.size)

    /** 여는 괄호마다 같은 문장 안의 짝 닫는 괄호 위치다 — 짝이 없으면 -1이다. */
    private val closing = IntArray(tokens.size) { -1 }

    /** CTE 정의 본문의 `(` 위치다 — 이 괄호는 함수 인자 그룹이 아니다. */
    private val cteBodies = HashSet<Int>()

    /** 토큰을 감싼 가장 안쪽 괄호가 함수 인자 그룹인지다(R1). */
    private val insideFunctionArguments = BooleanArray(tokens.size)

    /** 문장마다 모은 CTE 이름의 비교 키다(R2). */
    private val cteNames: List<Set<String>>

    init {
        markStatementsAndParens()
        cteNames = collectCteNames()
        markFunctionArguments()
    }

    /** [index]의 FROM이 관계를 열지 않는지 본다 — 함수 인자 안(R1)이거나 `IS [NOT] DISTINCT FROM`(R3)이다. */
    fun suppressesFrom(index: Int): Boolean = insideFunctionArguments[index] || isDistinctFromOperator(index)

    /** [start]부터 [next] 앞까지 읽은 피연산자가 같은 문장의 CTE 이름인지 본다(R2) — 한정 이름은 CTE가 아니다. */
    fun isCteReference(start: Int, next: Int): Boolean =
        next == start + 1 && cteKey(tokens[start]) in cteNames[statements[start]]

    /**
     * 토큰마다 문장 번호를, 여는 괄호마다 같은 문장 안의 짝 닫는 괄호를 적는다.
     * `;`는 열린 괄호를 모두 버린다 — 앞 문장의 닫히지 않은 괄호가 다음 문장의 문맥이 되지 않게 한다.
     */
    private fun markStatementsAndParens() {
        var statement = 0
        val open = ArrayDeque<Int>()
        for (index in tokens.indices) {
            statements[index] = statement
            when {
                isSymbol(index, ";") -> { statement++; open.clear() }
                isSymbol(index, "(") -> open.addLast(index)
                isSymbol(index, ")") -> open.removeLastOrNull()?.let { closing[it] = index }
            }
        }
    }

    /** R2: 문장마다 CTE 이름의 비교 키를 모으고, 그 정의의 본문 괄호를 [cteBodies]에 적는다. */
    private fun collectCteNames(): List<Set<String>> {
        val names = List((statements.lastOrNull() ?: 0) + 1) { HashSet<String>() }
        for (index in tokens.indices) {
            val withClause = isWord(index, "with")
            if (!withClause && !isSymbol(index, ",")) continue
            val nameIndex = if (withClause && isWord(index + 1, "recursive")) index + 2 else index + 1
            val body = cteBodyParen(nameIndex) ?: continue
            names[statements[nameIndex]].add(cteKey(tokens[nameIndex]))
            cteBodies.add(body)
        }
        return names
    }

    /**
     * [nameIndex]에서 `name [(cols)] AS [NOT] [MATERIALIZED] (`가 시작하면 본문의 `(` 위치를 돌려준다.
     * 모양이 다르면(`WITH GRANT OPTION`·`WITH (NOLOCK)`·`WITH TIME ZONE`) CTE 정의가 아니다.
     */
    private fun cteBodyParen(nameIndex: Int): Int? {
        if (!isCteName(nameIndex)) return null
        val columnsEnd = if (isSymbol(nameIndex + 1, "(")) closing[nameIndex + 1] else nameIndex
        if (columnsEnd < 0) return null // 닫히지 않은 열 목록이다.
        var cursor = columnsEnd + 1
        if (!isWord(cursor, "as")) return null
        cursor++
        if (isWord(cursor, "not")) cursor++
        if (isWord(cursor, "materialized")) cursor++
        return cursor.takeIf { isSymbol(it, "(") }
    }

    /** R1: 토큰마다 감싼 가장 안쪽 괄호가 함수 인자 그룹인지 적는다 — `;`는 열린 괄호 문맥을 모두 버린다. */
    private fun markFunctionArguments() {
        val groups = ArrayDeque<Boolean>()
        for (index in tokens.indices) {
            when {
                isSymbol(index, ";") -> groups.clear()
                isSymbol(index, "(") -> groups.addLast(index !in cteBodies && opensFunctionArguments(index))
                isSymbol(index, ")") -> groups.removeLastOrNull()
                else -> insideFunctionArguments[index] = groups.lastOrNull() == true
            }
        }
    }

    /**
     * `(`가 함수 인자 그룹을 여는지 본다 — 바로 앞이 함수 이름이고 그룹이 SELECT·WITH·VALUES로 시작하지 않는다.
     * 함수 이름은 절 키워드·관계 키워드가 아닌 비인용 이름이다. 공통 규칙의 "식별자"를 이렇게 좁혀야
     * `IN ((SELECT …) UNION SELECT … FROM b)`의 b, DB2 `OLD TABLE (DELETE FROM t …)`의 t를 잃지 않는다.
     */
    private fun opensFunctionArguments(open: Int): Boolean {
        val name = tokens.getOrNull(open - 1) ?: return false
        if (!isNameToken(name) || isClauseWord(name.text) || isRelationKeyword(name.text)) return false
        return listOf("select", "with", "values").none { isWord(open + 1, it) }
    }

    /** R3: FROM 바로 앞이 비인용 `distinct`이고 그 앞이 `is` 또는 `is not`이면 비교 연산자의 일부다. */
    private fun isDistinctFromOperator(index: Int): Boolean =
        isWord(index - 1, "distinct") &&
            (isWord(index - 2, "is") || (isWord(index - 2, "not") && isWord(index - 3, "is")))

    /** CTE 이름이 될 수 있는 토큰인지 본다 — 비인용 이름이거나 비어 있지 않은 인용 식별자다. */
    private fun isCteName(index: Int): Boolean {
        val token = tokens.getOrNull(index) ?: return false
        return if (token.quoted) token.text.isNotEmpty() else isNameToken(token)
    }

    /**
     * CTE 이름과 한정자 없는 피연산자의 비교 키다. 비인용 이름은 SQL 식별자 규칙대로 소문자로 접고
     * 인용 이름은 그대로 둔다(isthmus 참조 구현과 같은 선택이다).
     */
    private fun cteKey(token: SqlToken): String = if (token.quoted) token.text else token.text.lowercase()

    /** [index]의 토큰이 비인용 단어 [word](소문자)인지 대소문자 없이 본다 — 범위 밖이면 거짓이다. */
    private fun isWord(index: Int, word: String): Boolean =
        tokens.getOrNull(index)?.let { !it.quoted && it.text.lowercase() == word } == true

    /** [index]의 토큰이 비인용 기호 [symbol]인지 본다 — 범위 밖이면 거짓이다. */
    private fun isSymbol(index: Int, symbol: String): Boolean =
        tokens.getOrNull(index)?.let { !it.quoted && it.text == symbol } == true
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
private fun tableKeywordContext(tokens: List<SqlToken>, i: Int, strict: Boolean): Boolean {
    for (k in i - 1 downTo 0) {
        if (!isNameToken(tokens[k])) continue
        if (strict && tokens[k].text != tokens[k].text.uppercase()) return false
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
        // 산문 관사다 — "select an option from the menu" 같은 문장이
        // 관계명을 만들지 않게 한다(`a`는 실제 별칭·이름으로 흔해 제외한다).
        "the", "an",
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
