package probe
const val TOP = 3
object ConstantObject { const val USED = 7 }
object UnusedConstantObject { const val UNUSED = 9 }
class CompanionOwner { companion object { const val USED = 11 } }
class CallbackBody { init { println("KOTLIN_CALLBACK_EXECUTED") } }
object KotlinEntry {
 @JvmStatic fun main(args: Array<String>) {
  println(TOP + ConstantObject.USED + CompanionOwner.USED)
  lib.Callbacks.run { CallbackBody() }
 }
}
