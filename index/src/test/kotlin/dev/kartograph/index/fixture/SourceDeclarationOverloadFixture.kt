package dev.kartograph.index.fixture

class SourceDeclarationOverloadFixture {
    fun leaf(value: Int): Int = value
    fun leaf(value: String): String = value
    fun qualified(value: kotlin.Int): kotlin.Int = value
    fun qualified(value: kotlin.String): kotlin.String = value
}
