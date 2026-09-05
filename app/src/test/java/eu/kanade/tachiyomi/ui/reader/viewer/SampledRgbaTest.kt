package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class SampledRgbaTest {

    @Test
    fun `sampling preserves RGBA channels and skips complete source rows`() {
        val image = ByteBuffer.wrap(
            byteArrayOf(
                10, 11, 12, 13, 20, 21, 22, 23, 30, 31, 32, 33, 40, 41, 42, 43,
                50, 51, 52, 53, 60, 61, 62, 63, 70, 71, 72, 73, 80, 81, 82, 83,
                90, 91, 92, 93, 100, 101, 102, 103, 110, 111, 112, 113, 120, 121, 122, 123,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )
        image.position(8)

        val sampled = sampleRgba(image, sourceWidth = 4, width = 2, height = 2, sampleSize = 2)
        val pixels = ByteArray(sampled.remaining()).also(sampled::get)

        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 30, 31, 32, 33, 90, 91, 92, 93, 110, 111, 112, 113), pixels)
        assertEquals(8, image.position())
    }

    @Test
    fun `a narrow image remains readable when its height requires sampling`() {
        val image = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))

        val sampled = sampleRgba(image, sourceWidth = 1, width = 1, height = 1, sampleSize = 2)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ByteArray(sampled.remaining()).also(sampled::get))
    }

    @Test
    fun `full resolution reads from the start without consuming the native buffer`() {
        val image = ByteBuffer.allocateDirect(8).put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val sampled = sampleRgba(image, sourceWidth = 2, width = 2, height = 1, sampleSize = 1)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ByteArray(sampled.remaining()).also(sampled::get))
        assertEquals(8, image.position())
    }
}
