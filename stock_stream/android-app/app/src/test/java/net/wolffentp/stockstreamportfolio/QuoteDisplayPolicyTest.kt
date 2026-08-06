package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.data.model.QuoteRow
import net.wolffentp.stockstreamportfolio.ui.viewmodel.QuoteDisplayPolicy
import org.junit.Test

class QuoteDisplayPolicyTest {
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
}
