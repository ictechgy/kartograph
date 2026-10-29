package dev.kartograph.cli

import dev.kartograph.export.McpJsonCodec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `reach`·`impact --format language-traversal`의 `--persistence-facts`다. 실제 javac 산출물과 실제 `schema --graph-file` 문서로
 * 상속 Spring Data CRUD 호출이 persistence 사실로 모델링됐을 때만 `unresolvedCalls`에서 빠지는지 확인한다.
 */
class PersistenceModeledCallsCliTest {
    private data class Execution(val status: Int, val output: String, val error: String)

    private fun run(vararg arguments: String): Execution {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val status = KartographCli.run(arguments, PrintStream(output), PrintStream(error))
        return Execution(status, output.toString(), error.toString())
    }

    private fun write(root: Path, relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text + "\n")
    }

    /**
     * 합성 앱이다. Spring Data·JPA 어노테이션은 스냅샷 밖(lib)에 둔 빈 대역이라 `save`·`findById`는 프로젝트 정점이 없다.
     * OwnerService.create는 상속 CRUD를 부르고, forward는 저장소가 아닌 프로젝트 인터페이스의 상속 메서드를 부른다.
     */
    private fun project(root: Path): Path {
        write(root, "stubs/jakarta/persistence/Entity.java", "package jakarta.persistence; public @interface Entity {}")
        write(root, "stubs/jakarta/persistence/Id.java", "package jakarta.persistence; public @interface Id {}")
        write(root, "stubs/org/springframework/data/repository/Repository.java",
            "package org.springframework.data.repository; public interface Repository<T, ID> {}")
        write(root, "stubs/org/springframework/data/repository/CrudRepository.java",
            "package org.springframework.data.repository; public interface CrudRepository<T, ID> extends Repository<T, ID> {" +
                " <S extends T> S save(S entity); java.util.Optional<T> findById(ID id); }")
        write(root, "src/main/java/demo/Owner.java",
            "package demo;\nimport jakarta.persistence.Entity;\nimport jakarta.persistence.Id;\n@Entity\npublic class Owner { @Id Integer id; String name; }")
        write(root, "src/main/java/demo/BaseRepository.java",
            "package demo;\nimport org.springframework.data.repository.CrudRepository;\npublic interface BaseRepository<T> extends CrudRepository<T, Integer> {}")
        write(root, "src/main/java/demo/OwnerRepository.java", "package demo;\npublic interface OwnerRepository extends BaseRepository<Owner> {}")
        write(root, "src/main/java/demo/Sink.java", "package demo;\npublic interface Sink extends java.util.function.Consumer<Object> {}")
        write(root, "src/main/java/demo/OwnerService.java", """
            package demo;
            public class OwnerService {
                OwnerRepository owners;
                public void create(Owner owner) {
                    owners.save(owner);
                    owners.findById(1);
                }
                public void forward(Sink sink) {
                    sink.accept("x");
                }
            }
        """.trimIndent())
        val lib = Files.createDirectories(root.resolve("lib"))
        val classes = Files.createDirectories(root.resolve("classes"))
        val stubs = Files.walk(root.resolve("stubs")).use { paths -> paths.filter { it.toString().endsWith(".java") }.map { it.toString() }.toList() }
        val sources = Files.walk(root.resolve("src")).use { paths -> paths.filter { it.toString().endsWith(".java") }.map { it.toString() }.toList() }
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, null, null, "-d", lib.toString(), *stubs.toTypedArray()))
        assertEquals(0, compiler.run(null, null, null, "-g", "-cp", lib.toString(), "-d", classes.toString(), *sources.toTypedArray()))
        val snapshot = run("snapshot", "--classes", classes.toString(), "--project", root.toString(), "--include-paths")
        assertEquals(0, snapshot.status, snapshot.error)
        return root.resolve("graph.json").also { Files.writeString(it, snapshot.output) }
    }

    private fun unresolved(document: Map<*, *>): Map<String, Long> = ((document["roots"] as List<*>) + (document["reached"] as List<*>))
        .map { it as Map<*, *> }
        .mapNotNull { entry ->
            val usr = (entry["symbol"] as Map<*, *>?)?.get("usr") as String? ?: entry["id"] as String?
            (entry["unresolvedCalls"] as Long?)?.let { usr!!.substringAfter('#').substringBefore('(') to it }
        }.toMap()

    @Test
    fun `inherited repository calls with persistence facts are not unresolved`(@TempDir root: Path) {
        val graph = project(root)
        val schema = run("schema", "--project", root.toString(), "--graph-file", graph.toString())
        assertEquals(0, schema.status, schema.error)
        val facts = root.resolve("persistence.json").also { Files.writeString(it, schema.output) }
        val roots = arrayOf("--symbol", "method:demo/OwnerService#create(Ldemo/Owner;)V", "--symbol", "method:demo/OwnerService#forward(Ldemo/Sink;)V")
        val common = arrayOf("reach", *roots, "--graph-file", graph.toString(), "--project", root.toString(), "--generated-at", "2026-01-01T00:00:00Z")

        val before = McpJsonCodec.parse(run(*common).output) as Map<*, *>
        assertEquals(mapOf("create" to 2L, "forward" to 1L), unresolved(before))

        val after = run(*common, "--persistence-facts", facts.toString())
        assertEquals(0, after.status, after.error)
        val document = McpJsonCodec.parse(after.output) as Map<*, *>
        assertEquals(mapOf("forward" to 1L), unresolved(document))
        val limitations = (document["limitations"] as List<*>).map { it as String }
        assertTrue(limitations.any { it.startsWith("persistence-modeled-calls: 2 call(s)") }, limitations.toString())

        // 역방향 문서도 같은 규칙을 쓴다.
        val impact = run("impact", *roots, "--format", "language-traversal", "--graph-file", graph.toString(), "--project", root.toString(),
            "--persistence-facts", facts.toString())
        assertEquals(0, impact.status, impact.error)
        assertEquals(mapOf("forward" to 1L), unresolved(McpJsonCodec.parse(impact.output) as Map<*, *>))
    }

    @Test
    fun `calls without a persistence fact stay unresolved and bad documents fail`(@TempDir root: Path) {
        val graph = project(root)
        // 호출 줄이 다른 사실은 이 호출을 모델링했다는 근거가 아니다.
        val project = root.toRealPath().toString().replace('\\', '/')
        val facts = root.resolve("other.json").also {
            Files.writeString(it, """{"format": "bridge-facts", "target": "persistence", "project": "$project", "facts": [{"kind": "relation-use", "channel": "owner",
                "dynamic": false, "location": {"path": "src/main/java/demo/OwnerService.java", "line": 99, "column": 1},
                "symbol": {"qualifiedName": "demo.OwnerService.create", "usr": "method:demo/OwnerService#create(Ldemo/Owner;)V"}}]}""")
        }
        val common = arrayOf("reach", "method:demo/OwnerService#create(Ldemo/Owner;)V", "--graph-file", graph.toString(), "--project", root.toString())
        val document = McpJsonCodec.parse(run(*common, "--persistence-facts", facts.toString()).output) as Map<*, *>
        assertEquals(mapOf("create" to 2L), unresolved(document))
        assertTrue((document["limitations"] as List<*>).none { (it as String).startsWith("persistence-modeled-calls:") })

        val http = root.resolve("http.json").also { Files.writeString(it, """{"format": "bridge-facts", "target": "http", "project": "$project", "facts": []}""") }
        // 다른 프로젝트 루트의 persistence 문서는 이 순회의 근거가 아니다.
        val elsewhere = root.resolve("elsewhere.json").also {
            Files.writeString(it, """{"format": "bridge-facts", "target": "persistence", "project": "/elsewhere", "facts": []}""")
        }
        listOf(http, elsewhere, root.resolve("absent.json")).forEach { bad ->
            val execution = run(*common, "--persistence-facts", bad.toString())
            assertEquals(2, execution.status)
            assertContains(execution.error, "--persistence-facts")
        }
        assertContains(run("reach", "--help").output, "--persistence-facts")
    }
}
