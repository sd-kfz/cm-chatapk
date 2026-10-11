package org.cmchat.app.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import kotlinx.coroutines.flow.MutableStateFlow


object Flashlight {
    val on = MutableStateFlow(false)


    fun toggle(context: Context): Boolean {
        val want = !on.value
        return if (set(context, want)) { on.value = want; want } else on.value
    }

    fun set(context: Context, enabled: Boolean): Boolean = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { camId ->
            cm.getCameraCharacteristics(camId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        cm.setTorchMode(id, enabled)
        true
    }.getOrDefault(false)


    fun off(context: Context) {
        if (on.value) { set(context, false); on.value = false }
    }
}
