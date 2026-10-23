package dev.kartograph.core

/** 사람과 machine query가 공유하는 module-aware 선언 이름이다. */
public val GraphNode.qualifiedName: String
    get() {
        val owner = id.value.substringAfter(':').substringBefore('#').replace('/', '.')
        // module은 별도 메타데이터이며 언어의 package·owner 이름을 바꾸지 않는다.
        return owner + if ('#' in id.value) ".$name" else ""
    }

/** 이전 module 접두 selector도 입력 별칭으로 받되 출력의 언어 이름은 바꾸지 않는다. */
public fun GraphNode.matchesQualifiedName(requested: String): Boolean = qualifiedName == requested ||
    (moduleName?.let { "$it.$qualifiedName" } == requested)
