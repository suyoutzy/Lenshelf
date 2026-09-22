package com.example.photocategorycamera.probe

import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

internal class ProbeReport(val directory: File, private val notify: (String) -> Unit) {
    init {
        check(directory.mkdirs() || directory.isDirectory)
        event("start", "model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT)
    }

    @Synchronized
    fun event(type: String, vararg values: Pair<String, Any?>) {
        val json = JSONObject().put("event", type).put("elapsedNs", SystemClock.elapsedRealtimeNanos())
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        File(directory, "events.jsonl").appendText(json.toString() + "\n")
        if (type != "frame") notify("$type: ${values.joinToString { "${it.first}=${it.second}" }}")
    }
}
