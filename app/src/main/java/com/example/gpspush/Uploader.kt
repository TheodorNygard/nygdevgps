package com.example.gpspush

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * One POST of the whole spool as a JSON array. Returns true only for a
 * confirmed 2xx; anything else (exception, timeout, 4xx, 5xx) returns false and
 * the caller leaves the spool untouched.
 */
object Uploader {

    private const val TAG = "GpsPush"

    fun post(body: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (AUTH_HEADER_VALUE.isNotBlank()) {
                    setRequestProperty(AUTH_HEADER_NAME, AUTH_HEADER_VALUE)
                }
            }

            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            // Drain the response so the socket can be reused.
            try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
            } catch (e: Exception) {
                // Nothing to drain; the status code is what matters.
            }

            if (code !in 200..299) Log.w(TAG, "Upload rejected: HTTP $code")
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
