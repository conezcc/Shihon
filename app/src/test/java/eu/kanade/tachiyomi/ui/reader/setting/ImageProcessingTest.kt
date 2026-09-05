package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImageProcessingTest {

    @Test
    fun `neutral values do not change the image`() {
        assertEquals(0f, ImageProcessing.brightness(ImageProcessing.BRIGHTNESS_DEFAULT))
        assertEquals(1f, ImageProcessing.contrast(ImageProcessing.CONTRAST_DEFAULT))
        assertEquals(1f, ImageProcessing.gamma(ImageProcessing.GAMMA_DEFAULT))
        assertEquals(0f, ImageProcessing.textEnhancement(ImageProcessing.TEXT_ENHANCEMENT_DEFAULT))
    }

    @Test
    fun `image processing values map to bounded shader uniforms`() {
        assertEquals(-0.25f, ImageProcessing.brightness(-20))
        assertEquals(0.25f, ImageProcessing.brightness(20))
        assertEquals(0.5f, ImageProcessing.contrast(-20))
        assertEquals(1.5f, ImageProcessing.contrast(20))
        assertEquals(0.5f, ImageProcessing.gamma(0))
        assertEquals(2f, ImageProcessing.gamma(300))
        assertEquals(0.5f, ImageProcessing.textEnhancement(5))
        assertEquals(1f, ImageProcessing.textEnhancement(10))
        assertEquals(2f, ImageProcessing.textEnhancement(20))
        assertEquals(2f, ImageProcessing.textEnhancement(30))
    }
}
