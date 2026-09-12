package fixture

object MemberConstants {
    const val USED = "same"
    const val UNUSED = "same"
}

class Reader { fun read(): String = MemberConstants.USED }
object Reader2 { val x = 1; fun read(): String = MemberConstants.USED }
class NamedHolder { companion object Named { const val USED = "same" } }
fun readNamed(): String = NamedHolder.USED
private fun readPrivate(): String = MemberConstants.USED
