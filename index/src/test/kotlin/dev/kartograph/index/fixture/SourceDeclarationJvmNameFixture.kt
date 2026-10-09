package dev.kartograph.index.fixture

import kotlin.jvm.JvmName as Rename

class SourceDeclarationJvmNameFixture {
    @Rename("renamed")
    fun original(): Int = 1

    @[Rename("original")]
    fun renamed(): Int = 2
}

class SourceDeclarationBodylessFixture(value: Int) {
    class Nested
        constructor(value: Int) : SourceDeclarationBase(value)
}

open class SourceDeclarationBase(value: Int)
