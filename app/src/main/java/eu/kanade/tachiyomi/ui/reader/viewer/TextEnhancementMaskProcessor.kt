package eu.kanade.tachiyomi.ui.reader.viewer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import ca.mpreg.imagedecoder.ImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Produces a soft mask for faint text-like ink. The detector only locates likely
 * text regions; source darkness and local contrast determine which pixels are
 * actually darkened. The local contrast gate prevents a detector region over a
 * gray or halftone background from turning into a solid shadow.
 */
object TextEnhancementMaskProcessor {

    internal const val CACHE_ALGORITHM_VERSION = "v7"

    private val cacheMutex = Mutex()

    @Volatile
    private var runtime: Runtime? = null

    suspend fun createMask(
        context: Context,
        source: BufferedSource,
        cropBorders: Boolean,
    ): Bitmap? {
        return try {
            val cacheKey = contentKey(source, cropBorders)
            createMask(context.applicationContext, source, cropBorders, cacheKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Text enhancement mask generation failed" }
            null
        }
    }

    internal suspend fun cacheMask(
        context: Context,
        source: BufferedSource,
        cacheKey: String,
    ): Boolean {
        if (hasPersistentMask(context, cacheKey)) return true
        val mask = createMask(context.applicationContext, source, cropBorders = false, cacheKey) ?: return false
        mask.recycle()
        return promoteToPersistentCache(context, cacheKey)
    }

    /** Generates the full-quality mask payload stored inside a chapter build artifact. */
    internal suspend fun createArtifactMask(
        context: Context,
        source: BufferedSource,
    ): ByteArray? {
        val mask = detect(context.applicationContext, source, cropBorders = false) ?: return null
        try {
            return ByteArrayOutputStream(mask.byteCount + 16).use { bytes ->
                val pixels = ByteBuffer.allocate(mask.byteCount)
                mask.copyPixelsToBuffer(pixels)
                DataOutputStream(bytes).use { output ->
                    output.writeInt(ARTIFACT_MASK_MAGIC)
                    output.writeInt(mask.width)
                    output.writeInt(mask.height)
                    output.writeInt(pixels.position())
                    output.write(pixels.array(), 0, pixels.position())
                }
                bytes.toByteArray()
            }
        } finally {
            mask.recycle()
        }
    }

    internal fun readArtifactMask(input: InputStream): Bitmap? {
        return runCatching {
            DataInputStream(BufferedInputStream(input)).use { data ->
                if (data.readInt() != ARTIFACT_MASK_MAGIC) return@use null
                val width = data.readInt()
                val height = data.readInt()
                val byteCount = data.readInt()
                if (width !in 1..MODEL_MAX_SIDE || height !in 1..MODEL_MAX_SIDE) return@use null
                if (byteCount !in width * height..(width + 4) * height) return@use null
                val alpha = ByteArray(byteCount)
                data.readFully(alpha)
                Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
                }
            }
        }.getOrNull()
    }

    private suspend fun createMask(
        context: Context,
        source: BufferedSource,
        cropBorders: Boolean,
        cacheKey: String,
    ): Bitmap? {
        loadCachedMask(context, cacheKey)?.let { return it }

        return cacheMutex.withLock {
            loadCachedMask(context, cacheKey)?.let { return@withLock it }
            val mask = detect(context, source, cropBorders) ?: return@withLock null
            saveCachedMask(context, cacheKey, mask)
            mask
        }
    }

    internal fun hasPersistentMask(context: Context, cacheKey: String): Boolean {
        return persistentCacheDirectory(context.applicationContext).resolve("$cacheKey.mask").isFile
    }

    internal fun deleteCachedMask(context: Context, cacheKey: String) {
        cacheDirectory(context.applicationContext).resolve("$cacheKey.mask").delete()
        persistentCacheDirectory(context.applicationContext).resolve("$cacheKey.mask").delete()
    }

    internal fun loadPersistentMask(context: Context, cacheKey: String): Bitmap? {
        val file = persistentCacheDirectory(context.applicationContext).resolve("$cacheKey.mask")
        return file.takeIf(File::isFile)?.let { readCachedMask(it, touch = false) }
    }

    internal fun deletePersistentMasks(context: Context, keyPrefix: String) {
        persistentCacheDirectory(context.applicationContext).listFiles { file ->
            file.name.startsWith(keyPrefix) && file.extension == "mask"
        }.orEmpty().forEach(File::delete)
    }

    private fun detect(context: Context, source: BufferedSource, cropBorders: Boolean): Bitmap? {
        val decoder = source.peek().inputStream().use(ImageDecoder::new)
        val result = decoder.decode(crop = cropBorders)
        var sampleSize = 1
        while (max(result.width, result.height) / sampleSize > MAX_DECODE_SIDE) {
            sampleSize *= 2
        }
        val width = (result.width / sampleSize).coerceAtLeast(1)
        val height = (result.height / sampleSize).coerceAtLeast(1)
        // The decoder owns the full native RGBA buffer. Allocate only the sampled
        // bitmap on the Java side so parallel chapter processing stays bounded.
        val pixels = sampleRgba(result.image, result.width, width, height, sampleSize)
        val decoded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(pixels)
        }

        val inputSize = modelInputSize(decoded.width, decoded.height)
        val scaled = if (decoded.width == inputSize.width && decoded.height == inputSize.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, inputSize.width, inputSize.height, true).also {
                decoded.recycle()
            }
        }

        return try {
            runModel(context, scaled)
        } finally {
            scaled.recycle()
        }
    }

    private fun runModel(context: Context, bitmap: Bitmap): Bitmap? {
        val activeRuntime = runtime ?: synchronized(this) {
            runtime ?: createRuntime(context).also { runtime = it }
        }
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = ByteArray(pixelCount)
        val lumaIntegral = buildLumaIntegral(pixels, luma, width, height)

        val input = ByteBuffer.allocateDirect(pixelCount * CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        putNormalizedChannel(input, pixels, BLUE_SHIFT, BLUE_MEAN, BLUE_STD)
        putNormalizedChannel(input, pixels, GREEN_SHIFT, GREEN_MEAN, GREEN_STD)
        putNormalizedChannel(input, pixels, RED_SHIFT, RED_MEAN, RED_STD)
        input.rewind()

        val shape = longArrayOf(1, CHANNELS.toLong(), height.toLong(), width.toLong())
        OnnxTensor.createTensor(activeRuntime.environment, input, shape).use { tensor ->
            activeRuntime.session.run(mapOf(activeRuntime.inputName to tensor)).use { result ->
                val output = (result[0] as? OnnxTensor)?.floatBuffer ?: return null
                val alpha = ByteArray(pixelCount)
                for (index in 0 until pixelCount) {
                    val color = pixels[index]
                    val x = index % width
                    val y = index / width
                    val pixelLuma = luma[index].toInt() and 0xff
                    val darkness = 1f - pixelLuma / 255f
                    val localContrast = (
                        localMeanLuma(lumaIntegral, width, height, x, y) - pixelLuma
                        ).coerceAtLeast(0f) / 255f
                    val confidence = smoothstep(CONFIDENCE_LOW, CONFIDENCE_HIGH, output.get(index))
                    val ink = smoothstep(INK_LOW, INK_HIGH, darkness)
                    val detail = smoothstep(LOCAL_CONTRAST_LOW, LOCAL_CONTRAST_HIGH, localContrast)
                    alpha[index] = (255f * confidence * ink * detail).roundToInt().coerceIn(0, 255).toByte()
                }
                return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
                }
            }
        }
    }

    private fun buildLumaIntegral(pixels: IntArray, luma: ByteArray, width: Int, height: Int): IntArray {
        val stride = width + 1
        val integral = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            val sourceRow = y * width
            val previousIntegralRow = y * stride
            val integralRow = (y + 1) * stride
            for (x in 0 until width) {
                val sourceIndex = sourceRow + x
                val pixelLuma = luma(pixels[sourceIndex])
                luma[sourceIndex] = pixelLuma.toByte()
                rowSum += pixelLuma
                integral[integralRow + x + 1] = integral[previousIntegralRow + x + 1] + rowSum
            }
        }
        return integral
    }

    private fun localMeanLuma(
        integral: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
    ): Float {
        val left = max(0, x - LOCAL_CONTRAST_RADIUS)
        val top = max(0, y - LOCAL_CONTRAST_RADIUS)
        val right = min(width, x + LOCAL_CONTRAST_RADIUS + 1)
        val bottom = min(height, y + LOCAL_CONTRAST_RADIUS + 1)
        val stride = width + 1
        val sum = integral[bottom * stride + right] -
            integral[top * stride + right] -
            integral[bottom * stride + left] +
            integral[top * stride + left]
        return sum.toFloat() / ((right - left) * (bottom - top))
    }

    private fun luma(color: Int): Int {
        val red = color shr RED_SHIFT and 0xff
        val green = color shr GREEN_SHIFT and 0xff
        val blue = color shr BLUE_SHIFT and 0xff
        return (LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue) / LUMA_DIVISOR
    }

    private fun putNormalizedChannel(
        output: java.nio.FloatBuffer,
        pixels: IntArray,
        shift: Int,
        mean: Float,
        standardDeviation: Float,
    ) {
        pixels.forEach { color ->
            val channel = (color shr shift and 0xff) / 255f
            output.put((channel - mean) / standardDeviation)
        }
    }

    private fun createRuntime(context: Context): Runtime {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(INFERENCE_THREADS)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
            // The arena retained over 100 MB after inference in regression tests.
            // Disabling it trades a small amount of latency for stable reader memory.
            setCPUArenaAllocator(false)
        }
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val session = environment.createSession(model, options)
        return Runtime(environment, session, options, session.inputNames.first())
    }

    private fun modelInputSize(width: Int, height: Int): Size {
        val ratio = min(1f, MODEL_MAX_SIDE.toFloat() / max(width, height))
        return Size(
            width = max(MODEL_STRIDE, (width * ratio / MODEL_STRIDE).roundToInt() * MODEL_STRIDE),
            height = max(MODEL_STRIDE, (height * ratio / MODEL_STRIDE).roundToInt() * MODEL_STRIDE),
        )
    }

    private fun smoothstep(low: Float, high: Float, value: Float): Float {
        val scaled = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return scaled * scaled * (3f - 2f * scaled)
    }

    private fun contentKey(source: BufferedSource, cropBorders: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.peek().inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return "$CACHE_ALGORITHM_VERSION-${if (cropBorders) 'c' else 'u'}-$hash"
    }

    private fun loadCachedMask(context: Context, key: String): Bitmap? {
        val files = sequenceOf(
            persistentCacheDirectory(context).resolve("$key.mask") to false,
            cacheDirectory(context).resolve("$key.mask") to true,
        )
        for ((file, touch) in files) {
            if (!file.isFile) continue
            readCachedMask(file, touch)?.let { return it }
        }
        return null
    }

    private fun readCachedMask(file: File, touch: Boolean): Bitmap? {
        return try {
            val mask = DataInputStream(GZIPInputStream(BufferedInputStream(file.inputStream()))).use { input ->
                if (input.readInt() != MASK_CACHE_MAGIC) return@use null
                val width = input.readInt()
                val height = input.readInt()
                val byteCount = input.readInt()
                if (width !in 1..MODEL_MAX_SIDE || height !in 1..MODEL_MAX_SIDE) return@use null
                if (byteCount !in width * height..(width + 4) * height) return@use null
                val alpha = ByteArray(byteCount)
                input.readFully(alpha)
                Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
                    copyPixelsFromBuffer(ByteBuffer.wrap(alpha))
                }
            }
            if (mask == null) {
                file.delete()
            } else if (touch) {
                file.setLastModified(System.currentTimeMillis())
            }
            mask
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    private fun promoteToPersistentCache(context: Context, key: String): Boolean {
        val destination = persistentCacheDirectory(context).resolve("$key.mask")
        if (destination.isFile) return true
        val source = cacheDirectory(context).resolve("$key.mask")
        if (!source.isFile) return false

        return try {
            val temporary = destination.parentFile!!.resolve("$key.${System.nanoTime()}.tmp")
            source.copyTo(temporary, overwrite = true)
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                false
            } else {
                source.delete()
                true
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Unable to persist text enhancement mask" }
            false
        }
    }

    private fun saveCachedMask(context: Context, key: String, mask: Bitmap) {
        try {
            val directory = cacheDirectory(context)
            val destination = directory.resolve("$key.mask")
            val temporary = directory.resolve("$key.${System.nanoTime()}.tmp")
            val pixels = ByteBuffer.allocate(mask.byteCount)
            mask.copyPixelsToBuffer(pixels)
            DataOutputStream(FastGzipOutputStream(BufferedOutputStream(temporary.outputStream()))).use { output ->
                output.writeInt(MASK_CACHE_MAGIC)
                output.writeInt(mask.width)
                output.writeInt(mask.height)
                output.writeInt(pixels.position())
                output.write(pixels.array(), 0, pixels.position())
            }
            if (destination.exists()) destination.delete()
            if (!temporary.renameTo(destination)) temporary.delete()
            trimCache(directory)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Unable to cache text enhancement mask" }
        }
    }

    private fun cacheDirectory(context: Context): File {
        return context.cacheDir.resolve(CACHE_DIRECTORY).apply { mkdirs() }
    }

    private fun persistentCacheDirectory(context: Context): File {
        return context.noBackupFilesDir.resolve(PERSISTENT_CACHE_DIRECTORY).apply { mkdirs() }
    }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "mask" }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private data class Runtime(
        val environment: OrtEnvironment,
        val session: OrtSession,
        @Suppress("unused") val options: OrtSession.SessionOptions,
        val inputName: String,
    )

    private data class Size(val width: Int, val height: Int)

    private class FastGzipOutputStream(output: BufferedOutputStream) : GZIPOutputStream(output) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }

    private const val MODEL_ASSET = "text_enhancement/ppocrv5_mobile_det.onnx"
    private const val CACHE_DIRECTORY = "text-enhancement-mask-v4"
    private const val PERSISTENT_CACHE_DIRECTORY = "text-enhancement-mask-v5"
    private const val MASK_CACHE_MAGIC = 0x544d4331 // "TMC1": text-mask cache, format 1.
    private const val ARTIFACT_MASK_MAGIC = 0x50504d31
    private const val MAX_CACHE_BYTES = 64L * 1024L * 1024L
    private const val HASH_BUFFER_SIZE = 32 * 1024
    internal const val MODEL_MAX_SIDE = 768
    private const val MAX_DECODE_SIDE = MODEL_MAX_SIDE * 2
    private const val MODEL_STRIDE = 32
    private const val CHANNELS = 3

    // Page-level concurrency is controlled by the build thread preference.
    private const val INFERENCE_THREADS = 1

    private const val BLUE_SHIFT = 0
    private const val GREEN_SHIFT = 8
    private const val RED_SHIFT = 16
    private const val BLUE_MEAN = 0.485f
    private const val GREEN_MEAN = 0.456f
    private const val RED_MEAN = 0.406f
    private const val BLUE_STD = 0.229f
    private const val GREEN_STD = 0.224f
    private const val RED_STD = 0.225f

    private const val CONFIDENCE_LOW = 0.04f
    private const val CONFIDENCE_HIGH = 0.25f
    private const val INK_LOW = 0.025f
    private const val INK_HIGH = 0.30f

    private const val LOCAL_CONTRAST_RADIUS = 6
    private const val LOCAL_CONTRAST_LOW = 0.025f
    private const val LOCAL_CONTRAST_HIGH = 0.16f

    private const val LUMA_RED = 77
    private const val LUMA_GREEN = 150
    private const val LUMA_BLUE = 29
    private const val LUMA_DIVISOR = 256
}
