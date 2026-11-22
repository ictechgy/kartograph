package dev.kartograph.index.fixture.jvmoverloadssuspend

/**
 * suspend 원본 `fetch(IILkotlin/coroutines/Continuation;)Ljava/lang/Object;`다. compiler는 JVM 인자 끝의 Continuation을 남긴 채
 * default 매개변수만 뺀 `fetch(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;`를 만든다. Kotlin 호출은 `fetch$default`로 간다.
 */
@JvmOverloads
suspend fun fetch(id: Int, retries: Int = 3): String = "fetched $id/$retries"

/**
 * 직접 작성한 확장 함수다. 원본 JVM 시그니처 `fetch(II)Ljava/lang/Object;`는 Continuation을 receiver처럼 앞쪽 인자로 보고
 * 위 suspend 함수의 생성 overload를 계산했을 때 나오는 값과 같다. 쓰이지 않으므로 생성물로 숨지 않고 보고되어야 한다.
 */
@JvmOverloads
fun Int.fetch(scale: Int = 1): Any = this * scale

/** suspend fetch의 Kotlin 호출자다. */
suspend fun callFetch(): String = fetch(1)
