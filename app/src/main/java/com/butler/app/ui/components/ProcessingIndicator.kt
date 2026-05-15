package com.butler.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Processing indicator with animated dots
 */
@Composable
fun ProcessingIndicator(
    message: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")

    val dot1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 0
                1f at 400
                0f at 800
                1f at 1200
            }
        ),
        label = "dot1"
    )

    val dot2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 200
                1f at 600
                0f at 1000
                1f at 1200
            }
        ),
        label = "dot2"
    )

    val dot3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0f at 400
                1f at 800
                0f at 1200
            }
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(8.dp))

            AnimatedDots(dot1 = dot1, dot2 = dot2, dot3 = dot3)
        }
    )
}

/**
 * Animated loading dots
 */
@Composable
private fun AnimatedDots(
    dot1: Float,
    dot2: Float,
    dot3: Float
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(alpha = dot1)
        Dot(alpha = dot2)
        Dot(alpha = dot3)
    }
}

/**
 * Single animated dot
 */
@Composable
private fun Dot(alpha: Float) {
    Surface(
        modifier = Modifier.size(8.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha.coerceIn(0.3f, 1f))
    ) {}
}

/**
 * Full-screen loading overlay
 */
@Composable
fun LoadingOverlay(
    visible: Boolean,
    message: String = "Loading...",
    onDismiss: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 4.dp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
