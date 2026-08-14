package com.jerrywolff.phonesyncusbc.data

import android.Manifest
import com.jerrywolff.phonesyncusbc.domain.ConsentCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidPersonalDataCollectorTest {
    @Test
    fun `collect all requests every runtime provider permission`() {
        val permissions = AndroidPersonalDataCollector.requiredPermissions(
            AndroidPersonalDataCollector.SUPPORTED_CATEGORIES,
        )

        assertEquals(
            setOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR,
            ),
            permissions,
        )
    }

    @Test
    fun `notification export uses special access rather than a runtime permission`() {
        val permissions = AndroidPersonalDataCollector.requiredPermissions(
            setOf(ConsentCategory.NOTIFICATION_EXPORTS),
        )

        assertFalse(permissions.isNotEmpty())
    }
}