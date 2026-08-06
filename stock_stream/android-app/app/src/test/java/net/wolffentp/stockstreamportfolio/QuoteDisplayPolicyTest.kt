package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.data.model.QuoteRow
import net.wolffentp.stockstreamportfolio.ui.viewmodel.QuoteDisplayPolicy
import org.junit.Test

class QuoteDisplayPolicyTest {
    @Test
    fun totalGain_sumsRowsWithCompleteHoldingsData() {
        val rows = listOf(
            quoteRow(mapOf("Quantity" to "5", "Purchase Price" to "$90.00", "Last" to "$100.00")),
            quoteRow(mapOf("Quantity" to "2", "Purchase Price" to "1,200.00", "Last" to "$1,150.00")),
            quoteRow(mapOf("Quantity" to null, "Purchase Price" to "10", "Last" to "20"))
        )

        assertThat(QuoteDisplayPolicy.totalGain(rows)).isEqualTo(-50.0)
    }

    @Test
    fun totalGain_returnsNullWhenHoldingsCannotBeCalculated() {
        val rows = listOf(
            quoteRow(mapOf("Quantity" to "5", "Purchase Price" to null, "Last" to "$100.00")),
            quoteRow(emptyMap())
        )

        assertThat(QuoteDisplayPolicy.totalGain(rows)).isNull()
    }

    @Test
    fun unavailableRow_returnsUnavailableMessage() {
        assertThat(QuoteDisplayPolicy.rowStatus(null)).isEqualTo(QuoteDisplayPolicy.MARKET_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun nonLiveRow_doesNotClaimLive() {
        val row = QuoteRow(
            symbol = "MSFT",
            displayName = null,
            dataSource = "Provider",
            retrievedAtUtc = "2026-01-01T00:00:00Z",
            marketStatus = "Closed",
            freshnessStatus = "Delayed",
            isLive = false,
            message = null,
            fields = emptyMap(),
            missingFields = emptyList(),
            calculatedFields = emptyList(),
            errorCode = null,
            errorMessage = null
        )

        assertThat(QuoteDisplayPolicy.rowStatus(row)).isEqualTo(QuoteDisplayPolicy.MARKET_UNAVAILABLE_MESSAGE)
    }

    private fun quoteRow(fields: Map<String, String?>) = QuoteRow(
        symbol = "MSFT",
        displayName = null,
        dataSource = "Provider",
        retrievedAtUtc = "2026-01-01T00:00:00Z",
        marketStatus = "Open",
        freshnessStatus = "Live",
        isLive = true,
        message = null,
        fields = fields,
        missingFields = emptyList(),
        calculatedFields = emptyList(),
        errorCode = null,
        errorMessage = null
    )
}
