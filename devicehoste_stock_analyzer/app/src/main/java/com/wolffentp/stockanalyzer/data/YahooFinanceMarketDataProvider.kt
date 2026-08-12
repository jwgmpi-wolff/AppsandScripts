package com.wolffentp.stockanalyzer.data

import android.util.Log
import com.wolffentp.stockanalyzer.domain.Candle
import com.wolffentp.stockanalyzer.domain.CandleSeries
import com.wolffentp.stockanalyzer.domain.NewsSentimentBatch
import com.wolffentp.stockanalyzer.domain.Quote
import com.wolffentp.stockanalyzer.domain.TimestampedSentiment
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class YahooFinanceMarketDataProvider(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String = "https://query1.finance.yahoo.com",
    private val clock: () -> Instant = Instant::now,
) : MarketDataProvider {
    override val displayName = PROVIDER
    private val cacheMutex = Mutex()
    private val chartCache = mutableMapOf<String, CachedChart>()

    override suspend fun getQuote(symbol: String): Quote {
        val result = chart(symbol, interval = "1m", range = "1d")
        val price = result.meta.regularMarketPrice
            ?: throw MarketDataException.InvalidResponse("current price was unavailable")
        val timestamp = result.meta.regularMarketTime
            ?: throw MarketDataException.InvalidResponse("quote timestamp was unavailable")
        return Quote(symbol, price, Instant.ofEpochSecond(timestamp), PROVIDER)
    }

    override suspend fun getIntradayCandles(symbol: String, intervalMinutes: Int, rangeMinutes: Int): CandleSeries {
        val daily = intervalMinutes == 1_440
        if (!daily && intervalMinutes != 1) throw MarketDataException.InvalidResponse("unsupported candle interval")
        val result = chart(symbol, interval = if (daily) "1d" else "1m", range = if (daily) "6mo" else "1d")
        val values = result.indicators.quote.firstOrNull()
            ?: throw MarketDataException.InvalidResponse("candle values were unavailable")
        val candles = result.timestamp.mapIndexedNotNull { index, timestamp ->
            val open = values.open.getOrNull(index) ?: return@mapIndexedNotNull null
            val high = values.high.getOrNull(index) ?: return@mapIndexedNotNull null
            val low = values.low.getOrNull(index) ?: return@mapIndexedNotNull null
            val close = values.close.getOrNull(index) ?: return@mapIndexedNotNull null
            Candle(Instant.ofEpochSecond(timestamp), open, high, low, close, values.volume.getOrNull(index))
        }
        if (candles.isEmpty()) throw MarketDataException.MarketClosed()
        return CandleSeries(PROVIDER, clock(), intervalMinutes, candles)
    }

    override suspend fun getNewsOrSentiment(symbol: String): NewsSentimentBatch {
        val encoded = encode(symbol)
        val response = request<YahooSearchResponse>("/v1/finance/search?q=$encoded&quotesCount=0&newsCount=30")
        val items = response.news
            .filter { article -> article.relatedTickers.any { it.equals(symbol, ignoreCase = true) } }
            .mapNotNull { article ->
                val timestamp = article.providerPublishTime ?: return@mapNotNull null
                val headline = article.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val source = article.publisher?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                TimestampedSentiment(
                    score = HeadlineSentimentScorer.score(headline),
                    source = source,
                    publishedAt = Instant.ofEpochSecond(timestamp),
                    headline = headline,
                    url = article.link?.takeIf { it.startsWith("https://") },
                    scoringMethod = "Deterministic headline lexicon",
                )
            }
        return NewsSentimentBatch(PROVIDER, clock(), items)
    }

    private suspend fun chart(symbol: String, interval: String, range: String): YahooChartResult {
        val key = "$symbol:$interval:$range"
        return cacheMutex.withLock {
            val now = clock()
            chartCache[key]?.takeIf { Duration.between(it.retrievedAt, now).seconds in 0..10 }?.result
                ?: request<YahooChartResponse>("/v8/finance/chart/${encode(symbol)}?interval=$interval&range=$range")
                    .chart.result?.firstOrNull()
                    ?.also { chartCache[key] = CachedChart(now, it) }
                ?: throw MarketDataException.UnsupportedSymbol()
        }
    }

    private suspend inline fun <reified T> request(path: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .header("Accept", "application/json")
            .header("User-Agent", "StockMovementAnalyzer/1.4")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapStatus(response.code)
                val body = response.body?.string() ?: throw MarketDataException.InvalidResponse("empty body")
                try {
                    json.decodeFromString<T>(body)
                } catch (error: Exception) {
                    throw MarketDataException.InvalidResponse("malformed JSON")
                }
            }
        } catch (error: MarketDataException) {
            Log.w(TAG, "Yahoo Finance request failed: endpoint=${path.substringBefore('?')} type=${error::class.simpleName}")
            throw error
        } catch (error: IOException) {
            Log.w(TAG, "Yahoo Finance request failed: endpoint=${path.substringBefore('?')} type=Network")
            throw MarketDataException.NoInternet()
        }
    }

    private fun mapStatus(status: Int): MarketDataException = when (status) {
        404 -> MarketDataException.UnsupportedSymbol()
        429 -> MarketDataException.RateLimited()
        else -> MarketDataException.ProviderUnavailable()
    }

    private fun encode(value: String): String = URLEncoder.encode(value.trim().uppercase(), Charsets.UTF_8.name())

    private data class CachedChart(val retrievedAt: Instant, val result: YahooChartResult)

    private companion object {
        const val PROVIDER = "Yahoo Finance"
        const val TAG = "YahooMarketData"
    }
}

internal object HeadlineSentimentScorer {
    private val positive = setOf("beat", "beats", "bullish", "gain", "gains", "growth", "higher", "improve", "improves", "profit", "profits", "raise", "raises", "record", "surge", "surges", "upgrade", "upgrades")
    private val negative = setOf("bearish", "cut", "cuts", "decline", "declines", "downgrade", "downgrades", "drop", "drops", "fall", "falls", "fraud", "investigation", "lawsuit", "loss", "losses", "lower", "miss", "misses", "risk", "risks")

    fun score(headline: String): Double {
        val words = Regex("[a-z]+").findAll(headline.lowercase()).map { it.value }
        val raw = words.sumOf { word -> (if (word in positive) 1 else 0) - (if (word in negative) 1 else 0) }
        return (raw / 3.0).coerceIn(-1.0, 1.0)
    }
}

@Serializable
private data class YahooChartResponse(val chart: YahooChartContainer)

@Serializable
private data class YahooChartContainer(val result: List<YahooChartResult>? = null)

@Serializable
private data class YahooChartResult(
    val meta: YahooChartMeta,
    val timestamp: List<Long> = emptyList(),
    val indicators: YahooIndicators,
)

@Serializable
private data class YahooChartMeta(
    val regularMarketPrice: Double? = null,
    val regularMarketTime: Long? = null,
)

@Serializable
private data class YahooIndicators(val quote: List<YahooQuoteValues> = emptyList())

@Serializable
private data class YahooQuoteValues(
    val open: List<Double?> = emptyList(),
    val high: List<Double?> = emptyList(),
    val low: List<Double?> = emptyList(),
    val close: List<Double?> = emptyList(),
    val volume: List<Long?> = emptyList(),
)

@Serializable
private data class YahooSearchResponse(val news: List<YahooNewsItem> = emptyList())

@Serializable
private data class YahooNewsItem(
    val title: String? = null,
    val publisher: String? = null,
    val link: String? = null,
    val providerPublishTime: Long? = null,
    @SerialName("relatedTickers") val relatedTickers: List<String> = emptyList(),
)
