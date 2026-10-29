package io.github.mattdillioncodes.glass

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Glass Settings, all done with the remote: weather city and units, wallpaper and screensaver
 * timing, remote buttons ("press a button, pick what it does"), and the one-time permissions.
 * Every change rebuilds the page, so coming back from a system screen shows what's now allowed.
 */
class SettingsActivity : Activity() {
    private enum class Screen { MAIN, CITY, LEARN, PICK, UPLOAD }

    private lateinit var prefs: Prefs
    private lateinit var wallpaper: WallpaperView
    private lateinit var panel: GlassView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var screen = Screen.MAIN
    private var server: UploadServer? = null
    private var photosAdded = 0
    private var asking: Pair<String, (Boolean) -> Unit>? = null // a device waiting to be allowed
    private var devicesAllowed = 0
    private var serverStopped: String? = null // why the upload page stopped on its own
    private var importing = 0 // photos from the TV's own picker still being processed
    private var picking = false // the picker is in front: keep the upload page alive meanwhile
    private var confirmRemove = false
    private var learnedKey = 0
    private var cityQuery = ""
    private var cityStatus = ""
    private var cityResults: List<Place> = emptyList()
    private var focusId: String? = null // which control gets focus after the next rebuild

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Look.load(this)
        wallpaper = WallpaperView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(34), dp(44), dp(40))
            clipChildren = false
            clipToPadding = false // focused pills grow 6% past the edge
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(content)
        }
        panel = GlassView(this, dp(32).toFloat(), thick = true).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // In Auto the panel turns frosted or smoked to suit the photo; redo the text to match.
            onTone = {
                (currentFocus?.tag as? String)?.let { focusId = it }
                build()
            }
        }
        val root = FrameLayout(this).apply {
            clipChildren = false // the panel's shadow falls outside it
            addView(wallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(View(this@SettingsActivity).apply {
                setBackgroundColor(if (Look.mode == Look.Mode.DARK) Color.argb(110, 0, 0, 0) else Color.TRANSPARENT)
            },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(dp(880), MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
                .apply { setMargins(0, dp(36), 0, dp(36)) })
        }
        setContentView(root)
        root.post { loadBackdrop(root.width, root.height) }
    }

    /** A random wallpaper, blurred: the page background and what the panel's glass refracts. */
    private fun loadBackdrop(w: Int, h: Int) = Glass.worker.execute {
        val wp = Wallpapers.load(this, w, h, Wallpapers.photos(this).randomOrNull())
        val backdrop = Backdrop(wp.small, w, h, Look.dimPhotos(wp.dim))
        runOnUiThread {
            wallpaper.dim = false // the blurred copy is already dimmed (if it should be)
            wallpaper.bitmap = backdrop.blurredImage
            panel.backdrop = backdrop
        }
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    override fun onPause() {
        stopLearning()
        if (screen == Screen.LEARN) screen = Screen.MAIN
        super.onPause()
    }

    override fun onStop() {
        if (!picking) stopServer() // never leave the upload page running behind another app
        super.onStop()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && screen != Screen.MAIN) {
            stopLearning()
            show(Screen.MAIN)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun show(next: Screen, focus: String? = null) {
        if (screen == Screen.UPLOAD && next != Screen.UPLOAD) stopServer()
        if (next != Screen.MAIN) confirmRemove = false
        if (next == Screen.UPLOAD && screen != Screen.UPLOAD) { photosAdded = 0; devicesAllowed = 0; serverStopped = null }
        screen = next
        focusId = focus
        build()
        scroll.scrollTo(0, 0)
    }

    /** Contents are built in the panel's own tone, so labels and pills get the right ink. */
    private fun build() = Look.tone(panel.light) { buildContent() }

    private fun buildContent() {
        content.removeAllViews()
        when (screen) {
            Screen.MAIN -> buildMain()
            Screen.CITY -> buildCity()
            Screen.LEARN -> buildLearn()
            Screen.PICK -> buildPick()
            Screen.UPLOAD -> buildUpload()
        }
        content.post {
            val target = focusId?.let { content.findViewWithTag<View>(it) } ?: firstFocusable(content)
            target?.requestFocus()
        }
    }

    // --- Main page -----------------------------------------------------------------------------

    private fun buildMain() {
        title("Glass Settings")

        section("WEATHER")
        row(
            option("city", prefs.weatherCity.ifEmpty { "Choose a city" }) { show(Screen.CITY, "query") },
            *(if (prefs.hasWeatherCity) arrayOf(option("city-off", "Turn off") { prefs.weatherCity = "" }) else emptyArray()),
        )
        row(
            option("f", "°F", prefs.fahrenheit) { prefs.fahrenheit = true },
            option("c", "°C", !prefs.fahrenheit) { prefs.fahrenheit = false },
        )

        section("WALLPAPER")
        val own = Wallpapers.ownPhotos(this).size
        val sets = Wallpapers.SETS + (if (own > 0) mapOf(Wallpapers.OWN to "Your photos") else emptyMap())
        row(*sets.map { (key, name) ->
            val on = key in prefs.wallpaperSets
            option("set-$key", name, on) { prefs.wallpaperSets = if (on) prefs.wallpaperSets - key else prefs.wallpaperSets + key }
        }.toTypedArray())
        row(
            option("add-photos", "Add your photos") { show(Screen.UPLOAD, "upload-done") },
            // Two presses to delete: the first only asks.
            *(if (own > 0) arrayOf(option("remove-photos", if (confirmRemove) "Press again to remove $own" else "Remove your photos") {
                if (confirmRemove) { Wallpapers.removeOwnPhotos(this); confirmRemove = false; focusId = "add-photos" }
                else confirmRemove = true
            }) else emptyArray()),
        )
        hint("Change the photo every")
        row(*listOf(0 to "Only on Home", 10 to "10 s", 30 to "30 s", 60 to "1 min", 300 to "5 min").map { (s, text) ->
            option("rotate-$s", text, prefs.rotateSeconds == s) { prefs.rotateSeconds = s }
        }.toTypedArray())
        val photos = Wallpapers.photos(this).size
        hint(when {
            photos == 0 -> "No sets on: showing a generated wallpaper."
            own == 0 -> "$photos photos."
            else -> "$photos photos, including $own of yours."
        })

        section("SCREENSAVER")
        hint("Fade into the ambient clock on Home after")
        row(*listOf(0 to "Never", 2 to "2 min", 5 to "5 min", 10 to "10 min", 30 to "30 min").map { (m, text) ->
            option("ambient-$m", text, prefs.ambientAfterMinutes == m) { prefs.ambientAfterMinutes = m }
        }.toTypedArray())
        row(option("dream", "Use Glass as the TV's screensaver") {
            if (!open(Intent(Settings.ACTION_DREAM_SETTINGS)))
                say("This TV hides the screensaver picker. The README has a one-line fix.")
        })

        section("REMOTE BUTTONS")
        if (!KeyRemapService.running) {
            hint("Off. Glass needs accessibility access to see button presses. It only sees which " +
                "button was pressed, never what's on screen.")
            row(option("a11y", "Turn on") {
                if (!open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    say("This TV has no accessibility screen. The README shows how to turn it on over ADB.")
            })
        } else {
            val map = prefs.buttonMap
            hint(if (map.isEmpty()) "Give any button on your remote a new job, like an app shortcut button."
                else "Select a button to change it.")
            map.toSortedMap().forEach { (key, target) ->
                row(option("key-$key", "${KeyRemapService.keyName(key)}   →   ${targetName(target)}") {
                    learnedKey = key
                    show(Screen.PICK)
                })
            }
            row(option("learn", "Add a button") { show(Screen.LEARN, "cancel") })
        }

        section("SLEEP TIMER")
        if (SleepTimer.isReady(this)) {
            hint("On. Set a timer from the Control Center. Turn this off before uninstalling Glass: " +
                "Android won't remove an app that holds this permission.")
            row(option("admin-off", "Turn off") {
                SleepTimer.set(this, 0)
                getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(SleepTimer.admin(this))
                content.postDelayed({ build() }, 600) // removal finishes asynchronously
            })
        } else {
            hint("Lets the Control Center's sleep timer turn the TV off. Glass asks only for permission to lock the screen.")
            row(option("admin", "Allow") {
                val ask = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, SleepTimer.admin(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Glass's sleep timer turn off the TV.")
                if (!open(ask)) say("This TV doesn't offer that screen. The README shows how to allow it over ADB.")
            })
        }

        section("HOME SCREEN")
        row(option("home", "Choose the home app") {
            if (!open(Intent(Settings.ACTION_HOME_SETTINGS)))
                say("This TV doesn't let you pick a home app here. Press Home, or see the README.")
        })

        content.addView(Widgets.label(this, "Glass ${packageManager.getPackageInfo(packageName, 0).versionName}" +
            "   ·   github.com/MattDillionCodes/glass-launcher", 13f, Look.ink(130)),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(34) })
    }

    // --- Weather city search -------------------------------------------------------------------

    private fun buildCity() {
        title("Weather city")
        hint("Type a city and press search on the keyboard. Glass looks it up on open-meteo.com; " +
            "nothing else about you is sent.")
        val field = EditText(this).apply {
            tag = "query"
            setText(cityQuery)
            setSelection(cityQuery.length)
            hint = "City name"
            textSize = 18f
            typeface = Widgets.medium
            setTextColor(Look.ink())
            setHintTextColor(Look.ink(130))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(20), dp(12), dp(20), dp(12))
            val light = Look.light // the panel's tone when built; focus repaints later
            fun paint(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(100).toFloat()
                    setColor(Look.ink(light, if (focused) 50 else 22))
                    setStroke(dp(if (focused) 2 else 1), Look.ink(light, if (focused) 220 else 60))
                }
            }
            paint(false)
            setOnFocusChangeListener { _, f -> paint(f) }
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                    search(v.text.toString()); true
                } else false
            }
        }
        content.addView(field, LinearLayout.LayoutParams(dp(520), WRAP_CONTENT).apply { topMargin = dp(16) })
        if (cityStatus.isNotEmpty()) hint(cityStatus)
        cityResults.forEachIndexed { i, place ->
            row(option("place-$i", place.name) {
                prefs.weatherCity = place.name
                prefs.weatherLat = place.lat
                prefs.weatherLon = place.lon
                cityResults = emptyList()
                cityStatus = ""
                show(Screen.MAIN, "city")
            })
        }
        row(option("cancel", "Cancel") { show(Screen.MAIN, "city") })
    }

    private fun search(query: String) {
        cityQuery = query.trim()
        if (cityQuery.isEmpty()) return
        // Put the keyboard away so the results aren't hidden behind it.
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(content.windowToken, 0)
        cityStatus = "Searching…"
        cityResults = emptyList()
        build()
        Net.io.execute {
            val found = runCatching { Places.search(cityQuery) }
            runOnUiThread {
                if (screen != Screen.CITY) return@runOnUiThread
                cityResults = found.getOrDefault(emptyList())
                cityStatus = when {
                    found.isFailure -> "Couldn't reach the weather service. Check the TV's internet."
                    cityResults.isEmpty() -> "No places called \"$cityQuery\"."
                    else -> "Pick one:"
                }
                show(Screen.CITY, if (cityResults.isEmpty()) "query" else "place-0")
            }
        }
    }

    // --- Your photos: sent from a phone, or picked on the TV ------------------------------------

    private fun buildUpload() {
        title("Add your photos")
        serverStopped?.let { reason ->
            hint(reason)
            row(option("upload-restart", "Start again") { serverStopped = null }, option("upload-done", "Done") {
                show(Screen.MAIN, "add-photos")
            })
            return
        }
        val s = server ?: UploadServer(this,
            onPhoto = { runOnUiThread { photoArrived() } },
            onAsk = { device, answer -> runOnUiThread { askToAllow(device, answer) } },
            onStopped = { reason -> runOnUiThread { serverStopped = reason; server = null; dismissAsk(); build() } },
        ).also { server = if (it.start()) it else null }
        asking?.let { (device, _) ->
            // The phone is waiting on this; put it first and focus Allow.
            content.addView(Widgets.label(this, "$device wants to send photos to this TV", 22f),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            hint("Only allow it if it's yours: it's the one showing this TV's address right now.")
            row(option("ask-allow", "Allow") { answerAsk(true) }, option("ask-deny", "Don't allow") { answerAsk(false) })
            focusId = focusId ?: "ask-allow"
            return
        }
        val url = s?.url
        if (url == null) {
            hint("The TV needs to be on your home network (Wi-Fi or Ethernet) to receive photos.")
        } else {
            // Scan, allow, pick: the QR code holds the full address, so nobody has to type it.
            val steps = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                listOf(
                    "1" to "Scan this code with your phone's camera",
                    "2" to "Choose Allow when this TV asks",
                    "3" to "Pick photos on your phone",
                ).forEach { (n, text) ->
                    addView(LinearLayout(this@SettingsActivity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(Widgets.label(this@SettingsActivity, n, 17f, Look.ink()).apply {
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Look.ink(36)) }
                        }, LinearLayout.LayoutParams(dp(34), dp(34)))
                        addView(Widgets.label(this@SettingsActivity, text, 19f),
                            LinearLayout.LayoutParams(dp(400), WRAP_CONTENT).apply { marginStart = dp(16) })
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(18) })
                }
                addView(Widgets.label(this@SettingsActivity, "Photos appear here as they arrive, turned upright and " +
                    "cropped to fit. Only devices on your home network can connect, and only while this screen is open.",
                    14f, Look.ink(170)), LinearLayout.LayoutParams(dp(450), WRAP_CONTENT))
            }
            content.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(QrView(this@SettingsActivity).apply { text = url }, LinearLayout.LayoutParams(dp(250), dp(250)))
                addView(steps, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(36) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            hint("No camera? Type ${url.removePrefix("http://")} into a browser.")
            if (devicesAllowed > 0) hint(if (devicesAllowed == 1) "1 device allowed" else "$devicesAllowed devices allowed")
        }
        val total = photosAdded
        content.addView(Widgets.label(this, when {
            importing > 0 -> "Adding photos… $total so far"
            total == 0 -> "Waiting for photos…"
            total == 1 -> "1 photo added"
            else -> "$total photos added"
        }, 17f, if (total > 0) Color.rgb(48, 209, 88) else Look.ink(190)),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        row(
            option("upload-done", "Done") { show(Screen.MAIN, "add-photos") },
            *(if (tvPicker() != null) arrayOf(option("upload-pick", "Choose on this TV") { pickOnTv() }) else emptyArray()),
        )
    }

    private fun askToAllow(device: String, answer: (Boolean) -> Unit) {
        if (screen != Screen.UPLOAD) return answer(false)
        asking = device to answer
        focusId = "ask-allow"
        build()
    }

    private fun answerAsk(allow: Boolean) {
        val (_, answer) = asking ?: return
        asking = null
        answer(allow)
        if (allow) devicesAllowed++
        focusId = "upload-done"
        build()
    }

    /** Leaving the screen (or the server stopping) answers any waiting device with no. */
    private fun dismissAsk() {
        asking?.second?.invoke(false)
        asking = null
    }

    private fun photoArrived() {
        photosAdded++
        prefs.wallpaperSets = prefs.wallpaperSets + Wallpapers.OWN // they've just added them: show them
        if (screen == Screen.UPLOAD) build()
    }

    private fun stopServer() {
        dismissAsk()
        server?.stop()
        server = null
    }

    /**
     * The TV's own image picker, if it has a real one (the system photo picker can show photos on a
     * USB drive). Many TVs only have a do-nothing stub, so those don't get the button.
     */
    private fun tvPicker(): Intent? {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addCategory(Intent.CATEGORY_OPENABLE)
        val handler = intent.resolveActivity(packageManager) ?: return null
        return intent.takeUnless { "stub" in handler.className.lowercase() }
    }

    private fun pickOnTv() {
        val intent = tvPicker() ?: return
        picking = true
        if (runCatching { startActivityForResult(intent, PICK_PHOTOS) }.isFailure) {
            picking = false
            say("This TV can't pick photos itself. Use your phone instead.")
        }
    }

    @Deprecated("Activity result API: Glass uses plain framework Activity, no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_PHOTOS) return
        picking = false
        if (resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)
        importing += uris.size
        build()
        Glass.worker.execute {
            uris.forEach { uri ->
                val temp = java.io.File.createTempFile("pick", null, cacheDir)
                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    Wallpapers.importPhoto(this, temp) != null
                }.getOrDefault(false)
                temp.delete()
                runOnUiThread {
                    importing--
                    if (ok) photoArrived() else build()
                }
            }
        }
    }

    // --- Remote buttons: learn a press, then pick its job ---------------------------------------

    private fun buildLearn() {
        title("Press a button on your remote")
        hint("The one you want to change, like an app shortcut button. The arrows, OK and Back keep working.")
        row(option("cancel", "Cancel") { stopLearning(); show(Screen.MAIN, "learn") })
        KeyRemapService.learner = { code ->
            stopLearning()
            learnedKey = code
            show(Screen.PICK)
        }
    }

    private fun stopLearning() { KeyRemapService.learner = null }

    private fun buildPick() {
        val key = learnedKey
        val current = prefs.buttonMap[key]
        title("${KeyRemapService.keyName(key)} opens…")
        fun choose(target: String?) {
            prefs.buttonMap = prefs.buttonMap.toMutableMap().apply { if (target == null) remove(key) else put(key, target) }
            show(Screen.MAIN, if (target == null) "learn" else "key-$key")
        }
        section("GLASS")
        val glass = listOf(
            KeyRemapService.CONTROLS, KeyRemapService.AMBIENT, KeyRemapService.GLASS_SETTINGS,
            KeyRemapService.TV_SETTINGS, KeyRemapService.NOTHING,
        )
        grid(glass.map { t -> option("t-$t", targetName(t), t == current) { choose(t) } })
        if (current != null) row(option("default", "Back to what it did before") { choose(null) })

        section("APPS")
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        grid(apps.map { (pkg, label) ->
            val t = KeyRemapService.APP + pkg
            option("t-$t", label, t == current) { choose(t) }
        })
        focusId = focusId ?: current?.let { "t-$it" }
    }

    private fun targetName(target: String) = when (target) {
        KeyRemapService.CONTROLS -> "Control Center"
        KeyRemapService.AMBIENT -> "Ambient screen"
        KeyRemapService.GLASS_SETTINGS -> "Glass Settings"
        KeyRemapService.TV_SETTINGS -> "TV Settings"
        KeyRemapService.NOTHING -> "Nothing"
        else -> target.removePrefix(KeyRemapService.APP).let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault("$pkg (not installed)")
        }
    }

    // --- building blocks ------------------------------------------------------------------------

    /** A pill that runs [action], then rebuilds the page with focus back on itself. */
    private fun option(id: String, text: String, selected: Boolean = false, action: () -> Unit) =
        Widgets.pill(this, text, selected) {
            focusId = id
            val before = screen
            action()
            if (screen == before) build() // show() already rebuilt if the action changed screens
        }.apply { tag = id }

    private fun title(text: String) =
        content.addView(Widgets.label(this, text, 28f), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

    private fun section(text: String) =
        content.addView(Widgets.label(this, text, 12f, Look.ink(150)).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28); bottomMargin = dp(6) })

    private fun hint(text: String) =
        content.addView(Widgets.label(this, text, 15f, Look.ink(190)),
            LinearLayout.LayoutParams(dp(760), WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(4) })

    private fun row(vararg items: View) = content.addView(LinearLayout(this).apply {
        clipChildren = false
        items.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) marginStart = dp(10) })
        }
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

    /** Many choices (every installed app): three to a row, equal widths. */
    private fun grid(items: List<TextView>) = content.addView(GridLayout(this).apply {
        columnCount = 3
        clipChildren = false
        items.forEach { v ->
            v.maxLines = 1
            v.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(v, GridLayout.LayoutParams().apply { width = dp(250); setMargins(0, dp(8), dp(12), 0) })
        }
    })

    private fun open(intent: Intent) = runCatching { startActivity(intent) }.isSuccess

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun firstFocusable(v: View): View? = when {
        v.isFocusable && v.visibility == View.VISIBLE -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { firstFocusable(v.getChildAt(it)) }
        else -> null
    }

    private companion object {
        const val PICK_PHOTOS = 7
    }
}
