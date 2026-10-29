@file:JvmName("AuthoredCalls")
package authored
class Printable { override fun toString(): String = "printable" }
fun implicit(receiver: Printable): String = "value=$receiver"
fun explicit(receiver: Printable): String = "value=${receiver.toString()}"
fun zero(): String = "literal"
fun local(receiver: Printable): String { val copy = receiver; return "value=$copy" }
fun choose(flag: Boolean, receiver: Printable): String {
    if (flag) return receiver.toString()
    return "empty"
}
@JvmName("renamed") fun renamedSource(receiver: Printable): String = receiver.toString()
fun overload(receiver: Printable): String = receiver.toString()
fun overload(receiver: String): String = receiver
class Nested { class Owner { fun call(receiver: Printable): String = receiver.toString() } }
fun unsupportedLambda(receiver: Printable): String = run { receiver.toString() }
fun unsupportedProperty(receiver: String): Int = receiver.length
fun unsupportedOperator(left: Int, right: Int): Int = left + right
fun unsupportedDefault(receiver: Printable = Printable()): String = receiver.toString()
suspend fun unsupportedSuspend(receiver: Printable): String = receiver.toString()
fun unsupportedExternal(receiver: Any): String = receiver.toString()
