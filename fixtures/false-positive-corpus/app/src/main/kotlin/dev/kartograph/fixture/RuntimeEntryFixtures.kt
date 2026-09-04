package dev.kartograph.fixture

import android.content.Context
import android.hardware.camera2.CameraDevice
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.ViewModel
import androidx.work.Worker
import androidx.work.WorkerParameters
import retrofit2.http.GET

class JavascriptBridge {
    @JavascriptInterface
    fun receive(value: String) = value.length
}

class NativeBridge {
    external fun dispatch(value: Long): Int
}

class CorpusWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}

interface CorpusService {
    @GET("corpus")
    suspend fun corpus(): List<String>
}

class CorpusWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        WebViewCallbackDependency()
        return false
    }
}

class WebViewCallbackDependency

class CorpusViewModel : ViewModel() {
    override fun onCleared() {
        ViewModelCallbackDependency()
    }
}

class ViewModelCallbackDependency

class CorpusCameraStateCallback : CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
        CameraCallbackDependency()
    }

    override fun onDisconnected(camera: CameraDevice) = Unit

    override fun onError(camera: CameraDevice, error: Int) = Unit
}

class CameraCallbackDependency
