package com.example.gpspush

// ---------------------------------------------------------------------------
// EDIT THESE
// ---------------------------------------------------------------------------

/** Where fixes are POSTed. Must be https. */
const val ENDPOINT = "https://func-nygdev-api.azurewebsites.net/api/gps/locations"

/** How often the GPS chip produces a fix, in milliseconds. */
const val INTERVAL_MS = 10_000L

/**
 * How long the GPS chip is allowed to hold fixes in its hardware buffer before
 * delivering them to us as a batch. This is the main battery lever: the CPU and
 * the radio wake once per window instead of once per fix.
 */
const val BATCH_WINDOW_MS = 60_000L

/**
 * Name of the auth header sent with every upload. Azure Functions accepts its
 * function key here, which keeps it out of the request URL (and so out of the
 * App Insights request log).
 */
const val AUTH_HEADER_NAME = "x-functions-key"

/**
 * Value of the auth header — the Azure function key. If blank, no auth header
 * is sent at all.
 *
 * Injected at build time, never committed: put `gpspush.authKey=<key>` in
 * `local.properties`, pass `-Pgpspush.authKey=<key>`, or set `GPSPUSH_AUTH_KEY`
 * in the environment. See `app/build.gradle.kts`.
 */
val AUTH_HEADER_VALUE: String = BuildConfig.AUTH_HEADER_VALUE

// ---------------------------------------------------------------------------
// Rarely touched
// ---------------------------------------------------------------------------

/** JSONL spool file inside filesDir. */
const val QUEUE_FILE_NAME = "queue.jsonl"

/** Trim the spool once it grows past this. */
const val QUEUE_MAX_BYTES = 4L * 1024 * 1024

/** How many of the most recent lines survive a trim. */
const val QUEUE_KEEP_LINES = 20_000

const val CONNECT_TIMEOUT_MS = 15_000
const val READ_TIMEOUT_MS = 20_000

/** Upper bound on how long the upload wakelock can ever be held. */
const val WAKELOCK_TIMEOUT_MS = 30_000L

const val PREFS_NAME = "gpspush"
const val PREF_RUNNING = "running"
const val PREF_LAST_UPLOAD = "last_upload_at"

const val NOTIFICATION_CHANNEL_ID = "gps_logging"
const val NOTIFICATION_ID = 1
