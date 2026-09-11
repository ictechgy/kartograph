package probe

object Entry {
    @JvmStatic
    fun main(args: Array<String>) {
        Class.forName(name("Used")).getDeclaredConstructor().newInstance()
    }
}

class Used {
    init { println("USED") }
}

class Unused

private fun name(suffix: String): String = prefix() + suffix
private fun prefix(): String = "probe."
