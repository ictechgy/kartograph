package example

class Formatter {
    fun format(value: Int, render: (amount: Int, currency: String) -> String): String = render(value, "USD")
}
