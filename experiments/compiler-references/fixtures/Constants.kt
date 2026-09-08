@file:JvmName("NamedConstants")
package probe
const val TOP_USED = "same"
const val TOP_UNUSED = "same"
object Constants { const val USED = "same"; const val UNUSED = "same" }
class Holder { companion object { const val USED = "same"; const val UNUSED = "same" } }
fun readObject(): String = Constants.USED
fun readTop(): String = TOP_USED
fun readCompanion(): String = Holder.USED
fun readShadow(): String { val USED = "same"; return USED }
