package eu.kanade.tachiyomi.ui.reader.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.PageCropState
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ReaderSettingsViewModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences,
) : ViewModel() {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val pageCropState = viewerFlow
        .flatMapLatest { viewer ->
            (viewer as? PagerViewer)?.pageCropState ?: flowOf(PageCropState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PageCropState())

    fun toggleCurrentPageCrop() {
        (viewerFlow.value as? PagerViewer)?.toggleCurrentPageCrop()
    }

    fun toggleCropBorders() {
        preferences.cropBorders.set(!preferences.cropBorders.get())
    }
}
