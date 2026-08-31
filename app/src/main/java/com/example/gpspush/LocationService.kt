package com.example.gpspush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Foreground service that records fixes and pushes them to [ENDPOINT].
 *
 * Battery shape: the GPS chip batches fixes in hardware for [BATCH_WINDOW_MS]
 * (setMaxUpdateDelayMillis), so this process is woken roughly once per window
 * instead of once per fix. A wakelock is taken only around the upload itself.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()

    private lateinit var client: FusedLocationProviderClient
    private lateinit var queue: LocationQueue

    private var recording = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fixes = result.locations.toList()
            scope.launch {
                AppState.setQueued(queue.append(fixes))
                upload()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        queue = LocationQueue(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Stop path: flush what we have, then go away for good.
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            scope.launch {
                upload()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // Location permission revoked while we were down: nothing to do here.
            Log.w(TAG, "Cannot enter foreground: ${e.message}")
            AppState.setRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // A null intent means the system restarted us after killing the process
        // (START_STICKY); the setup below is the same either way.
        if (!recording) {
            recording = true
            AppState.setRunning(this, true)
            if (!requestUpdates()) return START_NOT_STICKY
            scope.launch {
                AppState.setQueued(queue.count())
                upload()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** @return false if the request could not be made and the service gave up. */
    private fun requestUpdates(): Boolean {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            // The whole point: let the GPS hardware hold fixes and hand them
            // over in one batch, so CPU and radio wake once per window.
            .setMaxUpdateDelayMillis(BATCH_WINDOW_MS)
            .setWaitForAccurateLocation(false)
            .build()

        return try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
            recording = false
            AppState.setRunning(this, false)
            stopSelf()
            false
        }
    }

    private fun stopRecording() {
        if (recording) {
            client.removeLocationUpdates(callback)
            recording = false
        }
        AppState.setRunning(this, false)
    }

    /**
     * Sends the whole spool as one JSON array. The spool is trimmed only after a
     * confirmed 2xx; every other outcome leaves it for the next batch.
     */
    private suspend fun upload() {
        // A batch that arrives mid-upload just queues; one upload at a time.
        if (!uploadLock.tryLock()) return
        try {
            val snapshot = queue.snapshot() ?: return

            val powerManager = getSystemService(PowerManager::class.java)
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
            try {
                if (Uploader.post(snapshot.body)) {
                    AppState.setQueued(queue.drop(snapshot.lineCount))
                    AppState.setLastUploadAt(this, System.currentTimeMillis())
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        } finally {
            uploadLock.unlock()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "GPS logging",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Logging GPS")
            .setContentText("Recording fixes and uploading in batches")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GpsPush"
        private const val WAKELOCK_TAG = "gpspush:upload"
        const val ACTION_STOP = "com.example.gpspush.STOP"
    }
}
