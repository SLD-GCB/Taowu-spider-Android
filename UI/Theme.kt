package com.taowu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 柔和青金配色（取色自应用图标：深青底 + 虎纹金主色）。
 * 全部为低饱和"雾面"色，避免纯黑/纯白刺眼。
 */
object UiColors {
    val bg = Color(0xFF0F2A30)          // 深青底
    val surface = Color(0xFF17383F)     // 卡片
    val surface2 = Color(0xFF1E464E)    // 更高一层 / 选中底
    val outline = Color(0xFF2A555F)     // 描边 / 分隔
    val text = Color(0xFFEAF2F0)        // 柔和暖白
    val muted = Color(0xFF93AEB2)       // 柔青灰（次要文字）
    val accent = Color(0xFFE5BB74)      // 主色：虎纹金
    val accentDim = Color(0xFF8A6E3E)   // 金的暗部
    val onAccent = Color(0xFF173238)    // 金底上的深青字
    val link = accent                   // 链接/高亮沿用主色
    val error = Color(0xFFE9A79C)       // 柔珊瑚
}

/** 让 Material 组件（按钮/开关/输入框/对话框/选项卡…）也跟着走这套色。 */
private val TaowuScheme = darkColorScheme(
    primary = UiColors.accent,
    onPrimary = UiColors.onAccent,
    primaryContainer = UiColors.surface2,
    onPrimaryContainer = UiColors.text,
    secondary = UiColors.accentDim,
    onSecondary = UiColors.onAccent,
    tertiary = UiColors.accent,
    onTertiary = UiColors.onAccent,
    background = UiColors.bg,
    onBackground = UiColors.text,
    surface = UiColors.surface,
    onSurface = UiColors.text,
    surfaceVariant = UiColors.surface2,
    onSurfaceVariant = UiColors.muted,
    outline = UiColors.outline,
    error = UiColors.error,
    onError = Color(0xFF3A1614),
    // 容器层级（对话框/弹出菜单/输入框底色等）也统一到青调
    surfaceTint = UiColors.accent,
    surfaceDim = Color(0xFF0B2328),
    surfaceBright = Color(0xFF2A555F),
    surfaceContainerLowest = Color(0xFF0A2025),
    surfaceContainerLow = Color(0xFF123037),
    surfaceContainer = UiColors.surface,
    surfaceContainerHigh = UiColors.surface2,
    surfaceContainerHighest = Color(0xFF26535C),
)

@Composable
fun TaowuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TaowuScheme, content = content)
}
