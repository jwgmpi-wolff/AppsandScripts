package net.wolffentp.stockstreamportfolio.ui.viewmodel

import net.wolffentp.stockstreamportfolio.data.model.RotatingView

class ViewRotator {
    fun nextIndex(views: List<RotatingView>, current: Int): Int {
        if (views.isEmpty()) return 0
        return (current + 1) % views.size
    }

    fun previousIndex(views: List<RotatingView>, current: Int): Int {
        if (views.isEmpty()) return 0
        return (current - 1 + views.size) % views.size
    }

    fun shouldAutoRotate(activeView: RotatingView?): Boolean {
        return activeView != null && !activeView.isPaused && activeView.rotationIntervalSeconds > 0
    }
}
