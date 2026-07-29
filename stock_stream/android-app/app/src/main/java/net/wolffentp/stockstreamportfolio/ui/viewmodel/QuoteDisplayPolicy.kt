package net.wolffentp.stockstreamportfolio.ui.viewmodel

import net.wolffentp.stockstreamportfolio.data.model.QuoteRow

object QuoteDisplayPolicy {
    const val MARKET_UNAVAILABLE_MESSAGE = "Market closed or live data unavailable."

    fun rowStatus(quoteRow: QuoteRow?): String {
        if (quoteRow == null) {
            return MARKET_UNAVAILABLE_MESSAGE
        }

        if (!quoteRow.isLive) {
            return quoteRow.message ?: MARKET_UNAVAILABLE_MESSAGE
        }

        return "Live"
    }
}
