package io.github.mattdillioncodes.glass

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Device admin with only the force-lock policy: lockNow() is the one public API an ordinary
 * app has to turn the screen off, which on Android TV puts the set into standby.
 * Allowed from Glass Settings, or over ADB: dpm set-active-admin --user 0 io.github.mattdillioncodes.glass/.SleepAdmin
 */
class SleepAdmin : DeviceAdminReceiver()

/** Fired by the alarm when the timer runs out. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepTimer.clear(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(SleepTimer.admin(context))) dpm.lockNow()
    }
}

object SleepTimer {
    private const val PREFS = "glass"
    private const val KEY_END = "sleep_end_elapsed"

    fun admin(context: Context) = ComponentName(context, SleepAdmin::class.java)

    fun isReady(context: Context) =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** Minutes left, or 0 when no timer is set. */
    fun minutesLeft(context: Context): Int {
        val end = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_END, 0L)
        val left = end - SystemClock.elapsedRealtime()
        if (end == 0L || left <= 0) return 0
        // A reboot clears alarms and restarts the clock this end time is measured on: if the alarm
        // is gone, so is the timer (otherwise it would read "Sleeping in 4000 min" and never fire).
        val armed = PendingIntent.getBroadcast(context, 0, Intent(context, SleepReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null
        if (!armed) { clear(context); return 0 }
        return ((left + 59_999) / 60_000).toInt()
    }

    /** [minutes] = 0 cancels. A 1-minute alarm window needs no exact-alarm permission. */
    fun set(context: Context, minutes: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context))
        if (minutes <= 0) { clear(context); return }
        val end = SystemClock.elapsedRealtime() + minutes * 60_000L
        alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, 60_000L, pending(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_END, end).apply()
    }

    fun clear(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_END).apply()
}
