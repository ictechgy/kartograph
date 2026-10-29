package example

class Router {
    fun admit(policy: Policy, value: Int): Boolean = policy.evaluate(value)
}

class Portal {
    fun enter(policy: Policy, value: Int): String = if (Router().admit(policy, value)) "open" else "closed"
}

class StrictCheck {
    fun check(value: Int): Boolean = StrictPolicy().evaluate(value)
}
