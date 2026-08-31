package com.example.gpspush

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The three things the status line shows. The running flag and the last upload
 * time are mirrored into SharedPreferences by the service, so reopening the app
 * after killing the activity shows the truth.
 */
object AppState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued

    private val _lastUploadAt = MutableStateFlow(0L)
    val lastUploadAt: StateFlow<Long> = _lastUploadAt

    fun load(context: Context) {
        val prefs = prefs(context)
        _running.value = prefs.getBoolean(PREF_RUNNING, false)
        _lastUploadAt.value = prefs.getLong(PREF_LAST_UPLOAD, 0L)
    }

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(PREF_RUNNING, running).apply()
        _running.value = running
    }

    fun setQueued(count: Int) {
        _queued.value = count
    }

    fun setLastUploadAt(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(PREF_LAST_UPLOAD, timestamp).apply()
        _lastUploadAt.value = timestamp
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
