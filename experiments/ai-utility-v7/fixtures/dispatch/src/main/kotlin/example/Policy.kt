package example

interface Policy { fun evaluate(value: Int): Boolean }

class StrictPolicy : Policy {
    override fun evaluate(value: Int): Boolean = value >= 0
}

class LoosePolicy : Policy {
    override fun evaluate(value: Int): Boolean = true
}
