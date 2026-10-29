package io.github.mattdillioncodes.glass

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Remote button remapper. As an accessibility service with key filtering it sees every remote
 * press before the TV's own global key handler, so it can give a button a new job (the app
 * shortcut buttons, Info, Settings...). It only ever sees key presses, never screen content.
 * Power and Home never reach it (the system handles them first).
 *
 * The mapping lives in [Prefs.buttonMap] and is set up on screen in Glass Settings, which turns on
 * [learner] to catch the next button pressed.
 */
class KeyRemapService : AccessibilityService() {
    private lateinit var prefs: Prefs
    private var map: Map<Int, String> = emptyMap()

    // Held in a field: SharedPreferences only keeps a weak reference to its listeners.
    private val onPrefs = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BUTTONS) map = prefs.buttonMap
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        map = prefs.buttonMap
        prefs.raw.registerOnSharedPreferenceChangeListener(onPrefs)
        running = true
    }

    override fun onDestroy() {
        running = false
        if (::prefs.isInitialized) prefs.raw.unregisterOnSharedPreferenceChangeListener(onPrefs)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        learner?.let { learn ->
            if (!canRemap(code)) return false // navigation still works while learning
            if (event.action == KeyEvent.ACTION_UP) learn(code)
            return true
        }
        val target = map[code] ?: return false
        // Swallow both down and up so the TV's own handler never sees it; act once, on release.
        if (event.action == KeyEvent.ACTION_UP) open(target)
        return true
    }

    private fun open(target: String) {
        val intent = when (target) {
            NOTHING -> return
            TV_SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            GLASS_SETTINGS -> Intent(this, SettingsActivity::class.java)
            // Brings Glass forward (singleTask) and toggles its ambient screen / Control Center.
            AMBIENT -> Intent(this, HomeActivity::class.java).putExtra(EXTRA_AMBIENT, true)
            CONTROLS -> Intent(this, HomeActivity::class.java).putExtra(EXTRA_CONTROLS, true)
            else -> {
                val pkg = target.removePrefix(APP)
                packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: packageManager.getLaunchIntentForPackage(pkg)
                    ?: run { Log.w(TAG, "button target $pkg is not installed"); return }
            }
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "GlassKeys"

        // Button targets, as stored in Prefs.buttonMap.
        const val APP = "pkg:"                      // + package name
        const val CONTROLS = "glass:controls"       // Glass's Control Center (toggle)
        const val AMBIENT = "glass:ambient"         // Glass's ambient screen (toggle)
        const val GLASS_SETTINGS = "glass:preferences"
        const val TV_SETTINGS = "glass:settings"    // the TV's full Settings app
        const val NOTHING = "glass:none"            // turn the button off

        /** True while the service is bound (it runs in Glass's own process). */
        @Volatile var running = false
            private set

        /** While set, the next remappable press goes here instead of doing anything. Main thread. */
        var learner: ((Int) -> Unit)? = null

        fun component(context: Context) = ComponentName(context, KeyRemapService::class.java)

        /**
         * Which buttons may be given a new job. Returning true for a key means Glass swallows it
         * (in learn mode, and later whenever it's mapped), so this guards against a mapping that
         * leaves the remote unable to navigate.
         */
        fun canRemap(keyCode: Int) = keyCode !in NEVER_REMAP

        /**
         * A block-list rather than an allow-list, so maker-specific buttons Glass has never heard
         * of (TCL's 4062 Netflix button...) can still be learned. Kept: everything needed to get
         * around (arrows, OK, Back), plus volume and power, which people expect to always work.
         */
        private val NEVER_REMAP = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP_LEFT,
            KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        /** "Info", "Prog Red", "Button 4062"... for showing a learned key in Settings. */
        fun keyName(keyCode: Int): String {
            val name = KeyEvent.keyCodeToString(keyCode)
            if (!name.startsWith("KEYCODE_")) return "Button $keyCode" // maker-specific code
            return name.removePrefix("KEYCODE_").split('_')
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }
        }
    }
}
