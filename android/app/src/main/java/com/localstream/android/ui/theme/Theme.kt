package com.localstream.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LocalStreamLightColors = lightColorScheme(
    primary = SpeedOrange,
    secondary = BurstAmber,
    background = Mist,
    surface = Mist,
    onPrimary = Mist,
    onBackground = DeepGraphite,
    onSurface = DeepGraphite
)

private val LocalStreamDarkColors = darkColorScheme(
    primary = BurstAmber,
    secondary = SpeedOrange,
    background = DeepGraphite,
    surface = DeepGraphite,
    onPrimary = DeepGraphite,
    onBackground = Mist,
    onSurface = Mist
)

@Composable
fun LocalStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LocalStreamDarkColors else LocalStreamLightColors,
        content = content
    )
}
