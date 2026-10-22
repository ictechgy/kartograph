package dev.kartograph.index.fixture

enum class EnumInventoryKotlinFixture {
    BETA,
    ALPHA {
        override fun label(): String = "alpha"
    };

    open fun label(): String = name

    companion object {
        @JvmField val ALIAS: EnumInventoryKotlinFixture = BETA
    }
}

enum class EmptyEnumInventoryKotlinFixture {
    ;
}
