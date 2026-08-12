package com.wolffentp.stockanalyzer.ui

import com.wolffentp.stockanalyzer.domain.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionPaletteTest {
    @Test fun riseIsBlue() = assertEquals(0xFF1565C0, DirectionPalette.argb(Direction.UP))
    @Test fun dropIsPurple() = assertEquals(0xFF7B1FA2, DirectionPalette.argb(Direction.DOWN))
    @Test fun neutralIsGray() = assertEquals(0xFF6B6B72, DirectionPalette.argb(Direction.NEUTRAL))
    @Test fun insufficientIsGray() = assertEquals(0xFF6B6B72, DirectionPalette.argb(Direction.NEUTRAL_INSUFFICIENT_DATA))
}