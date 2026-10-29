package dev.kartograph.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 등록표의 이전 구현과 특수 fallback의 predicate 호출까지 비교한다. */
class ParseRuntimeModelLookupTest {
    @Test
    fun `all registered selectors and near misses retain model and predicate behavior`() {
        val selectors = LegacyParseRuntimeLibraryModels.selectors()
        assertTrue(selectors.isNotEmpty())
        for (selector in selectors) {
            val variants = listOf(selector,
                selector.copy(owner = "unrelated/Owner"), selector.copy(name = selector.name + "Missing"),
                selector.copy(descriptor = selector.descriptor + "!"), selector.copy(static = !selector.static))
            for (value in variants) for (loader in listOf(false, true)) {
                same(value.owner, value.name, value.descriptor, value.static, loader)
            }
        }
    }

    @Test
    fun `loader and object fallbacks keep exact short circuit behavior for arbitrary owners`() {
        for (owner in listOf("custom/Loader", "java/lang/Class", "java/lang/String", "", "Ω/Owner")) {
            for (name in listOf("loadClass", "getClass", "forName", "unrelated", "")) {
                for (descriptor in listOf("(Ljava/lang/String;)Ljava/lang/Class;",
                    "(Ljava/lang/String;Z)Ljava/lang/Class;", "()Ljava/lang/Class;", "()V", "", "malformed")) {
                    for (static in listOf(false, true)) for (loader in listOf(false, true)) {
                        same(owner, name, descriptor, static, loader)
                    }
                }
            }
        }
        for (owner in listOf("custom/Loader", "java/lang/Class")) {
            var calls = 0
            RuntimeLibraryModels.find(owner, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false) {
                calls++
                false
            }
            assertEquals(1, calls)
            RuntimeLibraryModels.find(owner, "loadClass", "()V", false) { error("descriptor miss must not inspect hierarchy") }
            RuntimeLibraryModels.find(owner, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", true) {
                error("static call must not inspect hierarchy")
            }
            RuntimeLibraryModels.find(owner, "getClass", "()Ljava/lang/Class;", false) {
                error("Object fallback must not inspect hierarchy")
            }
        }
    }

    private fun same(owner: String, name: String, descriptor: String, static: Boolean, loader: Boolean) {
        val oldOwners = mutableListOf<String>()
        val newOwners = mutableListOf<String>()
        val expected = LegacyParseRuntimeLibraryModels.find(owner, name, descriptor, static) {
            oldOwners += it
            loader
        }
        val actual = RuntimeLibraryModels.find(owner, name, descriptor, static) {
            newOwners += it
            loader
        }
        assertEquals(expected, actual, "$owner#$name$descriptor static=$static loader=$loader")
        assertEquals(oldOwners, newOwners)
    }
}
