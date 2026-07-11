package com.gallerybox.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ==========================================
// 1. PREMIUM FLAGSHIP COLORS
// ==========================================
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF003B96), // Used for the currently playing song row
    onPrimaryContainer = Color(0xFFD6E4FF),

    secondary = Color(0xFF00E5A8),
    onSecondary = Color(0xFF003827),
    secondaryContainer = Color(0xFF00523C), // Used for Folder icons
    onSecondaryContainer = Color(0xFF59FFCE),

    tertiary = Color(0xFFFF4FC3),
    onTertiary = Color(0xFF3B002A),
    tertiaryContainer = Color(0xFF5B0043), // Used for Album icons
    onTertiaryContainer = Color(0xFFFFD8EB),

    background = Color(0xFF0A0A0A), // Deep OLED black
    onBackground = Color.White,

    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E), // For glass/cards
    onSurfaceVariant = Color(0xFFC4C7C5), // Used heavily for secondary text (timestamps, subtitles)

    error = Color(0xFFFF4C4C),
    onError = Color.White,
    errorContainer = Color(0xFF93000A), // Used for the Radio Headset Warning Banner
    onErrorContainer = Color(0xFFFFDAD6),

    outlineVariant = Color(0xFF444746) // Used for dividers
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2962FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001946),

    secondary = Color(0xFF00BFA5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF59FFCE),
    onSecondaryContainer = Color(0xFF002015),

    tertiary = Color(0xFFD500F9),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD6F9),
    onTertiaryContainer = Color(0xFF380043),

    background = Color(0xFFF8FAFF), // Cool premium white
    onBackground = Color(0xFF1A1A1A),

    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF0F4F8),
    onSurfaceVariant = Color(0xFF444746),

    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outlineVariant = Color(0xFFC4C7C5)
)

// ==========================================
// 2. MODERN TYPOGRAPHY
// ==========================================
val GalleryTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        color = Color.Gray
    )
)

// ==========================================
// 3. MODERN SHAPES
// ==========================================
val GalleryShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp) // The flagship Apple/Samsung curve
)

// ==========================================
// 4. MAIN THEME COMPOSABLE
// ==========================================
@Composable
fun GalleryBoxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // True enables Android 12+ Monet colors
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Edge-to-Edge transparency is handled in MainActivity via enableEdgeToEdge().
            // Control status bar icons (Light icons for Dark Mode, Dark icons for Light Mode)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GalleryTypography,
        shapes = GalleryShapes,
        content = content
    )
}