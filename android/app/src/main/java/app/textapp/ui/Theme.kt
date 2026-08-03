package app.textapp.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val VoidBlack = Color(0xFF09090B)
val CarbonCharcoal = Color(0xFF18181B)
val SteelShadow = Color(0xFF27272A)
val IndustrialGray = Color(0xFF71717A)
val TechSilver = Color(0xFFE4E4E7)
val ErrorRed = Color(0xFFF87171)

private val AppColors = darkColorScheme(
    primary = TechSilver,
    onPrimary = VoidBlack,
    primaryContainer = CarbonCharcoal,
    onPrimaryContainer = TechSilver,
    inversePrimary = VoidBlack,
    secondary = IndustrialGray,
    onSecondary = VoidBlack,
    secondaryContainer = SteelShadow,
    onSecondaryContainer = TechSilver,
    tertiary = TechSilver,
    onTertiary = VoidBlack,
    background = VoidBlack,
    onBackground = TechSilver,
    surface = CarbonCharcoal,
    onSurface = TechSilver,
    surfaceVariant = SteelShadow,
    onSurfaceVariant = IndustrialGray,
    surfaceContainer = CarbonCharcoal,
    surfaceContainerLow = Color(0xFF131316),
    surfaceContainerHigh = SteelShadow,
    surfaceContainerHighest = Color(0xFF2F2F33),
    outline = SteelShadow,
    outlineVariant = Color(0xFF1F1F22),
    error = ErrorRed,
    onError = VoidBlack,
    errorContainer = Color(0xFF3A1215),
    onErrorContainer = Color(0xFFFFB4AB),
    inverseSurface = TechSilver,
    inverseOnSurface = VoidBlack,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TextAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = Typography(),
        shapes = AppShapes,
        content = content,
    )
}
