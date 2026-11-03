package dev.kartograph.index.fixture.jvmoverloads

/** Kotlin 호출은 `greet$default`를 거쳐 원본에 닿는다. 생성 overload `greet(String)`은 metadata에 없다. */
@JvmOverloads
fun greet(name: String, punctuation: String = "!"): String = "hi $name$punctuation"

/** 같은 이름의 직접 작성 overload다. 생성물로 표시되면 안 된다. */
fun greet(times: Int): String = "hi".repeat(times)

/** greet의 Kotlin 호출자다. */
fun callGreet(): String = greet("a")

/** 쓰이지 않는 @JvmOverloads 함수의 원본은 계속 보고되어야 한다. */
@JvmOverloads
fun unusedOverloaded(value: Int, scale: Int = 1): Int = value * scale
