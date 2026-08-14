package com.jerrywolff.phonesyncusbc.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCapabilityPolicyTest {
    @Test
    fun `Google phones use MTP for media and documents`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.ANDROID, SourceFamily.GOOGLE_PHONE, "Pixel 9"),
        )

        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `iPhone media uses PTP while documents require exports`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.IOS, SourceFamily.IPHONE, "iPhone"),
        )

        assertEquals(AccessMode.PTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.PORTABLE_EXPORT, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `Android personal data uses local providers instead of file imports`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.IOS, SourceFamily.IPHONE, "iPhone"),
        )

        assertEquals(AccessMode.LOCAL_ANDROID_PROVIDER, capabilities.modeFor(ConsentCategory.SMS_EXPORTS))
        assertEquals(AccessMode.LOCAL_ANDROID_PROVIDER, capabilities.modeFor(ConsentCategory.CONTACTS))
        assertEquals(AccessMode.LOCAL_ANDROID_PROVIDER, capabilities.modeFor(ConsentCategory.CALL_LOGS))
        assertEquals(AccessMode.LOCAL_ANDROID_PROVIDER, capabilities.modeFor(ConsentCategory.CALENDAR))
        assertEquals(
            AccessMode.LOCAL_ANDROID_PROVIDER,
            capabilities.modeFor(ConsentCategory.NOTIFICATION_EXPORTS),
        )
    }

    @Test
    fun `Windows Phone uses MTP`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.WINDOWS_PHONE, SourceFamily.WINDOWS_PHONE, "Lumia"),
        )

        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.PHOTOS_AND_VIDEOS))
        assertEquals(AccessMode.MTP, capabilities.modeFor(ConsentCategory.DOCUMENTS))
    }

    @Test
    fun `FireWire is explicitly unsupported by Android USB host`() {
        val capabilities = SourceCapabilityPolicy.forSource(
            DetectedSource(
                SourcePlatform.ANDROID,
                SourceFamily.ANDROID_PHONE,
                "Legacy phone",
                PhysicalConnection.FIREWIRE,
            ),
        )

        assertFalse(capabilities.connectionSupported)
        assertTrue(capabilities.connectionMessage.contains("FireWire"))
    }

    @Test
    fun `private app data and accessibility scraping stay disabled while notification listener is supported`() {
        val policies = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.ANDROID, SourceFamily.ANDROID_PHONE, "Android phone"),
        ).protectedSurfaces

        assertTrue(policies.isNotEmpty())
        assertTrue(policies.first { it.surface == ProtectedSurface.LIVE_NOTIFICATION_CONTENT }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.SMS_DATABASE }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.CHAT_APPLICATION_DATABASE }.supported)
        assertFalse(policies.first { it.surface == ProtectedSurface.EMAIL_APPLICATION_DATABASE }.supported)
        assertTrue(
            policies.first { it.surface == ProtectedSurface.ACCESSIBILITY_SCRAPING }
                .alternative
                .isNullOrBlank(),
        )
    }

    private fun SourceCapabilities.modeFor(category: ConsentCategory): AccessMode {
        return categories.first { it.category == category }.accessMode
    }
}