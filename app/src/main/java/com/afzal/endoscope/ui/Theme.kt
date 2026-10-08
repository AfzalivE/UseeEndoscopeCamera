package com.afzal.endoscope.ui

import android.os.Build
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Dark, because a viewfinder reads best on black. Uses wallpaper colors on Android 12+. */
private val FallbackColors = darkColorScheme(
    primary = Color(0xFF7DD3E8),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5B),
    onPrimaryContainer = Color(0xFFA9EDFF),
    secondary = Color(0xFFB2CBD2),
    onSecondary = Color(0xFF1C3439),
    secondaryContainer = Color(0xFF334A50),
    onSecondaryContainer = Color(0xFFCDE7EE),
    tertiary = Color(0xFFFFB59D),
    onTertiary = Color(0xFF5C1A05),
    tertiaryContainer = Color(0xFF7B2F17),
    onTertiaryContainer = Color(0xFFFFDBCF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1415),
    surface = Color(0xFF0E1415),
    surfaceContainer = Color(0xFF1B2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637),
)

@Composable
fun EndoscopeTheme(content: @Composable () -> Unit) {
    val colorScheme = if (Build.VERSION.SDK_INT >= 31) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        FallbackColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
