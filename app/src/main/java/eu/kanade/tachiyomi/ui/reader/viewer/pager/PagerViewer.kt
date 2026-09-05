package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.PageCropProfiles
import eu.kanade.tachiyomi.ui.reader.viewer.PageCropState
import eu.kanade.tachiyomi.ui.reader.viewer.PagePanDirection
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.util.system.SmartOsPageTurnEffect
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope, readerPreferences)

    private val useSmoothPageTransitions: Boolean
        get() = config.usePageTransitions && (this is VerticalPagerViewer || !config.useSmartOsWaterRipple)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    private var pendingPageSelection: Pair<ReaderPage, Boolean>? = null

    private var currentPageSelection: Pair<ReaderPage, Boolean>? = null

    private val _pageCropState = MutableStateFlow(PageCropState())
    val pageCropState: StateFlow<PageCropState> = _pageCropState

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
            if (isIdle) {
                dispatchPendingPageSelection()
            }
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.items.getOrNull(pager.currentItem)
                if (item is ReaderPage) {
                    activity.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.textEnhancementChangedListener = { strength ->
            pager.children
                .filterIsInstance<PagerPageHolder>()
                .forEach { it.updateTextEnhancement(strength) }
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item == page }

    internal fun onPageViewportChanged(holder: PagerPageHolder) {
        if (holder.item != currentPage) return
        val state = holder.currentPageCropState() ?: PageCropState()
        _pageCropState.value = state.copy(
            available = state.available,
            active = !config.imageCropBorders && config.pageCropProfiles.containsKey(state.ratio),
        )
    }

    internal fun onPageImageReady(holder: PagerPageHolder) {
        if (holder.item == currentPage && isIdle) {
            dispatchPendingPageSelection()
            currentPageSelection
                ?.takeIf { (page) -> page == holder.item }
                ?.let { (_, forward) -> holder.onPageSelected(forward) }
        }
    }

    internal fun onPageCropProfilesChanged() {
        val page = currentPage as? ReaderPage ?: return
        getPageHolder(page)?.let(::onPageViewportChanged)
    }

    internal fun onCropBordersChanged(enabled: Boolean) {
        if (!enabled) return
        removeCurrentPageCrop()
        _pageCropState.value = _pageCropState.value.copy(active = false)
    }

    fun toggleCurrentPageCrop() {
        val page = currentPage as? ReaderPage ?: return
        val holder = getPageHolder(page) ?: return
        val state = holder.currentPageCropState() ?: return
        val ratio = state.ratio ?: return
        val profiles = config.pageCropProfiles.toMutableMap()
        val active = !config.imageCropBorders && profiles.containsKey(ratio)

        if (active) {
            profiles.remove(ratio)
            readerPreferences.pageCropProfiles.set(PageCropProfiles.serialize(profiles))
            holder.resetPageCrop()
        } else {
            if (config.imageCropBorders || !profiles.containsKey(ratio)) {
                val profile = holder.capturePageCropProfile() ?: return
                profiles[ratio] = profile
                readerPreferences.pageCropProfiles.set(PageCropProfiles.serialize(profiles))
            }
            if (config.imageCropBorders) {
                readerPreferences.cropBorders.set(false)
            }
        }
        _pageCropState.value = state.copy(available = true, active = !active)
    }

    fun removeCurrentPageCrop() {
        val ratio = _pageCropState.value.ratio ?: return
        if (!config.pageCropProfiles.containsKey(ratio)) return
        val profiles = config.pageCropProfiles.toMutableMap()
        profiles.remove(ratio)
        readerPreferences.pageCropProfiles.set(PageCropProfiles.serialize(profiles))
        _pageCropState.value = _pageCropState.value.copy(active = false)
    }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    private fun onPageChange(position: Int) {
        val page = adapter.items.getOrNull(position)
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            // Page numbers restart in every chapter and are shared by split pages. Compare
            // positions in the same live adapter list instead (RTL reverses that list).
            val forward = isForwardPageSelection(
                items = adapter.items,
                previous = currentPage,
                position = position,
                rightToLeft = this is R2LPagerViewer,
            ) ?: (currentPage !is ChapterTransition.Prev)
            if (
                currentPage is ReaderPage &&
                page is ReaderPage &&
                this !is VerticalPagerViewer &&
                config.useSmartOsWaterRipple
            ) {
                @Suppress("DEPRECATION")
                val displayRotation = activity.windowManager.defaultDisplay.rotation
                SmartOsPageTurnEffect.prepare(
                    forward = forward,
                    rightToLeft = this is R2LPagerViewer,
                    displayRotation = displayRotation,
                    speed = config.waterRippleSpeed,
                )
            }
            (currentPage as? ReaderPage)?.let(::getPageHolder)?.onPageDeselected()
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        currentPageSelection = page to forward
        pendingPageSelection = currentPageSelection
        getPageHolder(page)?.let(::onPageViewportChanged) ?: run {
            _pageCropState.value = PageCropState()
        }
        if (isIdle) {
            pager.post(::dispatchPendingPageSelection)
        }

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }
    }

    private fun dispatchPendingPageSelection() {
        if (!isIdle) return
        val (page, forward) = pendingPageSelection ?: return
        if (page != currentPage) {
            pendingPageSelection = null
            return
        }
        val holder = getPageHolder(page) ?: return
        pendingPageSelection = null
        holder.onPageSelected(forward)
        onPageViewportChanged(holder)
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        currentPageSelection = null
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition = config.alwaysShowChapterTransition ||
            adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        onPageChange(pager.currentItem)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, useSmoothPageTransitions)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
        val verticalDirection = if (this is R2LPagerViewer) PagePanDirection.UP else PagePanDirection.DOWN
        val navigatingBackward = this is R2LPagerViewer
        if (
            config.navigatePageSegments &&
            (!navigatingBackward || config.navigatePageSegmentsBackward) &&
            holder?.panForPageTurn(
                PagePanDirection.RIGHT,
                verticalDirection,
                config.navigatePageSegmentsSmoothly,
            ) == true
        ) {
            return
        }
        if (pager.currentItem != adapter.count - 1) {
            pager.setCurrentItem(pager.currentItem + 1, useSmoothPageTransitions)
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
        val verticalDirection = if (this is R2LPagerViewer) PagePanDirection.DOWN else PagePanDirection.UP
        val navigatingBackward = this !is R2LPagerViewer
        if (
            config.navigatePageSegments &&
            (!navigatingBackward || config.navigatePageSegmentsBackward) &&
            holder?.panForPageTurn(
                PagePanDirection.LEFT,
                verticalDirection,
                config.navigatePageSegmentsSmoothly,
            ) == true
        ) {
            return
        }
        if (pager.currentItem != 0) {
            pager.setCurrentItem(pager.currentItem - 1, useSmoothPageTransitions)
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }
}
