package eu.kanade.tachiyomi.ui.reader.setting

import kotlin.math.pow

object ImageProcessing {
    const val BRIGHTNESS_MIN = -10
    const val BRIGHTNESS_MAX = 10
    const val BRIGHTNESS_DEFAULT = 0

    const val CONTRAST_MIN = -10
    const val CONTRAST_MAX = 10
    const val CONTRAST_DEFAULT = 0

    const val GAMMA_MIN = 50
    const val GAMMA_MAX = 200
    const val GAMMA_DEFAULT = 100

    const val TEXT_ENHANCEMENT_MIN = 0
    const val TEXT_ENHANCEMENT_MAX = 20
    const val TEXT_ENHANCEMENT_DEFAULT = 0

    fun brightness(value: Int): Float {
        return value.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX) / 40f
    }

    fun contrast(value: Int): Float {
        return 1f + value.coerceIn(CONTRAST_MIN, CONTRAST_MAX) / 20f
    }

    fun gamma(value: Int): Float {
        return value.coerceIn(GAMMA_MIN, GAMMA_MAX) / 100f
    }

    fun textEnhancement(value: Int): Float {
        return value.coerceIn(TEXT_ENHANCEMENT_MIN, TEXT_ENHANCEMENT_MAX) / 10f
    }

    fun fallbackGammaContrast(value: Int): Float {
        return 2f * (1f - 0.5f.pow(gamma(value)))
    }
}
