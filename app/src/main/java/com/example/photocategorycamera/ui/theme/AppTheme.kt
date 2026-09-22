package com.example.photocategorycamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppColors = lightColorScheme(
    primary = Color(0xFF4564E6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9EDFF),
    onPrimaryContainer = Color(0xFF18213A),
    secondary = Color(0xFF68748A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9ECF2),
    onSecondaryContainer = Color(0xFF283244),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF18213A),
    surface = Color(0xFFFCFCFE),
    onSurface = Color(0xFF18213A),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF5C6678),
    outline = Color(0xFFAEB7C6),
    outlineVariant = Color(0xFFE0E4EB),
    error = Color(0xFFB42318),
    errorContainer = Color(0xFFFFE8E5),
    onErrorContainer = Color(0xFF6E130C),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun PhotoCategoryCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
