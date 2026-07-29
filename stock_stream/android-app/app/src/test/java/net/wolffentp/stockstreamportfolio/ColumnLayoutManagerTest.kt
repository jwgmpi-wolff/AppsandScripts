package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout
import net.wolffentp.stockstreamportfolio.ui.viewmodel.ColumnLayoutManager
import org.junit.Test

class ColumnLayoutManagerTest {
    private val manager = ColumnLayoutManager()

    @Test
    fun move_reordersColumns() {
        val layout = ColumnLayout(listOf("Symbol", "Last", "Bid"), emptySet(), "Compact", "2026-01-01T00:00:00Z")
        val updated = manager.move(layout, 0, 2)

        assertThat(updated.orderedColumns).containsExactly("Last", "Bid", "Symbol").inOrder()
    }

    @Test
    fun hide_persistsHiddenColumn() {
        val layout = manager.reset()
        val updated = manager.hide(layout, "Bid")

        assertThat(updated.hiddenColumns).contains("Bid")
    }
}
