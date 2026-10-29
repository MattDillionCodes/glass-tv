package io.github.mattdillioncodes.glass

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** Launch extra: show (or toggle) the ambient screen. Sent by the remote-button remapper. */
const val EXTRA_AMBIENT = "io.github.mattdillioncodes.glass.AMBIENT"

/** Launch extra: open (or toggle) the Control Center. Sent by the remote-button remapper. */
const val EXTRA_CONTROLS = "io.github.mattdillioncodes.glass.CONTROLS"

/**
 * tvOS-style home: Top Shelf (blurred art of the focused app) over a glass dock of up to five
 * pinned apps; Down opens the full app grid over a blurred wallpaper. Layout follows the tvOS
 * grid: 80pt side / 60pt top-bottom safe area, 320pt columns with 40pt gutters (in dp at 1080p).
 */
class HomeActivity : Activity() {
    private class App(val info: ActivityInfo, val label: String, val art: Drawable?)

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var gridWallpaper: WallpaperView
    private lateinit var shelfA: ShelfView
    private lateinit var shelfB: ShelfView
    private lateinit var shelfTitle: TextView
    private lateinit var status: View
    private lateinit var ambient: LinearLayout
    private lateinit var ambientTime: TextView
    private lateinit var ambientInfo: TextView
    private var inAmbient = false
    private var swallowKeyUp = false
    private var weatherLine = ""
    private lateinit var time: TextView
    private lateinit var weather: TextView
    private lateinit var gear: GlassView
    private lateinit var controls: ControlCenter
    private lateinit var dock: GlassView
    private lateinit var dockRow: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var gridScroll: ScrollView
    private lateinit var grid: GridLayout
    private lateinit var toast: GlassView
    private lateinit var toastText: TextView

    private var frontShelf: ShelfView? = null
    private var focusedImage: TileImage? = null
    private var lastDockTile: View? = null
    private var gridOpen = false
    private var allApps: List<App> = emptyList()
    private var photoMode = false
    private val photoQueue = ArrayDeque<String>()
    private val photoHistory = ArrayList<String>() // what's been shown, so ambient Left can go back
    private var historyIndex = -1
    private val rotation = Runnable { nextWallpaper() }

    private var weatherAt = 0L
    private var weatherFor = "" // the city/units the cached weather belongs to
    private val refresh = Runnable { refreshLiveData() }

    private companion object {
        const val IDLE_PAUSE_MS = 6 * 1000L // stop the focus sway this long after the last remote press
        const val DOCK_SLOTS = 5
        const val WEATHER_EVERY_MS = 15 * 60 * 1000L
    }

    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f) // tvOS-like ease curve
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tileW by lazy { dp(160) }
    private val tileH by lazy { dp(90) }

    /** The focused tile "gently sways" (tvOS focus): slow 3D tilt plus a moving glint. */
    private val sway = ValueAnimator.ofFloat(-1f, 1f).apply {
        duration = 4000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val v = a.animatedValue as Float
            focusedImage?.let { it.sway = v; it.rotationY = v * 3f }
        }
    }
    private val idlePause = Runnable { settleSway() }

    /** Remote idle: ease the focused tile back to straight and stop redrawing every frame. */
    private fun settleSway() {
        sway.cancel()
        focusedImage?.let { it.sway = 0f; it.animate().rotationY(0f).setDuration(500).start() }
    }

    /** Any remote press: resume the sway from its centre (value 0) so the tilt doesn't jump. */
    private fun wakeSway() {
        root.removeCallbacks(idlePause)
        if (!sway.isStarted) {
            focusedImage?.animate()?.cancel() // stop a settle still in flight before the sway drives it again
            sway.start()
            sway.currentPlayTime = sway.duration / 2
        }
        root.postDelayed(idlePause, IDLE_PAUSE_MS)
    }

    /** Kept out of the grid: Glass itself and system Settings (both live in the Control Center). */
    private val hiddenApps by lazy { setOf(packageName, "com.android.tv.settings", "com.google.android.tv.settings") }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    /** Drawables carry their own bounds, so every view showing the same art needs its own copy. */
    private fun Drawable.copy(): Drawable = constantState?.newDrawable(resources)?.mutate() ?: this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { clipChildren = false } // no background: the wallpaper covers it (less overdraw)
        wallpaper = WallpaperView(this)
        shelfA = ShelfView(this)
        shelfB = ShelfView(this).apply { alpha = 0f }
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(shelfA, FrameLayout.LayoutParams(MATCH_PARENT, dp(370)))
        root.addView(shelfB, FrameLayout.LayoutParams(MATCH_PARENT, dp(370)))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
        }, FrameLayout.LayoutParams(MATCH_PARENT, dp(140)))
        shelfTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = medium
            setShadowLayer(dpf(12), 0f, dpf(2), Color.argb(140, 0, 0, 0))
        }
        root.addView(shelfTitle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(40), 0, 0, dp(172)) })
        status = buildStatus()
        root.addView(status, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(30), dp(40), 0) })
        root.addView(buildAmbient(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
            .apply { setMargins(dp(40), 0, 0, dp(36)) })
        root.addView(buildDock(), FrameLayout.LayoutParams(dp(920), dp(126), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(0, 0, 0, dp(30)) })
        root.addView(buildGrid(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        controls = ControlCenter(this)
        root.addView(controls.view, FrameLayout.LayoutParams(dp(380), MATCH_PARENT, Gravity.END)
            .apply { setMargins(0, dp(30), dp(40), dp(30)) })
        toastText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = medium
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        toast = GlassView(this, dpf(22)).apply { alpha = 0f; addView(toastText) }
        root.addView(toast, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(30) }) // top centre: clear of the dock, the grid and the status row
        setContentView(root)
        loadApps()
        if (intent.getBooleanExtra(EXTRA_AMBIENT, false)) root.post { enterAmbient() }
        if (intent.getBooleanExtra(EXTRA_CONTROLS, false)) root.post { openControls() }
    }

    // --- Ambient: just the photo and a big clock with weather/date ---------------------------

    private fun buildAmbient(): View {
        val shadow = { t: TextView -> t.setShadowLayer(dpf(14), 0f, dpf(2), Color.argb(150, 0, 0, 0)) }
        ambientTime = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            shadow(this)
        }
        ambientInfo = TextView(this).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            textSize = 22f
            typeface = medium
            shadow(this)
        }
        ambient = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
            addView(ambientTime)
            addView(ambientInfo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        return ambient
    }

    private fun updateAmbientText() {
        val now = Date()
        ambientTime.text = DateFormat.getTimeFormat(this).format(now)
        ambientInfo.text = listOf(weatherLine, DateFormat.format("EEEE, MMMM d", now).toString())
            .filter { it.isNotEmpty() }.joinToString("   ·   ")
    }

    private fun enterAmbient() {
        if (inAmbient) return
        inAmbient = true
        closeControls()
        closeGrid(animate = false)
        settleSway()
        updateAmbientText()
        listOf(dock, status, shelfTitle, shelfA, shelfB).forEach { v ->
            v.animate().alpha(0f).setDuration(450).withEndAction { if (inAmbient) v.visibility = View.INVISIBLE }.start()
        }
        ambient.visibility = View.VISIBLE
        ambient.animate().alpha(1f).setDuration(450).start()
    }

    private fun exitAmbient() {
        if (!inAmbient) return
        inAmbient = false
        ambient.animate().alpha(0f).setDuration(250).withEndAction { if (!inAmbient) ambient.visibility = View.GONE }.start()
        listOf(dock, status, shelfTitle).forEach { v ->
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(300).start()
        }
        listOf(shelfA, shelfB).forEach { it.visibility = View.VISIBLE } // their alpha is managed by showShelf
        (lastDockTile ?: dockRow.getChildAt(0))?.requestFocus()
    }

    // --- Top-right: weather + time + Control Center button ---------------------------------

    private fun buildStatus(): View {
        time = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = medium
            setShadowLayer(dpf(8), 0f, dpf(1), Color.argb(120, 0, 0, 0))
        }
        weather = TextView(this).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            textSize = 20f
            typeface = medium
            setShadowLayer(dpf(8), 0f, dpf(1), Color.argb(120, 0, 0, 0))
            visibility = View.GONE // until a city is set and the first forecast arrives
        }
        val icon = TextView(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        gear = GlassView(this, dpf(22)).apply {
            addView(icon, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            isFocusable = true
            setOnClickListener { openControls() }
            setOnFocusChangeListener { v, focused ->
                val s = if (focused) 1.15f else 1f
                v.animate().scaleX(s).scaleY(s).setInterpolator(ease).setDuration(220).start()
                highlighted = focused
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(weather, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(18) })
            addView(time)
            addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(16) })
        }
    }

    // --- Dock: glass navigation layer with up to five pinned apps --------------------------

    private fun buildDock(): View {
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            clipChildren = false
            clipToPadding = false
        }
        dock = GlassView(this, dpf(32)).apply {
            clipChildren = false
            addView(dockRow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return dock
    }

    // --- All Apps grid ----------------------------------------------------------------------

    private fun buildGrid(): View {
        // Background = the glass's own small blurred copy, stretched: no live full-screen GPU blur.
        gridWallpaper = WallpaperView(this)
        grid = GridLayout(this).apply {
            columnCount = 5
            clipChildren = false
            clipToPadding = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(TextView(this@HomeActivity).apply {
                text = "Apps"
                setTextColor(Color.WHITE)
                textSize = 28f
                typeface = medium
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(10), 0, 0, dp(18)) })
            addView(TextView(this@HomeActivity).apply {
                text = "Long-press an app to pin it to the dock"
                setTextColor(Color.argb(150, 255, 255, 255))
                textSize = 14f
                typeface = medium
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(10), 0, 0, dp(18)) })
            addView(grid)
        }
        gridScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(30), dp(30), dp(30), dp(30))
            addView(column)
        }
        overlay = FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(gridWallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(View(this@HomeActivity).apply { setBackgroundColor(Color.argb(190, 0, 0, 0)) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(gridScroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return overlay
    }

    private fun openGrid() {
        if (gridOpen) return
        gridOpen = true
        dock.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gear.isFocusable = false
        gridScroll.scrollTo(0, 0)
        overlay.animate().cancel() // runs a pending close's end action (GONE) before we show it
        overlay.visibility = View.VISIBLE
        overlay.translationY = dpf(40)
        overlay.animate().alpha(1f).translationY(0f).setInterpolator(ease).setDuration(300).start()
        grid.getChildAt(0)?.requestFocus()
    }

    private fun closeGrid(animate: Boolean = true) {
        if (!gridOpen) return
        gridOpen = false
        dock.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gear.isFocusable = true
        (lastDockTile ?: dockRow.getChildAt(0))?.requestFocus()
        if (!animate) { overlay.alpha = 0f; overlay.visibility = View.GONE; return }
        overlay.animate().alpha(0f).translationY(dpf(40)).setInterpolator(ease).setDuration(250)
            .withEndAction { overlay.visibility = View.GONE }.start()
    }

    // --- Tiles ------------------------------------------------------------------------------

    private fun buildTile(app: App, inDock: Boolean, index: Int): View {
        val image = TileImage(this, dpf(14)).apply {
            if (app.art != null) {
                setImageDrawable(app.art.copy())
                scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                setImageDrawable(app.info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(22), dp(14), dp(22), dp(14))
                setBackgroundColor(Color.argb(50, 255, 255, 255))
            }
            cameraDistance = 12000f * resources.displayMetrics.density
        }
        val label = TextView(this).apply {
            text = app.label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = medium
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            addView(image, LinearLayout.LayoutParams(tileW, tileH))
            if (!inDock) addView(label, LinearLayout.LayoutParams(tileW, dp(26))) // dock tiles: art only
            setOnClickListener { launch(app.info) }
            setOnLongClickListener { togglePin(app, inDock); true }
            setOnFocusChangeListener { v, focused ->
                val s = if (focused) 1.1f else 1f
                image.lit = focused
                image.animate().scaleX(s).scaleY(s).translationZ(if (focused) dpf(14) else 0f)
                    .apply { if (!focused) rotationY(0f) }
                    .setInterpolator(ease).setDuration(220).start()
                label.animate().alpha(if (focused) 1f else 0f).translationY(if (focused) dpf(5) else 0f)
                    .setInterpolator(ease).setDuration(220).start()
                if (focused) {
                    focusedImage = image
                    if (inDock) { lastDockTile = v; showShelf(app) }
                } else if (focusedImage === image) {
                    focusedImage = null
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    inDock && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { openGrid(); true }
                    !inDock && index < grid.columnCount && keyCode == KeyEvent.KEYCODE_DPAD_UP -> { closeGrid(); true }
                    else -> false
                }
            }
        }
    }

    private fun showShelf(app: App) {
        val front = frontShelf
        if (!photoMode) {
            val back = if (front === shelfA) shelfB else shelfA
            back.art = app.art?.copy() ?: app.info.loadIcon(packageManager)
            back.animate().alpha(1f).setInterpolator(ease).setDuration(450).start()
            front?.animate()?.alpha(0f)?.setInterpolator(ease)?.setDuration(450)?.start()
            frontShelf = back
        }
        shelfTitle.animate().alpha(0f).setDuration(120).withEndAction {
            shelfTitle.text = app.label
            shelfTitle.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    // --- Control Center ---------------------------------------------------------------------

    private fun openControls() {
        closeGrid(animate = false)
        dock.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        gear.isFocusable = false
        controls.open()
    }

    private fun closeControls() {
        if (!controls.isOpen) return
        controls.close()
        dock.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gear.isFocusable = true
        gear.requestFocus()
    }

    // --- Dock pins: long-press a grid app to pin it, a dock app to unpin it -------------------

    /** Before the user pins anything: the popular streaming apps that are installed, else the first few. */
    private fun dockPins(): List<String> = prefs.dockPins ?: run {
        val installed = allApps.map { it.info.packageName }
        Prefs.POPULAR_APPS.filter { it in installed }.ifEmpty { installed }.take(DOCK_SLOTS)
    }

    private fun togglePin(app: App, inDock: Boolean) {
        val pkg = app.info.packageName
        val pins = dockPins().toMutableList()
        val shown = pins.filter { p -> allApps.any { it.info.packageName == p } }
        when {
            pkg in pins -> { pins.remove(pkg); showToast("Removed ${app.label} from the dock") }
            shown.size >= DOCK_SLOTS -> { showToast("Dock is full · long-press a dock app to remove it"); return }
            else -> { pins.add(pkg); showToast("Pinned ${app.label} to the dock") }
        }
        prefs.dockPins = pins
        val index = (dockRow.indexOfChild(lastDockTile)).coerceAtLeast(0)
        fillDock()
        lastDockTile = null // the old tiles are gone; Back from the grid lands on the first new one
        if (inDock) (dockRow.getChildAt(index.coerceAtMost(dockRow.childCount - 1)) ?: gear).requestFocus()
    }

    private fun fillDock() {
        val pinned = dockPins().mapNotNull { p -> allApps.find { it.info.packageName == p } }.take(DOCK_SLOTS)
        dockRow.removeAllViews()
        if (pinned.isEmpty()) {
            dockRow.addView(TextView(this).apply {
                text = "Long-press an app in All Apps (press down) to pin it here"
                setTextColor(Color.argb(190, 255, 255, 255))
                textSize = 16f
                typeface = medium
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(100)))
            dockRow.isFocusable = true // something to land on, so Down still opens the grid
            dockRow.setOnKeyListener { _, k, e ->
                (e.action == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_DPAD_DOWN).also { if (it) openGrid() }
            }
            return
        }
        dockRow.isFocusable = false
        pinned.forEachIndexed { i, app ->
            dockRow.addView(buildTile(app, inDock = true, index = i),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(10), 0, dp(10), 0) })
        }
    }

    private fun showToast(message: String) {
        toastText.text = message
        toast.animate().cancel()
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(180).start()
        root.removeCallbacks(hideToast)
        root.postDelayed(hideToast, 2200)
    }

    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(250).start() }

    private fun loadApps() {
        allApps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName !in hiddenApps }
            .map { ri: ResolveInfo ->
                val ai = ri.activityInfo
                App(ai, ri.loadLabel(packageManager).toString(),
                    ai.loadBanner(packageManager) ?: ai.applicationInfo.loadBanner(packageManager))
            }
            .sortedBy { it.label.lowercase() }
        fillDock()
        grid.removeAllViews()
        allApps.forEachIndexed { i, app ->
            grid.addView(buildTile(app, inDock = false, index = i), GridLayout.LayoutParams().apply {
                width = tileW
                height = WRAP_CONTENT
                setMargins(dp(10), dp(4), dp(10), dp(14))
            })
        }
        lastDockTile = null
        dockRow.post { (dockRow.getChildAt(0)?.takeIf { it.isFocusable } ?: dockRow).requestFocus() }
    }

    private fun launch(ai: ActivityInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(ai.packageName, ai.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
    }

    // --- Wallpaper rotation -----------------------------------------------------------------

    /**
     * Shows the next photo (shuffled, no repeats until all have shown), then schedules the one
     * after if rotation is on. Called every time Home becomes visible.
     */
    private fun nextWallpaper(manual: Boolean = false) {
        root.removeCallbacks(rotation)
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val photos = Wallpapers.photos(this)
        photoMode = photos.isNotEmpty()
        if (!photoMode && wallpaper.bitmap != null) return // generated wallpaper never changes
        if (photoMode) {
            // The photo is the Top Shelf now; app art would cover the best part of the shot.
            listOf(shelfA, shelfB).forEach { it.animate().cancel(); it.alpha = 0f } // beat any fade-in in flight
            photoQueue.retainAll(photos.toSet()) // drop photos from sets turned off in Settings
            if (photoQueue.isEmpty()) photoQueue.addAll(photos.shuffled())
            if (prefs.rotateSeconds > 0) root.postDelayed(rotation, prefs.rotateSeconds * 1000L)
        }
        // Step forward through history first (after scrolling back), then on to new shuffled photos.
        val photo = when {
            !photoMode -> null
            historyIndex < photoHistory.lastIndex -> photoHistory[++historyIndex]
            else -> photoQueue.removeFirstOrNull()?.also {
                photoHistory += it
                if (photoHistory.size > 100) photoHistory.removeAt(0)
                historyIndex = photoHistory.lastIndex
            }
        }
        showPhoto(photo, w, h, manual)
    }

    /** Ambient Left: back through the photos already shown. */
    private fun previousWallpaper() {
        if (!photoMode || historyIndex <= 0) return
        root.removeCallbacks(rotation)
        if (prefs.rotateSeconds > 0) root.postDelayed(rotation, prefs.rotateSeconds * 1000L)
        showPhoto(photoHistory[--historyIndex], root.width, root.height, manual = true)
    }

    /** "Photo 12 of 54", numbered by the photo's place in the set. */
    private fun showPhotoPosition() {
        val all = Wallpapers.photos(this)
        val current = photoHistory.getOrNull(historyIndex) ?: return
        showToast("◀   Photo ${all.indexOf(current) + 1} of ${all.size}   ▶")
    }

    private fun showPhoto(photo: String?, w: Int, h: Int, manual: Boolean = false) {
        Glass.worker.execute {
            val wp = Wallpapers.load(this, w, h, photo)
            val backdrop = Backdrop(wp.small, w, h, wp.dim)
            runOnUiThread {
                wallpaper.fadeMs = if (manual) 400L else 1500L // snappy when you scroll by hand
                wallpaper.dim = wp.dim
                gridWallpaper.dim = false // the blurred copy is already dimmed
                wallpaper.bitmap = wp.display
                gridWallpaper.bitmap = backdrop.blurredImage
                dock.backdrop = backdrop
                controls.view.backdrop = backdrop
            }
        }
    }

    // --- Weather ----------------------------------------------------------------------------

    /** Checks every minute; refetches when 15 minutes have passed or the city/units changed. */
    private fun refreshLiveData() {
        root.removeCallbacks(refresh)
        root.postDelayed(refresh, 60 * 1000L)
        if (!prefs.hasWeatherCity) {
            weatherLine = ""
            weather.visibility = View.GONE
            updateAmbientText()
            return
        }
        val key = "${prefs.weatherLat},${prefs.weatherLon},${prefs.fahrenheit}"
        val now = SystemClock.elapsedRealtime()
        if (key == weatherFor && now - weatherAt < WEATHER_EVERY_MS) return
        val lat = prefs.weatherLat
        val lon = prefs.weatherLon
        val f = prefs.fahrenheit
        Net.io.execute {
            runCatching { Weather.fetch(lat, lon, f) }.onFailure { Log.w("Glass", "weather", it) }.onSuccess { w ->
                runOnUiThread {
                    weatherAt = SystemClock.elapsedRealtime()
                    weatherFor = key
                    weatherLine = "${w.symbol} ${w.temp}°"
                    weather.text = weatherLine
                    weather.visibility = View.VISIBLE
                    updateAmbientText()
                }
            }
        }
    }

    // --- Lifecycle --------------------------------------------------------------------------

    private fun updateClock() {
        time.text = DateFormat.getTimeFormat(this).format(Date())
        updateAmbientText()
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }

    /** System broadcasts only; Android 13+ wants the exported flag stated explicitly. */
    private fun listen(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    override fun onStart() {
        super.onStart()
        updateClock()
        listen(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        listen(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        root.post { nextWallpaper() } // a new photo every time you come back to Home
        refreshLiveData() // also picks up a city/units change made in Glass Settings
    }

    /** Screensaver: after a while on Home with no remote presses, fade into ambient mode. */
    private val idleAmbient = Runnable { enterAmbient() }

    private fun restartIdleAmbient() {
        root.removeCallbacks(idleAmbient)
        if (prefs.ambientAfterMinutes > 0) root.postDelayed(idleAmbient, prefs.ambientAfterMinutes * 60_000L)
    }

    override fun onResume() {
        super.onResume()
        wakeSway()
        restartIdleAmbient()
    }

    override fun onPause() {
        root.removeCallbacks(idleAmbient) // only while Home is on screen, never over another app
        root.removeCallbacks(idlePause)
        sway.cancel() // don't animate behind a running app
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) restartIdleAmbient() // any press resets the screensaver
        // In ambient, Left/Right scroll the photos; any other button just wakes the home screen
        // and that press (down + up) does nothing else.
        if (inAmbient) {
            val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) { nextWallpaper(manual = true); showPhotoPosition() }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (down) { previousWallpaper(); showPhotoPosition() }
                else -> if (event.action == KeyEvent.ACTION_DOWN) { exitAmbient(); swallowKeyUp = true }
            }
            return true
        }
        if (swallowKeyUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowKeyUp = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) wakeSway() // before focus moves to the next tile
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        root.removeCallbacks(rotation)
        root.removeCallbacks(refresh)
        unregisterReceiver(clockReceiver)
        unregisterReceiver(packageReceiver)
        super.onStop()
    }

    /** Home pressed while already home: back to the first app. The remapped buttons toggle panels. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        restartIdleAmbient()
        if (intent.getBooleanExtra(EXTRA_AMBIENT, false)) {
            if (inAmbient) exitAmbient() else enterAmbient()
            return
        }
        if (intent.getBooleanExtra(EXTRA_CONTROLS, false)) {
            exitAmbient()
            if (controls.isOpen) closeControls() else openControls()
            return
        }
        exitAmbient()
        closeControls()
        closeGrid(animate = false)
        dockRow.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (controls.isOpen) closeControls() else closeGrid()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
