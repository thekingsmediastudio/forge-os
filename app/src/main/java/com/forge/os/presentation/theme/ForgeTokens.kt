package com.forge.os.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ForgeTokens — Single source of truth for all Forge OS design tokens.
 * Mirrors the CSS variables defined in redesign/style.css exactly.
 *
 * Structure:
 *  - Color       → all semantic and raw colour values
 *  - Spacing     → padding / gap values
 *  - Shape       → corner radius presets (mapped from --radius-*)
 *  - Shadow      → elevation presets
 *  - Typography  → font size + weight scales
 *  - Easing      → animation curves (mapped from --ease-*)
 *  - Glow        → the animated orange atmospheric glow colours
 */
object ForgeTokens {

    /* ── RAW COLOUR PALETTE ─────────────────────────────────── */
    object Raw {
        val Orange500    = Color(0xFFFF4D00)   // --accent
        val Orange400    = Color(0xFFFF7B3D)   // --accent-light
        val Orange600    = Color(0xFFCC3E00)   // --accent-dark
        val OrangeDim    = Color(0x14FF4D00)   // --accent-dim   (8%)
        val OrangeGlow   = Color(0x66FF4D00)   // --accent-glow  (40%)
        val OrangeAlpha22= Color(0x38FF4D00)   // status-badge bg alpha
        val OrangeAlpha44= Color(0x70FF4D00)   // status-badge border alpha

        val Success      = Color(0xFF00D26A)   // --success
        val Warning      = Color(0xFFFFB020)   // --warning
        val Error        = Color(0xFFF43F5E)   // --error
        val Info         = Color(0xFF0EA5E9)   // --info

        // Neutrals
        val Black        = Color(0xFF000000)
        val Base         = Color(0xFF030303)   // --bg-base
        val Surface1     = Color(0xFF0A0A0A)   // --bg-surface
        val Surface2     = Color(0xFF121212)   // --bg-surface-2
        val Surface3     = Color(0xFF1C1C1C)   // --bg-surface-3
        val GlassBg      = Color(0xB8000000)   // --bg-glass (≈72% black)

        val White        = Color(0xFFFFFFFF)
        val TextPrimary  = Color(0xFFFCFCFC)   // --text-primary
        val TextSecondary= Color(0xFF9A9A9A)   // --text-secondary
        val TextTertiary = Color(0xFF5C5C5C)   // --text-tertiary
        val TextDim      = Color(0xFF2E2E2E)   // --text-dim

        val Border       = Color(0x0FFFFFFF)   // --border       (6%)
        val BorderStrong = Color(0x1AFFFFFF)   // --border-strong(10%)
        val BorderAccent = Color(0x3DFF4D00)   // --border-accent(24%)

        // Glow orb colours (matching 3-orb backgroundGlow animation)
        val Glow1Center  = Color(0x66FF4500)   // rgba(255,69,0,0.4)
        val Glow1Mid     = Color(0x40FF6B35)   // rgba(255,107,53,0.25)
        val Glow2Center  = Color(0x59FF6B35)   // rgba(255,107,53,0.35)
        val Glow2Mid     = Color(0x33FF4500)   // rgba(255,69,0,0.2)
        val Glow3Center  = Color(0x4DFF4500)   // rgba(255,69,0,0.3)
    }

    /* ── SEMANTIC COLOURS (theme-aware aliases) ─────────────── */
    object Colors {
        // Accent / Brand
        val Accent        get() = Raw.Orange500
        val AccentLight   get() = Raw.Orange400
        val AccentDark    get() = Raw.Orange600
        val AccentDim     get() = Raw.OrangeDim
        val AccentGlow    get() = Raw.OrangeGlow

        // Status
        val Success       get() = Raw.Success
        val Warning       get() = Raw.Warning
        val Error         get() = Raw.Error
        val Info          get() = Raw.Info

        // Backgrounds
        val BgBase        get() = Raw.Base
        val BgSurface     get() = Raw.Surface1
        val BgSurface2    get() = Raw.Surface2
        val BgSurface3    get() = Raw.Surface3
        val BgGlass       get() = Raw.GlassBg

        // Text
        val TextPrimary   get() = Raw.TextPrimary
        val TextSecondary get() = Raw.TextSecondary
        val TextTertiary  get() = Raw.TextTertiary
        val TextDim       get() = Raw.TextDim

        // Borders
        val Border        get() = Raw.Border
        val BorderStrong  get() = Raw.BorderStrong
        val BorderAccent  get() = Raw.BorderAccent
    }

    /* ── SPACING / GAPS ─────────────────────────────────────── */
    object Spacing {
        val xs  = 4.dp    // gap-1
        val sm  = 8.dp    // gap-2
        val md  = 12.dp   // gap-3
        val lg  = 16.dp   // gap-4
        val xl  = 20.dp   // gap-5
        val xxl = 24.dp   // gap-6
        val xxxl= 32.dp
        val huge= 48.dp

        // Common screen padding
        val screenH = 20.dp
        val screenV = 24.dp
    }

    /* ── SHAPE / CORNER RADIUS ──────────────────────────────── */
    object Shape {
        val sm   = RoundedCornerShape(10.dp)   // --radius-sm
        val md   = RoundedCornerShape(14.dp)   // --radius-md
        val lg   = RoundedCornerShape(18.dp)   // --radius-lg
        val xl   = RoundedCornerShape(26.dp)   // --radius-xl
        val xxl  = RoundedCornerShape(36.dp)   // --radius-2xl
        val full = RoundedCornerShape(100.dp)  // --radius-full
        val card = RoundedCornerShape(28.dp)   // most card usage
    }

    /* ── ELEVATION / SHADOW ─────────────────────────────────── */
    object Elevation {
        val none : Dp = 0.dp
        val sm   : Dp = 2.dp    // --shadow-sm
        val md   : Dp = 8.dp    // --shadow-md
        val lg   : Dp = 16.dp   // --shadow-lg
        val xl   : Dp = 24.dp   // --shadow-xl
    }

    /* ── TYPOGRAPHY SCALE ───────────────────────────────────── */
    object Type {
        // Hero / Display
        val display1 = 48.sp
        val display2 = 36.sp
        val headline1= 32.sp   // headlineLarge
        val headline2= 24.sp   // headlineMedium

        // Body
        val body1    = 16.sp   // bodyLarge
        val body2    = 14.sp   // bodyMedium (default)
        val body3    = 13.sp   // slightly smaller

        // Label / Caption
        val label1   = 12.sp   // labelMedium
        val label2   = 11.sp   // section headers
        val caption  = 10.sp   // eyebrow / metadata
        val micro    = 9.sp    // badge text
    }

    /* ── ANIMATION EASING ───────────────────────────────────── */
    object Easing {
        // --ease-out: cubic-bezier(0.22, 1, 0.36, 1)  — snappy spring out
        val EaseOut    = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        // --ease-in-out: cubic-bezier(0.76, 0, 0.24, 1) — heavy in-out
        val EaseInOut  = CubicBezierEasing(0.76f, 0f, 0.24f, 1f)
        // Standard spring feel for FABs / cards
        val Spring     = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    }

    /* ── GLOW ANIMATION DURATIONS ───────────────────────────── */
    object GlowAnim {
        const val orb1Ms = 18_000  // backgroundGlow
        const val orb2Ms = 22_000  // backgroundGlow2
        const val orb3Ms = 25_000  // backgroundGlow3
    }

    /* ── COMPONENT SIZES ────────────────────────────────────── */
    object Size {
        val iconSm  = 16.dp
        val iconMd  = 20.dp
        val iconLg  = 24.dp
        val iconXl  = 28.dp

        val btnIconSm = 36.dp   // small icon button
        val btnIcon   = 40.dp   // standard icon button
        val btnIconLg = 48.dp   // large FAB-like

        val fabSm = 48.dp
        val fab   = 56.dp

        val logoSm = 32.dp
        val logoMd = 48.dp
        val logoLg = 72.dp
        val logoXl = 96.dp

        val statusDot  = 8.dp
        val statusDotLg= 12.dp

        val topBarHeight     = 72.dp
        val bottomNavHeight  = 64.dp
        val sectionHeaderH   = 36.dp
        val cardPaddingH     = 20.dp
        val cardPaddingV     = 16.dp
    }
}
