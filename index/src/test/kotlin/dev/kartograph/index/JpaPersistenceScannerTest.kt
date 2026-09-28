package dev.kartograph.index

import dev.kartograph.core.BridgeFact
import dev.kartograph.core.BridgeFactsDocument
import dev.kartograph.core.CodeGraph
import dev.kartograph.core.EdgeKind
import dev.kartograph.core.ExternalCall
import dev.kartograph.core.GraphEdge
import dev.kartograph.core.GraphNode
import dev.kartograph.core.InvocationKind
import dev.kartograph.core.NodeId
import dev.kartograph.core.NodeKind
import dev.kartograph.core.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JpaPersistenceScannerTest {
    @TempDir
    lateinit var project: Path

    private fun write(relative: String, text: String) {
        val path = project.resolve(relative)
        Files.createDirectories(path.parent)
        path.writeText(text.trimIndent())
    }

    private fun scan(naming: String? = "spring-boot-3", graph: CodeGraph? = null): BridgeFactsDocument =
        SchemaFactScanner(project, naming).scan(generatedAt = "2026-01-01T00:00:00Z", graph = graph)

    private fun BridgeFactsDocument.at(file: String, line: Int): List<BridgeFact> =
        facts.filter { it.location.path.endsWith(file) && it.location.line == line }

    private fun List<BridgeFact>.keys(): Set<String> = map { listOfNotNull(it.channel, it.method).joinToString(".") + if (it.dynamic) "?" else "" }.toSet()

    private val job = """
        package app
        import jakarta.persistence.*
        @Entity
        @Table(name = "jobs")
        class Job(
            @Id var id: Long = 0,
            var title: String = "",
            var createdAt: Long = 0,
            @ManyToOne var owner: Account? = null,
        )
        @Entity
        class Account(@Id var accountId: Long = 0, var displayName: String = "")
    """

    @Test
    fun `entity declarations use the detected naming strategy`() {
        write("src/main/kotlin/app/Job.kt", job)
        val document = scan()
        assertEquals(setOf("jobs"), document.at("Job.kt", 3).keys())
        assertEquals(setOf("jobs.created_at"), document.at("Job.kt", 8).keys())
        assertEquals(setOf("jobs.owner_account_id"), document.at("Job.kt", 9).keys())
        assertEquals(setOf("account"), document.at("Job.kt", 11).keys())
        assertTrue(document.limitations.any { it.startsWith("jpa-naming-assumed: JPA table and column names follow spring-boot-3") })
    }

    @Test
    fun `unknown naming keeps divergent names dynamic and agreed names concrete`() {
        write("src/main/kotlin/app/Job.kt", job)
        val document = scan(naming = null)
        assertEquals(setOf("jobs.title"), document.at("Job.kt", 7).keys())
        assertEquals(setOf("jobs?"), document.at("Job.kt", 8).keys())
        assertTrue(document.limitations.any { it.startsWith("jpa-naming-unresolved: ") })
    }

    @Test
    fun `repository methods resolve derived queries jpql and native sql`() {
        write("src/main/kotlin/app/Job.kt", job)
        write(
            "src/main/kotlin/app/JobRepository.kt",
            """
            package app
            import org.springframework.data.jpa.repository.JpaRepository
            import org.springframework.data.jpa.repository.Query
            interface JobRepository : JpaRepository<Job, Long> {
                fun findByTitleIgnoreCaseAndCreatedAtGreaterThanOrderByIdDesc(title: String, since: Long): List<Job>
                fun countByOwnerDisplayName(name: String): Long
                @Query("select j from Job j join j.owner o where o.displayName = :name and j.title like :t")
                fun search(name: String, t: String): List<Job>
                @Query(value = "SELECT * FROM jobs WHERE archived = 1", nativeQuery = true)
                fun archived(): List<Job>
                @Query("select j from #{#entityName} j where j.createdAt < :before")
                fun older(before: Long): List<Job>
                fun customFragmentOperation(): Int
                fun helper(): Int = 1
            }
            """,
        )
        val document = scan()
        assertEquals(setOf("jobs"), document.at("JobRepository.kt", 4).keys())
        assertEquals(setOf("jobs", "jobs.title", "jobs.created_at", "jobs.id"), document.at("JobRepository.kt", 5).keys())
        assertEquals(setOf("jobs", "jobs.owner_account_id", "account", "account.display_name"), document.at("JobRepository.kt", 6).keys())
        assertEquals(setOf("jobs", "jobs.owner_account_id", "account", "account.display_name", "jobs.title"), document.at("JobRepository.kt", 8).keys())
        assertEquals(setOf("jobs"), document.at("JobRepository.kt", 10).keys())
        assertEquals(setOf("jobs", "jobs.created_at"), document.at("JobRepository.kt", 12).keys())
        assertEquals(setOf("customFragmentOperation?"), document.at("JobRepository.kt", 13).keys())
        assertTrue(document.at("JobRepository.kt", 14).isEmpty())
        // @Query 문자열의 대문자 SQL은 리터럴 스캐너가 다시 읽지 않는다.
        assertEquals(1, document.at("JobRepository.kt", 9).size + document.at("JobRepository.kt", 10).size)
        assertTrue(document.limitations.any { it.startsWith("unresolved-repository-methods: 1 ") })
        assertTrue(document.limitations.any { it.startsWith("repository-call-sites-need-snapshot: ") })
    }

    @Test
    fun `java repository with generic base interface and named query`() {
        write(
            "src/main/java/app/Note.java",
            """
            package app;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.NamedQuery;
            @Entity
            @NamedQuery(name = "Note.pinned", query = "select n from Note n " + "where n.pinnedFlag = true")
            public class Note {
                @Id Long id;
                boolean pinnedFlag;
                String bodyText;
            }
            """,
        )
        write(
            "src/main/java/app/BaseRepository.java",
            """
            package app;
            import java.util.List;
            import org.springframework.data.repository.NoRepositoryBean;
            import org.springframework.data.repository.CrudRepository;
            @NoRepositoryBean
            public interface BaseRepository<T, ID> extends CrudRepository<T, ID> {
                List<T> findByBodyTextContaining(String text);
            }
            """,
        )
        write(
            "src/main/java/app/NoteRepository.java",
            """
            package app;
            import java.util.List;
            public interface NoteRepository extends BaseRepository<Note, Long> {
                List<Note> pinned();
            }
            """,
        )
        val document = scan()
        assertEquals(setOf("note", "note.pinned_flag"), document.at("Note.java", 6).keys())
        assertEquals(setOf("note"), document.at("NoteRepository.java", 3).keys())
        assertEquals(setOf("note", "note.pinned_flag"), document.at("NoteRepository.java", 4).keys())
    }

    @Test
    fun `snapshot call sites carry the calling method identity`() {
        write("src/main/kotlin/app/Job.kt", job)
        write(
            "src/main/kotlin/app/JobRepository.kt",
            """
            package app
            import org.springframework.data.jpa.repository.JpaRepository
            interface JobRepository : JpaRepository<Job, Long> {
                fun findByTitle(title: String): List<Job>
            }
            """,
        )
        write(
            "src/main/kotlin/app/JobService.kt",
            """
            package app
            class JobService(private val repo: JobRepository) {
                fun save(job: Job) = repo.save(job)
                fun byTitle(title: String) = repo.findByTitle(title)
            }
            """,
        )
        val declared = "method:app/JobRepository#findByTitle(Ljava/lang/String;)Ljava/util/List;"
        val save = "method:app/JobService#save(Lapp/Job;)Ljava/lang/Object;"
        val byTitle = "method:app/JobService#byTitle(Ljava/lang/String;)Ljava/util/List;"
        val graph = CodeGraph(
            listOf(
                GraphNode(NodeId(declared), "findByTitle", NodeKind.METHOD, location = SourceLocation("src/main/kotlin/app/JobRepository.kt")),
                GraphNode(NodeId(save), "save", NodeKind.METHOD, location = SourceLocation("src/main/kotlin/app/JobService.kt", 3)),
                GraphNode(NodeId(byTitle), "byTitle", NodeKind.METHOD, location = SourceLocation("src/main/kotlin/app/JobService.kt", 4)),
            ),
            listOf(GraphEdge(NodeId(byTitle), NodeId(declared), EdgeKind.CALL)),
            externalCalls = listOf(
                ExternalCall(NodeId(save), "app/JobRepository", "save", "(Ljava/lang/Object;)Ljava/lang/Object;", InvocationKind.INTERFACE,
                    SourceLocation("JobService.kt", 3)),
                ExternalCall(NodeId(save), "org/springframework/data/repository/CrudRepository", "count", "()J", InvocationKind.INTERFACE),
            ),
        )
        val document = scan(graph = graph)
        val saveFacts = document.at("JobService.kt", 3)
        assertEquals(setOf("jobs"), saveFacts.keys())
        assertEquals(setOf(save), saveFacts.map { it.symbol?.usr }.toSet())
        val titleFacts = document.at("JobService.kt", 4)
        assertEquals(setOf("jobs", "jobs.title"), titleFacts.keys())
        assertEquals(setOf(byTitle), titleFacts.map { it.symbol?.usr }.toSet())
        assertEquals(setOf(declared), document.at("JobRepository.kt", 4).map { it.symbol?.usr }.toSet())
        assertTrue(document.limitations.any { it.startsWith("untyped-repository-calls: 1 ") })
        assertTrue(document.limitations.none { it.startsWith("repository-call-sites-need-snapshot") })
    }

    @Test
    fun `call sites without a project path are counted instead of guessed`() {
        write("src/main/kotlin/app/Job.kt", job)
        write(
            "src/main/kotlin/app/JobRepository.kt",
            """
            package app
            import org.springframework.data.jpa.repository.JpaRepository
            interface JobRepository : JpaRepository<Job, Long>
            """,
        )
        val caller = "method:app/JobService#save(Lapp/Job;)V"
        val graph = CodeGraph(
            listOf(GraphNode(NodeId(caller), "save", NodeKind.METHOD)),
            emptyList(),
            externalCalls = listOf(ExternalCall(NodeId(caller), "app/JobRepository", "save", "(Ljava/lang/Object;)Ljava/lang/Object;", InvocationKind.INTERFACE)),
        )
        val document = scan(graph = graph)
        assertTrue(document.limitations.any { it.startsWith("missing-repository-call-locations: 1 ") })
    }

    @Test
    fun `entity manager and spring jdbc template calls are read`() {
        write("src/main/kotlin/app/Job.kt", job)
        write(
            "src/main/kotlin/app/JobDao.kt",
            """
            package app
            import jakarta.persistence.EntityManager
            import org.springframework.jdbc.core.JdbcTemplate
            class JobDao(private val em: EntityManager, private val jdbc: JdbcTemplate) {
                fun load(id: Long) = em.find(Job::class.java, id)
                fun titles() = em.createQuery("select j.title from Job j", String::class.java).resultList
                fun raw() = em.createNativeQuery("select * from legacy_jobs")
                fun store(job: Job) = em.persist(job)
                fun count() = jdbc.queryForObject("select count(*) from jobs", Long::class.java)
                fun update(other: Other) = other.update("not sql")
            }
            class Other { fun update(value: String) = value }
            """,
        )
        val document = scan()
        assertEquals(setOf("jobs"), document.at("JobDao.kt", 5).keys())
        assertEquals(setOf("jobs", "jobs.title"), document.at("JobDao.kt", 6).keys())
        assertEquals(setOf("legacy_jobs"), document.at("JobDao.kt", 7).keys())
        assertEquals(setOf("job?"), document.at("JobDao.kt", 8).keys())
        assertEquals(setOf("jobs"), document.at("JobDao.kt", 9).keys())
        assertTrue(document.at("JobDao.kt", 10).isEmpty())
        assertTrue(document.limitations.any { it.startsWith("untyped-entity-manager-operations: 1 ") })
    }

    @Test
    fun `unmodelled mappings and non-jpa repositories stay visible`() {
        write(
            "src/main/java/app/Legacy.java",
            """
            package app;
            import jakarta.persistence.*;
            import java.util.Map;
            @Entity
            public class Legacy {
                @Id Long id;
                @Column(table = "legacy_extra") String extra;
                @ElementCollection Map<String, String> attributes;
                @Column(name = Names.COLUMN) String constantNamed;
            }
            """,
        )
        write(
            "src/main/kotlin/app/DocumentRepository.kt",
            """
            package app
            import org.springframework.data.repository.CrudRepository
            class Document(val id: String)
            interface DocumentRepository : CrudRepository<Document, String>
            """,
        )
        val document = scan()
        assertEquals(setOf("legacy?"), document.at("Legacy.java", 7).keys())
        assertEquals(setOf("legacy?"), document.at("Legacy.java", 8).keys())
        assertEquals(setOf("legacy?"), document.at("Legacy.java", 9).keys())
        assertTrue(document.limitations.any { it.startsWith("jpa-unmodelled-mappings: 3 ") })
        assertTrue(document.limitations.any { it.startsWith("non-jpa-repositories: 1 ") })
    }

    @Test
    fun `suspend call sites keep declared query columns and constants are not methods`() {
        write("src/main/kotlin/app/Job.kt", job)
        write(
            "src/main/kotlin/app/JobRepository.kt",
            """
            package app
            import org.springframework.data.repository.kotlin.CoroutineCrudRepository
            interface JobRepository : CoroutineCrudRepository<Job, Long> {
                suspend fun findByTitle(title: String): Job?
            }
            """,
        )
        write(
            "src/main/java/app/LegacyRepository.java",
            """
            package app;
            import java.util.List;
            import org.springframework.data.repository.CrudRepository;
            public interface LegacyRepository extends CrudRepository<Job, Long> {
                String DEFAULT = Status.normalize("open");
                List<Job> findAll();
            }
            """,
        )
        write("src/main/resources/META-INF/orm.xml", "<entity-mappings version=\"3.1\"/>")
        val caller = "method:app/JobService#byTitle(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        val declared = "method:app/JobRepository#findByTitle(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"
        val graph = CodeGraph(
            listOf(
                GraphNode(NodeId(declared), "findByTitle", NodeKind.METHOD),
                GraphNode(NodeId(caller), "byTitle", NodeKind.METHOD, location = SourceLocation("src/main/kotlin/app/JobService.kt", 7)),
            ),
            listOf(GraphEdge(NodeId(caller), NodeId(declared), EdgeKind.CALL)),
        )
        val document = scan(graph = graph)
        assertEquals(setOf("jobs", "jobs.title"), document.at("JobService.kt", 7).keys())
        assertEquals(setOf(declared), document.at("JobRepository.kt", 4).mapNotNull { it.symbol?.usr }.toSet())
        assertTrue(document.at("LegacyRepository.java", 5).isEmpty())
        assertEquals(setOf("jobs"), document.at("LegacyRepository.java", 6).keys())
        assertTrue(document.limitations.none { it.startsWith("unresolved-repository-methods") })
        assertTrue(document.limitations.any { it.startsWith("jpa-xml-mappings: 1 ") })
    }

    @Test
    fun `unknown naming profile is rejected`() {
        assertFailsWith<IllegalArgumentException> { SchemaFactScanner(project, "spring-boot-2") }
        assertEquals(listOf("spring-boot-3", "spring-boot-4", "hibernate-6", "hibernate-7"), JPA_NAMING_PROFILES)
    }
}
