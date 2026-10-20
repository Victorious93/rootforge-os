package ai.rootforge.os.ui.screens

import ai.rootforge.os.glossary.Glossary
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everyday-words glossary. Reading only; official pages stay on Links.
 */
@Composable
fun GlossaryScreen(onOpenLinks: () -> Unit = {}) {
    ScreenColumn {
        NoticeCard(
            "One short line per term, in everyday words. Official Magisk, LSPosed, Vector, Shizuku and Sui pages are in Links. Nothing here is downloaded.",
        )
        ActionButton("Links directory", Icons.AutoMirrored.Filled.OpenInNew, ActionKind.Outline) { onOpenLinks() }
        Glossary.all.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(entry.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}
