package io.github.mattdillioncodes.glass

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * tvOS-style Control Center: a Liquid Glass panel that slides in from the right with HDMI
 * inputs, volume, a sleep timer, and shortcuts to the TV's picture settings and full Settings.
 */
/**
 * [onAppearance] runs after the light/dark switch flips, [onTint] after the transparency slider
 * moves, so Home can re-tint the rest of its glass to match.
 */
class ControlCenter(
    private val activity: Activity,
    private val onAppearance: () -> Unit,
    private val onTint: () -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val medium = Widgets.medium
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    private val audio = activity.getSystemService(AudioManager::class.java)
    private val tvInputs = activity.getSystemService(TvInputManager::class.java)

    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(22), dp(24), dp(22))
        clipChildren = false
        clipToPadding = false // focused pills grow 6% past the edge
    }
    // Scrolls: with HDMI inputs on a 960x540 dp screen, the panel can be taller than the TV.
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false // clips to the panel; focused pills grow within its padding
        addView(content)
    }
    val view: GlassView = GlassView(activity, 32 * density, thick = true).apply {
        visibility = View.GONE
        clipChildren = false
        addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // In Auto the panel turns frosted or smoked to suit the photo; redo the text to match.
        onTone = { if (isOpen) rebuildKeepingFocus() }
    }
    var isOpen = false
        private set
    private var sleepPills: LinearLayout? = null
    private var lookPills: LinearLayout? = null
    private val applyTint = Runnable {
        Look.load(activity)
        view.retint()
        onTint()
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        build()
        view.visibility = View.VISIBLE
        view.translationX = dp(400).toFloat()
        view.animate().translationX(0f).setInterpolator(ease).setDuration(320).start()
        scroll.scrollTo(0, 0)
        view.post { firstFocusable(content)?.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        view.animate().translationX(dp(400).toFloat()).setInterpolator(ease).setDuration(260)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    /** Contents are built in the panel's own tone, so labels and pills get the right ink. */
    private fun build() = Look.tone(view.light) { buildContent() }

    private fun rebuildKeepingFocus() {
        val index = Widgets.focusables(content).indexOf(content.findFocus())
        build()
        if (index >= 0) Widgets.focusables(content).getOrNull(index)?.requestFocus()
    }

    private fun buildContent() {
        content.removeAllViews()
        content.addView(label("Control Center", 22f, Look.ink()), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val inputs = inputPills()
        if (inputs.isNotEmpty()) { // streaming boxes and sticks have no HDMI inputs of their own
            section("INPUTS")
            content.addView(row(inputs))
        }

        section("VOLUME")
        content.addView(volumeSlider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        section("SLEEP TIMER")
        val left = SleepTimer.minutesLeft(activity)
        val status = label(sleepStatus(left), 13f, Look.ink(190))
        val presets = listOf(0, 15, 30, 60, 90)
        val selected = if (left == 0) 0 else presets.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - left) } ?: 0
        val sleepRow = row(presets.map { m ->
            pill(if (m == 0) "Off" else "$m", selected = m == selected) {
                // Without the permission a timer couldn't turn the TV off: don't pretend to set one.
                if (SleepTimer.isReady(activity)) SleepTimer.set(activity, m)
                build() // re-render selection + status, then keep focus on the pill just pressed
                sleepPills?.getChildAt(presets.indexOf(m))?.requestFocus()
            }
        })
        sleepPills = sleepRow
        content.addView(sleepRow)
        // Only when it says something the selected pill doesn't ("Sleeping in 12 min", needs permission).
        if (left > 0 || !SleepTimer.isReady(activity))
            content.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        section("APPEARANCE")
        val looks = listOf(Look.Mode.AUTO to "Auto", Look.Mode.LIGHT to "Light", Look.Mode.DARK to "Dark")
        val lookRow = row(looks.map { (mode, name) ->
            pill(name, selected = Look.mode == mode) {
                if (Look.mode != mode) {
                    Prefs(activity).glassMode = mode
                    Look.load(activity)
                    onAppearance() // new backdrop for Home's glass, this panel included; tones follow
                }
                build()
                lookPills?.getChildAt(looks.indexOfFirst { it.first == mode })?.requestFocus()
            }
        })
        lookPills = lookRow
        content.addView(lookRow)
        section("TRANSPARENCY") // its own heading, like Volume: keeps the slider's label short enough to fit
        content.addView(Slider(
            max = Look.MAX_TRANSPARENCY, initial = Look.transparency,
            icon = "◐", format = { "${it * 100 / Look.MAX_TRANSPARENCY}%" },
        ) {
            Prefs(activity).glassTransparency = it
            // Re-baking every panel is real work: wait until the remote pauses, then do it once.
            view.removeCallbacks(applyTint)
            view.postDelayed(applyTint, 150)
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        section("SETTINGS")
        content.addView(row(listOfNotNull(
            pill("Glass") { activity.startActivity(Intent(activity, SettingsActivity::class.java)) },
            Intent(Settings.ACTION_DISPLAY_SETTINGS).takeIf { canOpen(it) }?.let { pill("Picture") { start(it) } },
            pill("All Settings") { start(Intent(Settings.ACTION_SETTINGS)) },
        )))
    }

    private fun sleepStatus(left: Int) = when {
        !SleepTimer.isReady(activity) -> "Allow it in Glass Settings first"
        left == 0 -> "Off"
        else -> "Sleeping in $left min"
    }

    /** One pill per physical HDMI port; a CEC device on it shows its name, a signal shows a green dot. */
    private fun inputPills(): List<View> {
        val all = runCatching { tvInputs?.tvInputList.orEmpty() }.getOrDefault(emptyList())
        val ports = all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.loadLabel(activity).toString() }
        return ports.map { port ->
            val name = port.loadCustomLabel(activity) ?: port.loadLabel(activity)
            val device = all.firstOrNull { it.parentId == port.id }?.loadLabel(activity)
            val live = runCatching { tvInputs.getInputState(port.id) == TvInputManager.INPUT_STATE_CONNECTED }.getOrDefault(false)
            val text = SpannableStringBuilder().apply {
                if (live) append("● ", ForegroundColorSpan(Color.rgb(48, 209, 88)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(name)
                if (device != null && device.toString() != name.toString()) {
                    append("\n")
                    append(device, RelativeSizeSpan(0.75f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            pill(text) {
                val uri = TvContract.buildChannelUriForPassthroughInput(port.id)
                // Some TV players (TCL's) only match VIEW with the channel MIME type attached,
                // others only without it.
                val typed = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                start(typed) || start(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }

    // --- building blocks ----------------------------------------------------------------------

    /** Settings screens and TV players vary by maker; false if this TV has nothing for [intent]. */
    private fun canOpen(intent: Intent) = intent.resolveActivity(activity.packageManager) != null

    private fun start(intent: Intent) =
        runCatching { activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun label(text: CharSequence, size: Float, color: Int) = Widgets.label(activity, text, size, color)

    private fun section(title: String) {
        content.addView(label(title, 11f, Look.ink(150)).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(8) })
    }

    private fun row(items: List<View>) = LinearLayout(activity).apply {
        clipChildren = false
        items.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun pill(text: CharSequence, selected: Boolean = false, onClick: () -> Unit) =
        Widgets.pill(activity, text, selected, onClick)

    private fun firstFocusable(v: View?): View? = when {
        v == null -> null
        v.isFocusable && v.visibility == View.VISIBLE -> v
        v is LinearLayout -> (0 until v.childCount).firstNotNullOfOrNull { firstFocusable(v.getChildAt(it)) }
        else -> null
    }

    private fun volumeSlider() = Slider(
        max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        initial = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        icon = "🔊", format = { "$it" },
    ) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }

    /** A glass track from 0 to [max]; Left/Right on the remote move it while it's focused. */
    private inner class Slider(
        private val max: Int,
        initial: Int,
        private val icon: String,
        private val format: (Int) -> String,
        private val onChange: (Int) -> Unit,
    ) : View(activity) {
        private var value = initial
        private val step = maxOf(1, max / 50)
        private val light = Look.light // the panel's tone when built
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * density * 1.0f; typeface = medium }

        init {
            isFocusable = true
            defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -step
                KeyEvent.KEYCODE_DPAD_RIGHT -> step
                else -> return super.onKeyDown(keyCode, event)
            }
            val next = (value + delta).coerceIn(0, max)
            if (next != value) { value = next; onChange(value) }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            track.color = Look.ink(light, if (isFocused) 60 else 26)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
            val frac = if (max == 0) 0f else value / max.toFloat()
            fill.color = Look.ink(light, if (isFocused) 240 else 170)
            canvas.drawRoundRect(0f, 0f, maxOf(h, w * frac), h, r, r, fill)
            val label = if (isFocused) "◀  ${format(value)}  ▶" else "$icon  ${format(value)}"
            val pad = dp(16).toFloat()
            val y = h / 2 - (text.descent() + text.ascent()) / 2
            val edge = maxOf(h, w * frac)
            // Never split across the fill's edge (a low value would be cut in half). In order: inside
            // the fill, just past it, right-aligned on the track; else inside the fill regardless.
            val textW = text.measureText(label)
            val onFill = if (light) Color.WHITE else Color.rgb(18, 18, 22)
            val (x, color) = when {
                edge >= pad + textW + pad / 2 -> pad to onFill
                edge + pad * 0.75f + textW <= w - pad / 2 -> edge + pad * 0.75f to Look.ink(light)
                w - pad - textW >= edge + pad / 4 -> w - pad - textW to Look.ink(light)
                else -> pad to onFill
            }
            text.color = color
            canvas.drawText(label, x, y, text)
        }
    }
}
