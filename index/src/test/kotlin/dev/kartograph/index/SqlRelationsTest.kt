package dev.kartograph.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** rustograph와 동일한 관계 추출 계약을 Kotlin 포트가 지키는지 검증한다. */
class SqlRelationsTest {

    private fun relations(sql: String): List<String> = sqlRelations(sql).first.map { it.name }

    private fun relationsAndUnresolved(sql: String): Pair<List<String>, Int> =
        sqlRelations(sql).let { (found, unresolved) -> found.map { it.name } to unresolved }

    @Test
    fun `table valued functions are not table names`() {
        assertEquals(emptyList(), relations("SELECT * FROM pragma_table_info('t')"))
        assertEquals(listOf("users"), relations("SELECT * FROM users JOIN main.pragma_table_info('t') p ON true"))
        assertEquals(listOf("users"), relations("SELECT * FROM pragma_table_info('t') AS p, users"))
        assertEquals(1, sqlRelations("SELECT * FROM pragma_table_info('t')").second)
    }

    @Test
    fun `basic relation keywords`() {
        assertEquals(listOf("users"), relations("SELECT * FROM users"))
        assertEquals(listOf("users", "orders"), relations("SELECT * FROM users JOIN orders ON true"))
        assertEquals(listOf("users"), relations("INSERT INTO users (id) VALUES (1)"))
        assertEquals(listOf("users"), relations("UPDATE users SET name = 'x'"))
        assertEquals(listOf("users"), relations("DELETE FROM users"))
        assertEquals(listOf("s.t"), relations("SELECT * FROM s.t"))
        assertEquals(listOf("a%2Eb"), relations("SELECT * FROM `a.b`"))
    }

    @Test
    fun `comma lists and aliases`() {
        assertEquals(listOf("a", "b"), relations("SELECT * FROM a, b"))
        assertEquals(listOf("a", "b"), relations("SELECT * FROM a x, b y"))
        assertEquals(listOf("a", "b"), relations("SELECT * FROM a AS x, b AS y"))
    }

    @Test
    fun `statement boundaries`() {
        assertEquals(listOf("a", "b"), relations("UPDATE a SET x = 1; UPDATE b SET y = 2"))
        // 산문: "update the config"은 문장 머리여도 SET이 없어 발화하지 않는다.
        assertEquals(emptyList(), relations("please update the config"))
        assertEquals(listOf("t"), relations("UPDATE t SET x = 1"))
        // GRANT/REVOKE는 권한 단어가 앞서야 ON이 발화한다.
        assertEquals(listOf("metrics"), relations("GRANT SELECT ON TABLE metrics TO app"))
        assertEquals(listOf("metrics"), relations("GRANT SELECT ON metrics TO app"))
        assertEquals(emptyList(), relations("REVOKE SELECT ON FUNCTION f FROM r"))
        assertEquals(emptyList(), relations("grant select on the report to auditors"))
        assertEquals(emptyList(), relations("grant access on staging to intern"))
        // SQLDelight 라벨(`name:`)은 문장 머리를 차지하지 않는다 — 뒤의 동사가 머리다.
        assertEquals(listOf("users"), relations("markAdult:\nUPDATE users SET adult = 1"))
        assertEquals(listOf("users"), relations("clearAll:\nTRUNCATE users"))
        // 캐스트(`::`)는 라벨이 아니다.
        assertEquals(listOf("t"), relations("SELECT x::int FROM t"))
    }

    @Test
    fun `prose does not produce relations`() {
        assertEquals(emptyList(), relations("the report into the folder"))
        assertEquals(emptyList(), relations("merged the branch into main"))
        assertEquals(emptyList(), relations("drop the table at noon"))
        assertEquals(emptyList(), relations("turn the table over"))
    }

    @Test
    fun `subqueries and parens`() {
        assertEquals(listOf("a", "b"), relations("SELECT * FROM (SELECT * FROM a) x JOIN b ON true"))
        // INSERT .. SELECT는 양쪽 다 읽는다 — 읽기 원본도 관계 사용이다.
        assertEquals(listOf("a", "ignored_c"), relations("INSERT INTO a SELECT * FROM ignored_c"))
    }

    @Test
    fun `unresolved operands are counted`() {
        val (names, unresolved) = sqlRelations("DELETE FROM {} WHERE id = ?")
        assertTrue(names.isEmpty())
        assertEquals(1, unresolved)
        assertEquals(0, sqlRelations("SELECT * FROM users").second)
        // 이름 없이 끝나는 키워드도 미해석이다.
        assertEquals(1, sqlRelations("SELECT 1 FROM").second)
        // 미해석 피연산자는 개수로 센다.
        assertEquals(2, sqlRelations("SELECT * FROM {} JOIN ?").second)
    }

    @Test
    fun `looks like sql gate`() {
        assertTrue(looksLikeSql("SELECT * FROM t"))
        assertTrue(looksLikeSql("update t set x = 1"))
        assertFalse(looksLikeSql("please update the config"))
        assertFalse(looksLikeSql("a plain sentence"))
        assertFalse(looksLikeSql(""))
    }

    @Test
    fun `strict mode rejects prose and lowercase keywords`() {
        // 산문의 혼합 대소문자 키워드는 strict에서 발화하지 않는다.
        assertFalse(looksLikeSql("Select an option from the menu", strict = true))
        assertTrue(looksLikeSql("SELECT an option FROM the menu", strict = true))
        assertEquals(emptyList(), sqlRelations("Select an option from the menu", strict = true).first)
        // 게이트 없는 리터럴이 소문자 SQL이면 사실을 만들지 않는다.
        assertEquals(emptyList(), sqlRelations("select * from users", strict = true).first)
        // 관사는 이름 자리에 설 수 없다 — 대문자 산문의 오탐도 막는다.
        assertEquals(emptyList(), sqlRelations("SELECT a FROM the").first)
        // strict가 아니면 소문자 SQL은 그대로 읽는다.
        assertEquals(listOf("users"), relations("select * from users"))
    }

    @Test
    fun `shared family cases read only real relations`() {
        // 사례 파일의 relations는 집합이다 — cte/recursive-with-columns는 FROM nodes가 두 키워드 위치에서
        // 나와 목록이면 [nodes, nodes]다.
        val failures = SHARED_SQL_RELATION_CASES.mapNotNull { case ->
            val actual = relations(case.sql).toSet()
            "${case.id}: expected ${case.relations} but was $actual".takeIf { actual != case.relations }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `cte bodies that change data keep their relations`() {
        // CTE 본문 괄호는 함수 인자가 아니다 — `MATERIALIZED (`의 materialized는 절 키워드가 아니어도
        // DELETE의 FROM이 관계를 연다.
        assertEquals(setOf("t"), relations("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d").toSet())
        assertEquals(setOf("t"), relations("WITH d AS MATERIALIZED (DELETE FROM t RETURNING *) SELECT * FROM d").toSet())
        assertEquals(setOf("t"), relations("WITH d AS NOT MATERIALIZED (DELETE FROM t RETURNING *) SELECT * FROM d").toSet())
    }

    @Test
    fun `cte names compare like sql identifiers`() {
        // 비인용 이름은 대소문자를 접고, 인용 이름은 그대로 비교한다.
        assertEquals(emptyList(), relations("WITH Recent AS (SELECT 1) SELECT * FROM recent"))
        assertEquals(emptyList(), relations("WITH \"recent\" AS (SELECT 1) SELECT * FROM recent"))
        assertEquals(listOf("recent"), relations("WITH \"Recent\" AS (SELECT 1) SELECT * FROM recent"))
        // 한정 이름은 CTE가 아니다 — 한정자가 CTE 이름과 같아도 그 스키마의 관계다.
        assertEquals(listOf("s.recent"), relations("WITH recent AS (SELECT 1) SELECT * FROM s.recent"))
        assertEquals(listOf("recent.items"), relations("WITH recent AS (SELECT 1) SELECT * FROM recent.items"))
        // 쉼표로 이어진 CTE와 피연산자 목록에서도 CTE 이름만 빠진다.
        assertEquals(listOf("c"), relations("WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a, b, c"))
        assertEquals(listOf("c"), relations("WITH a AS (SELECT 1) SELECT * FROM c JOIN a ON true"))
    }

    @Test
    fun `with clauses that define no cte hide nothing`() {
        assertEquals(listOf("metrics"), relations("GRANT SELECT ON metrics TO app WITH GRANT OPTION"))
        assertEquals(listOf("orders"), relations("SELECT * FROM orders WITH (NOLOCK)"))
        assertEquals(listOf("events"), relations("SELECT CAST(at AS TIMESTAMP WITH TIME ZONE) FROM events"))
    }

    @Test
    fun `cte rule covers the whole statement`() {
        // 공통 규칙은 문장 단위다 — 본문이 같은 이름의 실제 테이블을 읽어도 그 사용은 내지 않는다.
        assertEquals(emptyList(), relations("WITH users AS (SELECT * FROM users WHERE active) SELECT * FROM users"))
    }

    @Test
    fun `subqueries inside function arguments still read relations`() {
        assertEquals(setOf("t"), relations("SELECT COALESCE((SELECT x FROM t), 0)").toSet())
        assertEquals(setOf("z", "w"), relations("SELECT count(*) FILTER (WHERE x IN (SELECT y FROM z)) FROM w").toSet())
        assertEquals(setOf("t", "u"), relations("SELECT ARRAY(SELECT x FROM t) FROM u").toSet())
        // 절 키워드 뒤의 괄호는 함수 인자가 아니다 — 괄호로 시작하는 집합 질의의 FROM도 연다.
        assertEquals(setOf("t", "u"), relations("SELECT * FROM ((SELECT a FROM t) UNION SELECT b FROM u) AS x").toSet())
        assertEquals(
            setOf("orders", "t", "u"),
            relations("SELECT * FROM orders WHERE id IN ((SELECT a FROM t) UNION SELECT b FROM u)").toSet(),
        )
    }

    @Test
    fun `function argument and distinct from operands are not unresolved`() {
        // 관계를 열지 않는 FROM은 피연산자를 읽지 않는다 — 미해석으로도 세지 않는다.
        assertEquals(listOf("users") to 0, relationsAndUnresolved("SELECT SUBSTRING(name FROM 2) FROM users"))
        assertEquals(listOf("t") to 0, relationsAndUnresolved("SELECT EXTRACT(EPOCH FROM ?) FROM t"))
        assertEquals(listOf("events") to 0, relationsAndUnresolved("SELECT * FROM events WHERE kind IS DISTINCT FROM ?"))
        // `$`는 식별자 문자라 `$1`은 이름 토큰이다 — 비교 피연산자라 관계가 아니다.
        assertEquals(listOf("events") to 0, relationsAndUnresolved("SELECT * FROM events WHERE kind IS DISTINCT FROM \$1"))
        // 숫자는 토큰이 아니고 FOR는 절 키워드라 이 꼴은 원래 미해석이 없다.
        assertEquals(listOf("users") to 0, relationsAndUnresolved("SELECT SUBSTRING(name FROM 2 FOR 3) FROM users"))
    }

    @Test
    fun `select distinct keeps its from`() {
        // R3는 IS가 앞설 때만이다 — `*`는 토큰이 아니라 `DISTINCT * FROM`에서도 DISTINCT와 FROM이 이웃한다.
        assertEquals(listOf("t"), relations("SELECT DISTINCT * FROM t"))
    }

    @Test
    fun `function argument context ends at the statement boundary`() {
        // 닫히지 않은 함수 괄호가 다음 문장의 FROM을 삼키지 않는다.
        assertEquals(listOf("t"), relations("SELECT EXTRACT(YEAR FROM x; SELECT * FROM t"))
    }

    @Test
    fun `strict mode applies the three rules regardless of keyword case`() {
        val sql = "WITH recent AS (SELECT * FROM orders) SELECT EXTRACT(YEAR FROM at) FROM recent WHERE a IS DISTINCT FROM b"
        assertEquals(listOf("orders"), sqlRelations(sql, strict = true).first.map { it.name })
        // 세 규칙은 관계를 빼기만 한다 — strict 게이트는 관계 자리를 여는 키워드(FROM 등)에만 대문자를 요구한다.
        assertEquals(
            listOf("orders"),
            sqlRelations("with recent as (SELECT * FROM orders) SELECT * FROM recent", strict = true).first.map { it.name },
        )
        assertEquals(
            listOf("events"),
            sqlRelations("SELECT * FROM events WHERE a is not distinct FROM b", strict = true).first.map { it.name },
        )
    }

    @Test
    fun `relation keywords do not open function arguments`() {
        // DB2 데이터 변경 테이블 참조다 — `TABLE (`은 함수 호출이 아니라 안쪽 DELETE의 FROM이 관계를 연다.
        // (앞의 OLD는 지금도 관계 이름으로 읽히는 기존 동작이라 포함 여부만 본다.)
        assertTrue("orders" in relations("SELECT * FROM OLD TABLE (DELETE FROM orders WHERE id = 1)"))
    }

    /** 계열 공통 SQL 관계 사례 하나다 — [relations]는 순서·중복이 없는 집합이다. */
    private data class SharedSqlRelationCase(val id: String, val sql: String, val relations: Set<String>)

    private companion object {
        /**
         * persistence 생산자 7곳과 isthmus 공유 적합성 벡터(`conformance/sql-relations.json`)가 함께 쓰는
         * 사례를 SQL·기대값 그대로 옮긴 표다. R1 함수 인자 안의 FROM, R2 문장 단위 CTE 이름,
         * R3 `IS [NOT] DISTINCT FROM`의 피연산자는 관계가 아니다.
         */
        val SHARED_SQL_RELATION_CASES = listOf(
            SharedSqlRelationCase("cte/simple", "WITH recent AS (SELECT * FROM orders) SELECT * FROM recent", setOf("orders")),
            SharedSqlRelationCase("cte/recursive-with-columns", "WITH RECURSIVE tree(id, parent_id) AS (SELECT id, parent_id FROM nodes UNION ALL SELECT n.id, n.parent_id FROM nodes n JOIN tree t ON n.parent_id = t.id) SELECT * FROM tree", setOf("nodes")),
            SharedSqlRelationCase("cte/multiple-materialized", "WITH a AS (SELECT 1), b AS MATERIALIZED (SELECT * FROM orders) SELECT * FROM a JOIN b ON true", setOf("orders")),
            SharedSqlRelationCase("cte/not-materialized", "WITH c AS NOT MATERIALIZED (SELECT * FROM orders) SELECT * FROM c", setOf("orders")),
            SharedSqlRelationCase("cte/insert-select", "WITH staged AS (SELECT * FROM imports) INSERT INTO orders SELECT * FROM staged", setOf("imports", "orders")),
            SharedSqlRelationCase("cte/scope-is-one-statement", "WITH recent AS (SELECT 1) SELECT * FROM recent; SELECT * FROM recent", setOf("recent")),
            SharedSqlRelationCase("function-from/extract", "SELECT EXTRACT(YEAR FROM created_at) FROM orders", setOf("orders")),
            SharedSqlRelationCase("function-from/trim", "SELECT TRIM(BOTH ' ' FROM name) FROM users", setOf("users")),
            SharedSqlRelationCase("function-from/substring", "SELECT SUBSTRING(name FROM 2 FOR 3) FROM users", setOf("users")),
            SharedSqlRelationCase("subquery/derived-table-still-fires", "SELECT * FROM (SELECT * FROM orders) AS sub", setOf("orders")),
            SharedSqlRelationCase("subquery/scalar-still-fires", "SELECT (SELECT max(id) FROM users) FROM orders", setOf("users", "orders")),
            SharedSqlRelationCase("subquery/in-select-still-fires", "SELECT * FROM orders WHERE id IN (SELECT order_id FROM payments)", setOf("orders", "payments")),
            SharedSqlRelationCase("distinct-from/is", "SELECT * FROM events WHERE a IS DISTINCT FROM b", setOf("events")),
            SharedSqlRelationCase("distinct-from/is-not", "SELECT * FROM events WHERE a IS NOT DISTINCT FROM b", setOf("events")),
        )
    }
}
