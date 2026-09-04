package dev.kartograph.core

/** 분석 대상 graph에 섞지 않고 dependency class의 상속 사실만 제공한다. */
public class ClassHierarchy(entries: Map<String, Set<String>>) {
    private val entries: Map<String, Set<String>> = entries.mapValues { (_, supertypes) -> supertypes.toSet() }

    /** class가 인덱스에 없으면 null, 알려진 root class면 빈 집합을 반환한다. */
    public fun directSupertypesOf(internalName: String): Set<String>? = entries[internalName]

    public companion object {
        /** dependency classpath를 받지 않은 분석에 쓰는 빈 hierarchy다. */
        public val EMPTY: ClassHierarchy = ClassHierarchy(emptyMap())
    }
}
