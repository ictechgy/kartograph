package dev.kartograph.index.fixture

/** 별도 JVM에서 분석 종료를 확인해 무한 반복 회귀가 테스트 worker를 남기지 않게 한다. */
object RuntimeFieldProbe {
    @JvmStatic fun main(arguments: Array<String>) {
        val indexed = dev.kartograph.index.ClassFileIndexer().indexWithObservations(listOf(java.nio.file.Path.of(arguments.single())))
        println(indexed.observations.sumOf { it.valueAnalysisLimits })
    }
}

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
