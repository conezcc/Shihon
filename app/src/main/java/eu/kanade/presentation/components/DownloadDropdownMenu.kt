package eu.kanade.presentation.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.MangaDownloadAction
import eu.kanade.presentation.manga.MangaPreprocessingAction
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun DownloadDropdownMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onDownloadClicked: (DownloadAction) -> Unit,
    offset: DpOffset? = null,
) {
    if (offset != null) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            content = {
                DownloadDropdownMenuItems(
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onDownloadClicked,
                )
            },
        )
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = {
                DownloadDropdownMenuItems(
                    onDismissRequest = onDismissRequest,
                    onDownloadClicked = onDownloadClicked,
                )
            },
        )
    }
}

@Composable
fun MangaDownloadDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actionCounts: Map<MangaDownloadAction, Int>,
    onDownloadClicked: (MangaDownloadAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        val options = listOf(
            MangaDownloadAction.ALL_CHAPTERS to stringResource(MR.strings.all),
            MangaDownloadAction.UNREAD_CHAPTERS to stringResource(MR.strings.download_unread),
            MangaDownloadAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.download_bookmarked),
            MangaDownloadAction.DELETE_DOWNLOADED_CHAPTERS to stringResource(MR.strings.delete_downloaded),
            MangaDownloadAction.CANCEL_DOWNLOADS to stringResource(MR.strings.action_cancel),
        )
        MangaChapterTaskDropdownMenuItems(
            options = options,
            actionCounts = actionCounts,
            onDismissRequest = onDismissRequest,
            onActionClicked = onDownloadClicked,
        )
    }
}

@Composable
fun MangaPreprocessingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actionCounts: Map<MangaPreprocessingAction, Int>,
    onPreprocessingClicked: (MangaPreprocessingAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        val options = listOf(
            MangaPreprocessingAction.ALL_CHAPTERS to stringResource(MR.strings.build_all),
            MangaPreprocessingAction.UNREAD_CHAPTERS to stringResource(MR.strings.preprocess_unread),
            MangaPreprocessingAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.preprocess_bookmarked),
            MangaPreprocessingAction.DELETE_PREPROCESSED_CHAPTERS to
                stringResource(MR.strings.delete_preprocessed),
            MangaPreprocessingAction.CANCEL_PREPROCESSING to stringResource(MR.strings.action_cancel),
        )
        MangaChapterTaskDropdownMenuItems(
            options = options,
            actionCounts = actionCounts,
            onDismissRequest = onDismissRequest,
            onActionClicked = onPreprocessingClicked,
        )
    }
}

@Composable
private fun <T> MangaChapterTaskDropdownMenuItems(
    options: List<Pair<T, String>>,
    actionCounts: Map<T, Int>,
    onDismissRequest: () -> Unit,
    onActionClicked: (T) -> Unit,
) {
    options.forEach { (action, label) ->
        val count = actionCounts[action] ?: 0
        DropdownMenuItem(
            text = {
                Text(
                    if (count > 0) {
                        stringResource(MR.strings.manga_chapter_action_count, label, count)
                    } else {
                        label
                    },
                )
            },
            enabled = count > 0,
            onClick = {
                onActionClicked(action)
                onDismissRequest()
            },
        )
    }
}

@Composable
private fun DownloadDropdownMenuItems(
    onDismissRequest: () -> Unit,
    onDownloadClicked: (DownloadAction) -> Unit,
) {
    val options = listOf(
        DownloadAction.NEXT_1_CHAPTER to pluralStringResource(MR.plurals.download_amount, 1, 1),
        DownloadAction.NEXT_5_CHAPTERS to pluralStringResource(MR.plurals.download_amount, 5, 5),
        DownloadAction.NEXT_10_CHAPTERS to pluralStringResource(MR.plurals.download_amount, 10, 10),
        DownloadAction.NEXT_25_CHAPTERS to pluralStringResource(MR.plurals.download_amount, 25, 25),
        DownloadAction.UNREAD_CHAPTERS to stringResource(MR.strings.download_unread),
        DownloadAction.BOOKMARKED_CHAPTERS to stringResource(MR.strings.download_bookmarked),
    )

    options.map { (downloadAction, string) ->
        DropdownMenuItem(
            text = { Text(text = string) },
            onClick = {
                onDownloadClicked(downloadAction)
                onDismissRequest()
            },
        )
    }
}
