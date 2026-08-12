package com.wolffentp.stockanalyzer.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchlistEntryTest {
    private val json = Json

    @Test
    fun holdingRoundTripPreservesUserEnteredValues() {
        val entry = WatchlistEntry("MSFT", quantity = 12.5, averageCost = 310.75)
        val decoded = json.decodeFromString<WatchlistEntry>(json.encodeToString(entry))
        assertEquals(entry, decoded)
    }

    @Test
    fun watchlistOnlyEntryDoesNotInventHoldingValues() {
        val decoded = json.decodeFromString<WatchlistEntry>("{\"symbol\":\"AAPL\"}")
        assertNull(decoded.quantity)
        assertNull(decoded.averageCost)
    }
}