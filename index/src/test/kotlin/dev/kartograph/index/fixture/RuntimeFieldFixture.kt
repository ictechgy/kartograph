package dev.kartograph.index.fixture

object KotlinFieldFixture {
    class A
    class B
    class Unused

    @JvmField var type: Class<*> = A::class.java

    @JvmStatic fun write(flag: Boolean, unknown: Class<*>) {
        type = if (flag) B::class.java else unknown
    }

    @JvmStatic fun direct() { type.getDeclaredConstructor().newInstance() }

    @JvmStatic fun reflective() {
        val field = KotlinFieldFixture::class.java.getDeclaredField("type")
        field.set(null, B::class.java)
        (field.get(null) as Class<*>).getDeclaredConstructor().newInstance()
    }

    @JvmField var cycle: Class<*>? = initial()
    @JvmStatic fun initial(): Class<*>? = cycle
    @JvmStatic fun reentry() { cycle!!.getDeclaredConstructor().newInstance() }
}
