package com.cgjnkim.mobile_jai

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * The phone's own flash LED, used as a continuous light (torch mode) for the flash
 * comparison. No camera permission is needed for torch mode, and no camera is opened.
 *
 * Always at full strength where the phone lets the level be chosen: a comparison is
 * only as good as the difference between its two halves.
 */
class PhoneTorch(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)

    /** The back camera's flash if it has one, else any camera's. */
    private val cameraId: String? = runCatching {
        val withFlash = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    }.onFailure { Log.w(TAG, "no torch", it) }.getOrNull()

    val available: Boolean get() = cameraId != null

    /** The strongest level the LED offers, or 1 where the level is not adjustable. */
    val maxLevel: Int = cameraId?.let { id ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } else 1
    } ?: 0

    @Volatile var isOn = false
        private set

    /** Returns whether the torch is now in the state asked for. */
    fun set(on: Boolean): Boolean {
        val id = cameraId ?: return false
        return runCatching {
            if (on && maxLevel > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.turnOnTorchWithStrengthLevel(id, maxLevel)
            } else {
                manager.setTorchMode(id, on)
            }
            isOn = on
            true
        }.onFailure { Log.w(TAG, "torch $on", it) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "PhoneTorch"
    }
}
