package dev.kartograph.analysis

import dev.kartograph.core.GraphNode
import dev.kartograph.core.JvmModifier
import dev.kartograph.core.NodeKind
import dev.kartograph.core.Visibility

/**
 * 그래프 밖 JVM launcher가 호출하는 프로세스 진입점 `main`을 식별한다.
 * 진입점은 호출자가 없다는 이유만으로 미사용으로 보고되면 안 된다.
 */
internal object JvmEntryPoint {
    /**
     * public static `main`은 launcher 진입점으로 본다. descriptor를 고정하지 않아
     * `suspend fun main()`처럼 compiler가 만드는 진입점 변형도 오탐에서 제외한다.
     * 이름이 `main`인 user top-level 함수를 보수적으로 제외하는 방향이다.
     */
    fun isLauncherMain(node: GraphNode): Boolean =
        node.kind == NodeKind.METHOD && !node.synthesized && node.name == "main" &&
            JvmModifier.STATIC in node.jvmModifiers && node.jvmVisibility == Visibility.PUBLIC
}
