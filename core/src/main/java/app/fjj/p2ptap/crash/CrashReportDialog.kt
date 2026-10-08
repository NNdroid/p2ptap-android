package app.fjj.p2ptap.crash

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import app.fjj.p2ptap.core.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Builds and shows an AlertDialog with the crash details captured by
 * [CrashReporter]. Called from the main Activity's onCreate() so the user
 * sees the crash report on the next launch after a fatal exception.
 *
 * The dialog offers three actions:
 *  - Share Report: ACTION_SEND intent with the full crash text
 *  - Copy Details: copies the crash text to the clipboard
 *  - Dismiss: closes the dialog
 */
object CrashReportDialog {

    /** Show the crash dialog if there is a pending crash. Returns true if a dialog was shown. */
    fun showIfNeeded(context: Context): Boolean {
        val info = CrashReporter.consumePendingCrash(context) ?: return false
        show(context, info)
        return true
    }

    /** Show the crash dialog for the given crash info. */
    fun show(context: Context, info: CrashInfo) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val time = sdf.format(info.timestamp)

        val body = buildString {
            append(label(context, R.string.crash_label_time, time))
            append('\n')
            append(label(context, R.string.crash_label_exception, info.exceptionClass))
            if (info.exceptionMessage.isNotBlank()) {
                append('\n')
                append(label(context, R.string.crash_label_message, info.exceptionMessage))
            }
            append('\n')
            append(label(context, R.string.crash_label_version, info.appVersion))
            append("\n\n")
            append(context.getString(R.string.crash_label_stack))
            append('\n')
            append(info.stackTrace)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.crash_dialog_title)
            .setView(makeBodyView(context, body))
            .setPositiveButton(R.string.crash_btn_report) { _, _ -> shareReport(context, info) }
            .setNeutralButton(R.string.crash_btn_copy) { _, _ -> copyReport(context, info) }
            .setNegativeButton(R.string.crash_btn_dismiss, null)
            .create()
        dialog.show()
    }

    private fun makeBodyView(context: Context, text: String): View {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 12f
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod()
            setPadding(48, 24, 48, 24)
        }
        return ScrollView(context).apply {
            addView(tv)
            isVerticalScrollBarEnabled = true
        }
    }

    private fun label(context: Context, @StringRes res: Int, value: String): String {
        val label = context.getString(res)
        return "$label: $value"
    }

    private fun shareReport(context: Context, info: CrashInfo) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_title))
            putExtra(Intent.EXTRA_TEXT, info.shareText)
        }
        if (hasChooser(context, intent)) {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.crash_btn_report))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun copyReport(context: Context, info: CrashInfo) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.crash_share_title), info.shareText))
        showSnackBar(context, context.getString(R.string.crash_copy_success))
    }

    private fun hasChooser(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    private fun showSnackBar(context: Context, message: String) {
        // Use a minimal toast-like notification via AlertDialog with a single OK button.
        // Activity's Snackbar would require a CoordinatorLayout parent; keep it simple.
        AlertDialog.Builder(context)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
