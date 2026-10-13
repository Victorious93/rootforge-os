package ai.rootforge.os.ui.screens

import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.TerminalPanel
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The secondary, Termux-like panel: full output plus a command line that is parsed, not passed through. */
@Composable
fun LogsScreen(state: AppUiState, actions: AppActions) {
    var line by rememberSaveable { mutableStateOf("") }
    val send = {
        if (line.isNotBlank()) {
            actions.runTerminal(line)
            line = ""
        }
    }
    Column(Modifier.fillMaxSize().background(ForgeColors.Terminal)) {
        TerminalPanel(state.log, expanded = true, onToggle = {}, actions = actions, modifier = Modifier.weight(1f), fullHeight = true)
        Text(
            "Type help for commands. Destructive commands open the same confirmation as the buttons.",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MonoStyle, color = Color(0xFF6B7684),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line, onValueChange = { line = it }, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MonoStyle,
                placeholder = { Text("adb shell getprop ro.product.model") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            IconButton(onClick = { send() }) { Icon(Icons.AutoMirrored.Filled.Send, "Run") }
        }
    }
}
