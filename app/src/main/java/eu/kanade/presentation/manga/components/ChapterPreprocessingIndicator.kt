package eu.kanade.presentation.manga.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.data.preprocessing.model.PreprocessingTask
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterPreprocessingAction {
    START,
    START_NOW,
    CANCEL,
    DELETE,
}

@Composable
fun ChapterPreprocessingIndicator(
    visible: Boolean,
    enabled: Boolean,
    stateProvider: () -> PreprocessingTask.State,
    progressProvider: () -> Int,
    onClick: (ChapterPreprocessingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    when (val state = stateProvider()) {
        PreprocessingTask.State.NOT_PREPROCESSED -> IdlePreprocessingIndicator(enabled, modifier, onClick)
        PreprocessingTask.State.QUEUED,
        PreprocessingTask.State.PROCESSING,
        -> ActivePreprocessingIndicator(enabled, state, progressProvider, modifier, onClick)
        PreprocessingTask.State.PREPROCESSED -> CompletedPreprocessingIndicator(enabled, modifier, onClick)
        PreprocessingTask.State.ERROR -> FailedPreprocessingIndicator(enabled, modifier, onClick)
    }
}

@Composable
private fun IdlePreprocessingIndicator(
    enabled: Boolean,
    modifier: Modifier,
    onClick: (ChapterPreprocessingAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                onClick = { onClick(ChapterPreprocessingAction.START) },
                onLongClick = { onClick(ChapterPreprocessingAction.START_NOW) },
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.RocketLaunch,
            contentDescription = stringResource(MR.strings.preprocessing),
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivePreprocessingIndicator(
    enabled: Boolean,
    state: PreprocessingTask.State,
    progressProvider: () -> Int,
    modifier: Modifier,
    onClick: (ChapterPreprocessingAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                onClick = { onClick(ChapterPreprocessingAction.CANCEL) },
                onLongClick = { onClick(ChapterPreprocessingAction.CANCEL) },
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val progress = progressProvider()
        val indeterminate = state == PreprocessingTask.State.QUEUED || progress == 0
        if (indeterminate) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp).padding(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
            )
        } else {
            val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                targetValue = progress / 100f,
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "preprocessingProgress",
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Butt,
            )
        }
        Icon(
            imageVector = MaterialSymbols.Rounded.RocketLaunch,
            contentDescription = stringResource(
                if (state == PreprocessingTask.State.QUEUED) {
                    MR.strings.preprocessing_queued
                } else {
                    MR.strings.preprocessing
                },
            ),
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompletedPreprocessingIndicator(
    enabled: Boolean,
    modifier: Modifier,
    onClick: (ChapterPreprocessingAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                onClick = { expanded = true },
                onLongClick = { expanded = true },
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
            contentDescription = stringResource(MR.strings.preprocessed),
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.action_delete)) },
                onClick = {
                    expanded = false
                    onClick(ChapterPreprocessingAction.DELETE)
                },
            )
        }
    }
}

@Composable
private fun FailedPreprocessingIndicator(
    enabled: Boolean,
    modifier: Modifier,
    onClick: (ChapterPreprocessingAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .combinedClickable(
                enabled = enabled,
                onClick = { onClick(ChapterPreprocessingAction.START_NOW) },
                onLongClick = { onClick(ChapterPreprocessingAction.START_NOW) },
                role = Role.Button,
                interactionSource = null,
                indication = ripple(bounded = false, radius = IconButtonTokens.StateLayerSize / 2),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Error,
            contentDescription = stringResource(MR.strings.preprocessing_failed),
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
