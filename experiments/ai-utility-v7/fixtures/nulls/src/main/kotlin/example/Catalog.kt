package example

class Catalog {
    fun label(name: String?): String = name ?: "guest"
    fun label(number: Int): String = "member-$number"
}
