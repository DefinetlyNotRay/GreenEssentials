package dev.greenhouse

import io.github.notenoughupdates.moulconfig.ChromaColour
import kotlin.math.roundToInt

/** Cache parsed colors while still evaluating animated colors each frame. */
internal object Appearance {
    private val colors = mutableMapOf<String, ChromaColour>()

    @Suppress("DEPRECATION")
    fun color(value: String): Int = colors.getOrPut(value) {
        if (colors.size >= 32) colors.clear()
        ChromaColour.forLegacyString(value)
    }.getEffectiveColourRGB()

    fun ghostColor(opacity: Float): Int =
        ((opacity.takeIf { it.isFinite() } ?: 50f).coerceIn(0f, 100f) * 255 / 100).roundToInt().shl(24) or 0xffffff
}
