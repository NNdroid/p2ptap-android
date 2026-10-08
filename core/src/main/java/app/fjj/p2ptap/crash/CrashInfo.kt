package app.fjj.p2ptap.crash

import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Immutable snapshot of the crash that took the app down. */
data class CrashInfo(
    val timestamp: Long,
    val threadName: String,
    val exceptionClass: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val appVersion: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    /** Plain-text block suitable for clipboard / share intent. */
    val shareText: String
        get() = buildString {
            append("P2PTap Crash Report\n")
            append("Time: ").append(formattedTime).append('\n')
            append("Version: ").append(appVersion).append('\n')
            append("Thread: ").append(threadName).append('\n')
            append("Exception: ").append(exceptionClass).append('\n')
            if (exceptionMessage.isNotBlank()) append("Message: ").append(exceptionMessage).append('\n')
            append('\n')
            append("Stack Trace:\n").append(stackTrace)
        }

    companion object {
        fun toJSON(info: CrashInfo): String {
            val o = JSONObject()
            o.put("timestamp", info.timestamp)
            o.put("thread", info.threadName)
            o.put("exception", info.exceptionClass)
            o.put("message", info.exceptionMessage)
            o.put("stack", info.stackTrace)
            o.put("version", info.appVersion)
            return o.toString()
        }

        fun fromJSON(raw: String): CrashInfo? = try {
            val o = JSONObject(raw)
            CrashInfo(
                timestamp = o.getLong("timestamp"),
                threadName = o.optString("thread", ""),
                exceptionClass = o.optString("exception", "Unknown"),
                exceptionMessage = o.optString("message", ""),
                stackTrace = o.optString("stack", ""),
                appVersion = o.optString("version", "")
            )
        } catch (_: JSONException) {
            null
        }
    }
}
