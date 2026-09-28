package dev.kartograph.index

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class JpaQueriesTest {
    @TempDir
    lateinit var project: Path

    private fun model(vararg sources: Pair<String, String>): Pair<JpaEntityModel, List<JpaSourceFile>> {
        val root = project.toRealPath()
        val files = sources.map { (name, text) ->
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            path.writeText(text.trimIndent())
            JpaSourceFile(root, path)
        }
        return JpaEntityModel.build(files) to files
    }

    private val boot = JpaNamingContext.fixed(JpaNamingProfile.SPRING_BOOT_3, "test")

    private fun JpaTouch.names(): Set<String> =
        tables.mapNotNull(boot::table).toSet() + columns.mapNotNull { column ->
            boot.table(column.table)?.let { table -> boot.column(column.column)?.let { "$table.$it" } }
        }

    private val entities = "Order.kt" to """
        package shop
        import jakarta.persistence.*
        @Entity
        @Table(name = "orders")
        class Order(
            @Id var id: Long = 0,
            var placedAt: Long = 0,
            @Embedded var shipTo: Address? = null,
            @ManyToOne var customer: Customer? = null,
            @OneToMany(mappedBy = "order") var lines: List<OrderLine> = emptyList(),
        )
        @Embeddable
        class Address(var zipCode: String = "", var city: String = "")
        @Entity
        class Customer(@Id var id: Long = 0, var emailAddress: String = "")
        @Entity
        class OrderLine(@Id var id: Long = 0, var quantity: Int = 0, @ManyToOne var order: Order? = null)
    """

    @Test
    fun `derived query names follow spring data part tree rules`() {
        assertEquals(listOf("placedAt", "id"), DerivedQuery.parse("findByPlacedAtBetweenOrderByIdDesc")?.properties)
        assertEquals(listOf("customerEmailAddress", "shipToZipCode"), DerivedQuery.parse("findDistinctTop3ByCustomerEmailAddressIgnoreCaseOrShipToZipCodeIsNotNull")?.properties)
        assertEquals(listOf("city", "zipCode"), DerivedQuery.parse("existsByCityAndZipCodeAllIgnoreCase")?.properties)
        assertEquals(listOf("placedAt", "id"), DerivedQuery.parse("deleteByPlacedAtLessThanOrderByIdAscPlacedAtDesc")?.properties?.take(2))
        assertNull(DerivedQuery.parse("recalculateTotals"))
        assertNull(DerivedQuery.parse("findByAOrderByBOrderByC"))
    }

    @Test
    fun `derived property paths split camel case humps against the model`() {
        val (model, _) = model(entities)
        val paths = JpaPathResolver(model)
        val order = model.entity("Order")!!
        val touch = JpaTouch()
        listOf("customerEmailAddress", "shipToZipCode", "lines_quantity").forEach { property ->
            paths.touch(paths.derived(order, property)!!, touch)
        }
        assertEquals(
            setOf("orders.customer_id", "customer", "customer.email_address", "orders", "orders.zip_code", "order_line",
                "order_line.order_id", "order_line.quantity"),
            touch.names(),
        )
        assertNull(paths.derived(order, "missingProperty"))
    }

    @Test
    fun `jpql resolves aliases joins collection members and implicit roots`() {
        val (model, _) = model(entities)
        val jpql = JpqlResolver(model, JpaPathResolver(model))
        assertEquals(
            setOf("orders", "orders.placed_at", "orders.customer_id", "customer", "customer.email_address"),
            jpql.resolve("SELECT o FROM Order o LEFT JOIN FETCH o.customer c WHERE c.emailAddress = :e AND o.placedAt > ?1").names(),
        )
        assertEquals(
            setOf("orders", "order_line", "order_line.order_id", "order_line.quantity", "orders.city"),
            jpql.resolve("select o from Order o, IN(o.lines) l where l.quantity > 2 and o.shipTo.city = 'x''y'").names(),
        )
        assertEquals(setOf("orders", "orders.placed_at"), jpql.resolve("from Order where placedAt > :t").names())
        val unresolved = jpql.resolve("select x from Unknown x where x.a = 1 and exists (select c from Customer c where c.nope = 1)")
        assertEquals(listOf("Unknown", "c.nope"), unresolved.unresolved)
        assertEquals(setOf("customer"), unresolved.names())
    }

    @Test
    fun `literal concatenation and descriptor helpers`() {
        assertEquals("select a from B a", concatenatedLiteral("\"select a \" + \"from B a\""))
        assertEquals("x", concatenatedLiteral("\"\"\"x\"\"\""))
        assertNull(concatenatedLiteral("\"select \" + suffix"))
        assertEquals(3, parameterCount("(J[Ljava/lang/String;Z)V"))
        assertEquals(0, parameterCount("method:a/B#c()V"))
        assertEquals(Triple("a/B", "c", "(I)V"), parseMethodId("method:a/B#c(I)V"))
        assertNull(parseMethodId("class:a/B"))
    }

    @Test
    fun `source model reads kotlin backing fields companions and bodyless classes`() {
        val (_, files) = model(
            "Model.kt" to """
                package m
                import jakarta.persistence.*
                @Entity class Plain(@Id val id: Long, val name: String)
                @Entity
                class Rich {
                    @Id var id: Long = 0
                    lateinit var label: String
                    val computed: String
                        get() = label
                    var tracked: String = ""
                        get() = field.trim()
                    val lazyValue by lazy { "x" }
                    companion object { val SHARED = 1 }
                }
            """,
        )
        val types = files.single().types.associateBy { it.name }
        assertEquals(listOf("id", "name"), types.getValue("Plain").members.map { it.name })
        val rich = types.getValue("Rich").members.associateBy { it.name }
        assertEquals(setOf("id", "label", "computed", "tracked", "lazyValue"), rich.keys)
        assertTrue(rich.getValue("label").persistent)
        assertTrue(!rich.getValue("computed").persistent)
        assertTrue(rich.getValue("tracked").persistent)
        assertTrue(rich.getValue("lazyValue").delegated)
    }
}
