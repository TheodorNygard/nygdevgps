package com.example.gpspush

import android.content.Context
import android.location.Location
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Durable spool. Every fix lands in a JSONL file in filesDir before any network
 * work happens, and lines are removed only after the server has confirmed 2xx.
 *
 * All file access goes through [mutex] because batched location callbacks and
 * the upload path can overlap.
 */
class LocationQueue(context: Context) {

    /** One snapshot of the spool: the request body plus how many lines it covers. */
    class Snapshot(val lineCount: Int, val body: String)

    private val file = File(context.filesDir, QUEUE_FILE_NAME)
    private val tmpFile = File(context.filesDir, "$QUEUE_FILE_NAME.tmp")
    private val mutex = Mutex()

    /** -1 means "not counted yet". */
    private var cachedCount = -1

    /** Appends fixes and returns the new queue depth. */
    suspend fun append(locations: List<Location>): Int = mutex.withLock {
        if (locations.isEmpty()) return@withLock countLocked()

        val before = countLocked()
        val text = buildString {
            for (location in locations) {
                append(toJsonLine(location))
                append('\n')
            }
        }
        file.appendText(text)
        cachedCount = before + locations.size

        if (file.length() > QUEUE_MAX_BYTES) trimLocked()
        cachedCount
    }

    /** Whole queue as a single JSON array, or null when there is nothing to send. */
    suspend fun snapshot(): Snapshot? = mutex.withLock {
        val lines = readLinesLocked()
        cachedCount = lines.size
        if (lines.isEmpty()) return@withLock null
        Snapshot(lines.size, lines.joinToString(separator = ",", prefix = "[", postfix = "]"))
    }

    /**
     * Removes the first [count] lines. Call this ONLY after a confirmed 2xx.
     * Fixes that arrived while the upload was in flight sit after those lines
     * and are preserved. Returns the new queue depth.
     */
    suspend fun drop(count: Int): Int = mutex.withLock {
        val lines = readLinesLocked()
        val remaining = if (count >= lines.size) emptyList() else lines.subList(count, lines.size)
        writeLinesLocked(remaining)
        cachedCount = remaining.size
        cachedCount
    }

    suspend fun count(): Int = mutex.withLock { countLocked() }

    // -- everything below assumes the mutex is held -------------------------

    private fun countLocked(): Int {
        if (cachedCount < 0) cachedCount = readLinesLocked().size
        return cachedCount
    }

    private fun readLinesLocked(): List<String> {
        if (!file.exists()) return emptyList()
        return try {
            // A crash mid-append can leave a truncated last line; only keep lines
            // that are complete JSON objects so we never POST garbage.
            file.readLines().filter { it.length > 1 && it.startsWith("{") && it.endsWith("}") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeLinesLocked(lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        tmpFile.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        if (!tmpFile.renameTo(file)) {
            file.writeText(tmpFile.readText())
            tmpFile.delete()
        }
    }

    private fun trimLocked() {
        val lines = readLinesLocked()
        if (lines.size <= QUEUE_KEEP_LINES) return
        val kept = lines.takeLast(QUEUE_KEEP_LINES)
        writeLinesLocked(kept)
        cachedCount = kept.size
    }

    private fun toJsonLine(location: Location): String {
        val json = JSONObject()
        json.put("lat", location.latitude)
        json.put("lon", location.longitude)
        json.put("acc", number(location.hasAccuracy(), location.accuracy.toDouble()))
        json.put("alt", number(location.hasAltitude(), location.altitude))
        json.put("spd", number(location.hasSpeed(), location.speed.toDouble()))
        json.put("ts", location.time)
        return json.toString()
    }

    /** JSONObject rejects NaN/Infinity, so anything not usable becomes null. */
    private fun number(present: Boolean, value: Double): Any =
        if (present && value.isFinite()) value else JSONObject.NULL
}
