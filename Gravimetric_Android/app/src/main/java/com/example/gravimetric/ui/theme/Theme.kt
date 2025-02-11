package com.example.gravimetric.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CoffeeBrown = Color(0xFF6F4E37)
val EspressoDark = Color(0xFF3E2723)
val LatteCream = Color(0xFFD7CCC8)
val CappuccinoFoam = Color(0xFFFFF3E0)
val DarkRoast = Color(0xFF2D1606)

// Define the theme colors
val LightColors = androidx.compose.material3.lightColorScheme(
    primary = CoffeeBrown,
    onPrimary = Color.White,
    background = CappuccinoFoam,
    onBackground = EspressoDark,
    surface = LatteCream,
    onSurface = EspressoDark
)

val DarkColors = androidx.compose.material3.darkColorScheme(
    primary = DarkRoast,
    onPrimary = Color.White,
    background = EspressoDark,
    onBackground = LatteCream,
    surface = CoffeeBrown,
    onSurface = LatteCream
)

@Composable
fun GravimetricTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}