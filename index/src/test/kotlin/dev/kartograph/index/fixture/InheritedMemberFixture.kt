package dev.kartograph.index.fixture

/** Kotlin compiler에서도 상속 field·super 호출의 실제 JVM owner를 검증한다. */
open class InheritedMemberBase {
    @JvmField var value: Int = 0
    open fun inherited(): Int = value
}

open class InheritedMemberMiddle : InheritedMemberBase()

class InheritedMemberChild : InheritedMemberMiddle() {
    fun readInherited(): Int = value
    fun callSuper(): Int = super.inherited()
    fun callVirtual(value: InheritedMemberMiddle): Int = value.inherited()
}
