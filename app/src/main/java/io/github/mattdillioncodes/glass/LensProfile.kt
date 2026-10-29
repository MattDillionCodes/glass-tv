package io.github.mattdillioncodes.glass

import kotlin.math.sqrt

/**
 * The shape of the Liquid Glass edge: how hard the glass bends the background at a point
 * inside its rim ("bezel").
 *
 * [t] runs from 0f at the very outer edge of the panel to 1f where the bezel meets the
 * flat middle of the glass. Return 0f..1f: 1f bends the background by the full maxShift
 * (12dp, content pulled in from deeper inside the panel), 0f leaves it untouched.
 *
 * Return ~0f at t = 1f, otherwise a visible seam appears where the bezel ends.
 */
fun lensProfile(t: Float): Float {
    // A quarter-circle rim: strongest bend at the outer edge, falling smoothly to 0 with zero
    // slope at t = 1, so the bezel blends into the flat centre without a seam.
    val u = 1f - t
    return 1f - sqrt(1f - u * u)
}
