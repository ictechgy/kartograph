package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
import dev.kartograph.index.SchemaFactScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * JPA 명명 벡터(`fixtures/jpa-naming/vectors.json`)를 kartograph `schema`의 엔티티 선언 사실과 대조한다.
 *
 * 벡터의 기대값은 실제 Hibernate 6·7 스키마 export(`experiments/jpa-persistence/run.py`)가 만든 테이블·컬럼 이름이다.
 * 케이스마다 명명 조합을 고정해 스캔하고, 테이블 집합과 테이블별 컬럼 집합이 정확히 같아야 한다 —
 * 부분 일치나 dynamic 사실로 빠져나가는 것을 허용하지 않는다.
 */
class JpaNamingVectorTest {
    @TempDir
    lateinit var project: Path

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/$name")) { "missing JPA naming vector resource $name" }.use { it.readAllBytes() }

    private fun vectors(): Map<*, *> = McpJsonCodec.parse(resource("vectors.json").toString(Charsets.UTF_8)) as Map<*, *>

    @Test
    fun `kartograph JPA naming agrees with every Hibernate schema export vector`() {
        val document = vectors()
        assertEquals<Any?>("kartograph-jpa-naming-vectors", document["format"])
        assertEquals<Any?>(1L, document["version"])
        (document["sources"] as List<*>).map { it as String }.forEach { source ->
            val target = project.resolve(source)
            Files.createDirectories(target.parent)
            Files.write(target, resource(source))
        }
        val cases = (document["cases"] as List<*>).map { it as Map<*, *> }
        assertTrue(cases.map { it["profile"] }.toSet().containsAll(listOf("spring-boot-3", "spring-boot-4", "hibernate-6", "hibernate-7")))
        cases.forEach { case ->
            assertEquals<Any?>("execution", case["provenance"], "vector ${case["id"]} must come from a Hibernate run")
            val expected = ((case["expect"] as Map<*, *>)["tables"] as Map<*, *>)
                .map { (table, columns) -> table as String to (columns as List<*>).map { it as String }.toSet() }.toMap()
            val scanned = SchemaFactScanner(project, case["profile"] as String).scan(generatedAt = "2026-01-01T00:00:00Z")
            val dynamic = scanned.facts.filter { it.dynamic }
            assertTrue(dynamic.isEmpty(), "vector ${case["id"]} produced dynamic facts: $dynamic")
            val actual = scanned.facts.filter { it.method == null }.mapNotNull { it.channel }.toSet()
                .associateWith { table -> scanned.facts.filter { it.channel == table && it.method != null }.mapNotNull { it.method }.toSet() }
            assertEquals(expected.keys, actual.keys, "vector ${case["id"]} tables")
            expected.forEach { (table, columns) -> assertEquals(columns, actual[table], "vector ${case["id"]} columns of $table") }
        }
    }
}
