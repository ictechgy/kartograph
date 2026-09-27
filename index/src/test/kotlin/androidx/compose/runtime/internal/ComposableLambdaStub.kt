@file:JvmName("ComposableLambdaKt")

package androidx.compose.runtime.internal

import androidx.compose.runtime.Composer

/**
 * Compose 컴파일러가 부르는 `ComposableLambdaKt.rememberComposableLambda`의 합성 대체물이다.
 * 실제 Compose runtime 없이 같은 JVM 이름·descriptor 모양(`Object` block → `ComposableLambda`)을 재현한다.
 */
interface ComposableLambda : (Any?, Int) -> Any?

/** [block]을 감싼 [ComposableLambda]를 돌려준다. 실제 runtime처럼 invoke가 block의 invoke로 이어진다. */
@Suppress("UNCHECKED_CAST")
fun rememberComposableLambda(key: Int, tracked: Boolean, block: Any, composer: Composer?): ComposableLambda {
    val function = block as (Any?, Int) -> Any?
    return object : ComposableLambda {
        override fun invoke(p1: Any?, p2: Int): Any? = function(p1, p2 + key + if (tracked && composer == null) 0 else 1)
    }
}
