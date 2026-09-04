package dev.lindroid.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF365E00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9FF91),
    onPrimaryContainer = Color(0xFF0D2000),
    secondary = Color(0xFF52643E),
    secondaryContainer = Color(0xFFD5EAB8),
    tertiary = Color(0xFF006B5C),
    tertiaryContainer = Color(0xFF8EF5DD),
    background = Color(0xFFF9FAF0),
    surface = Color(0xFFF9FAF0),
    surfaceContainer = Color(0xFFEDEFE4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7E46B),
    onPrimary = Color(0xFF193700),
    primaryContainer = Color(0xFF284D00),
    onPrimaryContainer = Color(0xFFD9FF91),
    secondary = Color(0xFFB9CEA0),
    secondaryContainer = Color(0xFF3B4B2B),
    tertiary = Color(0xFF70D8C1),
    tertiaryContainer = Color(0xFF005045),
    background = Color(0xFF11140E),
    surface = Color(0xFF11140E),
    surfaceContainer = Color(0xFF1D2119),
)

private val LindroidTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 40.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
)

private val LindroidShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(42.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LindroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = LindroidTypography,
        shapes = LindroidShapes,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
