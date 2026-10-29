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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * tvOS-style Control Center: a Liquid Glass panel that slides in from the right with HDMI
 * inputs, volume, a sleep timer, and shortcuts to the TV's picture settings and full Settings.
 */
class ControlCenter(private val activity: Activity) {
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
    val view: GlassView = GlassView(activity, 32 * density).apply {
        visibility = View.GONE
        clipChildren = false
        addView(content)
    }
    var isOpen = false
        private set
    private var sleepPills: LinearLayout? = null

    fun open() {
        if (isOpen) return
        isOpen = true
        build()
        view.visibility = View.VISIBLE
        view.translationX = dp(400).toFloat()
        view.animate().translationX(0f).setInterpolator(ease).setDuration(320).start()
        view.post { firstFocusable(content)?.requestFocus() }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        view.animate().translationX(dp(400).toFloat()).setInterpolator(ease).setDuration(260)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun build() {
        content.removeAllViews()
        content.addView(label("Control Center", 22f, Color.WHITE), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val inputs = inputPills()
        if (inputs.isNotEmpty()) { // streaming boxes and sticks have no HDMI inputs of their own
            section("INPUTS")
            content.addView(row(inputs))
        }

        section("VOLUME")
        content.addView(VolumeBar(), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        section("SLEEP TIMER")
        val left = SleepTimer.minutesLeft(activity)
        val status = label(sleepStatus(left), 13f, Color.argb(190, 255, 255, 255))
        val presets = listOf(0, 15, 30, 60, 90)
        val selected = if (left == 0) 0 else presets.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - left) } ?: 0
        val sleepRow = row(presets.map { m ->
            pill(if (m == 0) "Off" else "$m", selected = m == selected) {
                SleepTimer.set(activity, m)
                build() // re-render selection + status, then keep focus on the pill just pressed
                sleepPills?.getChildAt(presets.indexOf(m))?.requestFocus()
            }
        })
        sleepPills = sleepRow
        content.addView(sleepRow)
        content.addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        section("SETTINGS")
        content.addView(row(listOfNotNull(
            pill("Glass") { activity.startActivity(Intent(activity, SettingsActivity::class.java)) },
            Intent(Settings.ACTION_DISPLAY_SETTINGS).takeIf { canOpen(it) }?.let { pill("Picture") { start(it) } },
            pill("All Settings") { start(Intent(Settings.ACTION_SETTINGS)) },
        )))
    }

    private fun sleepStatus(left: Int) = when {
        !SleepTimer.isReady(activity) -> "Turn on in Glass settings first"
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
        content.addView(label(title, 11f, Color.argb(150, 255, 255, 255)).apply { letterSpacing = 0.14f },
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

    /** Volume as a glass track; left/right on the remote adjust it while it's focused. */
    private inner class VolumeBar : View(activity) {
        private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        private var value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        private val step = maxOf(1, max / 50)
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
            value = (value + delta).coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            track.color = if (isFocused) Color.argb(70, 255, 255, 255) else Color.argb(30, 255, 255, 255)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
            val frac = if (max == 0) 0f else value / max.toFloat()
            fill.color = if (isFocused) Color.argb(240, 255, 255, 255) else Color.argb(170, 255, 255, 255)
            canvas.drawRoundRect(0f, 0f, maxOf(h, w * frac), h, r, r, fill)
            val label = if (isFocused) "◀  $value  ▶" else "🔊  $value"
            val x = dp(16).toFloat()
            val y = h / 2 - (text.descent() + text.ascent()) / 2
            // White over the track, dark over the bright fill, split exactly at the fill's edge.
            val edge = maxOf(h, w * frac)
            canvas.save()
            canvas.clipRect(edge, 0f, w, h)
            text.color = Color.WHITE
            canvas.drawText(label, x, y, text)
            canvas.restore()
            canvas.save()
            canvas.clipRect(0f, 0f, edge, h)
            text.color = Color.rgb(18, 18, 22)
            canvas.drawText(label, x, y, text)
            canvas.restore()
        }
    }
}
