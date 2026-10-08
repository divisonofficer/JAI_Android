package com.cgjnkim.mobile_jai

import android.content.Context
import android.util.Log
import com.cgjnkim.mobile_jai.tapo.TapoPlug

/**
 * The flash comparison's white light: a lamp on a Tapo smart plug when one is on the
 * phone's Wi-Fi, else the phone's own LED ([PhoneTorch]).
 *
 * The plug is looked for with [find], which blocks for up to a second per Wi-Fi network;
 * until it is found, or once it stops answering, the phone's LED stands in. A failed
 * switch of the plug does not fall back to the LED within the same shot: half a
 * comparison lit by one light and recorded as the other would be worse than an unlit one.
 */
class WhiteLight(context: Context) {

    private val appContext = context.applicationContext
    private val torch = PhoneTorch(context)

    @Volatile var plug: TapoPlug? = null
        private set

    val available: Boolean get() = plug != null || torch.available

    val isOn: Boolean get() = plug?.isOn ?: torch.isOn

    /** What lit the scene, for the metadata and the status line. */
    val description: String get() = plug?.let { "Tapo ${it.model} ${it.mac}" } ?: "phone torch"

    /** The phone LED's level when it is the light, else 1: a plug is on or off. */
    val level: Int get() = if (plug != null) 1 else torch.maxLevel

    /**
     * How long to wait after switching before the light can be trusted to be fully on or
     * off. A guess for the plug, generous on purpose: a half-lit first frame spoils the
     * comparison silently, a longer wait only costs time. Measure it for a given lamp
     * from the compare bursts' rgb_measured_ratios before shortening it.
     */
    val settleMs: Long get() = if (plug != null) PLUG_SETTLE_MS else 0L

    /** Returns whether the light is now in the state asked for. */
    fun set(on: Boolean): Boolean {
        Log.i(TAG, "${if (on) "on" else "off"}: $description")
        val p = plug ?: return torch.set(on)
        return runCatching { p.set(on) }.getOrElse {
            Log.w(TAG, "$p did not switch ${if (on) "on" else "off"}; dropping it", it)
            plug = null
            false
        }
    }

    /**
     * Looks for a plug on Wi-Fi and keeps it, checking it answers. Returns it, or null.
     * Blocking; a plug already in hand is only re-checked.
     */
    fun find(): TapoPlug? {
        plug?.let { p ->
            if (runCatching { p.refresh() }.isSuccess) return p
            Log.w(TAG, "$p stopped answering")
            plug = null
        }
        val found = TapoPlug.find(appContext) ?: return null
        return runCatching { found.refresh(); found }
            .onFailure { Log.w(TAG, "$found found but not usable: ${it.message}") }
            .getOrNull()
            ?.also { plug = it }
    }

    companion object {
        private const val TAG = "WhiteLight"
        const val PLUG_SETTLE_MS = 1000L
    }
}
