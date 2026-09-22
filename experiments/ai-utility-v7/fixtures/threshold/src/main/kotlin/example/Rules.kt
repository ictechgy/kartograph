package example

class Rules {
    fun qualifies(age: Int): Boolean = age >= 18
    fun qualifies(code: String): Boolean = code == "adult"
}
