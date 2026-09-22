package example

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterTest {
    @Test fun `strict zero`() { assertEquals(true, Router().admit(StrictPolicy(), 0)) }
    @Test fun `loose zero`() { assertEquals(true, Router().admit(LoosePolicy(), 0)) }
    @Test fun `strict positive`() { assertEquals(true, Router().admit(StrictPolicy(), 1)) }
    @Test fun `strict negative`() { assertEquals(false, Router().admit(StrictPolicy(), -1)) }
    @Test fun `strict portal at zero`() { assertEquals("open", Portal().enter(StrictPolicy(), 0)) }
    @Test fun `loose portal at zero`() { assertEquals("open", Portal().enter(LoosePolicy(), 0)) }
    @Test fun `strict direct check`() { assertEquals(true, StrictCheck().check(0)) }
}
