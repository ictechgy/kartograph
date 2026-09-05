package dev.kartograph.fixture

class PrivateMemberFixture {
    private var usedValue: Int = 0
    private var writtenOnly: Int = 0
    private var unusedValue: Int = 0
    private var customValue: Int = 0
        get() = field + 1

    fun entry(): Int {
        writtenOnly = 1
        return used() + inlined { 1 } + customValue
    }

    private fun used(): Int = ++usedValue
    private fun unused(): Int = 42
    private fun reflected(): Int = 7
    private inline fun inlined(block: () -> Int): Int = block()
}
