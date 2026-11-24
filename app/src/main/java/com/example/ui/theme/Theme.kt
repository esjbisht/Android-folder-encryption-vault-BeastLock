package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

enum class VaultProgressTone {
  ENCRYPT,
  DECRYPT,
  NEUTRAL,
  WARNING,
}

@Immutable
data class VaultProgressPalette(
  val indicatorColor: Color,
  val trackColor: Color,
)

@Immutable
data class VaultSemanticColors(
  val isDark: Boolean,
  val lockedContainer: Color,
  val onLockedContainer: Color,
  val lockedAccent: Color,
  val unlockedContainer: Color,
  val onUnlockedContainer: Color,
  val unlockedAccent: Color,
  val partialContainer: Color,
  val onPartialContainer: Color,
  val partialAccent: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
  val warningAccent: Color,
  val dangerContainer: Color,
  val onDangerContainer: Color,
  val dangerAccent: Color,
  val infoContainer: Color,
  val onInfoContainer: Color,
  val infoAccent: Color,
  val progressTrack: Color,
  val progressEncrypt: Color,
  val progressDecrypt: Color,
  val progressNeutral: Color,
  val progressWarning: Color,
  val progressSurface: Color,
  val onProgressSurface: Color,
  val progressSurfaceBorder: Color,
  val heroAccent: Color,
)

data class ThemeSpec(
  val isDark: Boolean,
  val usesDynamicColor: Boolean,
  val semanticColors: VaultSemanticColors,
)

private val VaultDarkColorScheme = darkColorScheme(
  primary = CyberEmerald,
  onPrimary = OnCyberEmerald,
  primaryContainer = CyberEmeraldContainer,
  onPrimaryContainer = OnCyberEmeraldContainer,
  secondary = AmberVault,
  onSecondary = Color(0xFF281800),
  secondaryContainer = AmberVaultContainer,
  onSecondaryContainer = OnAmberVaultContainer,
  tertiary = VaultCobaltDark,
  onTertiary = Color(0xFF00234A),
  tertiaryContainer = VaultCobaltContainerDark,
  onTertiaryContainer = OnVaultCobaltContainerDark,
  background = ObsidianBase,
  onBackground = SteelInk,
  surface = ObsidianSurface,
  onSurface = SteelInk,
  surfaceVariant = ObsidianSurfaceHigh,
  onSurfaceVariant = SteelInkMuted,
  surfaceContainerLowest = ObsidianBase,
  surfaceContainerLow = ObsidianSurfaceLow,
  surfaceContainer = ObsidianSurfaceHigh,
  surfaceContainerHigh = ObsidianSurfaceHighest,
  surfaceContainerHighest = Color(0xFF2C3B52),
  outline = ObsidianOutline,
  outlineVariant = ObsidianOutlineVariant,
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
  tertiary = VaultCobaltLight,
  onTertiary = Color.White,
  tertiaryContainer = VaultCobaltContainerLight,
  onTertiaryContainer = OnVaultCobaltContainerLight,
  background = AlabasterBase,
  onBackground = LightInk,
  surface = AlabasterSurface,
  onSurface = LightInk,
  surfaceVariant = AlabasterSurfaceHigh,
  onSurfaceVariant = LightInkMuted,
  surfaceContainerLowest = AlabasterSurface,
  surfaceContainerLow = AlabasterSurfaceLow,
  surfaceContainer = AlabasterSurfaceHigh,
  surfaceContainerHigh = AlabasterSurfaceHighest,
  surfaceContainerHighest = Color(0xFFD0DCE8),
  outline = AlabasterOutline,
  outlineVariant = AlabasterOutlineVariant,
  error = DeepCrimson,
  onError = Color.White,
  errorContainer = DeepCrimsonContainer,
  onErrorContainer = OnDeepCrimsonContainer,
)

private val DarkSemanticColors = VaultSemanticColors(
  isDark = true,
  lockedContainer = CyberEmeraldContainer,
  onLockedContainer = OnCyberEmeraldContainer,
  lockedAccent = CyberEmerald,
  unlockedContainer = AmberVaultContainer,
  onUnlockedContainer = OnAmberVaultContainer,
  unlockedAccent = AmberVault,
  partialContainer = VaultCobaltContainerDark,
  onPartialContainer = OnVaultCobaltContainerDark,
  partialAccent = VaultCobaltDark,
  warningContainer = AmberVaultContainer,
  onWarningContainer = OnAmberVaultContainer,
  warningAccent = AmberVault,
  dangerContainer = CrimsonAlertContainer,
  onDangerContainer = OnCrimsonAlertContainer,
  dangerAccent = CrimsonAlert,
  infoContainer = VaultCobaltContainerDark,
  onInfoContainer = OnVaultCobaltContainerDark,
  infoAccent = VaultCobaltDark,
  progressTrack = ProgressTrackDark,
  progressEncrypt = CyberEmerald,
  progressDecrypt = VaultCobaltDark,
  progressNeutral = Color(0xFF38BDF8),
  progressWarning = AmberVault,
  progressSurface = ProgressSurfaceDark,
  onProgressSurface = SteelInk,
  progressSurfaceBorder = ProgressBorderDark,
  heroAccent = CyberEmerald,
)

private val LightSemanticColors = VaultSemanticColors(
  isDark = false,
  lockedContainer = DeepEmeraldContainer,
  onLockedContainer = OnDeepEmeraldContainer,
  lockedAccent = DeepEmerald,
  unlockedContainer = DeepAmberContainer,
  onUnlockedContainer = OnDeepAmberContainer,
  unlockedAccent = DeepAmber,
  partialContainer = VaultCobaltContainerLight,
  onPartialContainer = OnVaultCobaltContainerLight,
  partialAccent = VaultCobaltLight,
  warningContainer = DeepAmberContainer,
  onWarningContainer = OnDeepAmberContainer,
  warningAccent = DeepAmber,
  dangerContainer = DeepCrimsonContainer,
  onDangerContainer = OnDeepCrimsonContainer,
  dangerAccent = DeepCrimson,
  infoContainer = VaultCobaltContainerLight,
  onInfoContainer = OnVaultCobaltContainerLight,
  infoAccent = VaultCobaltLight,
  progressTrack = ProgressTrackLight,
  progressEncrypt = DeepEmerald,
  progressDecrypt = VaultCobaltLight,
  progressNeutral = Color(0xFF0369A1),
  progressWarning = DeepAmber,
  progressSurface = ProgressSurfaceLight,
  onProgressSurface = LightInk,
  progressSurfaceBorder = ProgressBorderLight,
  heroAccent = CyberEmerald,
)

object VaultThemeEngine {
  fun resolveIsDarkTheme(themeMode: ThemeMode, systemIsDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemIsDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

  fun semanticColorsFor(darkTheme: Boolean): VaultSemanticColors =
    if (darkTheme) DarkSemanticColors else LightSemanticColors

  fun resolveThemeSpec(
    themeMode: ThemeMode,
    systemIsDark: Boolean,
    dynamicColor: Boolean = false,
    sdkInt: Int = Build.VERSION.SDK_INT,
  ): ThemeSpec {
    val isDark = resolveIsDarkTheme(themeMode, systemIsDark)
    val usesDynamic = dynamicColor && sdkInt >= Build.VERSION_CODES.S
    return ThemeSpec(
      isDark = isDark,
      usesDynamicColor = usesDynamic,
      semanticColors = semanticColorsFor(isDark),
    )
  }

  fun progressPaletteFor(
    colors: VaultSemanticColors,
    tone: VaultProgressTone,
  ): VaultProgressPalette {
    val indicator = when (tone) {
      VaultProgressTone.ENCRYPT -> colors.progressEncrypt
      VaultProgressTone.DECRYPT -> colors.progressDecrypt
      VaultProgressTone.NEUTRAL -> colors.progressNeutral
      VaultProgressTone.WARNING -> colors.progressWarning
    }
    return VaultProgressPalette(
      indicatorColor = indicator,
      trackColor = colors.progressTrack,
    )
  }

  fun colorSchemeFor(darkTheme: Boolean): ColorScheme =
    if (darkTheme) VaultDarkColorScheme else VaultLightColorScheme

  fun relativeLuminance(color: Color): Double {
    fun linearize(channel: Float): Double {
      val c = channel.toDouble()
      return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
  }

  fun contrastRatio(foreground: Color, background: Color): Double {
    val l1 = relativeLuminance(foreground)
    val l2 = relativeLuminance(background)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
  }
}

val LocalVaultColors = staticCompositionLocalOf { DarkSemanticColors }

val VaultShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun resolveIsDarkTheme(themeMode: ThemeMode): Boolean =
  VaultThemeEngine.resolveIsDarkTheme(themeMode, isSystemInDarkTheme())

object BeastLockTheme {
  val colors: VaultSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalVaultColors.current
}

@Composable
fun BeastLockTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val spec = VaultThemeEngine.resolveThemeSpec(
    themeMode = themeMode,
    systemIsDark = systemDark,
    dynamicColor = dynamicColor,
  )
  val darkTheme = spec.isDark
  val context = LocalContext.current
  val baseColorScheme = if (spec.usesDynamicColor) {
    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else {
    VaultThemeEngine.colorSchemeFor(darkTheme)
  }

  val animSpec = tween<Color>(durationMillis = 220)
  val animatedScheme = baseColorScheme.copy(
    background = animateColorAsState(baseColorScheme.background, animSpec, label = "theme_bg").value,
    surface = animateColorAsState(baseColorScheme.surface, animSpec, label = "theme_surface").value,
    surfaceContainer = animateColorAsState(baseColorScheme.surfaceContainer, animSpec, label = "theme_container").value,
    surfaceContainerHigh = animateColorAsState(baseColorScheme.surfaceContainerHigh, animSpec, label = "theme_container_high").value,
    onBackground = animateColorAsState(baseColorScheme.onBackground, animSpec, label = "theme_on_bg").value,
    onSurface = animateColorAsState(baseColorScheme.onSurface, animSpec, label = "theme_on_surface").value,
  )

  val targetSemantic = spec.semanticColors
  val animatedSemantic = targetSemantic.copy(
    lockedContainer = animateColorAsState(targetSemantic.lockedContainer, animSpec, label = "sem_locked_bg").value,
    onLockedContainer = animateColorAsState(targetSemantic.onLockedContainer, animSpec, label = "sem_locked_fg").value,
    unlockedContainer = animateColorAsState(targetSemantic.unlockedContainer, animSpec, label = "sem_unlocked_bg").value,
    onUnlockedContainer = animateColorAsState(targetSemantic.onUnlockedContainer, animSpec, label = "sem_unlocked_fg").value,
    partialContainer = animateColorAsState(targetSemantic.partialContainer, animSpec, label = "sem_partial_bg").value,
    onPartialContainer = animateColorAsState(targetSemantic.onPartialContainer, animSpec, label = "sem_partial_fg").value,
    dangerContainer = animateColorAsState(targetSemantic.dangerContainer, animSpec, label = "sem_danger_bg").value,
    onDangerContainer = animateColorAsState(targetSemantic.onDangerContainer, animSpec, label = "sem_danger_fg").value,
    progressTrack = animateColorAsState(targetSemantic.progressTrack, animSpec, label = "sem_prog_track").value,
    progressEncrypt = animateColorAsState(targetSemantic.progressEncrypt, animSpec, label = "sem_prog_enc").value,
    progressDecrypt = animateColorAsState(targetSemantic.progressDecrypt, animSpec, label = "sem_prog_dec").value,
    progressNeutral = animateColorAsState(targetSemantic.progressNeutral, animSpec, label = "sem_prog_neu").value,
    progressSurface = animateColorAsState(targetSemantic.progressSurface, animSpec, label = "sem_prog_surf").value,
    onProgressSurface = animateColorAsState(targetSemantic.onProgressSurface, animSpec, label = "sem_prog_onsurf").value,
  )

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

  CompositionLocalProvider(LocalVaultColors provides animatedSemantic) {
    MaterialTheme(
      colorScheme = animatedScheme,
      typography = VaultTypography,
      shapes = VaultShapes,
      content = content,
    )
  }
}
