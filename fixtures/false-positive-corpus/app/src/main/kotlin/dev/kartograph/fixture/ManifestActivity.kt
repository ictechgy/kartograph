package dev.kartograph.fixture

import android.app.Activity
import android.hardware.camera2.CameraDevice
import android.webkit.WebViewClient

class ManifestActivity : Activity() {
    private val runtimeContract: RuntimeContract = RuntimeImplementation()

    fun runtimeCallback(): ManifestOwnedDependency = ManifestOwnedDependency()

    fun runtimeConstant(): Int = RUNTIME_CONSTANT + InlinedConstantOwner.VALUE

    fun runtimeWebViewClient(): WebViewClient = CorpusWebViewClient()

    fun runtimeViewModelType(): Class<CorpusViewModel> = CorpusViewModel::class.java

    fun runtimeCameraCallback(): CameraDevice.StateCallback = CorpusCameraStateCallback()

    fun invokeRuntimeContract(): Any = runtimeContract.invoke()

    fun runtimeSequence(): Sequence<Any> = sequence {
        yield(SyntheticCallbackDependency())
    }

    private companion object {
        const val RUNTIME_CONSTANT = 1
    }
}

class AliasTargetActivity : Activity()

class ManifestOwnedDependency

interface RuntimeContract {
    fun invoke(): Any
}

class RuntimeImplementation : RuntimeContract {
    override fun invoke(): Any = OverrideOnlyDependency()

    private companion object {
        init {
            ClassInitializerDependency()
        }
    }
}

class OverrideOnlyDependency

class SyntheticCallbackDependency

class ClassInitializerDependency

object InlinedConstantOwner {
    const val VALUE = 2
}
