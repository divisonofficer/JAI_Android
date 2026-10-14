package com.cgjnkim.mobile_jai.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P4851RelayTest {

    @Test fun readsTheModulesStatusPage() {
        // The body as the module on the rig sends it, 2026-10-14.
        assertEquals(P4851Relay.Status(listOf(false, true), false), P4851Relay.parseStatus("RY1:0\r\nRY2:1\r\n IN:0"))
        assertEquals(P4851Relay.Status(listOf(true, true), true), P4851Relay.parseStatus("RY1:1 RY2:1  IN:1"))
    }

    @Test fun anythingElseIsNotTheModule() {
        assertNull(P4851Relay.parseStatus("<html><body><center>200 OK</center></body></html>"))
        assertNull(P4851Relay.parseStatus(""))
    }
}
