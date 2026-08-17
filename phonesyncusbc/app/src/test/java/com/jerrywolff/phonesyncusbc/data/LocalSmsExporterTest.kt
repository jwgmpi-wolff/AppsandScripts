package com.jerrywolff.phonesyncusbc.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSmsExporterTest {
    @Test
    fun `serializer preserves all exposed SMS fields`() {
        val line = serializeLocalSmsRecord(
            LocalSmsRecord(
                id = 42,
                address = "+15551234567",
                body = "Meet at 5",
                type = 1,
                dateEpochMillis = 1_700_000_000_000,
                dateSentEpochMillis = 1_699_999_999_000,
                providerValues = mapOf(
                    "_id" to 42L,
                    "address" to "+15551234567",
                    "body" to "Meet at 5",
                    "read" to 1L,
                    "service_center" to null,
                ),
            ),
        )

        val document = JsonParser.parseString(line).asJsonObject
        assertEquals("android-sms", document["recordType"].asString)
        assertEquals("Meet at 5", document["body"].asString)
        assertEquals("inbox", document["typeLabel"].asString)
        val provider = document.getAsJsonObject("provider")
        assertEquals("Meet at 5", provider.get("body").asString)
        assertTrue(provider.get("service_center").isJsonNull)
        assertTrue(document.has("timestamp"))
    }

    @Test
    fun `SMS type labels cover every Android message queue`() {
        assertEquals("inbox", smsTypeLabel(1))
        assertEquals("sent", smsTypeLabel(2))
        assertEquals("draft", smsTypeLabel(3))
        assertEquals("outbox", smsTypeLabel(4))
        assertEquals("failed", smsTypeLabel(5))
        assertEquals("queued", smsTypeLabel(6))
        assertEquals("unknown", smsTypeLabel(null))
        assertFalse(smsTypeLabel(99) == "inbox")
    }
}