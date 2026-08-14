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
    fun `protected app data and accessibility scraping are never enabled`() {
        val policies = SourceCapabilityPolicy.forSource(
            DetectedSource(SourcePlatform.ANDROID, SourceFamily.ANDROID_PHONE, "Android phone"),
        ).protectedSurfaces

        assertTrue(policies.isNotEmpty())
        assertTrue(policies.all { !it.supported })
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