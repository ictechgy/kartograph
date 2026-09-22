package example

import org.junit.Assert.assertEquals
import org.junit.Test

class GateTest {
    @Test fun `eighteenth birthday`() { assertEquals(true, Gate().allow(18)) }
    @Test fun `nineteenth birthday`() { assertEquals(true, Gate().allow(19)) }
    @Test fun `younger applicant`() { assertEquals(false, Gate().allow(17)) }
    @Test fun `member code`() { assertEquals(true, Gate().allow("adult")) }
    @Test fun `portal birthday message`() { assertEquals("open", Portal().access(18)) }
    @Test fun `preview birthday label`() { assertEquals("adult", Preview().label(18)) }
    @Test fun `portal younger message`() { assertEquals("closed", Portal().access(17)) }
}
