package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import kotlin.math.roundToInt

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null

    private var config: Config? = null

    private var landscapeZoomRunnable: Runnable? = null

    private var landscapeZoomPreparationRunnable: Runnable? = null

    private var landscapeZoomOriginalScaleType: Int? = null

    private var landscapeZoomGeneration = 0

    private var landscapeZoomPreviewStarted = false

    private var selectedForward: Boolean? = null

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        // Selection and image-ready notifications can both arrive for the same page.
        // onReady handles a still-loading image; a duplicate must not reposition a
        // running preview or a viewport that the reader has already panned.
        if (selectedForward != null) return
        selectedForward = forward
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                if (!applyPageCropProfile()) {
                    positionForPageEntry(forward)
                    landscapeZoom(forward)
                }
            }
        }
    }

    open fun onPageDeselected() {
        selectedForward = null
        val view = pageView as? SubsamplingScaleImageView
        if (view == null || !view.resetLandscapeZoomPreview()) {
            cancelLandscapeZoomPreview()
        }
    }

    private fun cancelLandscapeZoomPreview() {
        clearLandscapeZoomPreviewCallbacks()
        landscapeZoomPreviewStarted = false
        landscapeZoomOriginalScaleType?.let { originalScaleType ->
            (pageView as? SubsamplingScaleImageView)?.setMinimumScaleType(originalScaleType)
        }
        landscapeZoomOriginalScaleType = null
    }

    private fun clearLandscapeZoomPreviewCallbacks() {
        landscapeZoomGeneration++
        landscapeZoomRunnable?.let { pageView?.removeCallbacks(it) }
        landscapeZoomRunnable = null
        landscapeZoomPreparationRunnable?.let { pageView?.removeCallbacks(it) }
        landscapeZoomPreparationRunnable = null
    }

    private fun SubsamplingScaleImageView.resetLandscapeZoomPreview(): Boolean {
        val currentConfig = config ?: return false
        if (
            !currentConfig.landscapeZoom ||
            !currentConfig.landscapeZoomPreview() ||
            !isReady ||
            sWidth <= sHeight ||
            hasActivePageCropProfile()
        ) {
            return false
        }

        clearLandscapeZoomPreviewCallbacks()
        landscapeZoomPreviewStarted = false
        if (currentConfig.minimumScaleType != SCALE_TYPE_CENTER_INSIDE) {
            landscapeZoomOriginalScaleType = currentConfig.minimumScaleType
            setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
        }
        setScaleAndCenter(
            minOf(width.toFloat() / sWidth.toFloat(), height.toFloat() / sHeight.toFloat()),
            PointF(sWidth / 2F, sHeight / 2F),
        )
        invalidate()
        return true
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val currentConfig = config
        if (currentConfig == null || !currentConfig.landscapeZoom || sWidth <= sHeight) {
            cancelLandscapeZoomPreview()
            return
        }

        val preview = forward && currentConfig.landscapeZoomPreview()
        if (preview && landscapeZoomPreviewStarted) return

        clearLandscapeZoomPreviewCallbacks()
        val generation = landscapeZoomGeneration
        val startAtBeginning = forward || !currentConfig.navigatePageSegmentsBackward
        val point = when (currentConfig.zoomStartPosition) {
            ZoomStartPosition.LEFT -> if (startAtBeginning) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
            ZoomStartPosition.RIGHT -> if (startAtBeginning) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
            ZoomStartPosition.CENTER -> center
        }
        val targetScale = height.toFloat() / sHeight.toFloat()
        if (preview) {
            landscapeZoomPreviewStarted = true
            val fullScale = minOf(
                width.toFloat() / sWidth.toFloat(),
                height.toFloat() / sHeight.toFloat(),
            )
            val fullCenter = PointF(sWidth / 2F, sHeight / 2F)
            if (
                currentConfig.minimumScaleType != SCALE_TYPE_CENTER_INSIDE &&
                landscapeZoomOriginalScaleType == null
            ) {
                landscapeZoomOriginalScaleType = currentConfig.minimumScaleType
                setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
            }
            setScaleAndCenter(fullScale, fullCenter)
            invalidate()

            val preparation = object : Runnable {
                override fun run() {
                    if (landscapeZoomPreparationRunnable !== this) return
                    if (!isReady || !isImageLoaded || !isVisibleOnScreen()) {
                        postOnAnimation(this)
                        return
                    }

                    setScaleAndCenter(fullScale, fullCenter)
                    invalidate()
                    postOnAnimation {
                        postOnAnimation {
                            if (landscapeZoomPreparationRunnable !== this) return@postOnAnimation
                            landscapeZoomPreparationRunnable = null
                            val zoom = Runnable {
                                if (
                                    generation != landscapeZoomGeneration ||
                                    selectedForward != true ||
                                    pageView !== this@landscapeZoom ||
                                    !isReady
                                ) {
                                    return@Runnable
                                }
                                landscapeZoomRunnable = null
                                val animation = animateScaleAndCenter(targetScale, point) ?: return@Runnable
                                animation
                                    .withDuration(LANDSCAPE_ZOOM_DURATION_MILLIS)
                                    .withEasing(EASE_IN_OUT_QUAD)
                                    .withInterruptible(true)
                                    .withOnAnimationEventListener(
                                        object : SubsamplingScaleImageView.DefaultOnAnimationEventListener() {
                                            override fun onComplete() {
                                                restoreLandscapeZoomScaleType(this@landscapeZoom, generation)
                                            }

                                            override fun onInterruptedByUser() {
                                                restoreLandscapeZoomScaleType(this@landscapeZoom, generation)
                                            }

                                            override fun onInterruptedByNewAnim() {
                                                restoreLandscapeZoomScaleType(this@landscapeZoom, generation)
                                            }
                                        },
                                    )
                                    .start()
                            }
                            landscapeZoomRunnable = zoom
                            if (
                                handler?.postDelayed(
                                    zoom,
                                    currentConfig.landscapeZoomPreviewDurationMillis(),
                                ) != true
                            ) {
                                landscapeZoomRunnable = null
                            }
                        }
                    }
                }
            }
            landscapeZoomPreparationRunnable = preparation
            postOnAnimation(preparation)
        } else {
            cancelLandscapeZoomPreview()
            setScaleAndCenter(targetScale, point)
        }
    }

    private fun restoreLandscapeZoomScaleType(view: SubsamplingScaleImageView, generation: Int) {
        if (generation != landscapeZoomGeneration) return
        val originalScaleType = landscapeZoomOriginalScaleType ?: return
        landscapeZoomOriginalScaleType = null
        view.setMinimumScaleType(originalScaleType)
    }

    private fun SubsamplingScaleImageView.positionForPageEntry(forward: Boolean) {
        val currentConfig = config ?: return
        if (!currentConfig.navigatePageSegments || (!forward && !currentConfig.navigatePageSegmentsBackward)) return
        val entry = pageEntryPosition(forward, currentConfig.pageSegmentForwardHorizontalDirection)
        setScaleAndCenter(
            scale,
            PointF(
                if (entry.horizontal == PagePanDirection.LEFT) 0F else sWidth.toFloat(),
                if (entry.vertical == PagePanDirection.UP) 0F else sHeight.toFloat(),
            ),
        )
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() {
        selectedForward = null
        cancelLandscapeZoomPreview()
        pageView?.let {
            when (it) {
                is SubsamplingScaleImageView -> it.recycle()
                is AppCompatImageView -> it.dispose()
            }
            it.isVisible = false
        }
    }

    fun setTextEnhancementMask(mask: android.graphics.Bitmap?, strength: Int) {
        (pageView as? InkSubsamplingImageView)?.setTextEnhancementMask(mask, strength)
            ?: mask?.recycle()
    }

    fun setTextEnhancementStrength(strength: Int) {
        (pageView as? InkSubsamplingImageView)?.setTextEnhancementStrength(strength)
    }

    fun clearTextEnhancementMask() {
        (pageView as? InkSubsamplingImageView)?.clearTextEnhancementMask()
    }

    /**
     * Moves to the next still-hidden part of an enlarged image. The axis with the most overflow
     * is preferred so tall pages move vertically and wide pages move horizontally.
     */
    internal fun panForPageTurn(
        horizontalDirection: PagePanDirection,
        verticalDirection: PagePanDirection,
        smoothly: Boolean,
    ): Boolean {
        val view = pageView as? SubsamplingScaleImageView ?: return false
        if (!view.isReady) return false
        if (view.hasActivePageCropProfile()) return false

        val remaining = RectF().also(view::getPanRemaining)
        val direction = selectPagePanDirection(
            PagePanRemaining(remaining.left, remaining.top, remaining.right, remaining.bottom),
            horizontalDirection,
            verticalDirection,
        ) ?: return false

        view.pan(direction, smoothly)
        return true
    }

    private fun SubsamplingScaleImageView.pan(direction: PagePanDirection, smoothly: Boolean) {
        val currentCenter = center ?: return
        val target = PointF(currentCenter.x, currentCenter.y)
        when (direction) {
            PagePanDirection.LEFT -> target.x -= width / scale
            PagePanDirection.RIGHT -> target.x += width / scale
            PagePanDirection.UP -> target.y -= height / scale
            PagePanDirection.DOWN -> target.y += height / scale
        }

        if (smoothly) {
            animateCenter(target)?.withEasing(EASE_OUT_QUAD)
                ?.withDuration(PAN_DURATION_MILLIS)
                ?.withInterruptible(true)
                ?.start()
        } else {
            setScaleAndCenter(scale, target)
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            InkSubsamplingImageView(context)
        }.apply {
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                        onViewportChanged?.invoke()
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        onViewportChanged?.invoke()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        isPanEnabled = true
        isZoomEnabled = true
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        cancelLandscapeZoomPreview()
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        val imageDimensions = when (data) {
            is BitmapDrawable -> ImageUtil.ImageDimensions(data.bitmap.width, data.bitmap.height)
            is BufferedSource -> ImageUtil.getImageDimensions(data)
            else -> null
        }
        val prepareLandscapeZoomPreview =
            config.landscapeZoom &&
                config.landscapeZoomPreview() &&
                config.minimumScaleType != SCALE_TYPE_CENTER_INSIDE &&
                imageDimensions != null &&
                imageDimensions.width > imageDimensions.height
        val initialPreviewState = if (prepareLandscapeZoomPreview) {
            val dimensions = checkNotNull(imageDimensions)
            ImageViewState(
                0F,
                PointF(dimensions.width / 2F, dimensions.height / 2F),
            )
        } else {
            null
        }
        if (prepareLandscapeZoomPreview) {
            landscapeZoomOriginalScaleType = config.minimumScaleType
            setMinimumScaleType(SCALE_TYPE_CENTER_INSIDE)
        } else {
            setMinimumScaleType(config.minimumScaleType)
        }
        isVisible = true
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    val pageCropApplied = applyPageCropProfile()
                    if (!pageCropApplied) {
                        val forward = selectedForward
                        if (forward != null) {
                            positionForPageEntry(forward)
                            landscapeZoom(forward)
                        } else {
                            resetLandscapeZoomPreview()
                        }
                    } else {
                        cancelLandscapeZoomPreview()
                    }
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoaded() {
                    val forward = selectedForward
                    if (!hasActivePageCropProfile() && forward != null) {
                        landscapeZoom(forward)
                    }
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                val imageSource = ImageSource.bitmap(data.bitmap)
                if (initialPreviewState != null) {
                    setImage(imageSource, initialPreviewState)
                } else {
                    setImage(imageSource)
                }
            }
            is BufferedSource -> {
                if (!isWebtoon) {
                    val imageSource = ImageSource.inputStream(data.inputStream())
                    if (initialPreviewState != null) {
                        setImage(imageSource, initialPreviewState)
                    } else {
                        setImage(imageSource)
                    }
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    fun currentPageCropState(): PageCropState? {
        val view = pageView as? SubsamplingScaleImageView ?: return null
        if (!view.isReady) return null
        val profile = view.capturePageCropProfile() ?: return null
        return profile.toState(
            active = !config!!.cropBorders && config!!.pageCropProfileProvider(view.sWidth, view.sHeight) != null,
        )
    }

    fun capturePageCropProfile(): PageCropProfile? {
        val view = pageView as? SubsamplingScaleImageView ?: return null
        if (!view.isReady) return null
        return view.capturePageCropProfile()
    }

    fun resetPageCrop() {
        val view = pageView as? SubsamplingScaleImageView ?: return
        if (!view.isReady) return
        view.resetScaleAndCenter()
        view.setupZoom(config)
        if (view.isVisibleOnScreen()) view.landscapeZoom(true)
        onViewportChanged?.invoke()
    }

    private fun SubsamplingScaleImageView.capturePageCropProfile(): PageCropProfile? {
        val imageCenter = center ?: return null
        val ratio = PageAspectRatio.fromDimensions(sWidth, sHeight) ?: return null
        if (width <= 0 || height <= 0 || scale <= 0F) return null
        val visibleWidth = width / scale
        val visibleHeight = height / scale
        return PageCropProfile(
            ratio = ratio,
            scaleByViewWidth = scale * sWidth / width,
            centerX = (imageCenter.x / sWidth).coerceIn(0F, 1F),
            centerY = (imageCenter.y / sHeight).coerceIn(0F, 1F),
            top = ((imageCenter.y - visibleHeight / 2F) / sHeight).coerceIn(0F, 1F),
            bottom = ((sHeight - imageCenter.y - visibleHeight / 2F) / sHeight).coerceIn(0F, 1F),
            left = ((imageCenter.x - visibleWidth / 2F) / sWidth).coerceIn(0F, 1F),
            right = ((sWidth - imageCenter.x - visibleWidth / 2F) / sWidth).coerceIn(0F, 1F),
        )
    }

    private fun SubsamplingScaleImageView.applyPageCropProfile(): Boolean {
        val currentConfig = config ?: return false
        if (currentConfig.cropBorders) return false
        val ratio = PageAspectRatio.fromDimensions(sWidth, sHeight) ?: return false
        val profile = currentConfig.pageCropProfileProvider(ratio.width, ratio.height) ?: return false
        if (width <= 0 || sWidth <= 0) return false
        val targetScale = (profile.scaleByViewWidth * width / sWidth).coerceIn(minScale, maxScale)
        setScaleAndCenter(
            targetScale,
            PointF(profile.centerX * sWidth, profile.centerY * sHeight),
        )
        isPanEnabled = false
        isZoomEnabled = false
        return true
    }

    private fun SubsamplingScaleImageView.hasActivePageCropProfile(): Boolean {
        val currentConfig = config ?: return false
        if (currentConfig.cropBorders) return false
        return currentConfig.pageCropProfileProvider(sWidth, sHeight) != null
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
        val landscapeZoomPreview: () -> Boolean = { false },
        val landscapeZoomPreviewDurationMillis: () -> Long = { 1200L },
        val navigatePageSegments: Boolean = false,
        val navigatePageSegmentsBackward: Boolean = false,
        val pageSegmentForwardHorizontalDirection: PagePanDirection = PagePanDirection.RIGHT,
        val pageCropProfileProvider: (Int, Int) -> PageCropProfile? = { _, _ -> null },
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
private const val PAN_DURATION_MILLIS = 250L
private const val LANDSCAPE_ZOOM_DURATION_MILLIS = 500L

private fun PageCropProfile.toState(active: Boolean) = PageCropState(
    available = true,
    active = active,
    ratio = ratio,
    topPercent = (top * 100).roundToInt(),
    bottomPercent = (bottom * 100).roundToInt(),
    leftPercent = (left * 100).roundToInt(),
    rightPercent = (right * 100).roundToInt(),
)
