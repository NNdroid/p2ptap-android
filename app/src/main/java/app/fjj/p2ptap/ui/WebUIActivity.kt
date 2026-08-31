package app.fjj.p2ptap.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import app.fjj.p2ptap.R
import app.fjj.p2ptap.config.AppConfigManager
import app.fjj.p2ptap.databinding.ActivityWebUiBinding
import app.fjj.p2ptap.service.P2PTapVpnService

class WebUIActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebUiBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncStatusBarColor()
        binding = ActivityWebUiBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val config = AppConfigManager.load(this)
        val tokenParam = if (config.webUiToken.isNotBlank()) {
            "?token=" + java.net.URLEncoder.encode(config.webUiToken.trim(), "UTF-8")
        } else {
            ""
        }
        val url = "http://127.0.0.1:${config.webUiPort}/$tokenParam"

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                if (config.webUiToken.isNotBlank()) {
                    val safeToken = config.webUiToken.trim().replace("'", "\\'")
                    val js = "try { localStorage.setItem('p2ptap_auth_token', '$safeToken'); } catch(e) {}"
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                binding.progressBar.visibility = View.GONE
                if (!P2PTapVpnService.isRunning()) {
                    Toast.makeText(this@WebUIActivity, getString(R.string.prompt_vpn_not_running), Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        if (!P2PTapVpnService.isRunning()) {
            Toast.makeText(this, getString(R.string.prompt_vpn_not_running), Toast.LENGTH_SHORT).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        binding.webView.loadUrl(url)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val item = menu?.add(0, 101, 0, "Refresh")
        item?.setIcon(android.R.drawable.ic_menu_rotate)
        item?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 101) {
            binding.webView.reload()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        try {
            binding.webView.loadUrl("about:blank")
            binding.webView.stopLoading()
            binding.webView.webChromeClient = null
            binding.webView.webViewClient = WebViewClient()
            binding.webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
