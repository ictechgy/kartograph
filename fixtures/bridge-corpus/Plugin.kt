package dev.kartograph.bridgefixture

class CameraPlugin {
    fun register(messenger: Any) {
        val camera = MethodChannel(
            messenger,
            "dev.kartograph/camera",
        )
        camera.setMethodCallHandler { call, _ ->
            when (call.method) {
                "takePhoto" -> Unit
            }
        }
    }
}

@ReactModule(name = "Calendar")
class CalendarModule {
    @ReactMethod
    fun addEvent() = Unit
}
