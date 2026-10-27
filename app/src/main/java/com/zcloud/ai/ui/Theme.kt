package com.zcloud.ai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 尼康标志色 */
object ZColors {
    val NikonYellow = Color(0xFFFFE600)
    val YellowDim = Color(0xFFB8A600)
    val Bg = Color(0xFF121212)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceHigh = Color(0xFF232323)
    val SurfaceHigher = Color(0xFF2C2C2C)
    val Outline = Color(0xFF3A3A3A)
    val TextPrimary = Color(0xFFEDEDED)
    val TextSecondary = Color(0xFF9A9A9A)
    val TextTertiary = Color(0xFF6B6B6B)
    val Danger = Color(0xFFFF5252)
    val Success = Color(0xFF4ADE80)
    val Info = Color(0xFF60A5FA)
    val Mono = Color(0xFF9CDCFE)
}

private val ZcColorScheme = darkColorScheme(
    primary = ZColors.NikonYellow,
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF3A3300),
    onPrimaryContainer = ZColors.NikonYellow,
    secondary = ZColors.NikonYellow,
    onSecondary = Color(0xFF121212),
    background = ZColors.Bg,
    onBackground = ZColors.TextPrimary,
    surface = ZColors.Surface,
    onSurface = ZColors.TextPrimary,
    surfaceVariant = ZColors.SurfaceHigh,
    onSurfaceVariant = ZColors.TextSecondary,
    outline = ZColors.Outline,
    outlineVariant = Color(0xFF2A2A2A),
    error = ZColors.Danger,
    onError = Color(0xFF121212),
)

private val ZcTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        labelMedium = labelMedium.copy(letterSpacing = 0.4.sp),
    )
}

/** 等宽字体样式，给 Hex 日志和数值用。 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    color = ZColors.Mono,
)

val NumStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    color = ZColors.TextPrimary,
)

@Composable
fun ZCloudTheme(content: @Composable () -> Unit) {
    // 应用强制暗黑（极客风），不跟随系统亮色
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = ZcColorScheme,
        typography = ZcTypography,
        content = content,
    )
}
