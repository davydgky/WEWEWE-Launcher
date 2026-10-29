package io.github.davydgky.launcher.search

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The flashlight as a switch for the search. Switching it needs no camera permission. */
class Torch(context: Context) {

    private val camera = context.getSystemService(CameraManager::class.java)
    private val state = MutableStateFlow(false)
    val isOn: StateFlow<Boolean> = state.asStateFlow()

    private val cameraId: String? by lazy {
        runCatching {
            camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
    }

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) state.value = enabled
        }
    }

    init {
        // Reports the current state at once, so a light switched on elsewhere is known before the first tap.
        camera.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Switches the light; false if the device has none or the camera is busy. */
    fun toggle(): Boolean {
        val id = cameraId ?: return false
        return runCatching { camera.setTorchMode(id, !state.value) }
            .onFailure { Log.w("Torch", "Cannot switch the flashlight", it) }
            .isSuccess
            .also { if (it) state.value = !state.value }
    }
}
