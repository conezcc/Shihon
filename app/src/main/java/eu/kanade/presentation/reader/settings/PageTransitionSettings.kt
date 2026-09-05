package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun WaterRippleSpeedOptions(
    selectedSpeed: ReaderPreferences.WaterRippleSpeed,
    onSpeedSelected: (ReaderPreferences.WaterRippleSpeed) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderPreferences.WaterRippleSpeed.entries.forEach { speed ->
            Row(
                modifier = Modifier.selectable(
                    selected = selectedSpeed == speed,
                    onClick = { onSpeedSelected(speed) },
                    role = Role.RadioButton,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selectedSpeed == speed,
                    onClick = null,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(speed.titleRes))
            }
        }
    }
}
