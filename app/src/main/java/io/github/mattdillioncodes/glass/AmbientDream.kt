package io.github.mattdillioncodes.glass

import android.graphics.Color
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * Glass's ambient screen as a system screensaver, so it runs over any app (not just on Home):
 * the photos crossfading, with a big clock and the weather and date. Any button wakes the TV.
 */
class AmbientDream : DreamService() {
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var time: TextView
    private lateinit var info: TextView
    private var weatherLine = ""
    private val queue = ArrayDeque<String>()
    private val tick = Runnable { update() }
    private val next = Runnable { nextPhoto() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        Look.load(this)
        val density = resources.displayMetrics.density
        fun shadow(t: TextView) = t.setShadowLayer(14 * density, 0f, 2 * density, Color.argb(150, 0, 0, 0))
        time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            shadow(this)
        }
        info = TextView(this).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            textSize = 22f
            typeface = Widgets.medium
            shadow(this)
        }
        wallpaper = WallpaperView(this)
        root = FrameLayout(this).apply {
            addView(wallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(LinearLayout(this@AmbientDream).apply {
                orientation = LinearLayout.VERTICAL
                addView(time)
                addView(info, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
            }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                .apply { setMargins((40 * density).toInt(), 0, 0, (36 * density).toInt()) })
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        update()
        root.post { nextPhoto() }
        val prefs = Prefs(this)
        if (prefs.hasWeatherCity) {
            val (lat, lon, f) = Triple(prefs.weatherLat, prefs.weatherLon, prefs.fahrenheit)
            Net.io.execute {
                runCatching { Weather.fetch(lat, lon, f) }.onSuccess { w ->
                    root.post { weatherLine = "${w.symbol} ${w.temp}°"; update() }
                }
            }
        }
    }

    override fun onDreamingStopped() {
        root.removeCallbacks(tick)
        root.removeCallbacks(next)
        super.onDreamingStopped()
    }

    /** Clock and date, redrawn at the top of each minute. */
    private fun update() {
        val now = Date()
        time.text = DateFormat.getTimeFormat(this).format(now)
        info.text = listOf(weatherLine, DateFormat.format("EEEE, MMMM d", now).toString())
            .filter { it.isNotEmpty() }.joinToString("   ·   ")
        root.removeCallbacks(tick)
        root.postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    private fun nextPhoto() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val photos = Wallpapers.photos(this)
        if (queue.isEmpty()) queue.addAll(photos.shuffled())
        val photo = queue.removeFirstOrNull()
        Glass.worker.execute {
            val wp = Wallpapers.load(this, w, h, photo)
            wp.small.recycle() // no glass here, so only the full-size photo is needed
            root.post {
                wallpaper.dim = Look.dimPhotos(wp.dim)
                wallpaper.bitmap = wp.display
            }
        }
        // Photos change at the user's rotation speed; "only on Home" means a calm 60 s here.
        if (photos.size > 1) root.postDelayed(next, Prefs(this).rotateSeconds.takeIf { it > 0 }?.times(1000L) ?: 60_000L)
    }
}
