package dev.kartograph.cli.fixture

object KotlinReturnEntry {
    var observed = false

    @JvmStatic
    fun main(args: Array<String>) {
        Class.forName(returnFixtureName("KotlinReturnUsed")).getDeclaredConstructor().newInstance()
    }
}

class KotlinReturnUsed {
    init { KotlinReturnEntry.observed = true }
}

class KotlinReturnUnused

private fun returnFixtureName(suffix: String): String = returnFixturePrefix() + suffix
private fun returnFixturePrefix(): String = "dev.kartograph.cli.fixture."
