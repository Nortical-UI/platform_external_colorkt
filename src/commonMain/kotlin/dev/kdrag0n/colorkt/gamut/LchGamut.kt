package dev.kdrag0n.colorkt.gamut

import dev.kdrag0n.colorkt.cam.Zcam
import dev.kdrag0n.colorkt.rgb.LinearSrgb
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.abs

private fun interface LchFactory {
    fun getColor(lightness: Double, chroma: Double, hue: Double): LinearSrgb
}

/**
 * sRGB gamut clipping using binary search to find the edge of the gamut for a specific color.
 *
 * Out-of-gamut colors are mapped using gamut intersection in a 2D plane, and hue is always preserved. Lightness and
 * chroma are changed depending on the clip method; see [ClipMethod] for details.
 *
 * [OklabGamut] has the same goal, but this is a generalized solution that works with any color space or color
 * appearance model with brightness/lightness, chroma/colorfulness, and hue attributes. It's not as fast as
 * [OklabGamut] and lacks methods that use the lightness of the maximum chroma in the hue plane, but is much more
 * flexible.
 *
 * Currently, only ZCAM is supported, but the underlying algorithm implementation may be exposed in the future as it is
 * portable to other color spaces and appearance models.
 */
public object LchGamut {
    // Epsilon for color spaces where lightness ranges from 0 to 100
    private const val EPSILON_100 = 0.0001

    // Epsilon for color spaces where lightness ranges from 0 to 1
    private const val EPSILON_1 = 0.000001

    private fun evalLine(slope: Double, intercept: Double, x: Double) =
        slope * x + intercept

    private fun clip(
        // Target point
        lightness: Double,
        chroma: Double,
        hue: Double,
        // Projection point within gamut
        l0: Double,
        // Color space parameters
        epsilon: Double,
        maxLightness: Double,
        factory: LchFactory,
    ): LinearSrgb {
        val initialResult = factory.getColor(lightness, chroma, hue)

        return when {
            initialResult.isInGamut() -> initialResult
            // Avoid searching black and white for performance
            lightness <= epsilon -> LinearSrgb(0.0, 0.0, 0.0)
            lightness >= maxLightness - epsilon -> LinearSrgb(1.0, 1.0, 1.0)

            // Clip with gamut intersection
            else -> {
                // Chroma is always 0 so the reference point is guaranteed to be within gamut
                val c0 = 0.0

                // Create a line - x=C, y=L - intersecting a hue plane
                val l1 = lightness
                val c1 = chroma
                val slope = (l1 - l0) / (c1 - c0)
                val intercept = l0 - slope * c0

                var lo = 0.0
                var hi = chroma

                var newLinearSrgb = initialResult
                while (abs(hi - lo) > epsilon) {
                    val midC = (lo + hi) / 2
                    val midL = evalLine(slope, intercept, midC)

                    newLinearSrgb = factory.getColor(midL, midC, hue)

                    if (!newLinearSrgb.isInGamut()) {
                        hi = midC
                    } else {
                        val midC2 = midC + epsilon
                        val midL2 = evalLine(slope, intercept, midC2)

                        val ptOutside = factory.getColor(midL2, midC2, hue)
                        if (ptOutside.isInGamut()) {
                            lo = midC
                        } else {
                            break
                        }
                    }
                }

                newLinearSrgb
            }
        }
    }

    /**
     * Convert this ZCAM color to linear sRGB, and clip it to sRGB gamut boundaries if it's not already within gamut.
     *
     * Out-of-gamut colors are mapped using gamut intersection in a 2D plane, and hue is always preserved. Lightness and
     * chroma are changed depending on the clip method; see [ClipMethod] for details.
     *
     * @return clipped color in linear sRGB
     */
    @JvmStatic
    @JvmOverloads
    public fun Zcam.clipToLinearSrgb(
        /**
         * Gamut clipping method to use. Different methods preserve different attributes and make different trade-offs.
         * @see [ClipMethod]
         */
        method: ClipMethod = ClipMethod.PRESERVE_LIGHTNESS,
        /**
         * For adaptive clipping methods only: the extent to which lightness should be preserved rather than chroma.
         * Larger numbers will preserve chroma more than lightness, and vice versa.
         *
         * This value is ignored when using other (non-adaptive) clipping methods.
         */
        alpha: Double = 0.05,
    ): LinearSrgb {
        val l0 = when (method) {
            ClipMethod.PRESERVE_LIGHTNESS -> lightness
            ClipMethod.PROJECT_TO_MID -> 50.0
            ClipMethod.ADAPTIVE_TOWARDS_MID -> OklabGamut.calcAdaptiveMidL(
                L = lightness / 100.0,
                C = chroma / 100.0,
                alpha = alpha,
            ) * 100.0
        }

        return clip(
            lightness = lightness,
            chroma = chroma,
            hue = hue,
            l0 = l0,
            epsilon = EPSILON_100,
            maxLightness = 100.0
        ) { l, c, h ->
            Zcam(
                lightness = l,
                chroma = c,
                hue = h,
                viewingConditions = viewingConditions,
            ).toXyzAbs(
                luminanceSource = Zcam.LuminanceSource.LIGHTNESS,
                chromaSource = Zcam.ChromaSource.CHROMA,
            ).toRel(viewingConditions.referenceWhite.y).toLinearSrgb()
        }
    }

    public enum class ClipMethod {
        /**
         * Preserve the target lightness (e.g. for contrast) by reducing chroma.
         */
        PRESERVE_LIGHTNESS,

        /**
         * Project towards neutral 50% gray.
         */
        PROJECT_TO_MID,

        /**
         * A mix of lightness-preserving chroma reduction and projecting towards neutral 50% gray.
         */
        ADAPTIVE_TOWARDS_MID,
    }
}
