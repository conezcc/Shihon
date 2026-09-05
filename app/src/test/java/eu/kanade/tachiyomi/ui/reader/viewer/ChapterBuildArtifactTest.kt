package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterBuildArtifactTest {
    @Test
    fun `build parallelism is fixed from one to eight with a default of two`() {
        assertEquals(1, ChapterPreprocessingArtifacts.MIN_PARALLELISM)
        assertEquals(8, ChapterPreprocessingArtifacts.MAX_PARALLELISM)
        assertEquals(1, ReaderPreferences.PREPROCESSING_THREADS_MIN)
        assertEquals(8, ReaderPreferences.PREPROCESSING_THREADS_MAX)
        assertEquals(2, ReaderPreferences.PREPROCESSING_THREADS_DEFAULT)
    }

    @Test
    fun `building keeps the full quality detector size`() {
        assertEquals(768, TextEnhancementMaskProcessor.MODEL_MAX_SIDE)
    }

    @Test
    fun `artifact format is product neutral`() {
        val formatIdentity = buildString {
            append(ChapterPreprocessingArtifacts.ARTIFACT_EXTENSION)
            append(ChapterPreprocessingArtifacts.DATABASE_SCHEMA)
        }

        assertEquals("ppc1", formatIdentity)
        assertFalse(formatIdentity.contains("shihon", ignoreCase = true))
        assertFalse(formatIdentity.contains("shihon", ignoreCase = true))
    }

    @Test
    fun `page payload compression is lossless`() {
        val original = ByteArray(768 * 768) { index ->
            when {
                index % 97 == 0 -> 0xff.toByte()
                index % 31 == 0 -> 0x70
                else -> 0
            }
        }

        val compressed = ChapterPreprocessingArtifacts.compressPayload(original)
        val restored = ChapterPreprocessingArtifacts.openPayload(compressed).use { it.readBytes() }

        assertArrayEquals(original, restored)
        assertTrue(compressed.size < original.size)
    }
}
