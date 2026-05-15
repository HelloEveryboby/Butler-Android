package com.butler.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.butler.app.bridge.model.Settings

/**
 * Settings bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apiKeyVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider()

            // API Configuration Section
            SettingsSection(title = "API Configuration") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { onSettingsChange(settings.copy(apiKey = it)) },
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        VisualTransformation { it }
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide" else "Show"
                            )
                        }
                    }
                )
            }

            // Voice Settings Section
            SettingsSection(title = "Voice Settings") {
                SettingsSwitch(
                    title = "Voice Output",
                    subtitle = "Speak responses aloud",
                    checked = settings.voiceEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(voiceEnabled = it)) },
                    icon = Icons.Default.VolumeUp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Speech Rate: ${settings.speechRate}x",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = { onSettingsChange(settings.copy(speechRate = it)) },
                    valueRange = 0.5f..2f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = settings.wakeWord,
                    onValueChange = { onSettingsChange(settings.copy(wakeWord = it)) },
                    label = { Text("Wake Word") },
                    placeholder = { Text("Butler") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Appearance Section
            SettingsSection(title = "Appearance") {
                SettingsSwitch(
                    title = "Dark Mode",
                    subtitle = "Use dark color scheme",
                    checked = settings.darkMode,
                    onCheckedChange = { onSettingsChange(settings.copy(darkMode = it)) },
                    icon = Icons.Default.DarkMode
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Language selector
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf(
                    "en" to "English",
                    "zh" to "中文",
                    "es" to "Español",
                    "fr" to "Français",
                    "de" to "Deutsch"
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = languages.find { it.first == settings.language }?.second ?: "English",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onSettingsChange(settings.copy(language = code))
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // About Section
            SettingsSection(title = "About") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Version")
                    Text(
                        text = "1.0.0",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Butler Android brings intelligent assistant capabilities to your mobile device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Settings section with title
 */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

/**
 * Settings toggle switch
 */
@Composable
fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
