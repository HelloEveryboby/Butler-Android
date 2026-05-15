package com.butler.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Voice wave indicator for recording state
 */
@Composable
fun VoiceWaveIndicator(
    isRecording: Boolean,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    val animatedAmplitudes = (0 until 20).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = if (isRecording) 1f else 0.3f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 600 + index * 50
                    0.3f at 0
                    1f at 300 + index * 25
                    0.3f at durationMillis
                },
                repeatMode = RepeatMode.Reverse
            ),
            label = "amplitude$index"
        )
    }

    val waveColor = if (isRecording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Recording indicator dot
        if (isRecording) {
            PulsingDot(color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(12.dp))
        }

        // Wave bars
        Canvas(
            modifier = Modifier
                .height(40.dp)
                .width(200.dp)
        ) {
            val barWidth = 6.dp.toPx()
            val spacing = 4.dp.toPx()
            val maxHeight = size.height

            animatedAmplitudes.forEachIndexed { index, animState ->
                val centerX = index * (barWidth + spacing) + barWidth / 2
                val barHeight = if (isRecording) {
                    animState.value * maxHeight * amplitude.coerceIn(0.2f, 1f)
                } else {
                    animState.value * maxHeight * 0.3f
                }

                val startY = (maxHeight - barHeight) / 2
                val endY = startY + barHeight

                drawLine(
                    color = waveColor.copy(alpha = 0.4f + animState.value * 0.6f),
                    start = Offset(centerX, startY),
                    end = Offset(centerX, endY),
                    strokeWidth = barWidth
                )
            }
        }

        if (isRecording) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Listening...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Animated voice wave bars
 */
@Composable
fun AnimatedVoiceWave(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voiceWave")

    val animations = (0 until barCount).map { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 400 + index * 100,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar$index"
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animations.forEach { animState ->
            Canvas(
                modifier = Modifier
                    .width(4.dp)
                    .height((animState.value * 24).dp)
            ) {
                drawRoundRect(
                    color = color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
        }
    }
}
