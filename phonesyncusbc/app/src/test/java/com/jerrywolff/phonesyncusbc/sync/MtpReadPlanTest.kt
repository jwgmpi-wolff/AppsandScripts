package com.jerrywolff.phonesyncusbc.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MtpReadPlanTest {
    @Test
    fun `iOS standard partial read is tried before full object`() {
        assertEquals(
            listOf(MtpReadMode.PARTIAL_STANDARD, MtpReadMode.FULL_OBJECT),
            mtpReadPlan(
                supportsPartial64 = false,
                supportsPartialStandard = true,
            ),
        )
    }

    @Test
    fun `all compatible read modes retain deterministic order`() {
        assertEquals(
            listOf(
                MtpReadMode.PARTIAL_64,
                MtpReadMode.PARTIAL_STANDARD,
                MtpReadMode.FULL_OBJECT,
            ),
            mtpReadPlan(
                supportsPartial64 = true,
                supportsPartialStandard = true,
            ),
        )
    }
}