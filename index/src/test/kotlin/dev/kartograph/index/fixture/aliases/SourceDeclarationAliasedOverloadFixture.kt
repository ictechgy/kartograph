package dev.kartograph.index.fixture.aliases

class SourceDeclarationAliasedOverloadFixture {
    fun leaf(value: Int): Int = value
    fun leaf(value: String): String = value
}
