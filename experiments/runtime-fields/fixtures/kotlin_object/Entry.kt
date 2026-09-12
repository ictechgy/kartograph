package probe

object Holder {
    @JvmField var type: Class<*> = Target::class.java
}

class Target { init { Used() } }
class Used { init { println("USED") } }
class Unused

object Entry {
    @JvmStatic
    fun main(args: Array<String>) {
        val type = Holder::class.java.getDeclaredField("type").get(null) as Class<*>
        type.getDeclaredConstructor().newInstance()
    }
}
