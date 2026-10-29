package probe

class PsiKotlin {
    fun leaf(value: Int): Int = value
    fun leaf(value: String): String = value
    fun caller(): Int = PsiJava.leaf(leaf(7))
    fun unused(): Int = 0
}
