package probe.helpercompanion
class Names { companion object { fun value(): String = "probe.helpercompanion.Used" } }
class Used { init { println("USED") } }
class Unused { init { println("UNUSED") } }
object Entry { @JvmStatic fun main(args: Array<String>) { Class.forName(Names.value()).getDeclaredConstructor().newInstance() } }
