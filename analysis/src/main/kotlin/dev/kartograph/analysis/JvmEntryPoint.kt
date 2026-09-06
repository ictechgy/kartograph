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
    /** public static이면서 launcher가 인식하는 descriptor의 main인지 반환한다. */
    fun isLauncherMain(node: GraphNode): Boolean =
        node.kind == NodeKind.METHOD && !node.synthesized &&
            JvmModifier.STATIC in node.jvmModifiers &&
            node.jvmVisibility == Visibility.PUBLIC &&
            LAUNCHER_SIGNATURE_SUFFIXES.any { suffix -> node.id.value.endsWith(suffix) }

    private val LAUNCHER_SIGNATURE_SUFFIXES = listOf("#main()V", "#main([Ljava/lang/String;)V")
}
