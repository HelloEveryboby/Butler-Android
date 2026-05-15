package com.butler.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.butler.app.bridge.BulterBridge
import com.butler.app.ui.components.*
import com.butler.app.ui.screens.*
import kotlinx.coroutines.launch

/**
 * Main Butler App UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButlerAppUI(
    onExit: () -> Unit
) {
    val viewModel = remember { MainViewModel() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    // Auto-scroll when new messages arrive
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Lifecycle-aware recording
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.pauseVoiceInput()
                Lifecycle.Event.ON_RESUME -> viewModel.resumeVoiceInput()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            ButlerTopBar(
                title = "Butler",
                isListening = uiState.isListening,
                onSettingsClick = { viewModel.toggleSettings() },
                onExit = onExit
            )
        },
        bottomBar = {
            Column {
                // Voice input indicator
                AnimatedVisibility(
                    visible = uiState.isListening,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    VoiceWaveIndicator(
                        isRecording = uiState.isRecording,
                        amplitude = uiState.audioAmplitude,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // Message input bar
                MessageInputBar(
                    text = uiState.inputText,
                    onTextChange = viewModel::updateInputText,
                    onSend = {
                        if (uiState.inputText.isNotBlank()) {
                            viewModel.sendMessage()
                            focusManager.clearFocus()
                        }
                    },
                    onVoiceClick = { viewModel.toggleVoiceInput() },
                    isVoiceEnabled = uiState.voiceEnabled,
                    isProcessing = uiState.isProcessing
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(
                        message = message,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // Processing indicator
            AnimatedVisibility(
                visible = uiState.isProcessing && uiState.messages.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ProcessingIndicator(
                    message = uiState.processingStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }

    // Settings sheet
    if (uiState.showSettings) {
        SettingsSheet(
            settings = uiState.settings,
            onSettingsChange = viewModel::updateSettings,
            onDismiss = { viewModel.toggleSettings() }
        )
    }

    // Error snackbar
    uiState.error?.let { error ->
        LaunchedEffect(error) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearError()
        }
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = viewModel::clearError) {
                    Text("Dismiss")
                }
            }
        ) {
            Text(error)
        }
    }
}

/**
 * Top app bar with Butler branding
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButlerTopBar(
    title: String,
    isListening: Boolean,
    onSettingsClick: () -> Unit,
    onExit: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(text = title)
                if (isListening) {
                    PulsingDot(color = MaterialTheme.colorScheme.error)
                }
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

/**
 * Message input bar with text and voice input
 */
@Composable
fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceClick: () -> Unit,
    isVoiceEnabled: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Butler...") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                )
            )

            // Voice button
            FilledIconButton(
                onClick = onVoiceClick,
                enabled = isVoiceEnabled && !isProcessing,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isVoiceEnabled)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isVoiceEnabled)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = if (isVoiceEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = if (isVoiceEnabled) "Voice input on" else "Voice input off"
                )
            }

            // Send button
            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isProcessing
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send"
                )
            }
        }
    }
}

/**
 * Pulsing dot animation for listening state
 */
@Composable
fun PulsingDot(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
