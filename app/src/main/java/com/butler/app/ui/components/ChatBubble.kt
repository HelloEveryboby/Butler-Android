package com.butler.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.butler.app.bridge.model.Message
import com.butler.app.bridge.model.MessageRole
import com.butler.app.ui.theme.ButlerBubble
import com.butler.app.ui.theme.ButlerBubbleDark
import com.butler.app.ui.theme.UserBubble
import com.butler.app.ui.theme.UserBubbleDark
import java.text.SimpleDateFormat
import java.util.*

/**
 * Chat bubble for messages
 */
@Composable
fun ChatBubble(
    message: Message,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER

    val bubbleColor by animateColorAsState(
        targetValue = if (isUser) UserBubble else ButlerBubble,
        animationSpec = tween(300),
        label = "bubbleColor"
    )

    val bubbleDark by animateColorAsState(
        targetValue = if (isUser) UserBubbleDark else ButlerBubbleDark,
        animationSpec = tween(300),
        label = "bubbleDark"
    )

    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Avatar for Butler
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "B",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Message content
                    if (message.isCode) {
                        CodeBlock(
                            code = message.content,
                            isError = message.isError
                        )
                    } else {
                        Text(
                            text = message.content,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Timestamp
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Avatar for User
        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "U",
                    color = MaterialTheme.colorScheme.onSecondary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Code block in message
 */
@Composable
fun CodeBlock(
    code: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text = code,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier.padding(8.dp)
        )
    }
}

/**
 * Format timestamp to time string
 */
private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
