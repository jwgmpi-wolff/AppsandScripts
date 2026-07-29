package net.wolffentp.stockstreamportfolio

import com.google.common.truth.Truth.assertThat
import net.wolffentp.stockstreamportfolio.data.model.RotatingView
import net.wolffentp.stockstreamportfolio.ui.viewmodel.ViewRotator
import org.junit.Test

class ViewRotatorTest {
    private val rotator = ViewRotator()
    private val view = RotatingView("1", "Quote", listOf("Symbol"), "Symbol", "Asc", null, 10, 5, false, "2026-01-01T00:00:00Z")

    @Test
    fun nextIndex_wraps() {
        val views = listOf(view, view.copy(id = "2"))
        assertThat(rotator.nextIndex(views, 1)).isEqualTo(0)
    }

    @Test
    fun shouldAutoRotate_respectsPause() {
        assertThat(rotator.shouldAutoRotate(view.copy(isPaused = true))).isFalse()
        assertThat(rotator.shouldAutoRotate(view.copy(isPaused = false))).isTrue()
    }
}
