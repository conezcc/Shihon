package eu.kanade.tachiyomi.data.preprocessing

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.preprocessing.model.PreprocessingTask
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ChapterPreprocessingArtifacts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** Independent queue for CPU-heavy, offline chapter preprocessing. */
@Inject
@SingleIn(AppScope::class)
class PreprocessingManager(
    private val context: Context,
    private val provider: DownloadProvider,
    private val readerPreferences: ReaderPreferences,
    private val sourceManager: SourceManager,
    private val store: PreprocessingStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _queueState = MutableStateFlow<List<PreprocessingTask>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val _taskEvents = MutableSharedFlow<PreprocessingTask>(extraBufferCapacity = 64)
    val taskEvents = _taskEvents.asSharedFlow()

    private val _artifactChanges = MutableSharedFlow<Set<Long>>(extraBufferCapacity = 16)
    val artifactChanges = _artifactChanges.asSharedFlow()
    private val _artifactRevision = MutableStateFlow(0L)
    val artifactRevision = _artifactRevision.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private var processorJob: Job? = null

    @Volatile private var isPaused = false

    init {
        scope.launch {
            ChapterPreprocessingArtifacts.recoverInterruptedBuilds(context)
            val restored = async { store.restore() }.await()
            val pending = restored.filterNot { ChapterPreprocessingArtifacts.isGenerated(context, it.chapter.id) }
            if (pending.isNotEmpty()) {
                _queueState.value = pending
                store.addAll(pending)
                if (readerPreferences.preprocessingEnabled.get()) startPreprocessing()
            }
        }
        readerPreferences.preprocessingEnabled.changes()
            .drop(1)
            .onEach { enabled ->
                if (enabled && queueState.value.isNotEmpty()) startPreprocessing()
                if (!enabled) pausePreprocessing()
            }
            .launchIn(scope)
    }

    suspend fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean = true) {
        if (!readerPreferences.preprocessingEnabled.get()) return
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        val requestedIds = chapters.mapTo(mutableSetOf()) { it.id }
        val retrying = queueState.value.filter {
            it.chapter.id in requestedIds && it.state == PreprocessingTask.State.ERROR
        }
        retrying.forEach {
            it.state = PreprocessingTask.State.QUEUED
            _taskEvents.tryEmit(it)
        }
        val existingIds = queueState.value.mapTo(mutableSetOf()) { it.chapter.id }
        val tasks = chapters.asSequence()
            .filterNot { it.id in existingIds || ChapterPreprocessingArtifacts.isGenerated(context, it.id) }
            .filter { chapter -> findDownloadedChapter(source, manga, chapter) != null }
            .sortedByDescending(Chapter::sourceOrder)
            .map { PreprocessingTask(source, manga, it) }
            .toList()
        if (tasks.isNotEmpty()) {
            _queueState.update { it + tasks }
            store.addAll(tasks)
            tasks.forEach(_taskEvents::tryEmit)
        }
        if (tasks.isEmpty() && retrying.isEmpty()) return
        if (autoStart) startPreprocessing()
    }

    suspend fun queueAutomatically(manga: Manga, chapter: Chapter) {
        if (!readerPreferences.automaticPreprocessing.get()) return
        queueChapters(manga, listOf(chapter))
    }

    suspend fun startNow(manga: Manga, chapter: Chapter) {
        queueChapters(manga, listOf(chapter), autoStart = false)
        val task = getQueuedTaskOrNull(chapter.id) ?: return
        _queueState.update { queue -> listOf(task) + queue.filterNot { it === task } }
        store.clear()
        store.addAll(queueState.value)
        if (processorJob?.isActive == true) {
            processorJob?.cancel()
        } else {
            startPreprocessing()
        }
    }

    fun startPreprocessing() {
        if (!readerPreferences.preprocessingEnabled.get() || queueState.value.isEmpty()) return
        processorJob?.takeIf(Job::isActive)?.let { active ->
            active.invokeOnCompletion {
                if (
                    !isPaused &&
                    readerPreferences.preprocessingEnabled.get() &&
                    queueState.value.any { task -> task.state == PreprocessingTask.State.QUEUED }
                ) {
                    PreprocessingJob.start(context)
                }
            }
            return
        }
        if (isRunning.value) return
        isPaused = false
        PreprocessingJob.start(context)
    }

    fun pausePreprocessing() {
        isPaused = true
        processorJob?.cancel()
        queueState.value.filter { it.state == PreprocessingTask.State.PROCESSING }.forEach {
            it.state = PreprocessingTask.State.QUEUED
            _taskEvents.tryEmit(it)
        }
        PreprocessingJob.stop(context)
    }

    fun clearQueue() {
        isPaused = true
        processorJob?.cancel()
        val removed = queueState.value
        _queueState.value = emptyList()
        store.clear()
        removed.forEach {
            it.state = PreprocessingTask.State.NOT_PREPROCESSED
            _taskEvents.tryEmit(it)
        }
        PreprocessingJob.stop(context)
        isPaused = false
    }

    fun cancel(tasks: Collection<PreprocessingTask>) {
        if (tasks.isEmpty()) return
        val ids = tasks.mapTo(mutableSetOf()) { it.chapter.id }
        val activeRemoved = queueState.value.any {
            it.chapter.id in ids && it.state == PreprocessingTask.State.PROCESSING
        }
        if (activeRemoved) processorJob?.cancel()
        _queueState.update { queue -> queue.filterNot { it.chapter.id in ids } }
        store.removeAll(tasks)
        tasks.forEach {
            it.state = PreprocessingTask.State.NOT_PREPROCESSED
            _taskEvents.tryEmit(it)
        }
    }

    fun deleteArtifacts(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        val queued = queueState.value.filter { it.chapter.id in chapterIds }
        val activeJob = processorJob.takeIf {
            queued.any { task -> task.state == PreprocessingTask.State.PROCESSING }
        }
        cancel(queued)
        val delete = {
            ChapterPreprocessingArtifacts.delete(context, chapterIds)
            _artifactChanges.tryEmit(chapterIds.toSet())
            _artifactRevision.value++
        }
        if (activeJob != null) {
            scope.launch {
                activeJob.join()
                delete()
            }
        } else {
            delete()
        }
    }

    fun getQueuedTaskOrNull(chapterId: Long): PreprocessingTask? {
        return queueState.value.firstOrNull { it.chapter.id == chapterId }
    }

    fun state(chapterId: Long): PreprocessingTask.State {
        return getQueuedTaskOrNull(chapterId)?.state ?: if (
            ChapterPreprocessingArtifacts.isGenerated(context, chapterId)
        ) {
            PreprocessingTask.State.PREPROCESSED
        } else {
            PreprocessingTask.State.NOT_PREPROCESSED
        }
    }

    internal fun workerStart(): Job? {
        if (queueState.value.isEmpty() || !readerPreferences.preprocessingEnabled.get()) return null
        isPaused = false
        launchProcessor()
        return processorJob
    }

    internal fun workerStop(owner: Job) {
        if (processorJob === owner) owner.cancel()
    }

    private fun launchProcessor() {
        if (processorJob?.isActive == true) return
        queueState.value.filter { it.state == PreprocessingTask.State.ERROR }.forEach {
            it.state = PreprocessingTask.State.QUEUED
        }
        _isRunning.value = true
        processorJob = scope.launch {
            try {
                while (!isPaused) {
                    val task = queueState.value.firstOrNull { it.state == PreprocessingTask.State.QUEUED }
                        ?: break
                    process(task)
                }
            } finally {
                _isRunning.value = false
                if (
                    !isPaused &&
                    readerPreferences.preprocessingEnabled.get() &&
                    queueState.value.any { it.state == PreprocessingTask.State.QUEUED }
                ) {
                    PreprocessingJob.start(context)
                }
            }
        }
    }

    private suspend fun process(task: PreprocessingTask) {
        task.state = PreprocessingTask.State.PROCESSING
        _taskEvents.emit(task)
        val chapterFile = findDownloadedChapter(task.source, task.manga, task.chapter)
        if (chapterFile == null) {
            fail(task, "Downloaded chapter file no longer exists")
            return
        }
        try {
            val success = ChapterPreprocessingArtifacts.build(
                context = context,
                chapterId = task.chapter.id,
                chapterFile = chapterFile,
                parallelism = readerPreferences.preprocessingThreads.get(),
                onPrepared = { pageCount ->
                    task.prepare(pageCount)
                    _taskEvents.tryEmit(task)
                },
                onPageBuilt = { pageIndex, pageCount ->
                    task.pageBuilt(pageIndex, pageCount)
                    _taskEvents.tryEmit(task)
                },
            )
            if (!success) {
                fail(task, "Unable to create preprocessing artifact")
                return
            }
            ChapterPreprocessingArtifacts.deleteLegacy(context, task.manga, listOf(task.chapter))
            task.state = PreprocessingTask.State.PREPROCESSED
            task.pageBuilt(task.totalPages - 1, task.totalPages)
            _taskEvents.emit(task)
            _queueState.update { queue -> queue.filterNot { it === task } }
            store.remove(task)
            _artifactChanges.emit(setOf(task.chapter.id))
            _artifactRevision.value++
        } catch (error: CancellationException) {
            task.state = if (getQueuedTaskOrNull(task.chapter.id) != null) {
                PreprocessingTask.State.QUEUED
            } else {
                PreprocessingTask.State.NOT_PREPROCESSED
            }
            _taskEvents.tryEmit(task)
            throw error
        } catch (error: Throwable) {
            logcat(LogPriority.ERROR, error) { "Chapter preprocessing failed" }
            fail(task, error.message ?: "Chapter preprocessing failed")
        }
    }

    private suspend fun fail(task: PreprocessingTask, reason: String) {
        logcat(LogPriority.WARN) { "Preprocessing failed for ${task.chapter.id}: $reason" }
        task.state = PreprocessingTask.State.ERROR
        _taskEvents.emit(task)
    }

    private fun findDownloadedChapter(source: HttpSource, manga: Manga, chapter: Chapter) =
        provider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        )
}
