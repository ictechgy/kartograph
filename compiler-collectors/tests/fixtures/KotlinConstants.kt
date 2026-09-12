@file:JvmName("RenamedConstants")
package fixture

const val TOP_USED = "same"
const val TOP_UNUSED = "same"

object Constants {
    const val USED = "same"
    const val UNUSED = "same"
}

class Holder {
    companion object {
        const val USED = "same"
        const val UNUSED = "same"
    }
}

@JvmName("renamedRead")
fun readJvmName(): String = TOP_USED

fun readObject(): String = Constants.USED

fun readTop(): String = TOP_USED

fun readCompanion(): String = Holder.USED

fun overloaded(value: Int): String = Constants.USED + value

fun overloaded(value: String): String = TOP_USED + value

fun readShadow(): String {
    val USED = "same"
    return USED
}
