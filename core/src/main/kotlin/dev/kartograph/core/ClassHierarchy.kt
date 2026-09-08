package dev.kartograph.core

/** 분석 대상 graph에 섞지 않고 dependency class의 상속 사실만 제공한다. */
public class ClassHierarchy(entries: Map<String, Set<String>>, methods: Map<String, List<HierarchyMethod>> = emptyMap()) {
    private val entries: Map<String, Set<String>> = entries.mapValues { (_, supertypes) -> supertypes.toSet() }
    private val methods: Map<String, List<HierarchyMethod>> = methods.mapValues { (_, members) -> members.toList() }

    /** class가 인덱스에 없으면 null, 알려진 root class면 빈 집합을 반환한다. */
    public fun directSupertypesOf(internalName: String): Set<String>? = entries[internalName]

    /** 메서드 header를 읽지 않았으면 null이며 body를 분석 그래프에 수입하지 않는다. */
    public fun declaredMethodsOf(internalName: String): List<HierarchyMethod>? = methods[internalName]

    public companion object {
        /** dependency classpath를 받지 않은 분석에 쓰는 빈 hierarchy다. */
        public val EMPTY: ClassHierarchy = ClassHierarchy(emptyMap())
    }
}

/** 외부 dispatch의 접근·final 경계를 확인하기 위한 메서드 선언 사실이다. */
public data class HierarchyMethod(
    val name: String,
    val descriptor: String,
    val visibility: Visibility,
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
)
