package example

class Greeting {
    fun render(name: String?): String = "hello " + Catalog().label(name)
    fun render(number: Int): String = "hello " + Catalog().label(number)
}

class Audit {
    fun tag(name: String?): String = Catalog().label(name).uppercase()
}

class Welcome {
    fun message(name: String?): String = Greeting().render(name) + "!"
}
