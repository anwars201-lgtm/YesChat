package com.appmuse.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Rounded shape scale — applied app-wide so cards, buttons, text fields, and
 *  dialogs share one corner radius. Tune the radii here to shift the whole app
 *  between sharp/soft; never hardcode RoundedCornerShape in screens. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** App theme — wraps content in Material3 with the app palette + type scale +
 *  shape scale. Light/dark follow the system; the agent customises Color.kt +
 *  Type.kt to match the user's chosen design.
 *
 *  `dynamicColor` (Material You, Android 12+) is DEFAULT-OFF on purpose: the customer's
 *  chosen brand palette is law (it's the whole point of the onboarding colour question
 *  and the palette applier), and dynamic colour would override it with the device
 *  wallpaper's colours. A project that specifically wants the wallpaper-adaptive Android
 *  feel can opt in by passing `dynamicColor = true`; otherwise the brand palette wins. */
@Composable
fun AppmuseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
