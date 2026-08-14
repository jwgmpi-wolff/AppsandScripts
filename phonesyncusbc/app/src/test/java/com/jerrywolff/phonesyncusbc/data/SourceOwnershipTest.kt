package com.jerrywolff.phonesyncusbc.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOwnershipTest {
    @Test
    fun `collector exports are not external source data`() {
        assertFalse(isExternalSourcePeer(LOCAL_ANDROID_PEER_ID))
    }

    @Test
    fun `USB peer records are external source data`() {
        assertTrue(isExternalSourcePeer("usb-peer-sha256"))
    }
}