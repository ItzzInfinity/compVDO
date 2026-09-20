package com.compvdo.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * compVDO Material 3 theme — follows system light/dark (R12.4).
 * Uses dynamic colour on Android 12+ for a device-native feel,
 * falls back to the brand blue seed colour on older devices.
 */

private val LightColorScheme = lightColorScheme(
    primary = CompVdoBlue,
    secondary = CompVdoGreen,
    tertiary = CompVdoMediumBlue,
)

private val DarkColorScheme = darkColorScheme(
    primary = CompVdoBlue,
    secondary = CompVdoGreen,
    tertiary = CompVdoMediumBlue,
)

@Composable
fun CompVdoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
