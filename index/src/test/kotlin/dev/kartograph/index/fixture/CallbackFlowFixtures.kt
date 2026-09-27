package dev.kartograph.index.fixture

import androidx.compose.runtime.internal.rememberComposableLambda

/**
 * 콜백 값 흐름(람다 전달·파라미터 쓰임) 관측을 확인하는 합성 fixture다.
 *
 * 각 화면 함수(`screen*`)가 람다를 만들어 공통 함수에 넘기고, 람다 본문은 [CallbackFlowFixture.route]를 부른다.
 * 공통 함수가 파라미터를 실행하는지, 다른 함수로 넘기는지, 필드·반환·라이브러리로 빠져나가는지를 모양별로 나눈다.
 */
internal class CallbackFlowFixture {
    /** 필드로 빠져나간 콜백을 담는다. */
    var stored: (() -> Unit)? = null

    /** 모든 람다 본문이 부르는 route 호출 대체물이다. */
    fun route(label: String) = label.length

    /** 다른 화면이 넘기는 무관한 람다 본문이다. */
    fun unrelated(label: String) = label.hashCode()

    /** 파라미터를 직접 실행한다. */
    fun direct(onClick: () -> Unit) {
        onClick()
    }

    /** 파라미터를 수정 없이 [forwardInner]로 넘긴다. */
    fun forwardOuter(onClick: () -> Unit) = forwardInner(onClick)

    /** 넘겨받은 파라미터를 실행한다. */
    fun forwardInner(onClick: () -> Unit) {
        onClick()
    }

    /** 실행하면서 필드에도 저장한다. 다른 곳에서 실행할 수 있다. */
    fun escapeField(onClick: () -> Unit) {
        onClick()
        stored = onClick
    }

    /** 실행하지 않고 반환만 한다. */
    fun escapeReturn(onClick: () -> Unit): () -> Unit = onClick

    /** 라이브러리 코드에 넘긴다. */
    fun handToLibrary(onClick: () -> Unit): List<() -> Unit> = java.util.Collections.singletonList(onClick)

    /** 인라인 함수의 람다 본문은 호출한 함수 안으로 복사된다. */
    inline fun inlined(block: () -> Unit) {
        block()
    }

    /** Kotlin `fun interface`의 SAM 메서드를 실행한다. */
    fun sam(listener: Listener) {
        listener.onEvent()
    }

    /** JDK 함수형 인터페이스를 실행한다. */
    fun runnable(task: Runnable) {
        task.run()
    }

    /**
     * Compose 재구성 람다처럼 파라미터를 캡처한 람다가 같은 함수로 되돌려 넘기기만 한다. 실행 지점이 늘지 않는다.
     */
    fun restartable(onClick: () -> Unit) {
        onClick()
        keep { restartable(onClick) }
    }

    /** 캡처한 람다 안에서 실행한다. 그 람다를 누가 언제 실행할지 모른다. */
    fun closureInvoke(onClick: () -> Unit) {
        keep { onClick() }
    }

    /** 람다를 받아 두기만 한다(실행하지 않는다). */
    fun keep(block: () -> Unit) = block.hashCode()

    /** Compose content slot처럼 함수형 파라미터를 실행한다. */
    fun slot(content: (Any?, Int) -> Any?) {
        content(null, 0)
    }

    fun screenDirect() = direct { route("direct") }

    fun screenOther() = direct { unrelated("other") }

    fun screenForward() = forwardOuter { route("forward") }

    fun screenField() = escapeField { route("field") }

    fun screenReturn() = escapeReturn { route("return") }

    fun screenLibrary() = handToLibrary { route("library") }

    fun screenInline() = inlined { route("inline") }

    fun screenSam() = sam { route("sam") }

    fun screenAnonymous() = sam(object : Listener {
        override fun onEvent() {
            route("anonymous")
        }
    })

    fun screenRunnable() = runnable { route("runnable") }

    fun screenClassLambda() = direct(@JvmSerializableLambda { route("class") })

    fun screenReference() = direct(::referenced)

    fun referenced() {
        route("reference")
    }

    fun screenRestartable() = restartable { route("restartable") }

    fun screenClosure() = closureInvoke { route("closure") }

    fun screenComposable() = slot(rememberComposableLambda(7, true, { _: Any?, _: Any? -> route("composable") }, null))

    /** 분기에서 람다와 다른 값이 합쳐진 인자도 람다일 수 있다. */
    fun screenMerged(flag: Boolean) {
        val callback: () -> Unit = if (flag) ({ route("merged") }) else ::referenced
        direct(callback)
    }
}

/** SAM 변환 대상 Kotlin 함수형 인터페이스다. */
internal fun interface Listener {
    fun onEvent()
}
