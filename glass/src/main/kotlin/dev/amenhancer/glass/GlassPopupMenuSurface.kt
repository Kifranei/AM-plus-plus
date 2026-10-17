package dev.amenhancer.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Liquid glass material for a floating dropdown menu. Same recipe as the bottom bar capsule
 * (vibrancy + blur + lens refraction + 40% tint), on a rounded rectangle instead of a capsule.
 * Draws no content: the host's native menu items stay on top and keep their own touch handling.
 */
@Composable
fun GlassPopupMenuSurface(
    backdrop: Backdrop,
    cornerRadius: Dp,
    dark: Boolean,
    panelBlur: Dp = GlassPolicy.PANEL_BLUR_DP.dp,
) {
    val containerColor = remember(dark) {
        if (dark) Color(0xFF121212).copy(alpha = 0.4f) else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    }
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        Modifier.fillMaxSize().drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(panelBlur.toPx())
                // Keep the item area outside refraction; menus are small and tall.
                val refraction = minOf(cornerRadius.toPx() * 0.75f, 18f.dp.toPx(), size.minDimension * 0.375f)
                lens(refraction, refraction)
            },
            onDrawSurface = { drawRect(containerColor) },
        ),
    )
}
