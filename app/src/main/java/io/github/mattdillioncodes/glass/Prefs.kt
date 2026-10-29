package io.github.mattdillioncodes.glass

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Every user-adjustable setting in one place, so the home screen, the screensaver, the Control
 * Center and the button remapper all read the same values. Changes made in Glass Settings take
 * effect the next time each screen reads them.
 */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("glass", Context.MODE_PRIVATE)

    // --- Weather ---------------------------------------------------------------------------
    /** "Lisbon, Portugal" etc.; empty until the user picks a city (no IP-based location lookups). */
    var weatherCity: String
        get() = raw.getString("weather_city", "") ?: ""
        set(v) = raw.edit().putString("weather_city", v).apply()
    var weatherLat: Double
        get() = raw.getFloat("weather_lat", 0f).toDouble()
        set(v) = raw.edit().putFloat("weather_lat", v.toFloat()).apply()
    var weatherLon: Double
        get() = raw.getFloat("weather_lon", 0f).toDouble()
        set(v) = raw.edit().putFloat("weather_lon", v.toFloat()).apply()
    val hasWeatherCity get() = weatherCity.isNotEmpty()

    /** °F in the countries that use it, °C everywhere else, unless the user picked one. */
    var fahrenheit: Boolean
        get() = raw.getBoolean("fahrenheit", Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "KY", "PW"))
        set(v) = raw.edit().putBoolean("fahrenheit", v).apply()

    // --- Timing ----------------------------------------------------------------------------
    /** Wallpaper rotation; 0 = only when you come back to Home. */
    var rotateSeconds: Int
        get() = raw.getInt("rotate_seconds", 30)
        set(v) = raw.edit().putInt("rotate_seconds", v).apply()

    /** Go ambient after this long idle on Home; 0 = never. */
    var ambientAfterMinutes: Int
        get() = raw.getInt("ambient_after_minutes", 5)
        set(v) = raw.edit().putInt("ambient_after_minutes", v).apply()

    // --- Appearance ---------------------------------------------------------------------------
    /** Auto (each panel follows the photo behind it), or always Light / always Dark. */
    var glassMode: Look.Mode
        get() = runCatching { Look.Mode.valueOf(raw.getString("glass_mode", null) ?: "") }.getOrDefault(Look.Mode.AUTO)
        set(v) = raw.edit().putString("glass_mode", v.name).apply()

    /** How see-through the glass is, 0 (frosted) to [Look.MAX_TRANSPARENCY] (clear). */
    var glassTransparency: Int
        get() = raw.getInt("glass_transparency", Look.DEFAULT_TRANSPARENCY)
        set(v) = raw.edit().putInt("glass_transparency", v.coerceIn(0, Look.MAX_TRANSPARENCY)).apply()

    // --- Wallpaper sets ------------------------------------------------------------------------
    /** Which photo sets rotate (keys of [Wallpapers.SETS], plus [Wallpapers.OWN]). All of them at first. */
    var wallpaperSets: Set<String>
        get() = raw.getStringSet("wallpaper_sets", null) ?: (Wallpapers.SETS.keys + Wallpapers.OWN)
        set(v) = raw.edit().putStringSet("wallpaper_sets", v).apply()

    // --- Dock ------------------------------------------------------------------------------
    /** Pinned packages in dock order, or null before the user has pinned anything. */
    var dockPins: List<String>?
        get() = raw.getString("dock", null)?.split(',')?.filter { it.isNotBlank() }
        set(v) = raw.edit().putString("dock", v?.joinToString(",")).apply()

    // --- Remote buttons --------------------------------------------------------------------
    /** keycode -> target (see the target constants in [KeyRemapService]). Empty until the user maps a button. */
    var buttonMap: Map<Int, String>
        get() = (raw.getString(KEY_BUTTONS, "") ?: "").split(';').mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0) null else e.substring(0, i).toIntOrNull()?.let { it to e.substring(i + 1) }
        }.toMap()
        set(v) = raw.edit().putString(KEY_BUTTONS, v.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()

    companion object {
        const val KEY_BUTTONS = "buttons"

        /** Streaming apps put in the dock on first run, if installed, in this order. */
        val POPULAR_APPS = listOf(
            "com.google.android.youtube.tv",            // YouTube
            "com.netflix.ninja",                        // Netflix
            "com.disney.disneyplus",                    // Disney+
            "com.amazon.amazonvideo.livingroom",        // Prime Video
            "com.wbd.stream",                           // Max
            "com.apple.atve.androidtv.appletv",         // Apple TV
            "com.hulu.livingroomplus",                  // Hulu
            "com.spotify.tv.android",                   // Spotify
            "com.plexapp.android",                      // Plex
            "com.google.android.youtube.tvunplugged",   // YouTube TV
            "tv.twitch.android.app",                    // Twitch
            "com.cbs.ott",                              // Paramount+
            "com.peacocktv.peacockandroid",             // Peacock
        )
    }
}
