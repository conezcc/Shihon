package eu.kanade.tachiyomi.data.preprocessing

import android.content.Context
import androidx.core.content.edit
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.preprocessing.model.PreprocessingTask
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/** Persists only pending work; completed outputs are represented by their chapter artifact. */
@Inject
class PreprocessingStore(
    context: Context,
    private val sourceManager: SourceManager,
    private val json: Json,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {
    private val preferences = context.getSharedPreferences("active_preprocessing", Context.MODE_PRIVATE)
    private var counter = 0

    fun addAll(tasks: List<PreprocessingTask>) {
        preferences.edit {
            tasks.forEach { putString(it.chapter.id.toString(), serialize(it)) }
        }
    }

    fun remove(task: PreprocessingTask) {
        preferences.edit { remove(task.chapter.id.toString()) }
    }

    fun removeAll(tasks: Collection<PreprocessingTask>) {
        preferences.edit { tasks.forEach { remove(it.chapter.id.toString()) } }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    suspend fun restore(): List<PreprocessingTask> {
        val objects = preferences.all.values
            .mapNotNull { it as? String }
            .mapNotNull(::deserialize)
            .sortedBy(PreprocessingObject::order)
        val mangaCache = mutableMapOf<Long, Manga?>()
        val tasks = objects.mapNotNull { item ->
            val manga = mangaCache.getOrPut(item.mangaId) {
                getManga.await(item.mangaId)
            } ?: return@mapNotNull null
            val chapter = getChapter.await(item.chapterId) ?: return@mapNotNull null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return@mapNotNull null
            PreprocessingTask(source, manga, chapter)
        }
        clear()
        return tasks
    }

    private fun serialize(task: PreprocessingTask): String {
        return json.encodeToString(
            PreprocessingObject(task.manga.id, task.chapter.id, counter++),
        )
    }

    private fun deserialize(value: String): PreprocessingObject? {
        return runCatching { json.decodeFromString<PreprocessingObject>(value) }.getOrNull()
    }
}

@Serializable
private data class PreprocessingObject(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
)
