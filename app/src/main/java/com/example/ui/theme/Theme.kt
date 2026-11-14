package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
  SYSTEM("System"),
  LIGHT("Light"),
  DARK("Dark"),
  ;

  companion object {
    fun fromName(name: String?): ThemeMode =
      entries.firstOrNull { it.name == name } ?: SYSTEM
  }
}

private val VaultDarkColorScheme = darkColorScheme(
  primary = CyberEmerald,
  onPrimary = OnCyberEmerald,
  primaryContainer = CyberEmeraldContainer,
  onPrimaryContainer = OnCyberEmeraldContainer,
  secondary = AmberVault,
  onSecondary = Color(0xFF281800),
  secondaryContainer = AmberVaultContainer,
  onSecondaryContainer = OnAmberVaultContainer,
  tertiary = Color(0xFF60A5FA),
  onTertiary = Color(0xFF00234A),
  background = ObsidianBase,
  onBackground = SteelInk,
  surface = ObsidianSurface,
  onSurface = SteelInk,
  surfaceVariant = ObsidianSurfaceHigh,
  onSurfaceVariant = SteelInkMuted,
  surfaceContainerLow = ObsidianSurface,
  surfaceContainer = ObsidianSurfaceHigh,
  surfaceContainerHigh = ObsidianSurfaceHighest,
  outline = ObsidianOutline,
  outlineVariant = Color(0xFF1F2A3A),
  error = CrimsonAlert,
  onError = Color(0xFF3B060D),
  errorContainer = CrimsonAlertContainer,
  onErrorContainer = OnCrimsonAlertContainer,
)

private val VaultLightColorScheme = lightColorScheme(
  primary = DeepEmerald,
  onPrimary = Color.White,
  primaryContainer = DeepEmeraldContainer,
  onPrimaryContainer = OnDeepEmeraldContainer,
  secondary = DeepAmber,
  onSecondary = Color.White,
  secondaryContainer = DeepAmberContainer,
  onSecondaryContainer = OnDeepAmberContainer,
  tertiary = Color(0xFF1D4ED8),
  onTertiary = Color.White,
  background = AlabasterBase,
  onBackground = LightInk,
  surface = AlabasterSurface,
  onSurface = LightInk,
  surfaceVariant = AlabasterSurfaceHigh,
  onSurfaceVariant = LightInkMuted,
  surfaceContainerLow = AlabasterSurface,
  surfaceContainer = AlabasterSurfaceHigh,
  surfaceContainerHigh = Color(0xFFE2E8F0),
  outline = AlabasterOutline,
  outlineVariant = Color(0xFFE2E8F0),
  error = Color(0xFFDC2626),
  onError = Color.White,
  errorContainer = Color(0xFFFEE2E2),
  onErrorContainer = Color(0xFF7F1D1D),
)

val VaultShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun resolveIsDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
  ThemeMode.SYSTEM -> isSystemInDarkTheme()
  ThemeMode.LIGHT -> false
  ThemeMode.DARK -> true
}

@Composable
fun BeastLockTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val darkTheme = resolveIsDarkTheme(themeMode)
  val context = LocalContext.current
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> VaultDarkColorScheme
    else -> VaultLightColorScheme
  }

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
      }
    }
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = VaultTypography,
    shapes = VaultShapes,
    content = content,
  )
}
