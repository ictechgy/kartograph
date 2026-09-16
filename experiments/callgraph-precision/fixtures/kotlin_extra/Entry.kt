package probe
object Entry {
 interface Step { fun run() }
 object Marker { @JvmField var live=false; @JvmField var dormant=false }
 class Live : Step { override fun run(){Marker.live=true} }
 class Dormant : Step { override fun run(){Marker.dormant=true} }
 @JvmStatic fun execute(step: Step?) { step?.run() }
 @JvmStatic fun main(args: Array<String>?) { Dormant(); val step: Step=Live(); execute(step) }
}
