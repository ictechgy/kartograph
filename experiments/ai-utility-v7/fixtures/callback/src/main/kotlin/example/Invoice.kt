package example

class Invoice {
    fun total(value: Int): String = Formatter().format(value) { amount, currency -> "$currency:$amount" }
}

class Relay {
    fun dispatch(value: Int, emit: (amount: Int, currency: String) -> String): String = Formatter().format(value, emit)
}

class Receipt {
    fun text(value: Int): String = Invoice().total(value) + " paid"
}

class Report {
    fun currency(value: Int): String = Formatter().format(value) { _, currency -> currency }
    fun positive(value: Int): String = Relay().dispatch(value) { amount, _ -> (amount > 0).toString() }
}
