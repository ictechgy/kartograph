package dev.kartograph.index.fixture

/** 실제 Kotlin metadata 보강 뒤에도 runtime 관측값이 유지되는지 확인한다. */
class RuntimeObservationFixture {
    external fun nativeCall()
    fun reflect(name: String): Class<*> = Class.forName(name)
}
