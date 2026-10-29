@file:JvmName("KotlinBodiesFile")
package inventory
fun mark(value: String): String = value
fun empty() {}
class Implicit
class Explicit constructor()
open class KotlinBase<T>(val value: T) {
    open fun map(value: T): T = value
}
class KotlinBodies(value: String = mark("default")) : KotlinBase<String>(mark(value)) {
    val stored = mark("property")
    var custom = mark("storage")
        get() = mark(field)
        set(value) { field = mark(value) }
    init { mark("init") }
    constructor(value: Int) : this(mark("secondary")) { mark("secondary-body") }
    override fun map(value: String): String = mark(value)
    fun overload(): String = mark("overload")
    fun overload(value: String): String = mark(value)
    fun locals() {
        fun local(): String = mark("local")
        val anonymous = object : Runnable { override fun run() { mark("anonymous") } }
        val lambda = { mark("lambda") }
        local()
    }
    class Nested { fun zero() {} }
    abstract class Abstract { abstract fun missing() }
    companion object { val staticValue = mark("static-property") }
}
