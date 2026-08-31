package com.example.gpspush

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The service owns this flag; read it so the buttons are right if the
        // app was killed and reopened while the service kept running.
        AppState.load(this)

        if (savedInstanceState == null) {
            requestPermissions()
            requestIgnoreBatteryOptimizations()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val running by AppState.running.collectAsState()
                    val queued by AppState.queued.collectAsState()
                    val lastUploadAt by AppState.lastUploadAt.collectAsState()

                    LaunchedEffect(Unit) {
                        val depth = withContext(Dispatchers.IO) { LocationQueue(applicationContext).count() }
                        AppState.setQueued(depth)
                    }

                    MainScreen(
                        running = running,
                        queued = queued,
                        lastUploadAt = lastUploadAt,
                        onStart = ::startLogging,
                        onStop = ::stopLogging,
                    )
                }
            }
        }
    }

    private fun startLogging() {
        if (!hasLocationPermission()) {
            requestPermissions()
            return
        }
        startForegroundService(Intent(this, LocationService::class.java))
    }

    private fun stopLogging() {
        // Plain startService: the service is already in the foreground, and this
        // lets it flush the queue once before it shuts itself down.
        startService(Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Background location is deliberately not requested: the service always starts from this activity. */
    private fun requestPermissions() {
        val wanted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    /** Without this, Doze defers uploads during long stationary stretches. */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            Log.w("GpsPush", "Battery optimization dialog unavailable: ${e.message}")
        }
    }
}

@Composable
private fun MainScreen(
    running: Boolean,
    queued: Int,
    lastUploadAt: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onStart, enabled = !running) { Text("Start") }
            Button(onClick = onStop, enabled = running) { Text("Stop") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = statusLine(running, queued, lastUploadAt),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun statusLine(running: Boolean, queued: Int, lastUploadAt: Long): String {
    val state = if (running) "Running" else "Stopped"
    val last = if (lastUploadAt > 0L) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastUploadAt))
    } else {
        "never"
    }
    return "$state · $queued queued · last upload $last"
}
