package eu.kanade.tachiyomi.data.preprocessing.model

import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class PreprocessingTask(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
) {
    private val _state = MutableStateFlow(State.QUEUED)
    val stateFlow = _state.asStateFlow()
    var state: State
        get() = _state.value
        set(value) {
            _state.value = value
        }

    private val _progress = MutableStateFlow(0)
    val progressFlow = _progress.asStateFlow()
    val progress: Int get() = _progress.value

    var completedPages: Int = 0
        private set
    var totalPages: Int = 0
        private set

    fun prepare(pageCount: Int) {
        totalPages = pageCount
        completedPages = 0
        _progress.value = 0
    }

    fun pageBuilt(pageIndex: Int, pageCount: Int) {
        totalPages = pageCount
        completedPages = (pageIndex + 1).coerceAtMost(pageCount)
        _progress.value = if (pageCount == 0) 0 else completedPages * 100 / pageCount
    }

    enum class State {
        NOT_PREPROCESSED,
        QUEUED,
        PROCESSING,
        PREPROCESSED,
        ERROR,
    }
}
