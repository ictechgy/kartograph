package dev.kartograph.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.io.path.writeText
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SchemaFactScannerTest {

    @TempDir
    lateinit var project: Path

    private fun scan(): dev.kartograph.core.BridgeFactsDocument =
        SchemaFactScanner(project).scan(generatedAt = "2026-01-01T00:00:00Z")

    @Test
    fun `room query emits relation uses`() {
        project.resolve("Dao.kt").writeText(
            """
            import androidx.room.Query
            import androidx.room.Dao
            @Dao
            interface UserDao {
                @Query("SELECT * FROM users WHERE id = :id")
                fun find(id: Long): Any
                @Query("SELECT * FROM orders o JOIN users u ON o.user_id = u.id")
                fun joined(): List<Any>
            }
            """.trimIndent(),
        )
        val doc = scan()
        assertEquals("persistence", doc.target)
        assertEquals("kotlin", doc.platform)
        val channels = doc.facts.map { it.channel }
        assertTrue("users" in channels)
        assertTrue("orders" in channels)
    }

    @Test
    fun `room entity tableName and columns`() {
        project.resolve("User.kt").writeText(
            """
            import androidx.room.Entity
            import androidx.room.ColumnInfo
            @Entity(tableName = "app_users")
            data class User(
                val id: Long,
                @ColumnInfo(name = "display_name") val name: String,
            )
            """.trimIndent(),
        )
        val doc = scan()
        val entityFact = doc.facts.single { it.channel == "app_users" && it.method == null }
        assertEquals(false, entityFact.dynamic)
        val columns = doc.facts.filter { it.channel == "app_users" && it.method != null }.map { it.method }
        assertTrue("id" in columns)
        assertTrue("display_name" in columns)
    }

    @Test
    fun `insert resolves entity parameter to table`() {
        project.resolve("Models.kt").writeText(
            """
            import androidx.room.Entity
            @Entity(tableName = "users")
            data class User(val id: Long)
            """.trimIndent(),
        )
        project.resolve("Dao.kt").writeText(
            """
            import androidx.room.Insert
            interface UserDao {
                @Insert
                fun insert(user: User)
                @Insert
                fun insertAll(users: List<User>)
            }
            """.trimIndent(),
        )
        val doc = scan()
        val inserts = doc.facts.filter { it.channel == "users" && it.location.path == "Dao.kt" }
        assertEquals(2, inserts.size)
    }

    @Test
    fun `unresolvable entity type stays dynamic and counted`() {
        project.resolve("Dao.kt").writeText(
            """
            import androidx.room.Delete
            interface Dao {
                @Delete
                fun remove(item: External)
            }
            """.trimIndent(),
        )
        val doc = scan()
        val fact = doc.facts.single()
        assertTrue(fact.dynamic)
        assertEquals("External", fact.channel)
        assertTrue(doc.limitations.any { it.startsWith("unresolved-room-entities: 1") })
    }

    @Test
    fun `jdbc call arguments are scanned`() {
        project.resolve("Repo.kt").writeText(
            """
            import java.sql.Connection
            class Repo(private val conn: Connection) {
                fun run() {
                    conn.prepareStatement("SELECT * FROM inventory WHERE sku = ?")
                    conn.createStatement().executeUpdate("UPDATE inventory SET count = 0")
                }
            }
            """.trimIndent(),
        )
        val doc = scan()
        val uses = doc.facts.filter { it.channel == "inventory" }
        assertEquals(2, uses.size)
        assertTrue(uses.all { !it.dynamic })
    }

    @Test
    fun `jdbc gate keeps ungated call arguments out`() {
        project.resolve("Misc.kt").writeText(
            """
            class Misc {
                fun run() {
                    helper.prepareStatement("SELECT * FROM phantom")
                }
            }
            """.trimIndent(),
        )
        // java.sql import가 없으면 prepareStatement도 스캔 대상이 아니다 —
        // 리터럴 자체가 SQL 모양이므로 일반 리터럴 경로에서만 읽힌다.
        val doc = scan()
        assertEquals(listOf("phantom"), doc.facts.map { it.channel })
    }

    @Test
    fun `exposed table object and dsl receivers`() {
        project.resolve("Tables.kt").writeText(
            """
            import org.jetbrains.exposed.sql.Table
            import org.jetbrains.exposed.sql.selectAll
            object Users : Table("users") {
                val id = integer("id")
                val email = varchar("email", 255)
            }
            object Orders : Table("orders") {
                val id = integer("id")
                val userId = reference("user_id", Users)
            }
            fun list() {
                Users.selectAll()
                Users.innerJoin(Orders)
            }
            """.trimIndent(),
        )
        val doc = scan()
        val channels = doc.facts.map { it.channel }
        assertTrue("users" in channels)
        assertTrue("orders" in channels)
        val columns = doc.facts.filter { it.channel == "users" }.mapNotNull { it.method }
        assertTrue("id" in columns && "email" in columns)
    }

    @Test
    fun `jooq table and fetch calls`() {
        project.resolve("JooqRepo.kt").writeText(
            """
            import org.jooq.DSLContext
            class JooqRepo(private val dsl: DSLContext) {
                fun run() {
                    dsl.table("public.accounts")
                    dsl.fetch("SELECT * FROM ledger")
                }
            }
            """.trimIndent(),
        )
        val doc = scan()
        val channels = doc.facts.map { it.channel }
        assertTrue("public.accounts" in channels)
        assertTrue("ledger" in channels)
    }

    @Test
    fun `sqldelight file is scanned`() {
        project.resolve("UserQueries.sq").writeText(
            """
            selectAll:
            SELECT * FROM users;
            insertUser:
            INSERT INTO users(name) VALUES (?);
            """.trimIndent(),
        )
        val doc = scan()
        val uses = doc.facts.filter { it.channel == "users" }
        assertTrue(uses.size >= 2)
        assertTrue(doc.limitations.any { it.startsWith("sqldelight-query-files: 1") })
    }

    @Test
    fun `interpolated sql literal is dynamic with prefix`() {
        project.resolve("Dyn.kt").writeText(
            """
            fun q(table: String) = "SELECT * FROM ${'$'}table WHERE id = 1"
            """.trimIndent(),
        )
        val doc = scan()
        val fact = doc.facts.single()
        assertTrue(fact.dynamic)
        assertEquals("SELECT * FROM", fact.channelPrefix)
        assertTrue(doc.limitations.any { it.startsWith("unjoined-dynamic-relations: 1") })
    }

    @Test
    fun `comments and prose strings are not scanned`() {
        project.resolve("Notes.kt").writeText(
            """
            // SELECT * FROM fake_comments
            /* DELETE FROM fake_block */
            val note = "please update the table before lunch"
            val s = "grant select on the report to auditors"
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.none { it.channel?.contains("fake") == true })
        assertTrue(doc.facts.none { it.channel == "the" || it.channel == "report" })
    }

    @Test
    fun `unresolved placeholder operand is counted`() {
        project.resolve("Tpl.kt").writeText(
            """
            val sql = "SELECT * FROM {} WHERE id = ?"
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.any { it.dynamic })
        assertTrue(doc.limitations.any { it.startsWith("unjoined-dynamic-relations: 1") })
    }

    @Test
    fun `empty project emits null target`() {
        project.resolve("Empty.kt").writeText("class Empty")
        val doc = scan()
        assertNull(doc.target)
        assertTrue(doc.facts.isEmpty())
    }

    @Test
    fun `java source is scanned`() {
        project.resolve("Repo.java").writeText(
            """
            import java.sql.Connection;
            class Repo {
                void run(Connection conn) throws Exception {
                    conn.prepareStatement("DELETE FROM sessions WHERE expired = 1");
                }
            }
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.any { it.channel == "sessions" })
    }

    @Test
    fun `room query with placeholder table operand stays honest`() {
        project.resolve("Dao.kt").writeText(
            """
            import androidx.room.Query
            interface Dao {
                @Query("SELECT * FROM {} WHERE id = :id")
                fun weird(id: Long): Any
            }
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.single().dynamic)
    }

    @Test
    fun `raw string sql literal is scanned`() {
        // Kotlin raw string 안에 `"""`를 넣을 수 없어 연결로 만든다.
        val q = "\"\"\""
        project.resolve("Raw.kt").writeText(
            "val sql = ${q}SELECT * FROM raw_table\n    JOIN other ON other.id = raw_table.id$q\n",
        )
        val doc = scan()
        val channels = doc.facts.map { it.channel }
        assertTrue("raw_table" in channels && "other" in channels)
    }

    @Test
    fun `deterministic ordering`() {
        project.resolve("A.kt").writeText(
            """
            val a = "SELECT * FROM zeta"
            val b = "DELETE FROM alpha"
            """.trimIndent(),
        )
        val first = scan()
        val second = scan()
        assertEquals(first.facts, second.facts)
        assertEquals(listOf("zeta", "alpha"), first.facts.map { it.channel })
    }

    @Test
    fun `foreign key carries parent and child columns`() {
        project.resolve("Pet.kt").writeText(
            """
            import androidx.room.Entity
            import androidx.room.ForeignKey
            import androidx.room.ColumnInfo
            @Entity(tableName = "owners")
            data class Owner(val id: Long)
            @Entity(
                tableName = "pets",
                foreignKeys = [ForeignKey(
                    entity = Owner::class,
                    parentColumns = ["id"],
                    childColumns = ["owner_id"],
                )],
            )
            data class Pet(val id: Long, @ColumnInfo(name = "owner_id") val ownerId: Long)
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.any { it.channel == "owners" && it.method == "id" })
        assertTrue(doc.facts.any { it.channel == "pets" && it.method == "owner_id" })
    }
}
