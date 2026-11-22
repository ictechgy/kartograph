package dev.kartograph.index.fixture.suspendinlinecopy

/**
 * 쓰이는 public suspend inline 함수다. compiler는 상태 기계가 있는 `doubled(ILkotlin/coroutines/Continuation;)`와 함께
 * inliner가 복사해 갈 private 사본 `doubled$$forInline`을 같은 descriptor로 남긴다. 사본은 호출되지 않는다.
 */
suspend inline fun doubled(value: Int): Int = value * 2

/** `@JvmName`으로 바꾼 JVM 이름에도 사본 접미사가 붙는다(`renamedTriple$$forInline`). */
@JvmName("renamedTriple")
suspend inline fun tripled(value: Int): Int = value * 3

/** suspend가 아닌 inline 함수다. compiler 사본이 없다. */
@Suppress("NOTHING_TO_INLINE")
inline fun quadrupled(value: Int): Int = value * 4

/**
 * 직접 작성한 private 함수다. JVM 이름은 위 inline 함수의 사본 꼴이지만 원본이 suspend가 아니어서 compiler 사본이 아니다.
 * 쓰이지 않으므로 private 보고에 남아야 한다.
 */
@JvmName("quadrupled\$\$forInline")
private fun authoredInlineLookalike(value: Int): Int = value

/** suspend지만 inline이 아닌 함수다. compiler 사본이 없다. */
suspend fun plainSuspend(value: Int): Int = value + 1

/**
 * 직접 작성한 private suspend 함수다. JVM 이름과 descriptor가 위 suspend 함수의 사본 꼴이지만 원본이 inline이 아니어서
 * compiler 사본이 아니므로 생성물로 표시하지 않는다. compiler가 public `access$plainSuspend$$forInline` accessor를 함께 만들어
 * private 보고에서는 그 accessor를 거쳐 도달한다.
 */
@JvmName("plainSuspend\$\$forInline")
private suspend fun authoredSuspendLookalike(value: Int): Int = value

/** member suspend inline 함수의 사본도 같은 owner에 private으로 남는다. */
class Doubler {
    /** 사본 `twice$$forInline`을 남기는 member 함수다. */
    suspend inline fun twice(value: Int): Int = value * 2
}

/** 위 함수들의 Kotlin 호출자다. inline 함수 본문은 이 안에 펼쳐지므로 원본·사본 어느 쪽도 호출하지 않는다. */
suspend fun callAll(): Int = doubled(1) + tripled(2) + quadrupled(3) + plainSuspend(4) + Doubler().twice(5)
