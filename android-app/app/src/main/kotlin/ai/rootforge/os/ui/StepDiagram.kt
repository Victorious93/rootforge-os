package ai.rootforge.os.ui

import ai.rootforge.os.device.DiagramStep
import ai.rootforge.os.ui.theme.ForgeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Lightweight Compose-drawn step diagram. Bundled in both the Android APK
 * and :desktop (this file is compiled into both). No bitmaps, no runtime
 * downloads.
 */
@Composable
fun StepDiagram(steps: List<DiagramStep>, modifier: Modifier = Modifier) {
    if (steps.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        steps.forEachIndexed { index, step ->
            if (index > 0) {
                Box(
                    Modifier.weight(1f).height(28.dp).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalDivider(color = ForgeColors.Ember.copy(alpha = 0.5f))
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.widthIn(max = 72.dp),
            ) {
                Box(
                    Modifier.size(28.dp).background(ForgeColors.Ember, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        color = Color(0xFF2A1400),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    step.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
