package dev.kartograph.core

/** Framework annotation이 선언 이름에서 파생하는 생성 sibling class 이름을 한곳에서 계산한다. */
public object GeneratedSiblingNaming {
    /** 지원 annotation과 JVM internal class name으로 가능한 정확한 sibling 이름을 반환한다. */
    public fun candidatesFor(sourceInternalName: String, annotations: Set<String>): Set<String> = buildSet {
        if ("com/squareup/moshi/JsonClass" in annotations) {
            val packagePrefix = sourceInternalName.substringBeforeLast('/', missingDelimiterValue = "")
            val simpleName = sourceInternalName.substringAfterLast('/').replace('$', '_') + "JsonAdapter"
            add(if (packagePrefix.isEmpty()) simpleName else "$packagePrefix/$simpleName")
        }
        if ("androidx/room/Database" in annotations) add("${sourceInternalName}_Impl")
    }
}
