package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import app.fjj.p2ptap.R
import app.fjj.p2ptap.databinding.ActivityLogViewerBinding
import app.fjj.p2ptap.service.LogCollector
import app.fjj.p2ptap.service.LogEntry
import app.fjj.p2ptap.service.LogFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private var isPaused = false
    private var activeLevelFilter = "ALL"
    private var searchQuery = ""

    companion object {
        private const val PREFS_NAME = "p2ptap_ui_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val MAX_LOG_LINES = 2000
        // Matches the telemetry poll cadence so both screens move together.
        private const val POLL_MS = 1_500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncStatusBarColor()
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupThemeControls()
        setupActionButtons()
        setupFilters()
        startLogPolling()
    }

    override fun onResume() {
        super.onResume()
        syncStatusBarColor()
    }

    private fun syncStatusBarColor() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = ContextCompat.getColor(this, R.color.window_bg)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight
    }

    private fun setupThemeControls() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDark = currentNightMode == Configuration.UI_MODE_NIGHT_YES

        binding.btnToggleTheme.setOnClickListener {
            val targetMode = if (isDark) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            prefs.edit().putInt(KEY_NIGHT_MODE, targetMode).apply()
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }
    }

    private fun setupActionButtons() {
        binding.btnCopyLogs.setOnClickListener {
            val textToCopy = binding.tvLogs.text.toString()
            if (textToCopy.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("P2PTap Logs", textToCopy))
                Toast.makeText(this, getString(R.string.msg_copied_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearLogs.setOnClickListener {
            LogCollector.clear()
            renderLogs()
            Toast.makeText(this, getString(R.string.btn_clear_logs), Toast.LENGTH_SHORT).show()
        }

        binding.btnPauseResume.setOnClickListener {
            isPaused = !isPaused
            if (isPaused) {
                binding.btnPauseResume.setIconResource(R.drawable.ic_play)
                Toast.makeText(this, getString(R.string.btn_pause_logs), Toast.LENGTH_SHORT).show()
            } else {
                binding.btnPauseResume.setIconResource(R.drawable.ic_pause)
                Toast.makeText(this, getString(R.string.btn_resume_logs), Toast.LENGTH_SHORT).show()
                renderLogs()
            }
        }

        binding.btnShareLogs.setOnClickListener {
            val logText = binding.tvLogs.text.toString()
            if (logText.isNotBlank()) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.title_log_viewer))
                    putExtra(Intent.EXTRA_TEXT, logText)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_view_logs)))
            }
        }
    }

    private fun setupFilters() {
        binding.etSearchLogs.doAfterTextChanged { s ->
            searchQuery = s?.toString()?.trim() ?: ""
            renderLogs()
        }

        binding.chipGroupLevels.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            activeLevelFilter = when (checkedIds.first()) {
                R.id.chipInfo -> "INFO"
                R.id.chipWarn -> "WARN"
                R.id.chipError -> "ERROR"
                R.id.chipDebug -> "DEBUG"
                else -> "ALL"
            }
            renderLogs()
        }
    }

    /**
     * Poll LogCollector at a fixed cadence, replacing the old
     * `Runtime.exec("logcat …")` subprocess that set-top boxes refuse.
     * The in-process sink captures the tunnel's own lines without a
     * process boundary or shell permission.
     */
    private fun startLogPolling() {
        lifecycleScope.launch {
            while (isActive) {
                if (!isPaused) {
                    renderLogs()
                }
                delay(POLL_MS)
            }
        }
    }

    private fun renderLogs() {
        if (!LogCollector.isAttached) {
            binding.tvLogs.text = getString(R.string.logs_collector_unavailable)
            binding.tvLogStats.text = ""
            return
        }

        val entries = LogCollector.tail(MAX_LOG_LINES)

        val filteredEntries = entries.filter { entry ->
            val matchesLevel = when (activeLevelFilter) {
                "INFO" -> entry.priority == LogFormat.INFO
                "WARN" -> entry.priority == LogFormat.WARN
                "ERROR" -> entry.priority >= LogFormat.ERROR
                "DEBUG" -> entry.priority <= LogFormat.DEBUG
                else -> true
            }

            val text = LogFormat.format(entry)
            val matchesSearch = if (searchQuery.isEmpty()) true else text.contains(searchQuery, ignoreCase = true)

            matchesLevel && matchesSearch
        }

        val spannable = buildSyntaxHighlightedLogs(filteredEntries)
        binding.tvLogs.text = spannable
        val logTotal = entries.size
        binding.tvLogStats.text = resources.getQuantityString(
            R.plurals.msg_filtered_logs_fmt, logTotal, filteredEntries.size, logTotal
        )

        if (!isPaused) {
            binding.scrollView.post {
                binding.scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun buildSyntaxHighlightedLogs(entries: List<LogEntry>): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        if (entries.isEmpty()) {
            ssb.append(getString(R.string.logs_empty) + "\n")
            return ssb
        }

        val colorTime = ContextCompat.getColor(this, R.color.log_time)
        val colorTag = ContextCompat.getColor(this, R.color.log_tag)
        val colorInfo = ContextCompat.getColor(this, R.color.log_info)
        val colorWarn = ContextCompat.getColor(this, R.color.log_warn)
        val colorError = ContextCompat.getColor(this, R.color.log_error)
        val colorDebug = ContextCompat.getColor(this, R.color.log_debug)
        val colorText = ContextCompat.getColor(this, R.color.log_text)

        for (entry in entries) {
            val line = LogFormat.format(entry) + "\n"
            val start = ssb.length
            ssb.append(line)
            val end = ssb.length

            val (levelColor, isBold) = when {
                entry.priority >= LogFormat.ERROR ->
                    Pair(colorError, true)
                entry.priority >= LogFormat.WARN ->
                    Pair(colorWarn, true)
                entry.priority >= LogFormat.INFO ->
                    Pair(colorInfo, false)
                entry.priority >= LogFormat.DEBUG ->
                    Pair(colorDebug, false)
                else ->
                    Pair(colorText, false)
            }

            ssb.setSpan(ForegroundColorSpan(levelColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (isBold) {
                ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return ssb
    }
}