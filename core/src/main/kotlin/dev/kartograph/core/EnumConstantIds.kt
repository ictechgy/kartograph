package dev.kartograph.core

/** enum 소유 class와 self-typed field의 JVM ID 모양을 입력 adapter와 질의가 함께 검증한다. */
public object EnumConstantIds {
    /** class ID 문법만 검증한다. 실제 enum kind나 capture 존재를 증명하지 않는다. */
    public fun isOwner(id: NodeId): Boolean {
        if (!id.value.startsWith("class:")) return false
        val owner = id.value.removePrefix("class:")
        return owner.isNotBlank() && owner.split('/').all { segment ->
            segment.isNotEmpty() && segment.none { it in ".;[" }
        }
    }

    /** 주어진 class의 정확한 self descriptor를 가진 field ID인지다. ACC_ENUM 사실은 별도로 요구한다. */
    public fun belongsTo(owner: NodeId, constant: NodeId): Boolean {
        if (!isOwner(owner)) return false
        val internalName = owner.value.removePrefix("class:")
        val prefix = "field:$internalName#"
        val suffix = ":L$internalName;"
        val value = constant.value
        if (!value.startsWith(prefix) || !value.endsWith(suffix) || value.length <= prefix.length + suffix.length) return false
        val name = value.substring(prefix.length, value.length - suffix.length)
        return name.isNotBlank() && name.none { it in ".;[/" }
    }
}
