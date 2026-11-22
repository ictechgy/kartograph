package dev.kartograph.index.fixture.jvmoverloadsmangled

/** 매개변수에 쓰면 compiler가 함수의 JVM 이름을 `name-hash`로 바꾸는 value class다. */
@JvmInline
value class Token(val raw: Int) {
    /** value class 멤버는 정적 `scaled-impl(III)I`가 되고 생성 overload `scaled-impl(II)I`도 같은 이름을 쓴다. */
    @JvmOverloads
    fun scaled(by: Int, offset: Int = 0): Int = raw * by + offset
}

/**
 * 원본은 `load-DBCjkj4(IIILkotlin/coroutines/Continuation;)Ljava/lang/Object;`다. 생성 overload는 원본 이름을 물려받지 않고
 * 각자 다른 hash 이름(`load-rdVXx4g`, `load-NUMGiXg`)을 받는다.
 */
@JvmOverloads
suspend fun load(token: Token, page: Int = 1, size: Int = 2): Int = token.raw + page + size

/**
 * 직접 작성했고 쓰이지 않는다. JVM 이름을 load 원본과 같게 두어, 원본 이름으로 계산한 load의 생성 overload
 * `load-DBCjkj4(IILkotlin/coroutines/Continuation;)Ljava/lang/Object;`가 이 함수의 원본과 같아진다.
 * `@JvmName` 함수의 생성 overload는 같은 이름을 쓴다.
 */
@JvmName("load-DBCjkj4")
@JvmOverloads
suspend fun authoredLoad(first: Int, second: Int = 0): Int = first + second

/** 같은 형태의 비-suspend 원본 `read-rdVXx4g(III)I`다. 생성 overload는 `read-NUMGiXg(II)I`, `read-hMQyODE(I)I`다. */
@JvmOverloads
fun read(token: Token, page: Int = 1, size: Int = 2): Int = token.raw + page + size

/** 직접 작성했고 쓰이지 않는다. 원본 `read-rdVXx4g(II)I`는 read 원본 이름으로 계산한 생성 overload와 같다. */
@JvmName("read-rdVXx4g")
@JvmOverloads
fun authoredRead(first: Int, second: Int = 0): Int = first + second

/** load와 read의 Kotlin 호출자다. 둘 다 `$default`를 거친다. */
suspend fun callLoadAndRead(): Int = load(Token(1)) + read(Token(2))
