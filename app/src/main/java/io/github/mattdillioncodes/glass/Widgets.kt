package io.github.mattdillioncodes.glass

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/** The small pieces the Control Center and Glass Settings are both built from. */
object Widgets {
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun label(context: Context, text: CharSequence, size: Float, color: Int = Color.WHITE) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = medium
    }

    /** A glass capsule button: bright and lifted when focused, blue-tinted when selected. */
    fun pill(context: Context, text: CharSequence, selected: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        val density = resources.displayMetrics.density
        val pad = { v: Int -> (v * density).toInt() }
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
                setColor(when {
                    focused -> Color.argb(235, 255, 255, 255)
                    selected -> Color.argb(90, 10, 132, 255)
                    else -> Color.argb(30, 255, 255, 255)
                })
                setStroke(pad(1), if (selected && !focused) Color.argb(220, 10, 132, 255) else Color.argb(60, 255, 255, 255))
            }
            setTextColor(if (focused) Color.rgb(18, 18, 22) else Color.WHITE)
            animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(150).start()
        }
        paint(false)
        setOnFocusChangeListener { _, f -> paint(f) }
        setOnClickListener { onClick() }
    }
}
