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

    @Test
    fun `sqldelight label does not swallow update relation`() {
        project.resolve("UserQueries.sq").writeText(
            """
            selectAll:
            SELECT * FROM users;
            markAdult:
            UPDATE users SET adult = 1;
            """.trimIndent(),
        )
        val doc = scan()
        // 라벨 다음의 UPDATE도 관계를 낸다 — 라벨이 문장 머리를 차지하면 안 된다.
        assertEquals(2, doc.facts.count { it.channel == "users" && !it.dynamic })
    }

    @Test
    fun `prose literal does not fabricate relations`() {
        project.resolve("Help.kt").writeText(
            """
            val help = "Select an option from the menu below"
            val lower = "select * from users"
            """.trimIndent(),
        )
        val doc = scan()
        // 게이트 없는 리터럴은 strict 모드다 — 혼합·소문자 키워드는 산문으로 본다.
        assertTrue(doc.facts.isEmpty())
    }

    @Test
    fun `char literal does not corrupt the masked view`() {
        project.resolve("User.kt").writeText(
            """
            import androidx.room.Entity
            val quote: Char = '"'
            @Entity(tableName = "users")
            data class User(val id: Long)
            """.trimIndent(),
        )
        val doc = scan()
        // `'"'`가 문자열로 오인되면 뒤의 선언이 통째로 사라진다 — 살아 있어야 한다.
        assertTrue(doc.facts.any { it.channel == "users" && it.method == "id" })
    }

    @Test
    fun `no-arg gated call emits nothing`() {
        project.resolve("Repo.kt").writeText(
            """
            import java.sql.Statement
            class Repo(private val stmt: Statement) {
                fun run() { stmt.execute() }
            }
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.isEmpty())
        assertNull(doc.target)
        // 무인자 호출은 관계 피연산자가 없다 — 동적 사실도 limitation도 안 나온다.
        assertTrue(doc.limitations.none { it.startsWith("unjoined-dynamic-relations") })
    }

    @Test
    fun `comparison expression argument is not a named argument`() {
        project.resolve("Repo.kt").writeText(
            """
            import java.sql.Connection
            class Repo(private val conn: Connection) {
                fun run() { conn.execute(id == 1) }
            }
            """.trimIndent(),
        )
        val doc = scan()
        // `id == 1`은 named argument가 아니다 — 식이 버려지지 않고 동적 근거로 남는다.
        assertTrue(doc.facts.any { it.dynamic })
    }

    @Test
    fun `use-site targeted column info is read`() {
        project.resolve("User.kt").writeText(
            """
            import androidx.room.Entity
            import androidx.room.ColumnInfo
            @Entity(tableName = "users")
            class User {
                @field:ColumnInfo(name = "email_addr")
                val email: String? = null
            }
            """.trimIndent(),
        )
        val doc = scan()
        assertTrue(doc.facts.any { it.channel == "users" && it.method == "email_addr" })
    }

    @Test
    fun `nested class constructor properties are not outer columns`() {
        project.resolve("Outer.kt").writeText(
            """
            import androidx.room.Entity
            @Entity(tableName = "t")
            data class Outer(val a: Long) {
                data class Inner(val b: Long)
            }
            """.trimIndent(),
        )
        val doc = scan()
        val methods = doc.facts.filter { it.channel == "t" }.map { it.method }
        assertTrue("a" in methods)
        assertTrue("b" !in methods)
    }

    @Test
    fun `dynamic exposed column name stays dynamic on its table`() {
        project.resolve("Tables.kt").writeText(
            """
            import org.jetbrains.exposed.sql.Table
            object Users : Table("users") {
                val label = varchar(columnName, 50)
            }
            """.trimIndent(),
        )
        val doc = scan()
        // 컬럼명이 식별자면 읽히지 않지만 테이블 귀속은 확실하다 — 동적 근거로 남긴다.
        assertTrue(doc.facts.any { it.channel == "users" && it.dynamic })
    }
}
