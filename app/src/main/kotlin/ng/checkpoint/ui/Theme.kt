package ng.checkpoint.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Near-black neutrals, one blue action colour, semantic colour only where it carries meaning.
 * The three bucket colours are the only place hue means something, so nothing else competes
 * with them.
 *
 * Dark is not a preference here. This is read at night, at the roadside, on a phone held low.
 */
object Ink {
    val bg = Color(0xFF0C0C0C)
    val card = Color(0xFF141414)
    val raise = Color(0xFF1A1A1A)
    val line = Color(0xFF252525)
    val lineHi = Color(0xFF333333)

    val fg = Color(0xFFE0E0E0)
    val t1 = Color(0xFFCCCCCC)
    val t2 = Color(0xFF999999)
    val t3 = Color(0xFF666666)
    val t4 = Color(0xFF555555)
    val t5 = Color(0xFF444444)

    val accent = Color(0xFF2563EB)

    /** settled */
    val ok = Color(0xFF059669)
    val okDim = Color(0xFF064E3B)

    /** contested */
    val warn = Color(0xFFD97706)
    val warnDim = Color(0xFF78350F)

    /** folk, meaning no legal basis */
    val bad = Color(0xFFEF4444)
}

/** The bar colour and the tag a bucket gets. Unknown buckets stay grey rather than guess. */
fun bucketColour(bucket: String): Color = when (bucket) {
    "settled" -> Ink.ok
    "contested" -> Ink.warn
    "folk" -> Ink.bad
    else -> Ink.t5
}

private val mono = FontFamily.Monospace

private val typography = Typography(
    bodyLarge = TextStyle(fontFamily = mono, fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = mono, fontSize = 11.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontFamily = mono, fontSize = 10.sp, lineHeight = 14.sp),
    titleMedium = TextStyle(fontFamily = mono, fontSize = 16.sp, lineHeight = 22.sp),
)

@Composable
fun CheckpointTheme(content: @Composable () -> Unit) {
    // One scheme, whatever the system is set to. A light theme would wash out the three
    // bucket colours, and they are the only signal on screen that has to survive a glance.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.accent,
            background = Ink.bg,
            surface = Ink.card,
            onBackground = Ink.fg,
            onSurface = Ink.fg,
        ),
        typography = typography,
        content = content,
    )
}
