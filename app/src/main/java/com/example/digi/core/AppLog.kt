package com.example.digi.core

import android.util.Log
import com.example.digi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local logging: logcat plus an in-memory ring buffer.
 *
 * The ring buffer exists because a signage box is usually on a wall with no USB cable in reach.
 * When someone is standing in front of a screen that is showing the wrong thing, the fastest
 * diagnosis is the on-device overlay reading back the last few hundred lines — not adb.
 *
 * Deliberately separate from the CMS event stream ([com.example.digi.data.repo.EventReporter]):
 * that one is gated on an operator enabling live capture and is subject to batching and dedupe,
 * while this one always records and never touches the network. Something has to work when the
 * network is the thing that is broken.
 */
object AppLog {

    private const val CAPACITY = 400

    data class Entry(
        val at: Long,
        val level: String,
        val tag: String,
        val message: String,
        val error: String? = null,
    ) {
        fun format(): String = "${TIME.format(Date(at))} $level/$tag: $message" +
            (error?.let { "\n    $it" } ?: "")
    }

    private val TIME = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Entry>(CAPACITY)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest last. Rendered by the diagnostics overlay. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
        record("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        record("I", tag, message, null)
        // Breadcrumbs, not reports. Crashlytics attaches the recent ones to whatever crash follows,
        // which is what turns a stack trace into a sequence of events leading to it.
        FirebaseTelemetry.breadcrumb("I/$tag: $message")
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        record("W", tag, message, error?.toShortString())
        FirebaseTelemetry.breadcrumb("W/$tag: $message")
    }

    /**
     * An error the app handled.
     *
     * Also reported to Crashlytics as a non-fatal, because on a wall-mounted unattended box these
     * are the failures that never get noticed otherwise: nobody is watching, and the screen keeps
     * showing something plausible while a download fails for the tenth time.
     */
    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        record("E", tag, message, error?.toShortString())
        FirebaseTelemetry.nonFatal(tag, message, error)
    }

    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.toList() }

    private fun record(level: String, tag: String, message: String, error: String?) {
        val entry = Entry(System.currentTimeMillis(), level, tag, message, error)
        val copy = synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(entry)
            buffer.toList()
        }
        _entries.value = copy
    }

    /** Stack traces are useless in a 400-line ring buffer; the type and message are not. */
    private fun Throwable.toShortString(): String =
        "${this::class.java.simpleName}: ${message ?: "(no message)"}"
}
