package app.fjj.p2ptap.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
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
import app.fjj.p2ptap.service.P2PStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections

class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private val rawLogLines = Collections.synchronizedList(mutableListOf<String>())
    private var isPaused = false
    private var activeLevelFilter = "ALL"
    private var searchQuery = ""
    private var logProcess: Process? = null

    companion object {
        private const val PREFS_NAME = "p2ptap_ui_prefs"
        private const val KEY_NIGHT_MODE = "night_mode"
        private const val MAX_LOG_LINES = 2000
        private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[a-zA-Z]|\\[[0-9;]+m")
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
        startLogCollector()
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
            rawLogLines.clear()
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
                    putExtra(Intent.EXTRA_SUBJECT, "P2PTap Diagnostic Logs")
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

    private fun startLogCollector() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Initial state entry
            val initial = "[Info] Real-time P2P State: ${P2PStateRepository.state.value} (${P2PStateRepository.message.value})"
            rawLogLines.add(initial)

            withContext(Dispatchers.Main) { renderLogs() }

            try {
                // Clear logcat buffer and start continuous streaming
                val process = Runtime.getRuntime().exec("logcat -v time -s P2PTapVpnService:V P2PTap:V GoLog:V Node:V Protect:V Gateway:V P2P:V")
                logProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                while (isActive) {
                    val line = reader.readLine() ?: break
                    var hasNewData = false

                    if (line.isNotBlank()) {
                        val cleanLine = line.replace(ANSI_REGEX, "").trimEnd()
                        if (cleanLine.isNotBlank()) {
                            synchronized(rawLogLines) {
                                if (rawLogLines.size >= MAX_LOG_LINES) {
                                    rawLogLines.removeAt(0)
                                }
                                rawLogLines.add(cleanLine)
                            }
                            hasNewData = true
                        }
                    }

                    // Drain any additional immediately available lines in batch
                    while (reader.ready() && isActive) {
                        val extra = reader.readLine() ?: break
                        if (extra.isNotBlank()) {
                            val cleanExtra = extra.replace(ANSI_REGEX, "").trimEnd()
                            if (cleanExtra.isNotBlank()) {
                                synchronized(rawLogLines) {
                                    if (rawLogLines.size >= MAX_LOG_LINES) {
                                        rawLogLines.removeAt(0)
                                    }
                                    rawLogLines.add(cleanExtra)
                                }
                                hasNewData = true
                            }
                        }
                    }

                    if (hasNewData && !isPaused && isActive) {
                        withContext(Dispatchers.Main) {
                            renderLogs()
                        }
                    }
                }
                reader.close()
            } catch (e: Exception) {
                val errMsg = "[Error] Logcat reader terminated: ${e.message}"
                rawLogLines.add(errMsg)
                withContext(Dispatchers.Main) { renderLogs() }
            } finally {
                try {
                    logProcess?.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        try {
            logProcess?.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun renderLogs() {
        val filteredList = synchronized(rawLogLines) {
            rawLogLines.filter { line ->
                // 1. Level Filter
                val matchesLevel = when (activeLevelFilter) {
                    "INFO" -> line.contains("/I") || line.contains("[INFO]", ignoreCase = true) || line.contains("info", ignoreCase = true)
                    "WARN" -> line.contains("/W") || line.contains("[WARN]", ignoreCase = true) || line.contains("warn", ignoreCase = true)
                    "ERROR" -> line.contains("/E") || line.contains("[ERROR]", ignoreCase = true) || line.contains("error", ignoreCase = true) || line.contains("fatal", ignoreCase = true)
                    "DEBUG" -> line.contains("/D") || line.contains("/V") || line.contains("[DEBUG]", ignoreCase = true) || line.contains("debug", ignoreCase = true)
                    else -> true
                }

                // 2. Text Search
                val matchesSearch = if (searchQuery.isEmpty()) true else line.contains(searchQuery, ignoreCase = true)

                matchesLevel && matchesSearch
            }
        }

        val spannable = buildSyntaxHighlightedLogs(filteredList)
        binding.tvLogs.text = spannable
        binding.tvLogStats.text = getString(R.string.msg_filtered_logs_fmt, filteredList.size, rawLogLines.size)

        if (!isPaused) {
            binding.scrollView.post {
                binding.scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun buildSyntaxHighlightedLogs(lines: List<String>): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        if (lines.isEmpty()) {
            ssb.append("[System] 无匹配日志记录\n")
            return ssb
        }

        val colorTime = ContextCompat.getColor(this, R.color.log_time)
        val colorTag = ContextCompat.getColor(this, R.color.log_tag)
        val colorInfo = ContextCompat.getColor(this, R.color.log_info)
        val colorWarn = ContextCompat.getColor(this, R.color.log_warn)
        val colorError = ContextCompat.getColor(this, R.color.log_error)
        val colorDebug = ContextCompat.getColor(this, R.color.log_debug)
        val colorText = ContextCompat.getColor(this, R.color.log_text)

        for (line in lines) {
            val start = ssb.length
            ssb.append(line).append("\n")
            val end = ssb.length

            val (levelColor, isBold) = when {
                line.contains("/E") || line.contains("[ERROR]", ignoreCase = true) || line.contains("error", ignoreCase = true) || line.contains("fatal", ignoreCase = true) ->
                    Pair(colorError, true)
                line.contains("/W") || line.contains("[WARN]", ignoreCase = true) || line.contains("warn", ignoreCase = true) ->
                    Pair(colorWarn, true)
                line.contains("/I") || line.contains("[INFO]", ignoreCase = true) || line.contains("info", ignoreCase = true) ->
                    Pair(colorInfo, false)
                line.contains("/D") || line.contains("/V") || line.contains("[DEBUG]", ignoreCase = true) ->
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
