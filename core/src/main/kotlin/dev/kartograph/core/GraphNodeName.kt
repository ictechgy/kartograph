package dev.kartograph.core

/** 사람과 machine query가 공유하는 module-aware 선언 이름이다. */
public val GraphNode.qualifiedName: String
    get() {
        val owner = id.value.substringAfter(':').substringBefore('#').replace('/', '.')
        val qualifiedOwner = moduleName?.takeUnless { owner == it || owner.startsWith("$it.") }
            ?.let { "$it.$owner" } ?: owner
        return qualifiedOwner + if ('#' in id.value) ".$name" else ""
    }
