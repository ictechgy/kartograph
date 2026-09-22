package example

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceTest {
    @Test fun `invoice total`() { assertEquals("USD:8", Invoice().total(8)) }
    @Test fun `receipt text`() { assertEquals("USD:8 paid", Receipt().text(8)) }
    @Test fun `currency label`() { assertEquals("USD", Report().currency(8)) }
    @Test fun `positive amount`() { assertEquals("true", Report().positive(8)) }
    @Test fun `zero amount`() { assertEquals("false", Report().positive(0)) }
    @Test fun `negative amount`() { assertEquals("false", Report().positive(-2)) }
}
