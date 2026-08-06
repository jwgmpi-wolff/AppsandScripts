package net.wolffentp.stockstreamportfolio.ui.viewmodel

import net.wolffentp.stockstreamportfolio.data.model.QuoteRow

object QuoteDisplayPolicy {
    const val MARKET_UNAVAILABLE_MESSAGE = "Market closed or live data unavailable."

    fun totalGain(rows: List<QuoteRow>): Double? {
        val gains = rows.mapNotNull { row ->
            val quantity = row.fields["Quantity"].toNumberOrNull() ?: return@mapNotNull null
            val purchasePrice = row.fields["Purchase Price"].toNumberOrNull() ?: return@mapNotNull null
            val lastPrice = row.fields["Last"].toNumberOrNull() ?: return@mapNotNull null
            (lastPrice - purchasePrice) * quantity
        }

        return gains.takeIf { it.isNotEmpty() }?.sum()
    }

    fun rowStatus(quoteRow: QuoteRow?): String {
        if (quoteRow == null) {
            return MARKET_UNAVAILABLE_MESSAGE
        }

        if (!quoteRow.isLive) {
            return quoteRow.message ?: MARKET_UNAVAILABLE_MESSAGE
        }

        return "Live"
    }

    private fun String?.toNumberOrNull(): Double? {
        if (this.isNullOrBlank()) {
            return null
        }

        val trimmed = trim()
        val isParenthesized = trimmed.startsWith("(") && trimmed.endsWith(")")
        val normalized = trimmed.replace(Regex("[^0-9.+-]"), "")
        val value = normalized.toDoubleOrNull() ?: return null
        return if (isParenthesized) -value else value
    }
}
