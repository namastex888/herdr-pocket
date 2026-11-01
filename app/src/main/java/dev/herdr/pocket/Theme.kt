package dev.herdr.pocket

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Automagik Stage colours (apps/bots-dsh stage.js, apps/desktop shell.css), light and dark. The phone's setting picks one. */
@Immutable data class Palette(
    val canvas: Color, val chrome: Color, val surface: Color, val raised: Color, val border: Color,
    val ink: Color, val muted: Color, val dim: Color, val accent: Color, val onAccent: Color,
    val working: Color, val blocked: Color, val done: Color, val ok: Color,
    val input: Color, val selected: Color, val dark: Boolean,
)

val StageLight = Palette(
    canvas = Color(0xfff6eee2), chrome = Color(0xfffbf6ee), surface = Color(0xfffffdf9), raised = Color(0xffefe5d6),
    border = Color(0xffe3d8ca), ink = Color(0xff3c3049), muted = Color(0xff6c5f71), dim = Color(0xff9a8c95),
    accent = Color(0xff007087), onAccent = Color(0xffffffff), working = Color(0xff00697f), blocked = Color(0xffa73d83),
    done = Color(0xff10754f), ok = Color(0xff10754f), input = Color(0xffefe5d6), selected = Color(0xfff0e2d0), dark = false,
)

val StageDark = Palette(
    canvas = Color(0xff1e1923), chrome = Color(0xff262029), surface = Color(0xff2b2431), raised = Color(0xff322a39),
    border = Color(0xff3a3240), ink = Color(0xfff3e9dd), muted = Color(0xffbcadb8), dim = Color(0xff8a7d88),
    accent = Color(0xff7fd8ea), onAccent = Color(0xff0d262c), working = Color(0xff5cc8dc), blocked = Color(0xfff2a6dc),
    done = Color(0xff7ad9a8), ok = Color(0xff7ad9a8), input = Color(0xff2b2431), selected = Color(0xff3a3140), dark = true,
)

/** The terminal stays dark in both modes: agents' output is coloured for a dark background. */
val TermBg = Color(0xff18141c)
val TermInk = Color(0xfff3e9dd)
const val TermBgArgb = 0xff18141c.toInt()
/** Accent for notifications and other places outside Compose (Stage light accent; Android tints it as needed). */
const val BrandArgb = 0xff007087.toInt()

val LocalPalette = staticCompositionLocalOf { StageDark }

val Canvas: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.canvas
val Chrome: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chrome
val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
val Raised: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.raised
val Border: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.border
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.onAccent
val Working: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.working
val Blocked: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blocked
val Done: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.done
val Ok: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ok

// The drawer's tokens, kept by name so the sidebar code reads the same.
val SidebarChrome: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.chrome
val SidebarDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.dim
val SidebarMuted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val SidebarInput: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.input
val SidebarSelected: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.selected
val SidebarConnected: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ok

@Composable fun AutomagikTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) StageDark else StageLight
    // Every role Material reads is a Stage colour, so no stock purple shows through (containers, chips, drawer items).
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.raised, onPrimaryContainer = p.ink,
        secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.selected, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = p.onAccent, tertiaryContainer = p.raised, onTertiaryContainer = p.ink,
        background = p.canvas, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
        surfaceVariant = p.raised, onSurfaceVariant = p.muted, surfaceTint = p.accent,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised, surfaceBright = p.surface, surfaceDim = p.canvas,
        inverseSurface = p.ink, inverseOnSurface = p.canvas, inversePrimary = p.accent,
        outline = p.border, outlineVariant = p.border, error = p.blocked, onError = p.onAccent, scrim = Color.Black)
    CompositionLocalProvider(LocalPalette provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
