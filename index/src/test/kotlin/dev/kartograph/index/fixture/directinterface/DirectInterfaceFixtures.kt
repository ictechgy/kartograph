package dev.kartograph.index.fixture.directinterface

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext

interface ProjectContract { fun value(): Int }
interface InheritedContract : ProjectContract
class ProjectImplementation : InheritedContract { override fun value(): Int = 61 }
class ProjectList : java.util.AbstractList<String>() {
    override val size: Int get() = 47
    override fun get(index: Int): String = "element"
}
class ProjectContinuation : Continuation<String> {
    override val context = EmptyCoroutineContext
    var observed: String = "unset"
    override fun resumeWith(result: Result<String>) { observed = result.getOrThrow() }
}
fun direct(value: ProjectContract): Int = value.value()
fun inherited(value: InheritedContract): Int = value.value()
fun externalList(value: List<String>): Int = value.size
fun resume(value: Continuation<String>) { value.resumeWith(Result.success("resumed")) }
