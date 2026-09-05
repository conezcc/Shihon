package eu.kanade.tachiyomi.ui.reader.viewer

import java.nio.ByteBuffer

/** Copies a bounded RGBA sample without allocating a full-resolution bitmap. */
internal fun sampleRgba(
    source: ByteBuffer,
    sourceWidth: Int,
    width: Int,
    height: Int,
    sampleSize: Int,
): ByteBuffer {
    val input = source.duplicate()
    input.rewind()
    if (sampleSize == 1) return input
    val output = ByteBuffer.allocate(width * height * 4)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val offset = (y * sampleSize * sourceWidth + x * sampleSize) * 4
            repeat(4) { channel -> output.put(input.get(offset + channel)) }
        }
    }
    output.flip()
    return output
}
