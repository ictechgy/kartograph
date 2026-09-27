package dev.kartograph.index.fixture

/** 어휘적 소속(`EnclosingMethod`) 수집을 확인하는 합성 fixture다. 익명 객체와 class 기반 람다를 만든다. */
internal class LambdaEnclosureFixture {
    /** 초기화 문맥의 익명 객체는 감싼 메서드가 없어 class로 기록된다. */
    val initializerCallback: Runnable = object : Runnable {
        override fun run() = Unit
    }

    /** 메서드 안 익명 객체와 그 안의 class 기반 람다(중첩 소속)를 만든다. */
    fun register(): Runnable = object : Runnable {
        override fun run() {
            nestedLambda()()
        }

        fun nestedLambda(): () -> Unit = @JvmSerializableLambda { sink() }
    }

    /** class 기반 람다가 invoke 본문을 가진다. */
    fun serializableLambda(): () -> Unit = @JvmSerializableLambda { sink() }

    fun sink() = Unit
}
