package example

class Gate {
    fun allow(age: Int): Boolean = Rules().qualifies(age)
    fun allow(code: String): Boolean = Rules().qualifies(code)
}

class Portal {
    fun access(age: Int): String = if (Gate().allow(age)) "open" else "closed"
}

class Preview {
    fun label(age: Int): String = if (Rules().qualifies(age)) "adult" else "minor"
}
