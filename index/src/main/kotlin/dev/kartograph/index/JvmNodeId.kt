package dev.kartograph.index

import dev.kartograph.core.NodeId

/** ASM과 metadata adapter가 동일 정점에 합류하도록 JVM signature 기반 ID를 만든다. */
public object JvmNodeId {
    /** JVM internal class name을 class 정점 ID로 바꾼다. */
    public fun classId(internalName: String): NodeId = NodeId("class:$internalName")

    /** owner, JVM method name, descriptor를 method 정점 ID로 바꾼다. */
    public fun methodId(owner: String, name: String, descriptor: String): NodeId =
        NodeId("method:$owner#$name$descriptor")

    /** owner, JVM field name, descriptor를 field 정점 ID로 바꾼다. */
    public fun fieldId(owner: String, name: String, descriptor: String): NodeId =
        NodeId("field:$owner#$name:$descriptor")
}
