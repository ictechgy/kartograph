package probe.helperobject
object Names { fun value(ignored: Long, suffix: String): String = "probe.helperobject." + suffix }
class Used { init { println("USED") } }
class Unused { init { println("UNUSED") } }
object Entry { @JvmStatic fun main(args: Array<String>) { Class.forName(Names.value(7L, "Used")).getDeclaredConstructor().newInstance() } }
