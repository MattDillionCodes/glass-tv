package io.github.mattdillioncodes.glass

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * How the glass looks. In [Mode.AUTO] (the default) every panel picks its own tone from the photo
 * behind it, like Apple's Liquid Glass: frosted with dark text over bright content, smoked with
 * white text over dark content. Light and Dark force one tone everywhere.
 *
 * [light] is the tone of whatever is being built *right now*: a panel sets it with [tone] while
 * its contents are created, so labels and pills pick up the right ink. Text drawn straight on the
 * photo (the clock, the app name) stays white with a shadow either way.
 */
object Look {
    enum class Mode { AUTO, LIGHT, DARK }

    const val MAX_TRANSPARENCY = 10
    const val DEFAULT_TRANSPARENCY = 5 // the original look

    var mode = Mode.AUTO
        private set
    var transparency = DEFAULT_TRANSPARENCY
        private set
    var light = false
        private set

    fun load(context: Context) {
        val prefs = Prefs(context)
        mode = prefs.glassMode
        transparency = prefs.glassTransparency
        light = mode == Mode.LIGHT
    }

    /** Builds [block] in a given tone (one panel's contents), then restores the previous one. */
    fun <T> tone(light: Boolean, block: () -> T): T {
        val previous = this.light
        this.light = light
        try { return block() } finally { this.light = previous }
    }

    /**
     * The tone a panel should take over a patch of this luminance (0..1). Auto switches with a
     * dead band around the middle, so a panel over mid-grey doesn't flicker between the two.
     */
    fun toneFor(luminance: Float, current: Boolean) = when (mode) {
        Mode.LIGHT -> true
        Mode.DARK -> false
        Mode.AUTO -> when {
            luminance > 0.58f -> true
            luminance < 0.42f -> false
            else -> current
        }
    }

    /** Text and icons on glass, at [alpha]. */
    fun ink(alpha: Int = 255) = ink(light, alpha)
    fun ink(light: Boolean, alpha: Int = 255): Int =
        if (light) Color.argb(alpha, 22, 22, 28) else Color.argb(alpha, 255, 255, 255)

    /**
     * What's laid over the refracted wallpaper: white frost, or smoke. Transparency only thins this
     * layer; the blur and the bent edges stay, so it still reads as glass. [thick] panels (big ones,
     * full of text) are more opaque, as Apple's larger glass is.
     */
    fun glassTint(light: Boolean, thick: Boolean): Int {
        val base = if (light) 240 - 62 * transparency / 5 else 200 - 16 * transparency // frosted/smoked .. clear
        val alpha = (base + if (thick) 30 else 0).coerceAtMost(245)
        return if (light) Color.argb(alpha, 248, 248, 252) else Color.argb(alpha, 14, 14, 20)
    }
    fun glassFocused(light: Boolean) = if (light) Color.argb(110, 255, 255, 255) else Color.argb(70, 255, 255, 255)
    fun sheenAlpha(light: Boolean) = if (light) 70 else 20
    fun shadowAlpha(light: Boolean, thick: Boolean) = (if (light) 55 else 110) + if (thick) 30 else 0

    /**
     * Apple's rule for clear glass: over content that fights the text (bright behind white text,
     * dark behind dark text), lay a 35% layer of the opposite color under the glass. Scales in as
     * transparency goes past the default; returns 0 (nothing) when it isn't needed.
     */
    fun clarityGuard(light: Boolean, luminance: Float): Int {
        val clear = (transparency - DEFAULT_TRANSPARENCY).coerceAtLeast(0) / (MAX_TRANSPARENCY - DEFAULT_TRANSPARENCY).toFloat()
        val clash = if (light) luminance < 0.45f else luminance > 0.55f
        if (clear == 0f || !clash) return 0
        val alpha = (0.35f * 255 * clear).toInt()
        return if (light) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)
    }

    /** Only forced Dark darkens the photos themselves; Auto and Light show them at full brightness. */
    fun dimPhotos(photo: Boolean) = photo && mode == Mode.DARK
}

/** The small pieces the Control Center and Glass Settings are both built from. */
object Widgets {
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun label(context: Context, text: CharSequence, size: Float, color: Int = Look.ink()) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = medium
    }

    /** A glass capsule button: high-contrast and lifted when focused, blue-tinted when selected. */
    fun pill(context: Context, text: CharSequence, selected: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        val density = resources.displayMetrics.density
        val pad = { v: Int -> (v * density).toInt() }
        val light = Look.light // the tone of the panel being built; focus changes repaint later
        this.text = text
        textSize = 15f
        typeface = medium
        gravity = Gravity.CENTER
        isFocusable = true
        defaultFocusHighlightEnabled = false
        setPadding(pad(16), pad(9), pad(16), pad(9))
        fun paint(focused: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = 100f * density
                // Focus inverts the glass's own ink: a white capsule on smoked glass, a dark one on frosted.
                setColor(when {
                    focused -> if (light) Color.argb(235, 22, 22, 28) else Color.argb(235, 255, 255, 255)
                    selected -> Color.argb(90, 10, 132, 255)
                    else -> Look.ink(light, if (light) 18 else 30)
                })
                setStroke(pad(1), if (selected && !focused) Color.argb(220, 10, 132, 255) else Look.ink(light, if (light) 40 else 60))
            }
            setTextColor(when {
                focused -> if (light) Color.WHITE else Color.rgb(18, 18, 22)
                else -> Look.ink(light)
            })
            animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(150).start()
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }

    /** Focusable views under [root] in layout order: lets a rebuilt panel put focus back where it was. */
    fun focusables(root: View): List<View> = when {
        root.isFocusable && root.visibility == View.VISIBLE -> listOf(root)
        root is ViewGroup -> (0 until root.childCount).flatMap { focusables(root.getChildAt(it)) }
        else -> emptyList()
    }
}
