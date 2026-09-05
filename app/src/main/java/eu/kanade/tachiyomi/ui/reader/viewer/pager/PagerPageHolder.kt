package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ImageProcessing
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ChapterPreprocessingArtifacts
import eu.kanade.tachiyomi.ui.reader.viewer.PageAspectRatio
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import kotlin.math.roundToInt

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    private var textEnhancementJob: Job? = null
    private var buildUpdateJob: Job? = null
    private var hasTextEnhancementMask = false
    private var textEnhancementSupported = false

    init {
        onViewportChanged = { viewer.onPageViewportChanged(this) }
        loadJob = scope.launch { loadPageAndProcessStatus() }
        buildUpdateJob = scope.launch {
            ChapterPreprocessingArtifacts.pageChanges.collect { change ->
                val pageIndex = (page as? InsertPage)?.parent?.index ?: page.index
                if (change.chapterId == page.chapter.chapter.id && change.pageIndex == pageIndex) {
                    textEnhancementJob?.cancel()
                    textEnhancementJob = null
                    updateTextEnhancement(viewer.config.textEnhancement)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val horizontalPadding = (
            w * viewer.config.pagerHorizontalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR
            ).roundToInt()
        val verticalPadding = (
            h * viewer.config.pagerVerticalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR
            ).roundToInt()
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        textEnhancementJob?.cancel()
        textEnhancementJob = null
        buildUpdateJob?.cancel()
        buildUpdateJob = null
        hasTextEnhancementMask = false
        textEnhancementSupported = false
        clearTextEnhancementMask()
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val image = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                LoadedPageImage(source, isAnimated, background)
            }
            withUIContext {
                val config = Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = viewer.config.imageScaleType,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = viewer.config.imageZoomType,
                    landscapeZoom = viewer.config.landscapeZoom,
                    landscapeZoomPreview = { viewer.config.landscapeZoomPreview },
                    landscapeZoomPreviewDurationMillis = {
                        viewer.config.landscapeZoomPreviewDurationMillis
                    },
                    navigatePageSegments = viewer.config.navigatePageSegments,
                    navigatePageSegmentsBackward = viewer.config.navigatePageSegmentsBackward,
                    pageSegmentForwardHorizontalDirection = if (viewer is R2LPagerViewer) {
                        eu.kanade.tachiyomi.ui.reader.viewer.PagePanDirection.LEFT
                    } else {
                        eu.kanade.tachiyomi.ui.reader.viewer.PagePanDirection.RIGHT
                    },
                    pageCropProfileProvider = { width, height ->
                        PageAspectRatio.fromDimensions(width, height)
                            ?.let(viewer.config.pageCropProfiles::get)
                    },
                )
                setImage(image.source, image.isAnimated, config)
                textEnhancementSupported = !image.isAnimated
                if (!image.isAnimated) {
                    pageBackground = image.background
                }
                updateTextEnhancement(viewer.config.textEnhancement)
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(
        page: ReaderPage,
        imageSource: BufferedSource,
        notifyPageSplit: Boolean = true,
    ): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        if (notifyPageSplit) onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    fun updateTextEnhancement(strength: Int) {
        setTextEnhancementStrength(strength)
        if (strength <= ImageProcessing.TEXT_ENHANCEMENT_MIN) {
            textEnhancementJob?.cancel()
            textEnhancementJob = null
            hasTextEnhancementMask = false
            clearTextEnhancementMask()
            return
        }
        if (!textEnhancementSupported) {
            return
        }
        if (hasTextEnhancementMask || textEnhancementJob?.isActive == true) {
            return
        }

        textEnhancementJob = scope.launch {
            val mask = withIOContext {
                val pageIndex = (page as? InsertPage)?.parent?.index ?: page.index
                ChapterPreprocessingArtifacts.loadPage(context, page.chapter.chapter.id!!, pageIndex)
            }
            if (mask == null) return@launch
            if (viewer.config.textEnhancement <= ImageProcessing.TEXT_ENHANCEMENT_MIN) {
                mask.recycle()
                return@launch
            }
            hasTextEnhancementMask = true
            setTextEnhancementMask(mask, viewer.config.textEnhancement)
        }
    }

    private data class LoadedPageImage(
        val source: BufferedSource,
        val isAnimated: Boolean,
        val background: Drawable?,
    )

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        viewer.onPageViewportChanged(this)
        viewer.onPageImageReady(this)
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
