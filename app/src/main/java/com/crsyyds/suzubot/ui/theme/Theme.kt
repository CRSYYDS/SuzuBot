package com.crsyyds.suzubot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 配色参考 PCL2（Plain Craft Launcher）：
 *  - 深灰底（不是纯黑，留出层次）
 *  - 青蓝色作为唯一强调色
 *  - 面板比底色略亮一点，靠圆角和留白分层，不用阴影
 *
 * 设计要求「轻量」：全部是纯色 + 圆角，没有模糊、没有渐变纹理、没有位图资源。
 */

// PCL 青蓝
val PclCyan = Color(0xFF26C6DA)
val PclCyanDeep = Color(0xFF0B9EB8)
val PclCyanSoft = Color(0xFF1B2A31)   // 强调色在深色底上的「浅化」容器

// 深浅层次
val PclBg = Color(0xFF1B1E22)          // 主背景
val PclPanel = Color(0xFF252A30)       // 卡片
val PclPanelHigh = Color(0xFF2D333A)   // 卡片上的次级块（输入框、内嵌块）
val PclStroke = Color(0xFF343B44)      // 描边

// 文字
val PclText = Color(0xFFE8ECEF)
val PclTextDim = Color(0xFF8E99A4)
val PclTextFaint = Color(0xFF5D6670)

// 语义
val PclOk = Color(0xFF4CD07D)
val PclWarn = Color(0xFFFFB020)
val PclErr = Color(0xFFFF5D5D)

private val DarkColors = darkColorScheme(
	primary = PclCyan,
	onPrimary = Color(0xFF04252A),
	primaryContainer = PclCyanSoft,
	onPrimaryContainer = PclCyan,
	secondary = PclCyanDeep,
	onSecondary = Color.White,
	background = PclBg,
	onBackground = PclText,
	surface = PclPanel,
	onSurface = PclText,
	surfaceVariant = PclPanelHigh,
	onSurfaceVariant = PclTextDim,
	error = PclErr,
	onError = Color(0xFF2A0808),
	errorContainer = Color(0xFF3A1A1C),
	onErrorContainer = Color(0xFFFFB3B3),
	outline = PclStroke
)

// 浅色主题也保留 PCL 的青蓝，只是把底翻过来（老机型不一定开深色模式）
private val LightColors = lightColorScheme(
	primary = PclCyanDeep,
	onPrimary = Color.White,
	primaryContainer = Color(0xFFD6F5FA),
	onPrimaryContainer = Color(0xFF00363F),
	secondary = PclCyan,
	onSecondary = Color(0xFF00363F),
	background = Color(0xFFF3F5F7),
	onBackground = Color(0xFF1B1E22),
	surface = Color.White,
	onSurface = Color(0xFF1B1E22),
	surfaceVariant = Color(0xFFEDF1F4),
	onSurfaceVariant = Color(0xFF5D6670),
	error = Color(0xFFD32F2F),
	onError = Color.White,
	errorContainer = Color(0xFFFDE7E7),
	onErrorContainer = Color(0xFF7F1010),
	outline = Color(0xFFD5DBE1)
)

val MonoStyle = TextStyle(
	fontFamily = FontFamily.Monospace,
	fontSize = 12.sp,
	lineHeight = 18.sp
)

private val AppTypography = Typography(
	displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
	headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
	headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
	titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
	titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
	bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
	bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
	bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
	labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun QQBotTheme(
	darkTheme: Boolean = true,   // PCL 味道只在深色下成立，默认深色
	content: @Composable () -> Unit
) {
	MaterialTheme(
		colorScheme = if (darkTheme) DarkColors else LightColors,
		typography = AppTypography,
		content = content
	)
}
