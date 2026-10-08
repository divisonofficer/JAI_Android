package com.cgjnkim.mobile_jai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cgjnkim.mobile_jai.tapo.TapoPlug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Switches a real Tapo plug from inside the app's process, so under the app's own network
 * rules: a VPN that covers the app blocks the plug's Wi-Fi (see [TapoPlug.blockedByVpn]).
 * Skipped when no plug is on Wi-Fi. Leaves the plug off.
 */
@RunWith(AndroidJUnit4::class)
class TapoPlugDeviceTest {

    @Test fun switchesThePlugOnAndOff() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val t0 = System.nanoTime()
        val plug = TapoPlug.find(context)
        assumeTrue("no Tapo plug on Wi-Fi (blocked by VPN: ${TapoPlug.blockedByVpn})", plug != null)
        assertNotNull(plug)
        Log.i(TAG, "found $plug in ${(System.nanoTime() - t0) / 1_000_000} ms")
        try {
            for (on in listOf(true, false)) {
                val t = System.nanoTime()
                plug!!.set(on)
                Log.i(TAG, "set($on) in ${(System.nanoTime() - t) / 1_000_000} ms")
                assertEquals(on, plug.refresh())
                Thread.sleep(1500)
            }
        } finally {
            runCatching { plug!!.set(false) }
        }
    }

    private companion object {
        const val TAG = "TapoPlugDeviceTest"
    }
}
