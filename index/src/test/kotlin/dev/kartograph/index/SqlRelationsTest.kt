package dev.kartograph.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** rustograph와 동일한 관계 추출 계약을 Kotlin 포트가 지키는지 검증한다. */
class SqlRelationsTest {

    private fun relations(sql: String): List<String> = sqlRelations(sql).first.map { it.name }

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
}
