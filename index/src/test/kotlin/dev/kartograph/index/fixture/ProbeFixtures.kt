package dev.kartograph.index.fixture

@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class Marker

interface Base

@Marker
internal class Caller : Base {
    private val dependency = Dependency()

    fun callTwice(): String {
        dependency.touch()
        dependency.touch()
        return dependency.value
    }
}

internal data class Dependency(@field:Marker var value: String = "value") {
    fun touch() {
        value += "!"
    }
}

internal object Singleton

private class PrivateTopLevel

internal class RuntimeHierarchyFixture : java.io.ByteArrayOutputStream()

internal class NestedOwner {
    private class PrivateNested
}

internal fun Dependency.extensionValue(): String = value

internal class SignatureOnly

internal interface SignatureConsumer {
    fun consume(value: SignatureOnly): SignatureOnly
}
