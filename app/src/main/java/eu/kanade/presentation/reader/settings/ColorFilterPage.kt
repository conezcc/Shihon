package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import eu.kanade.tachiyomi.ui.reader.setting.ImageProcessing
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.Companion.ColorFilterMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.ColorFilterPage(viewModel: ReaderSettingsViewModel) {
    val imageBrightness by viewModel.preferences.imageBrightness.collectAsState()
    SliderItem(
        value = imageBrightness,
        valueRange = ImageProcessing.BRIGHTNESS_MIN..ImageProcessing.BRIGHTNESS_MAX,
        label = stringResource(MR.strings.pref_image_brightness),
        valueString = imageBrightness.signedValue(),
        onChange = viewModel.preferences.imageBrightness::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    val imageContrast by viewModel.preferences.imageContrast.collectAsState()
    SliderItem(
        value = imageContrast,
        valueRange = ImageProcessing.CONTRAST_MIN..ImageProcessing.CONTRAST_MAX,
        label = stringResource(MR.strings.pref_image_contrast),
        valueString = imageContrast.signedValue(),
        onChange = viewModel.preferences.imageContrast::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    val imageGamma by viewModel.preferences.imageGamma.collectAsState()
    SliderItem(
        value = imageGamma,
        valueRange = ImageProcessing.GAMMA_MIN..ImageProcessing.GAMMA_MAX,
        steps = 29,
        label = stringResource(MR.strings.pref_image_gamma),
        valueString = imageGamma.gammaValue(),
        onChange = viewModel.preferences.imageGamma::set,
        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    val preprocessingEnabled by viewModel.preferences.preprocessingEnabled.collectAsState()
    if (preprocessingEnabled) {
        val textEnhancement by viewModel.preferences.textEnhancement.collectAsState()
        SliderItem(
            value = textEnhancement,
            valueRange = ImageProcessing.TEXT_ENHANCEMENT_MIN..ImageProcessing.TEXT_ENHANCEMENT_MAX,
            label = stringResource(MR.strings.pref_text_enhancement),
            valueString = if (textEnhancement == ImageProcessing.TEXT_ENHANCEMENT_MIN) {
                stringResource(MR.strings.off)
            } else {
                textEnhancement.toString()
            },
            onChange = viewModel.preferences.textEnhancement::set,
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val customBrightness by viewModel.preferences.customBrightness.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_brightness),
        pref = viewModel.preferences.customBrightness,
    )

    /*
     * Sets the brightness of the screen. Range is [-75, 100].
     * From -75 to -1 a semi-transparent black view is shown at the top with the minimum brightness.
     * From 1 to 100 it sets that value as brightness.
     * 0 sets system brightness and hides the overlay.
     */
    if (customBrightness) {
        val customBrightnessValue by viewModel.preferences.customBrightnessValue.collectAsState()
        SliderItem(
            value = customBrightnessValue,
            valueRange = -75..100,
            steps = 0,
            label = stringResource(MR.strings.pref_custom_brightness),
            onChange = { viewModel.preferences.customBrightnessValue.set(it) },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }

    val colorFilter by viewModel.preferences.colorFilter.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_custom_color_filter),
        pref = viewModel.preferences.colorFilter,
    )
    if (colorFilter) {
        val colorFilterValue by viewModel.preferences.colorFilterValue.collectAsState()
        SliderItem(
            value = colorFilterValue.red,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_r_value),
            onChange = { newRValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newRValue, RED_MASK, 16)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.green,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_g_value),
            onChange = { newGValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newGValue, GREEN_MASK, 8)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.blue,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_b_value),
            onChange = { newBValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newBValue, BLUE_MASK, 0)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        SliderItem(
            value = colorFilterValue.alpha,
            valueRange = 0..255,
            steps = 0,
            label = stringResource(MR.strings.color_filter_a_value),
            onChange = { newAValue ->
                viewModel.preferences.colorFilterValue.getAndSet {
                    getColorValue(it, newAValue, ALPHA_MASK, 24)
                }
            },
            pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        val colorFilterMode by viewModel.preferences.colorFilterMode.collectAsState()
        SettingsChipRow(MR.strings.pref_color_filter_mode) {
            ColorFilterMode.mapIndexed { index, it ->
                FilterChip(
                    selected = colorFilterMode == index,
                    onClick = { viewModel.preferences.colorFilterMode.set(index) },
                    label = { Text(stringResource(it.first)) },
                )
            }
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_grayscale),
        pref = viewModel.preferences.grayscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_inverted_colors),
        pref = viewModel.preferences.invertedColors,
    )
}

private fun getColorValue(currentColor: Int, color: Int, mask: Long, bitShift: Int): Int {
    return (color shl bitShift) or (currentColor and mask.inv().toInt())
}
private const val ALPHA_MASK: Long = 0xFF000000
private const val RED_MASK: Long = 0x00FF0000
private const val GREEN_MASK: Long = 0x0000FF00
private const val BLUE_MASK: Long = 0x000000FF

private fun Int.signedValue(): String = if (this > 0) "+$this" else toString()

private fun Int.gammaValue(): String = "%d.%02d".format(this / 100, this % 100)
