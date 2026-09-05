package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.reader.settings.WaterRippleSpeedOptions
import eu.kanade.tachiyomi.ui.reader.setting.ImageProcessing
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.SmartOsPageTurnEffect
import eu.kanade.tachiyomi.util.system.hasDisplayCutout
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

object SettingsReaderScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_reader

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val readerPref = remember { context.appGraph.readerPreferences }
        val pageTransitions by readerPref.pageTransitions.collectAsState()
        val waterRipple by readerPref.waterRipplePageTransitions.collectAsState()
        val waterRippleSpeed by readerPref.waterRippleSpeed.collectAsState()

        val transitionPreferences = buildList {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPref.pageTransitions,
                    title = stringResource(MR.strings.pref_page_transitions),
                ),
            )
            if (pageTransitions && SmartOsPageTurnEffect.isSupported) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.page_transition_water_ripple),
                        widget = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = waterRipple,
                                    onCheckedChange = null,
                                )
                                if (waterRipple) {
                                    WaterRippleSpeedOptions(
                                        selectedSpeed = waterRippleSpeed,
                                        onSpeedSelected = readerPref.waterRippleSpeed::set,
                                    )
                                }
                            }
                        },
                        onClick = { readerPref.waterRipplePageTransitions.set(!waterRipple) },
                    ),
                )
            }
        }

        return listOf(
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.defaultReadingMode,
                entries = ReadingMode.entries.drop(1)
                    .associate { it.flagValue to stringResource(it.stringRes) },
                title = stringResource(MR.strings.pref_viewer_type),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = readerPref.doubleTapAnimSpeed,
                entries = mapOf(
                    1 to stringResource(MR.strings.double_tap_anim_speed_0),
                    500 to stringResource(MR.strings.double_tap_anim_speed_normal),
                    250 to stringResource(MR.strings.double_tap_anim_speed_fast),
                ),
                title = stringResource(MR.strings.pref_double_tap_anim_speed),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.showReadingMode,
                title = stringResource(MR.strings.pref_show_reading_mode),
                subtitle = stringResource(MR.strings.pref_show_reading_mode_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = readerPref.showNavigationOverlayOnStart,
                title = stringResource(MR.strings.pref_show_navigation_mode),
                subtitle = stringResource(MR.strings.pref_show_navigation_mode_summary),
            ),
        ) + transitionPreferences + listOf(
            getDisplayGroup(readerPreferences = readerPref),
            getImageProcessingGroup(readerPreferences = readerPref),
            getEInkGroup(readerPreferences = readerPref),
            getReadingGroup(readerPreferences = readerPref),
            getPagedGroup(readerPreferences = readerPref),
            getWebtoonGroup(readerPreferences = readerPref),
            getNavigationGroup(readerPreferences = readerPref),
            getActionsGroup(readerPreferences = readerPref),
        )
    }

    @Composable
    private fun getDisplayGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val fullscreen by readerPreferences.fullscreen.collectAsState()
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_display),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.defaultOrientationType,
                    entries = ReaderOrientation.entries.drop(1)
                        .associate { it.flagValue to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_rotation_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerTheme,
                    entries = mapOf(
                        1 to stringResource(MR.strings.black_background),
                        2 to stringResource(MR.strings.gray_background),
                        0 to stringResource(MR.strings.white_background),
                        3 to stringResource(MR.strings.automatic_background),
                    ),
                    title = stringResource(MR.strings.pref_reader_theme),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.fullscreen,
                    title = stringResource(MR.strings.pref_fullscreen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.drawUnderCutout,
                    title = stringResource(MR.strings.pref_cutout_short),
                    enabled = LocalView.current.hasDisplayCutout() && fullscreen,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.keepScreenOn,
                    title = stringResource(MR.strings.pref_keep_screen_on),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.showPageNumber,
                    title = stringResource(MR.strings.pref_show_page_number),
                ),
            ),
        )
    }

    @Composable
    private fun getImageProcessingGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val imageBrightnessPref = readerPreferences.imageBrightness
        val imageBrightness by imageBrightnessPref.collectAsState()
        val imageContrastPref = readerPreferences.imageContrast
        val imageContrast by imageContrastPref.collectAsState()
        val imageGammaPref = readerPreferences.imageGamma
        val imageGamma by imageGammaPref.collectAsState()
        val textEnhancementPref = readerPreferences.textEnhancement
        val textEnhancement by textEnhancementPref.collectAsState()
        val preprocessingEnabled by readerPreferences.preprocessingEnabled.collectAsState()
        val preprocessingThreadsPref = readerPreferences.preprocessingThreads
        val preprocessingThreads by preprocessingThreadsPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_image_processing),
            preferenceItems = listOf(
                Preference.PreferenceItem.SliderPreference(
                    value = imageBrightness,
                    valueRange = ImageProcessing.BRIGHTNESS_MIN..ImageProcessing.BRIGHTNESS_MAX,
                    title = stringResource(MR.strings.pref_image_brightness),
                    valueString = imageBrightness.signedValue(),
                    onValueChanged = imageBrightnessPref::set,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = imageContrast,
                    valueRange = ImageProcessing.CONTRAST_MIN..ImageProcessing.CONTRAST_MAX,
                    title = stringResource(MR.strings.pref_image_contrast),
                    valueString = imageContrast.signedValue(),
                    onValueChanged = imageContrastPref::set,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = imageGamma,
                    valueRange = ImageProcessing.GAMMA_MIN..ImageProcessing.GAMMA_MAX,
                    steps = 29,
                    title = stringResource(MR.strings.pref_image_gamma),
                    valueString = imageGamma.gammaValue(),
                    onValueChanged = imageGammaPref::set,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.preprocessingEnabled,
                    title = stringResource(MR.strings.pref_text_enhancement_masks),
                    subtitle = stringResource(MR.strings.pref_text_enhancement_masks_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.automaticPreprocessing,
                    title = stringResource(MR.strings.pref_automatic_preprocessing),
                    subtitle = stringResource(MR.strings.pref_automatic_preprocessing_summary),
                    enabled = preprocessingEnabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = preprocessingThreads,
                    valueRange = ReaderPreferences.let {
                        it.PREPROCESSING_THREADS_MIN..it.PREPROCESSING_THREADS_MAX
                    },
                    title = stringResource(MR.strings.pref_preprocessing_threads),
                    subtitle = stringResource(MR.strings.pref_preprocessing_threads_summary),
                    valueString = preprocessingThreads.toString(),
                    onValueChanged = preprocessingThreadsPref::set,
                    enabled = preprocessingEnabled,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = textEnhancement,
                    valueRange = ImageProcessing.TEXT_ENHANCEMENT_MIN..ImageProcessing.TEXT_ENHANCEMENT_MAX,
                    title = stringResource(MR.strings.pref_text_enhancement),
                    subtitle = stringResource(MR.strings.pref_text_enhancement_summary),
                    valueString = if (textEnhancement == ImageProcessing.TEXT_ENHANCEMENT_MIN) {
                        stringResource(MR.strings.off)
                    } else {
                        textEnhancement.toString()
                    },
                    onValueChanged = textEnhancementPref::set,
                    enabled = preprocessingEnabled,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.grayscale,
                    title = stringResource(MR.strings.pref_grayscale),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.invertedColors,
                    title = stringResource(MR.strings.pref_inverted_colors),
                ),
            ),
        )
    }

    @Composable
    private fun getEInkGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val flashPageState by readerPreferences.flashOnPageChange.collectAsState()

        val flashMillisPref = readerPreferences.flashDurationMillis
        val flashMillis by flashMillisPref.collectAsState()

        val flashIntervalPref = readerPreferences.flashPageInterval
        val flashInterval by flashIntervalPref.collectAsState()

        val flashColorPref = readerPreferences.flashColor

        return Preference.PreferenceGroup(
            title = "E-Ink",
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.flashOnPageChange,
                    title = stringResource(MR.strings.pref_flash_page),
                    subtitle = stringResource(MR.strings.pref_flash_page_summ),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
                    valueRange = 1..15,
                    title = stringResource(MR.strings.pref_flash_duration),
                    valueString = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
                    enabled = flashPageState,
                    onValueChanged = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = flashInterval,
                    valueRange = 1..10,
                    title = stringResource(MR.strings.pref_flash_page_interval),
                    valueString = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
                    enabled = flashPageState,
                    onValueChanged = { flashIntervalPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = flashColorPref,
                    entries = mapOf(
                        ReaderPreferences.FlashColor.BLACK to stringResource(MR.strings.pref_flash_style_black),
                        ReaderPreferences.FlashColor.WHITE to stringResource(MR.strings.pref_flash_style_white),
                        ReaderPreferences.FlashColor.WHITE_BLACK
                            to stringResource(MR.strings.pref_flash_style_white_black),
                    ),
                    title = stringResource(MR.strings.pref_flash_with),
                    enabled = flashPageState,
                ),
            ),
        )
    }

    @Composable
    private fun getReadingGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_reading),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipRead,
                    title = stringResource(MR.strings.pref_skip_read_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipFiltered,
                    title = stringResource(MR.strings.pref_skip_filtered_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.skipDupe,
                    title = stringResource(MR.strings.pref_skip_dupe_chapters),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.alwaysShowChapterTransition,
                    title = stringResource(MR.strings.pref_always_show_chapter_transition),
                ),
            ),
        )
    }

    @Composable
    private fun getPagedGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val numberFormat = remember { NumberFormat.getPercentInstance() }
        val secondsFormat = remember {
            NumberFormat.getNumberInstance().apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
        }
        val previewDurationStep = ReaderPreferences.LANDSCAPE_ZOOM_PREVIEW_DURATION_STEP_MILLIS

        val navModePref = readerPreferences.navigationModePager
        val imageScaleTypePref = readerPreferences.imageScaleType
        val dualPageSplitPref = readerPreferences.dualPageSplitPaged
        val rotateToFitPref = readerPreferences.dualPageRotateToFit
        val pagerHorizontalPaddingPref = readerPreferences.pagerHorizontalPadding
        val pagerVerticalPaddingPref = readerPreferences.pagerVerticalPadding
        val landscapeZoomPreviewDurationPref = readerPreferences.landscapeZoomPreviewDurationMillis

        val navMode by navModePref.collectAsState()
        val imageScaleType by imageScaleTypePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()
        val pagerHorizontalPadding by pagerHorizontalPaddingPref.collectAsState()
        val pagerVerticalPadding by pagerVerticalPaddingPref.collectAsState()
        val navigatePageSegments by readerPreferences.navigatePageSegments.collectAsState()
        val landscapeZoom by readerPreferences.landscapeZoom.collectAsState()
        val landscapeZoomPreview by readerPreferences.navigateToPan.collectAsState()
        val landscapeZoomPreviewDuration by landscapeZoomPreviewDurationPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pager_viewer),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.pagerNavInverted,
                    entries = listOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = imageScaleTypePref,
                    entries = ReaderPreferences.ImageScaleType
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_image_scale_type),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.zoomStart,
                    entries = ReaderPreferences.ZoomStart
                        .mapIndexed { index, it -> index + 1 to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_zoom_start),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = pagerHorizontalPadding,
                    valueRange = ReaderPreferences.let { it.PAGER_PADDING_MIN..it.PAGER_PADDING_MAX },
                    title = stringResource(MR.strings.pref_pager_horizontal_padding),
                    valueString = numberFormat.format(
                        pagerHorizontalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR,
                    ),
                    onValueChanged = pagerHorizontalPaddingPref::set,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = pagerVerticalPadding,
                    valueRange = ReaderPreferences.let { it.PAGER_PADDING_MIN..it.PAGER_PADDING_MAX },
                    title = stringResource(MR.strings.pref_pager_vertical_padding),
                    valueString = numberFormat.format(
                        pagerVerticalPadding / ReaderPreferences.PAGER_PADDING_PERCENTAGE_DIVISOR,
                    ),
                    onValueChanged = pagerVerticalPaddingPref::set,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBorders,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.landscapeZoom,
                    title = stringResource(MR.strings.pref_landscape_zoom),
                    enabled = imageScaleType == 1,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigateToPan,
                    title = stringResource(MR.strings.pref_navigate_pan),
                    subtitle = stringResource(MR.strings.pref_navigate_pan_summary),
                    enabled = landscapeZoom,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = landscapeZoomPreviewDuration / previewDurationStep,
                    valueRange = ReaderPreferences.LANDSCAPE_ZOOM_PREVIEW_DURATION_STEPS,
                    title = stringResource(MR.strings.pref_landscape_zoom_preview_duration),
                    valueString = stringResource(
                        MR.strings.pref_landscape_zoom_preview_duration_value,
                        secondsFormat.format(landscapeZoomPreviewDuration / 1000.0),
                    ),
                    enabled = landscapeZoom && landscapeZoomPreview,
                    onValueChanged = {
                        landscapeZoomPreviewDurationPref.set(
                            it * previewDurationStep,
                        )
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigatePageSegments,
                    title = stringResource(MR.strings.pref_navigate_page_segments),
                    subtitle = stringResource(MR.strings.pref_navigate_page_segments_summary),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigatePageSegmentsBackward,
                    title = stringResource(MR.strings.pref_navigate_page_segments_backward),
                    subtitle = stringResource(MR.strings.pref_navigate_page_segments_backward_summary),
                    enabled = navMode != 5 && navigatePageSegments,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.navigatePageSegmentsSmoothly,
                    title = stringResource(MR.strings.pref_navigate_page_segments_smoothly),
                    subtitle = stringResource(MR.strings.pref_navigate_page_segments_smoothly_summary),
                    enabled = navMode != 5 && navigatePageSegments,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.disablePagerSwipe,
                    title = stringResource(MR.strings.pref_disable_pager_swipe),
                    subtitle = stringResource(MR.strings.pref_disable_pager_swipe_summary),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertPaged,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvert,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
            ),
        )
    }

    @Composable
    private fun getWebtoonGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val numberFormat = remember { NumberFormat.getPercentInstance() }

        val navModePref = readerPreferences.navigationModeWebtoon
        val dualPageSplitPref = readerPreferences.dualPageSplitWebtoon
        val rotateToFitPref = readerPreferences.dualPageRotateToFitWebtoon
        val webtoonSidePaddingPref = readerPreferences.webtoonSidePadding

        val navMode by navModePref.collectAsState()
        val dualPageSplit by dualPageSplitPref.collectAsState()
        val rotateToFit by rotateToFitPref.collectAsState()
        val webtoonSidePadding by webtoonSidePaddingPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.webtoon_viewer),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = navModePref,
                    entries = ReaderPreferences.TapZones
                        .mapIndexed { index, it -> index to stringResource(it) }
                        .toMap(),
                    title = stringResource(MR.strings.pref_viewer_nav),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.webtoonNavInverted,
                    entries = listOf(
                        ReaderPreferences.TappingInvertMode.NONE,
                        ReaderPreferences.TappingInvertMode.HORIZONTAL,
                        ReaderPreferences.TappingInvertMode.VERTICAL,
                        ReaderPreferences.TappingInvertMode.BOTH,
                    )
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.pref_read_with_tapping_inverted),
                    enabled = navMode != 5,
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = webtoonSidePadding,
                    valueRange = ReaderPreferences.let {
                        it.WEBTOON_PADDING_MIN..it.WEBTOON_PADDING_MAX
                    },
                    title = stringResource(MR.strings.pref_webtoon_side_padding),
                    valueString = numberFormat.format(webtoonSidePadding / 100f),
                    onValueChanged = { webtoonSidePaddingPref.set(it) },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = readerPreferences.readerHideThreshold,
                    entries = mapOf(
                        ReaderPreferences.ReaderHideThreshold.HIGHEST to stringResource(MR.strings.pref_highest),
                        ReaderPreferences.ReaderHideThreshold.HIGH to stringResource(MR.strings.pref_high),
                        ReaderPreferences.ReaderHideThreshold.LOW to stringResource(MR.strings.pref_low),
                        ReaderPreferences.ReaderHideThreshold.LOWEST to stringResource(MR.strings.pref_lowest),
                    ),
                    title = stringResource(MR.strings.pref_hide_threshold),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.cropBordersWebtoon,
                    title = stringResource(MR.strings.pref_crop_borders),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = dualPageSplitPref,
                    title = stringResource(MR.strings.pref_dual_page_split),
                    onValueChanged = {
                        rotateToFitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageInvertWebtoon,
                    title = stringResource(MR.strings.pref_dual_page_invert),
                    subtitle = stringResource(MR.strings.pref_dual_page_invert_summary),
                    enabled = dualPageSplit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = rotateToFitPref,
                    title = stringResource(MR.strings.pref_page_rotate),
                    onValueChanged = {
                        dualPageSplitPref.set(false)
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.dualPageRotateToFitInvertWebtoon,
                    title = stringResource(MR.strings.pref_page_rotate_invert),
                    enabled = rotateToFit,
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDoubleTapZoomEnabled,
                    title = stringResource(MR.strings.pref_double_tap_zoom),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.webtoonDisableZoomOut,
                    title = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
                ),
            ),
        )
    }

    @Composable
    private fun getNavigationGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        val readWithVolumeKeysPref = readerPreferences.readWithVolumeKeys
        val readWithVolumeKeys by readWithVolumeKeysPref.collectAsState()

        val verticalNavigator by readerPreferences.verticalNavigator.collectAsState()
        val verticalNavigatorHeightPref = readerPreferences.verticalNavigatorHeight
        val verticalNavigatorHeight by verticalNavigatorHeightPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_navigation),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readWithVolumeKeysPref,
                    title = stringResource(MR.strings.pref_read_with_volume_keys),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithVolumeKeysInverted,
                    title = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
                    enabled = readWithVolumeKeys,
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = readerPreferences.verticalNavigator,
                    entries = ReadingMode.entries.filter { it != ReadingMode.DEFAULT }
                        .associate { it to stringResource(it.stringRes) },
                    title = stringResource(MR.strings.pref_vertical_navigator),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.verticalNavigatorOnLeft,
                    title = stringResource(MR.strings.pref_webtoon_vertical_navigator_on_left),
                    enabled = verticalNavigator.isNotEmpty(),
                ),
                Preference.PreferenceItem.SliderPreference(
                    value = verticalNavigatorHeight,
                    valueRange = 65..100,
                    steps = 6,
                    title = stringResource(MR.strings.pref_vertical_navigator_height),
                    onValueChanged = { verticalNavigatorHeightPref.set(it) },
                    enabled = verticalNavigator.isNotEmpty(),
                ),
            ),
        )
    }

    @Composable
    private fun getActionsGroup(readerPreferences: ReaderPreferences): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_reader_actions),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.readWithLongTap,
                    title = stringResource(MR.strings.pref_read_with_long_tap),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = readerPreferences.folderPerManga,
                    title = stringResource(MR.strings.pref_create_folder_per_manga),
                    subtitle = stringResource(MR.strings.pref_create_folder_per_manga_summary),
                ),
            ),
        )
    }
}

private fun Int.signedValue(): String = if (this > 0) "+$this" else toString()

private fun Int.gammaValue(): String = "%d.%02d".format(this / 100, this % 100)
