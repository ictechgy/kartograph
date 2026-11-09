package dev.kartograph.index.fixture.jvmstaticoverloads

/**
 * companion의 `@JvmStatic @JvmOverloads` 함수는 바깥 class에 정적 bridge를 남기고 bridge에도 `@JvmOverloads`가 붙는다.
 * 바깥 class metadata에는 그 함수가 없으므로 bridge를 생성 overload로 표시하면 안 된다.
 */
class JvmStaticOverloads {
    /** 같은 이름이지만 매개변수가 다른 직접 작성 함수다. 이름만 같다고 bridge를 생성물로 보면 안 된다. */
    fun h(text: String): Int = text.length

    /** 다른 이름, 같은 반환 타입, 더 긴 매개변수 목록이다. 이름 조건이 없으면 bridge `h(I)I`의 거짓 근거가 된다. */
    fun other(left: Int, right: Int): Int = left + right

    /** companion의 원본과 생성 overload다. */
    companion object {
        /** 원본 `h(II)I`와 생성 overload `h(I)I`가 companion과 바깥 class(정적 bridge) 양쪽에 생긴다. */
        @JvmStatic
        @JvmOverloads
        fun h(value: Int, scale: Int = 0): Int = value + scale
    }
}
