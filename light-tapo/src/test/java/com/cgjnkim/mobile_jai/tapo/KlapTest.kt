package com.cgjnkim.mobile_jai.tapo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KlapTest {

    private val local = ByteArray(16) { it.toByte() }
    private val remote = ByteArray(16) { (100 + it).toByte() }
    private val setup = Klap.authHash("test@tp-link.net", "test")

    /** The plug's handshake1 answer: its seed, then proof that it holds [auth]. */
    private fun answer(auth: ByteArray) = remote + Klap.sha256(local, remote, auth)

    @Test fun picksTheCredentialsThePlugHolds() {
        val candidates = Klap.DEFAULT_CREDENTIALS.map { (u, p) -> Klap.authHash(u, p) }
        assertArrayEquals(setup, Klap.match(local, answer(setup), candidates))
        assertNull(Klap.match(local, answer(Klap.authHash("someone@example.com", "x")), candidates))
        assertNull(Klap.match(local, ByteArray(20), candidates))
    }

    @Test fun requestsRoundTripAndAdvanceTheSequence() {
        val client = Klap(local, remote, setup)
        val plug = Klap(local, remote, setup) // the plug derives the same keys
        val start = client.seq
        val (body, seq) = client.encrypt("""{"method":"get_device_info"}""")
        assertEquals(start + 1, seq)
        // The body is signature + ciphertext; a reply is laid out the same way.
        assertEquals("""{"method":"get_device_info"}""", plug.decrypt(body, seq))
        assertEquals(start + 2, client.encrypt("{}").second)
    }

    @Test fun readsTheRigsDiscoveryReply() {
        // Verbatim from the P110 on the rig, unbound, 2026-10-08.
        val json = """{"result":{"device_id":"55da325a4badb29e4794c89e3b3c51ce","owner":"","device_type":"SMART.TAPOPLUG","device_model":"P110(EU)","ip":"192.168.0.1","mac":"8C-90-2D-14-66-04","is_support_iot_cloud":true,"obd_src":"tplink","factory_default":true,"mgt_encrypt_schm":{"is_support_https":false,"encrypt_type":"KLAP","http_port":80,"lv":2}},"error_code":0}"""
        assertEquals(
            TapoPlug.Discovery("SMART.TAPOPLUG", "P110(EU)", "8C-90-2D-14-66-04", "KLAP", 80),
            TapoPlug.parseDiscovery(json),
        )
    }
}
