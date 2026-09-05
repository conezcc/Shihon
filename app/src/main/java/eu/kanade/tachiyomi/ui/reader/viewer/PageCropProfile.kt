package eu.kanade.tachiyomi.ui.reader.viewer

import kotlin.math.abs

data class PageAspectRatio(
    val width: Int,
    val height: Int,
) {
    init {
        require(width in 1..99 && height in 1..99)
    }

    val key: String = "$width:$height"

    override fun toString(): String = key

    companion object {
        private val commonRatios = listOf(
            PageAspectRatio(1, 1),
            PageAspectRatio(5, 4),
            PageAspectRatio(4, 3),
            PageAspectRatio(3, 2),
            PageAspectRatio(16, 9),
            PageAspectRatio(2, 1),
            PageAspectRatio(20, 9),
            PageAspectRatio(21, 9),
            PageAspectRatio(4, 5),
            PageAspectRatio(3, 4),
            PageAspectRatio(2, 3),
            PageAspectRatio(9, 16),
            PageAspectRatio(1, 2),
            PageAspectRatio(9, 20),
            PageAspectRatio(9, 21),
        )

        fun fromDimensions(width: Int, height: Int): PageAspectRatio? {
            if (width <= 0 || height <= 0) return null

            val value = width.toDouble() / height
            return commonRatios.minBy { abs(value - it.value) }
        }

        fun parse(value: String): PageAspectRatio? {
            val parts = value.split(':')
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            return runCatching { PageAspectRatio(width, height) }.getOrNull()
        }

        private val PageAspectRatio.value: Double
            get() = width.toDouble() / height
    }
}

data class PageCropProfile(
    val ratio: PageAspectRatio,
    val scaleByViewWidth: Float,
    val centerX: Float,
    val centerY: Float,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
)

data class PageCropState(
    val available: Boolean = false,
    val active: Boolean = false,
    val ratio: PageAspectRatio? = null,
    val topPercent: Int = 0,
    val bottomPercent: Int = 0,
    val leftPercent: Int = 0,
    val rightPercent: Int = 0,
)

object PageCropProfiles {
    fun parse(value: String): Map<PageAspectRatio, PageCropProfile> = value
        .split(';')
        .mapNotNull(::parseEntry)
        .associateBy(PageCropProfile::ratio)

    fun serialize(profiles: Map<PageAspectRatio, PageCropProfile>): String = profiles.values
        .sortedWith(compareBy({ it.ratio.width }, { it.ratio.height }))
        .joinToString(";") { profile ->
            listOf(
                profile.ratio.key,
                profile.scaleByViewWidth,
                profile.centerX,
                profile.centerY,
                profile.top,
                profile.bottom,
                profile.left,
                profile.right,
            ).joinToString(",")
        }

    private fun parseEntry(value: String): PageCropProfile? {
        val parts = value.split(',')
        if (parts.size != 8) return null
        val ratio = PageAspectRatio.parse(parts[0]) ?: return null
        val values = parts.drop(1).map { it.toFloatOrNull() ?: return null }
        if (values.any { !it.isFinite() }) return null
        if (values[0] <= 0F || values.drop(1).any { it !in 0F..1F }) return null
        return PageCropProfile(
            ratio = ratio,
            scaleByViewWidth = values[0],
            centerX = values[1],
            centerY = values[2],
            top = values[3],
            bottom = values[4],
            left = values[5],
            right = values[6],
        )
    }
}
