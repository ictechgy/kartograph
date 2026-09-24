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
        assertTrue(unresolved)
        assertFalse(sqlRelations("SELECT * FROM users").second)
        // 이름 없이 끝나는 키워드도 미해석이다.
        assertTrue(sqlRelations("SELECT 1 FROM").second)
    }

    @Test
    fun `looks like sql gate`() {
        assertTrue(looksLikeSql("SELECT * FROM t"))
        assertTrue(looksLikeSql("update t set x = 1"))
        assertFalse(looksLikeSql("please update the config"))
        assertFalse(looksLikeSql("a plain sentence"))
        assertFalse(looksLikeSql(""))
    }
}
