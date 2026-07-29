package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.data.model.WatchlistItem
import org.junit.Test

class WatchlistBehaviorTest {
    @Test
    fun addRemove_watchlistInMemoryBehavior() {
        val list = mutableListOf<WatchlistItem>()
        list.add(WatchlistItem("MSFT", null, null, "2026-01-01T00:00:00Z", true))
        list.removeIf { it.symbol == "MSFT" }

        assertThat(list).isEmpty()
    }
}
