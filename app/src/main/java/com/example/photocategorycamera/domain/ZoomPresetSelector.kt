package com.example.photocategorycamera.domain

object ZoomPresetSelector {
    private val preferred = listOf(0.5f, 0.7f, 1f, 2f, 5f)

    fun forRange(minZoomRatio: Float, maxZoomRatio: Float): List<Float> {
        if (minZoomRatio <= 0f || maxZoomRatio < minZoomRatio) return emptyList()
        val supported = preferred.filter { it in minZoomRatio..maxZoomRatio }
        if (supported.size >= 2) return supported

        return buildList {
            addAll(supported)
            add(minZoomRatio)
            if (maxZoomRatio > minZoomRatio) add(maxZoomRatio)
        }.distinctBy { (it * 100).toInt() }.sorted()
    }
}
