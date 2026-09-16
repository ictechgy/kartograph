package probe
class NamedProvider : java.util.function.Supplier<String> {
 override fun get(): String { NamedDependency(); return "done" }
}
class NamedDependency { init { println("NAMED_KOTLIN_DEPENDENCY_EXECUTED") } }
object KotlinEntry {
 fun invoke(provider: java.util.function.Supplier<String>) { println(provider.get()) }
 @JvmStatic fun main(args: Array<String>) { invoke(NamedProvider()) }
}
