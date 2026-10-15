package com.cgjnkim.mobile_jai

import android.content.Context
import android.util.Log
import com.cgjnkim.mobile_jai.jai.GigeDiscovery
import com.cgjnkim.mobile_jai.relay.P4851Relay
import com.cgjnkim.mobile_jai.relay.WchNetConfig
import com.cgjnkim.mobile_jai.tapo.TapoPlug
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The flash's white light, the first of these that is there:
 *
 *  1. a lamp on a P4851-4 relay module on the camera's Ethernet link ([P4851Relay]);
 *  2. a lamp on a Tapo smart plug on the phone's Wi-Fi ([TapoPlug]);
 *  3. the phone's own LED ([PhoneTorch]).
 *
 * The first two are looked for with [find], which blocks for a second or more; until one
 * is found, or once it stops answering, the next stands in. A failed switch does not fall
 * back within the same shot: half a comparison lit by one light and recorded as another
 * would be worse than an unlit one.
 */
class WhiteLight(context: Context) {

    private val appContext = context.applicationContext
    private val torch = PhoneTorch(context)

    @Volatile var relay: P4851Relay? = null
        private set

    @Volatile var plug: TapoPlug? = null
        private set

    /** Where the relay module was last found: its address is static, so usually there again. */
    @Volatile private var relayAddress: Inet4Address? = InetAddress.getByName(RELAY_DEFAULT_ADDRESS) as Inet4Address

    @Volatile private var relayLampOn = false

    val available: Boolean get() = relay != null || plug != null || torch.available

    val isOn: Boolean get() = when {
        relay != null -> relayLampOn
        plug != null -> plug!!.isOn
        else -> torch.isOn
    }

    /** What lit the scene, for the metadata and the status line. */
    val description: String get() = relay?.let { "Relay lamp (P4851-4 ch$RELAY_CHANNEL @ ${it.address.hostAddress})" }
        ?: plug?.let { "Tapo ${it.model} ${it.mac}" }
        ?: "phone torch"

    /** Lamp B: the relay module's other channel, a plain on/off lamp. Only with the relay. */
    val hasLampB: Boolean get() = relay != null

    @Volatile var lampBOn = false
        private set

    val lampBDescription: String get() = relay?.let { "Relay lamp B (P4851-4 ch$LAMP_B_CHANNEL @ ${it.address.hostAddress})" } ?: "no lamp B"

    /** Lamp B on or off: one switch, no modes. Returns whether it is now so. */
    fun setLampB(on: Boolean): Boolean {
        val r = relay ?: return false
        Log.i(TAG, "lamp B ${if (on) "on" else "off"}")
        return runCatching {
            r.set(LAMP_B_CHANNEL, if (LAMP_B_ON_NC) !on else on)
            lampBOn = on
            true
        }.getOrElse { Log.w(TAG, "lamp B did not switch", it); false }
    }

    /** The phone LED's level when it is the light, else 1: a relay or a plug is on or off. */
    val level: Int get() = if (relay != null || plug != null) 1 else torch.maxLevel

    /**
     * How long to wait after switching before the light can be trusted to be fully on or
     * off. Guesses, generous on purpose: a half-lit first frame spoils a comparison
     * silently, a longer wait only costs time. For the plug the Wi-Fi command is most of it;
     * the relay is on the wire and clicks in ~10 ms, so its wait is the lamp's own.
     */
    val settleMs: Long get() = when {
        relay != null -> RELAY_SETTLE_MS
        plug != null -> PLUG_SETTLE_MS
        else -> 0L
    }

    /** Returns whether the light is now in the state asked for. */
    fun set(on: Boolean): Boolean {
        Log.i(TAG, "${if (on) "on" else "off"}: $description")
        relay?.let { r ->
            return runCatching {
                if (on) relayLampOnFast(r) else relayLampOffAndRewind(r)
                true
            }.getOrElse {
                Log.w(TAG, "$r did not switch; dropping it", it)
                relay = null
                false
            }
        }
        val p = plug ?: return torch.set(on)
        return runCatching { p.set(on) }.getOrElse {
            Log.w(TAG, "$p did not switch ${if (on) "on" else "off"}; dropping it", it)
            plug = null
            false
        }
    }

    /**
     * Looks for the relay module on the Ethernet link, then a plug on Wi-Fi, and keeps
     * what answers. Returns its description, or null if neither is there. Blocking; a light
     * already in hand is only re-checked.
     */
    fun find(): String? {
        findRelay()?.let { return description }
        plug?.let { p ->
            if (runCatching { p.refresh() }.isSuccess) return description
            Log.w(TAG, "$p stopped answering")
            plug = null
        }
        val found = TapoPlug.find(appContext) ?: return null
        return runCatching { found.refresh(); found }
            .onFailure { Log.w(TAG, "$found found but not usable: ${it.message}") }
            .getOrNull()
            ?.also { plug = it }
            ?.let { description }
    }

    /** The relay lamp on or off; wired to NC, it is lit while the relay is released. */
    private fun relayLamp(r: P4851Relay, on: Boolean) {
        r.set(RELAY_CHANNEL, if (RELAY_LAMP_ON_NC) !on else on)
        relayLampOn = on
    }

    /**
     * The lamp steps to its next mode (of [LAMP_MODES]) every time it gets power. It is
     * kept so that its next power-on is the mode the rig wants: a flash is then a single
     * switch-on, lit at once ([relayLampOnFast]), and the rest of the round is done when it
     * is switched off, after the shot ([relayLampOffAndRewind]). Already on, it is left
     * alone: switching it again would step it.
     */
    private fun relayLampOnFast(r: P4851Relay) {
        if (!relayLampOn) relayLamp(r, true)
    }

    /** Off, then on-off [LAMP_MODES] - 1 more times: off - on - off - on - off for three modes. */
    private fun relayLampOffAndRewind(r: P4851Relay) {
        if (!relayLampOn) return
        relayLamp(r, false)
        repeat(LAMP_MODES - 1) { pulse(r) }
    }

    /** One power-on of the lamp from off, ending off again: one step of its mode. */
    private fun pulse(r: P4851Relay) {
        Thread.sleep(LAMP_PULSE_OFF_MS)
        relayLamp(r, true)
        Thread.sleep(LAMP_PULSE_ON_MS)
        relayLamp(r, false)
    }

    /**
     * Steps the lamp's mode by one, keeping it on or off as it is: for when it has drifted
     * out of step -- the module rebooted, say, which on NC lights it uncounted. Returns
     * false when the white light is not the relay lamp. Blocking.
     */
    fun stepLampMode(): Boolean {
        val r = relay ?: return false
        return runCatching {
            if (relayLampOn) {
                relayLamp(r, false)
                Thread.sleep(LAMP_PULSE_OFF_MS)
                relayLamp(r, true)
            } else {
                pulse(r)
            }
            Log.i(TAG, "lamp mode stepped")
            true
        }.getOrElse { Log.w(TAG, "lamp step", it); false }
    }

    private fun findRelay(): P4851Relay? {
        relay?.let { r ->
            runCatching { r.status() }.onSuccess { s ->
                relayLampOn = s.relays[RELAY_CHANNEL - 1] != RELAY_LAMP_ON_NC
                lampBOn = s.relays[LAMP_B_CHANNEL - 1] != LAMP_B_ON_NC
                return r
            }
            Log.w(TAG, "$r stopped answering")
            relay = null
        }
        val link = GigeDiscovery.findLink() ?: return null
        val target = link.hostAt(RELAY_HOST_PART)
        val found = P4851Relay.find(link.address, link.prefixLength, relayAddress)
        // On the JAI's address it would clash with the camera as soon as that is plugged in.
        if (found == null || found.address == link.hostAt(JAI_HOST_PART)) {
            readdressRelay(link.address, link.prefixLength, target)
            if (found == null) return null
        }
        relayAddress = found.address
        runCatching { found.status() }.onSuccess { s ->
            relayLampOn = s.relays[RELAY_CHANNEL - 1] != RELAY_LAMP_ON_NC
            lampBOn = s.relays[LAMP_B_CHANNEL - 1] != LAMP_B_ON_NC
        }
        relay = found
        // Lamp B is only ever lit for a comparison's lit half; on NC it lights itself at rest.
        if (lampBOn) setLampB(false)
        return found
    }

    /**
     * What to tell the user about the relay module, once: set when it has been given a new
     * address that needs a power cycle to take. Read and cleared by the caller.
     */
    @Volatile var relayNotice: String? = null

    /** The address already written and waiting for a power cycle, so it is written only once. */
    @Volatile private var readdressedTo: Inet4Address? = null

    /**
     * A relay module that is not on the tether's subnet -- its address is static, the
     * subnet is new at every phone boot -- is found by WCH's broadcast search and given
     * [target] in it, the phone as gateway. It takes the address at its next power-up.
     */
    private fun readdressRelay(local: Inet4Address, prefixLength: Int, target: Inet4Address) {
        val modules = runCatching { WchNetConfig.search(local) }.onFailure { Log.w(TAG, "WCH search", it) }.getOrNull() ?: return
        val module = modules.firstOrNull { it.mac.startsWith(RELAY_MAC_PREFIX, ignoreCase = true) || it.name.contains("RELAY") } ?: return
        if (module.address == target) {
            // Written already; the module is still on its old address until power-cycled.
            relayNotice = "Power-cycle the relay module: it moves to ${target.hostAddress} at power-up"
            return
        }
        if (readdressedTo == target) return
        val ok = runCatching { WchNetConfig.readdress(local, module, target, local, prefixLength) }
            .onFailure { Log.w(TAG, "relay readdress", it) }.getOrDefault(false)
        if (ok) {
            readdressedTo = target
            relayAddress = target
            relayNotice = "Relay module was at ${module.address.hostAddress}; set to ${target.hostAddress}. Power-cycle it once"
        }
    }

    companion object {
        private const val TAG = "WhiteLight"
        const val PLUG_SETTLE_MS = 1000L
        const val RELAY_SETTLE_MS = 300L

        /** The module's factory-set static address on the rig. */
        private const val RELAY_DEFAULT_ADDRESS = "192.168.123.200"

        /** Where the relay module is put on the tether's subnet: clear of the JAI's .200. */
        private const val RELAY_HOST_PART = 210
        private const val JAI_HOST_PART = 200

        /** WCH (Jiangsu Qinheng), the network chip on the P4851-4. */
        private const val RELAY_MAC_PREFIX = "38:3b:26"

        /**
         * Lamp B, added 2026-10-15: a plain on/off lamp on the module's other relay, wired
         * to NC like lamp A -- it went dark when relay 1 was energized, and is lit at rest.
         */
        const val LAMP_B_CHANNEL = 1
        const val LAMP_B_ON_NC = true

        /** The relay the lamp is wired through. */
        const val RELAY_CHANNEL = 2

        /**
         * The lamp is on the relay's NC contact (seen 2026-10-14: it went out when relay 2
         * was energized). Rewired to NO, set this false: then the lamp is dark when the
         * module loses power, and the coil is not held while the lamp is off.
         */
        const val RELAY_LAMP_ON_NC = true

        /**
         * The lamp on the relay cycles through this many modes, one step per power-on. The
         * rig wants the first: before a session, step it ([stepLampMode]) until a flash
         * lights in mode 1. Pulse timing is what was seen to register every step,
         * 2026-10-14: ~0.3 s on, ~0.6 s off with the module's request time.
         */
        const val LAMP_MODES = 3
        private const val LAMP_PULSE_ON_MS = 300L
        private const val LAMP_PULSE_OFF_MS = 300L
    }
}
