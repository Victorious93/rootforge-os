package ai.rootforge.os.ui.screens

import ai.rootforge.os.ask.AskEngine
import ai.rootforge.os.ask.AskResult
import ai.rootforge.os.ask.AskTopic
import ai.rootforge.os.guide.GuideHit
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.SectionLabel
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val examples = listOf(
    "adb devices shows nothing",
    "How do I unlock the bootloader?",
    "How do I root my phone?",
    "Which partition do I flash, boot or init_boot?",
    "Stuck in a bootloop",
    "Banking app says my phone is rooted",
)

/**
 * Ask: type a root or ADB question and get matching answers from material bundled with the app
 * (curated answers plus the Root Guide). Keyword matching, not a language model; it needs no
 * connection, sends nothing anywhere and never touches the target.
 */
@Composable
fun AskScreen(
    onOpenGuide: (id: String, anchor: String?) -> Unit,
    engine: AskEngine = remember { AskEngine() },
    initialQuestion: String = "",
) {
    var question by rememberSaveable { mutableStateOf(initialQuestion) }
    var asked by rememberSaveable { mutableStateOf(initialQuestion) }
    val result: AskResult? = remember(asked) { if (asked.isBlank()) null else engine.ask(asked) }

    fun submit(q: String = question) { question = q; asked = q.trim() }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NoticeCard(
                "Answers come from text bundled in the app, matched by keywords. This is not an AI model and it cannot see your phone, " +
                    "so check each answer against the guide page for your exact device before running anything that flashes or unlocks.",
            )
        }
        item {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask about root, ADB or fastboot") },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
        }
        item { ActionButton("Ask", Icons.Filled.QuestionAnswer, ActionKind.Primary, enabled = question.isNotBlank()) { submit() } }

        if (result == null) {
            item { SectionLabel("Try asking") }
            items(examples) { e ->
                Text(
                    e,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { submit(e) }.padding(vertical = 8.dp),
                )
            }
        } else if (result.isEmpty) {
            item {
                NoticeCard(
                    "Nothing in the bundled material matches that. Try the command or error text itself (for example \"unauthorized\" or " +
                        "\"fastboot flashing unlock\"), or browse the Root Guide.",
                )
            }
        } else {
            if (result.topics.isNotEmpty()) {
                item { SectionLabel("Answers") }
                items(result.topics, key = { it.id }) { TopicCard(it, onOpenGuide) }
            }
            if (result.guideHits.isNotEmpty()) {
                item { SectionLabel("In the Root Guide") }
                items(result.guideHits) { hit -> HitCard(hit, onOpenGuide) }
            }
        }
    }
}

@Composable
private fun TopicCard(topic: AskTopic, onOpenGuide: (String, String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(topic.title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(topic.answer, style = MaterialTheme.typography.bodyMedium) }
            if (topic.commands.isNotEmpty()) {
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        topic.commands.forEach { Text(it, style = MonoStyle, color = ForgeColors.Ember) }
                    }
                }
            }
            topic.caveat?.let { Text("Varies: $it", style = MaterialTheme.typography.bodySmall, color = ForgeColors.Warn) }
            topic.guide.forEach { id ->
                val title = ai.rootforge.os.guide.GuideLibrary.default.entry(id)?.title ?: return@forEach
                Text(
                    "Read: $title",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenGuide(id, null) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HitCard(hit: GuideHit, onOpenGuide: (String, String?) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpenGuide(hit.entry.id, hit.anchor) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(hit.entry.title + " › " + hit.heading, style = MaterialTheme.typography.titleSmall)
            Text(hit.snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
