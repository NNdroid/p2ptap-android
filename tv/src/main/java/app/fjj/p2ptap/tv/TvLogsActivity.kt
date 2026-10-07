package app.fjj.p2ptap.tv

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.service.LogCollector
import app.fjj.p2ptap.service.LogEntry
import app.fjj.p2ptap.service.LogFormat
import app.fjj.p2ptap.tv.databinding.ActivityTvLogsBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The tunnel's own output, shown as a live tail.
 *
 * There is no scrolling here on purpose. A set-top box remote has no drag and
 * a long text pane is unreadable from a sofa, so this screen shows the newest
 * lines only and refreshes them as they arrive — `tail -f`, not `logcat`. The
 * full ring buffer is still in [LogCollector]; widening the tail is a one-line
 * change if someone wants more history on screen.
 */
class TvLogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvLogsBinding

    /** Show only warnings and errors. */
    private var errorsOnly = false

    /**
     * The last string rendered. Skipping redundant setText matters more here
     * than elsewhere: the poll fires every second and an unchanged string
     * would otherwise invalidate a large monospace layout on a tick.
     */
    private var rendered: CharSequence? = null

    /** The tail poll. Started in onCreate, see there for why not earlier. */
    private var poll: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLogsErrorsOnly.setOnClickListener {
            errorsOnly = !errorsOnly
            rendered = null
            render()
        }
        binding.tvLogsClear.setOnClickListener {
            LogCollector.clear()
            rendered = null
            render()
        }

        binding.tvLogsErrorsOnly.requestFocus()

        // Not a property initializer: that would run the first iteration in the
        // constructor, on Dispatchers.Main.immediate, before setContentView has
        // run and binding is assigned. render() then threw
        // UninitializedPropertyAccessException on every cold start.
        poll = lifecycleScope.launch {
            while (isActive) {
                render()
                delay(POLL_MS)
            }
        }
    }

    override fun onDestroy() {
        poll?.cancel()
        super.onDestroy()
    }

    /**
     * How many lines fit in the pane right now, measured rather than guessed.
     *
     * Guessed values break on every box with an odd resolution or a different
     * DPI, and the failure mode is silent: the pane clips its own text.
     */
    private fun visibleLines(): Int {
        val pane = binding.tvLogPane
        if (pane.height <= 0) return 0
        val usable = pane.height - pane.paddingTop - pane.paddingBottom
        val line = pane.lineHeight
        if (line <= 0 || usable <= 0) return 0
        return usable / line
    }

    private fun render() {
        if (!LogCollector.isAttached) {
            rendered = null
            binding.tvLogsStatus.text = getString(R.string.tv_logs_unavailable)
            binding.tvLogPane.text = getString(R.string.tv_logs_unavailable)
            return
        }

        val tail = LogCollector.tail(visibleLines())
        binding.tvLogsStatus.text = if (LogCollector.droppedCount > 0) {
            getString(R.string.tv_logs_truncated_fmt, LogCollector.lineCount, LogCollector.droppedCount)
        } else {
            getString(R.string.tv_logs_live_fmt, LogCollector.lineCount)
        }

        val shown = if (errorsOnly) tail.filter { it.priority >= LogFormat.WARN } else tail

        val text = if (shown.isEmpty()) {
            if (errorsOnly) getString(R.string.tv_logs_none_matching)
            else getString(R.string.tv_logs_empty)
        } else {
            colorize(shown)
        }

        binding.tvLogsErrorsOnly.text = getString(
            if (errorsOnly) R.string.tv_logs_all else R.string.tv_logs_errors_only
        )

        if (text != rendered) {
            rendered = text
            binding.tvLogPane.text = text
        }
    }

    /**
     * Colour the severity, leaving the rest in the pane's default tone.
     *
     * A wall of uniform grey text cannot be read from ten feet; the reader
     * needs to find the red. Everything else stays quiet on purpose.
     */
    private fun colorize(entries: List<LogEntry>): CharSequence {
        val error = ContextCompat.getColor(this, R.color.tv_status_error)
        val warn = ContextCompat.getColor(this, R.color.tv_status_connecting)

        val out = SpannableStringBuilder()
        for (entry in entries) {
            val line = LogFormat.format(entry) + "\n"
            val start = out.length
            out.append(line)
            val color = when {
                entry.priority >= LogFormat.ERROR -> error
                entry.priority >= LogFormat.WARN -> warn
                else -> null
            }
            if (color != null) {
                out.setSpan(
                    ForegroundColorSpan(color), start, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return out
    }

    private companion object {
        // A log tail updates more often than a human can read, so the cadence
        // is about perceived liveness rather than accuracy. 1.5s matches the
        // telemetry poll so both screens move together.
        const val POLL_MS = 1_500L
    }
}
