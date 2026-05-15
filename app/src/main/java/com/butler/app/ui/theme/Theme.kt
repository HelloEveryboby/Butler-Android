package com.butler.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Blue60,
    onPrimary = Gray95,
    primaryContainer = Blue20,
    onPrimaryContainer = Blue80,
    secondary = Amber60,
    onSecondary = Gray10,
    secondaryContainer = Amber20,
    onSecondaryContainer = Amber80,
    tertiary = InfoBlue,
    onTertiary = Gray95,
    background = Gray90,
    onBackground = Gray10,
    surface = Gray80,
    onSurface = Gray10,
    surfaceVariant = Gray60,
    onSurfaceVariant = Gray20,
    error = ErrorRed,
    onError = Gray95,
    errorContainer = ErrorRed.copy(alpha = 0.3f),
    onErrorContainer = ErrorRed
)

private val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Gray95,
    primaryContainer = Blue80,
    onPrimaryContainer = Blue20,
    secondary = Amber40,
    onSecondary = Gray10,
    secondaryContainer = Amber80,
    onSecondaryContainer = Amber20,
    tertiary = InfoBlue,
    onTertiary = Gray95,
    background = Gray10,
    onBackground = Gray90,
    surface = Gray95,
    onSurface = Gray90,
    surfaceVariant = Gray20,
    onSurfaceVariant = Gray60,
    error = ErrorRed,
    onError = Gray95,
    errorContainer = ErrorRed.copy(alpha = 0.1f),
    onErrorContainer = ErrorRed
)

@Composable
fun ButlerAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
