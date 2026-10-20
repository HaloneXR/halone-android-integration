package dev.halone.plugin.android

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlin.math.roundToInt

/**
 * Keeps the phone's screen at its top refresh rate while [wanted]: Android
 * draws for the glasses only as often as the phone's own screen refreshes, so
 * a phone resting at 60 Hz holds glasses running faster to 60 frames a second.
 *
 * The value it replaces is kept on disk until it is put back, so a plugin
 * killed while holding puts it back the next time it can.
 */
class RefreshRateHold(private val context: Context, private val privileged: Privileged) {
    private val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var wanted = false
    private val onAccess: () -> Unit = { apply() }

    fun start() {
        privileged.addListener(onAccess)
        apply()
    }

    fun stop() {
        privileged.removeListener(onAccess)
    }

    fun want(hold: Boolean) {
        wanted = hold
        apply()
    }

    private fun apply() {
        val service = privileged.service ?: return
        val holding = saved.getBoolean(HOLDING, false)
        runCatching {
            if (wanted && !holding) {
                val previous = service.systemSetting(KEY)
                saved.edit().putBoolean(HOLDING, true).putString(PREVIOUS, previous).commit()
                service.setSystemSetting(KEY, topRate().toString())
            } else if (!wanted && holding) {
                service.setSystemSetting(KEY, saved.getString(PREVIOUS, null))
                saved.edit().clear().commit()
            }
        }.onFailure { Log.w(TAG, "refresh rate", it) }
    }

    private fun topRate(): Int {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return display.supportedModes.maxOf { it.refreshRate }.roundToInt()
    }

    private companion object {
        const val TAG = "HaloneAndroidApps"
        const val PREFS = "refresh-rate"
        const val HOLDING = "holding"
        const val PREVIOUS = "previous"
        const val KEY = "min_refresh_rate"
    }
}
