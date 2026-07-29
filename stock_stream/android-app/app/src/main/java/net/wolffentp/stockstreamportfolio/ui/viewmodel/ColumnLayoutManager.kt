package net.wolffentp.stockstreamportfolio.ui.viewmodel

import net.wolffentp.stockstreamportfolio.data.model.ColumnCatalog
import net.wolffentp.stockstreamportfolio.data.model.ColumnLayout

class ColumnLayoutManager {
    fun move(layout: ColumnLayout, from: Int, to: Int): ColumnLayout {
        if (from !in layout.orderedColumns.indices || to !in layout.orderedColumns.indices) {
            return layout
        }

        val mutable = layout.orderedColumns.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)

        return layout.copy(orderedColumns = mutable, updatedAtUtc = now())
    }

    fun hide(layout: ColumnLayout, column: String): ColumnLayout {
        if (!ColumnCatalog.all.contains(column)) return layout
        return layout.copy(hiddenColumns = layout.hiddenColumns + column, updatedAtUtc = now())
    }

    fun unhide(layout: ColumnLayout, column: String): ColumnLayout {
        return layout.copy(hiddenColumns = layout.hiddenColumns - column, updatedAtUtc = now())
    }

    fun reset(displayDensity: String = "Compact"): ColumnLayout {
        return ColumnLayout(
            orderedColumns = ColumnCatalog.all,
            hiddenColumns = emptySet(),
            displayDensity = displayDensity,
            updatedAtUtc = now()
        )
    }

    private fun now(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
}
