package example

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test fun `unnamed visitor`() { assertEquals("hello guest", Greeting().render(null as String?)) }
    @Test fun `named visitor`() { assertEquals("hello Ada", Greeting().render("Ada")) }
    @Test fun `empty visitor name`() { assertEquals("hello ", Greeting().render("")) }
    @Test fun `numbered member`() { assertEquals("hello member-7", Greeting().render(7)) }
    @Test fun `audit of unnamed visitor`() { assertEquals("GUEST", Audit().tag(null)) }
    @Test fun `welcome of unnamed visitor`() { assertEquals("hello guest!", Welcome().message(null)) }
    @Test fun `audit of named visitor`() { assertEquals("ADA", Audit().tag("Ada")) }
}
