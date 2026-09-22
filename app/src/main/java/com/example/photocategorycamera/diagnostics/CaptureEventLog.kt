package com.example.photocategorycamera.diagnostics

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Small bounded JSONL trace used to verify shutter, exposure, export and SAF timing on a real device. */
object CaptureEventLog {
    private const val MAX_BYTES = 4L * 1024L * 1024L
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LenshelfCaptureEvents").apply { isDaemon = true }
    }

    fun record(
        context: Context,
        event: String,
        taskId: String? = null,
        elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
        values: Map<String, Any?> = emptyMap(),
    ) {
        val line = JSONObject().apply {
            put("event", event)
            put("taskId", taskId)
            put("elapsedRealtimeNs", elapsedRealtimeNs)
            put("wallTimeMs", System.currentTimeMillis())
            values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }.toString()
        writer.execute {
            val directory = File(context.filesDir, "capture-events").apply { mkdirs() }
            val file = File(directory, "events.jsonl")
            if (file.length() >= MAX_BYTES) {
                val previous = File(directory, "events.previous.jsonl")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }
}
