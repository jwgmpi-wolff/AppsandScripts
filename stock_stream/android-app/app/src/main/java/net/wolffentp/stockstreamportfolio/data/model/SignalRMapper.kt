package net.wolffentp.stockstreamportfolio.data.model

object SignalRMapper {
    fun toEnvelope(source: net.wolffentp.stockstreamportfolio.data.api.SignalREnvelope): QuoteEnvelope {
        val rows = source.rows.map { row ->
            val fields = (row["fields"] as? Map<*, *>)
                ?.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    name to value?.toString()
                }
                ?.toMap()
                ?: emptyMap()
            QuoteRow(
                symbol = row["symbol"] as? String ?: "UNKNOWN",
                displayName = row["displayName"] as? String,
                dataSource = row["dataSource"] as? String ?: source.provider,
                retrievedAtUtc = row["retrievedAtUtc"] as? String ?: "",
                marketStatus = row["marketStatus"]?.toString() ?: "Unknown",
                freshnessStatus = row["freshnessStatus"]?.toString() ?: "Unknown",
                isLive = row["isLive"] as? Boolean ?: false,
                message = row["message"] as? String,
                fields = fields,
                missingFields = toStringList(row["missingFields"]),
                calculatedFields = toStringList(row["calculatedFields"]),
                errorCode = row["errorCode"] as? String,
                errorMessage = row["errorMessage"] as? String
            )
        }

        return QuoteEnvelope(
            provider = source.provider,
            lastSuccessfulLiveUpdateTimestampUtc = source.lastSuccessfulLiveUpdateTimestampUtc,
            rows = rows
        )
    }

    private fun toStringList(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { it?.toString() }
    }
}
