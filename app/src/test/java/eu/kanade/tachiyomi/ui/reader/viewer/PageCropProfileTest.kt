package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageCropProfileTest {

    @Test
    fun `near sixteen by nine dimensions share one ratio`() {
        val standard = PageAspectRatio.fromDimensions(1600, 900)
        val rounded = PageAspectRatio.fromDimensions(159, 89)

        assertEquals(PageAspectRatio(16, 9), standard)
        assertEquals(standard, rounded)
    }

    @Test
    fun `different common ratios remain independent`() {
        assertEquals(PageAspectRatio(16, 9), PageAspectRatio.fromDimensions(1920, 1080))
        assertEquals(PageAspectRatio(20, 9), PageAspectRatio.fromDimensions(2000, 900))
    }

    @Test
    fun `near portrait ratios use the same common ratio`() {
        assertEquals(PageAspectRatio(2, 3), PageAspectRatio.fromDimensions(56, 87))
        assertEquals(PageAspectRatio(2, 3), PageAspectRatio.fromDimensions(20, 31))
    }

    @Test
    fun `crop profiles survive preference serialization`() {
        val ratio = PageAspectRatio(16, 9)
        val profile = PageCropProfile(
            ratio = ratio,
            scaleByViewWidth = 1.25F,
            centerX = 0.53F,
            centerY = 0.49F,
            top = 0.05F,
            bottom = 0.03F,
            left = 0.10F,
            right = 0.04F,
        )

        val restored = PageCropProfiles.parse(PageCropProfiles.serialize(mapOf(ratio to profile)))

        assertEquals(profile, restored[ratio])
    }
}
