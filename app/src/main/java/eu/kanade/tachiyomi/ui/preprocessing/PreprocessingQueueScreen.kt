package eu.kanade.tachiyomi.ui.preprocessing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.preprocessing.PreprocessingManager
import eu.kanade.tachiyomi.data.preprocessing.model.PreprocessingTask
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Error
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.RocketLaunch
import mihon.icons.materialsymbols.roundedfilled.PlayArrow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

data object PreprocessingQueueScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<PreprocessingQueueViewModel>()
        val tasks by viewModel.tasks.collectAsStateWithLifecycle()
        val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.preprocessing_queue),
                    navigateUp = navigator::pop,
                    actions = {
                        if (tasks.isNotEmpty()) {
                            AppBarActions(
                                listOf(
                                    eu.kanade.presentation.components.AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = viewModel::clearQueue,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                if (tasks.isNotEmpty()) {
                    SmallExtendedFloatingActionButton(
                        text = {
                            Text(stringResource(if (isRunning) MR.strings.action_pause else MR.strings.action_resume))
                        },
                        icon = {
                            Icon(
                                imageVector = if (isRunning) {
                                    MaterialSymbols.Rounded.Pause
                                } else {
                                    MaterialSymbols.RoundedFilled.PlayArrow
                                },
                                contentDescription = null,
                            )
                        },
                        onClick = if (isRunning) viewModel::pause else viewModel::start,
                    )
                }
            },
        ) { contentPadding ->
            if (tasks.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_preprocessing,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            LazyColumn(contentPadding = contentPadding) {
                items(tasks, key = { it.chapter.id }) { task ->
                    PreprocessingTaskItem(task = task, onCancel = { viewModel.cancel(task) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PreprocessingTaskItem(task: PreprocessingTask, onCancel: () -> Unit) {
    val state by task.stateFlow.collectAsStateWithLifecycle()
    val progress by task.progressFlow.collectAsStateWithLifecycle()
    ListItem(
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(task.chapter.name, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        when (state) {
                            PreprocessingTask.State.QUEUED -> stringResource(MR.strings.preprocessing_queued)
                            PreprocessingTask.State.PROCESSING -> "$progress%"
                            PreprocessingTask.State.ERROR -> stringResource(MR.strings.preprocessing_failed)
                            else -> ""
                        },
                    )
                }
                if (state == PreprocessingTask.State.PROCESSING) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        leadingContent = {
            when (state) {
                PreprocessingTask.State.QUEUED -> Icon(MaterialSymbols.Rounded.RocketLaunch, contentDescription = null)
                PreprocessingTask.State.ERROR -> Icon(MaterialSymbols.Rounded.Error, contentDescription = null)
                else -> Icon(MaterialSymbols.Rounded.RocketLaunch, contentDescription = null)
            }
        },
        trailingContent = {
            IconButton(onClick = onCancel) {
                Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(MR.strings.action_cancel))
            }
        },
    ) {
        Text(task.manga.title, maxLines = 1)
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class PreprocessingQueueViewModel(
    private val manager: PreprocessingManager,
) : ViewModel() {
    val tasks = manager.queueState
    val isRunning = manager.isRunning

    fun start() = manager.startPreprocessing()
    fun pause() = manager.pausePreprocessing()
    fun clearQueue() = manager.clearQueue()
    fun cancel(task: PreprocessingTask) = manager.cancel(listOf(task))
}
