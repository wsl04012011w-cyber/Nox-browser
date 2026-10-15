package com.nox.browser

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

class MainActivity : Activity() {
    private data class BrowserTab(
        val id: Long,
        var url: String,
        var title: String = ""
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var addressBar: EditText
    private lateinit var pageTitle: TextView
    private lateinit var webContainer: LinearLayout
    private lateinit var tabsButton: Button

    private var webView: WebView? = null
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: Long = -1L
    private var nextId: Long = 1L

    private val background = Color.rgb(18, 20, 24)
    private val surface = Color.rgb(29, 32, 38)
    private val foreground = Color.rgb(238, 240, 244)
    private val secondary = Color.rgb(157, 164, 176)
    private val accent = Color.rgb(145, 190, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background

        prefs = getSharedPreferences("nox_session", Context.MODE_PRIVATE)
        restoreTabs()
        buildUi()

        if (tabs.isEmpty()) {
            createTab(HOME_URL, activate = true)
        } else {
            val chosen = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
            activateTab(chosen.id)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "nox"
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(foreground)
            setPadding(dp(4), 0, dp(8), 0)
        }
        pageTitle = TextView(this).apply {
            text = "Less noise. More web."
            textSize = 12f
            setTextColor(secondary)
            maxLines = 1
        }
        brandRow.addView(brand)
        brandRow.addView(pageTitle, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(brandRow)

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            setHintTextColor(secondary)
            setTextColor(foreground)
            textSize = 15f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), 0, dp(12), 0)
            setBackgroundColor(surface)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                    navigateFromAddress()
                    true
                } else {
                    false
                }
            }
        }
        val addressParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        }
        root.addView(addressBar, addressParams)

        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        root.addView(
            webContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        controls.addView(makeButton("‹", "Back") {
            webView?.let { if (it.canGoBack()) it.goBack() }
        })
        controls.addView(makeButton("›", "Forward") {
            webView?.let { if (it.canGoForward()) it.goForward() }
        })
        controls.addView(makeButton("↻", "Reload") { webView?.reload() })
        controls.addView(makeButton("⌂", "Home") { goHome() })
        tabsButton = makeButton("1", "Tabs") { showTabPicker() }
        controls.addView(tabsButton)
        controls.addView(makeButton("+", "New tab") { createTab(HOME_URL, activate = true) })
        root.addView(controls)

        setContentView(root)
    }

    private fun makeButton(label: String, description: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            contentDescription = description
            textSize = 18f
            setTextColor(foreground)
            setBackgroundColor(background)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener { action() }
        }
    }

    private fun createWebView(tab: BrowserTab): WebView {
        val view = WebView(this)
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title.orEmpty()
                if (activeTabId == tab.id) {
                    pageTitle.text = tab.title.ifBlank { "Nox Browser" }
                }
                saveTabs()
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return uri.scheme !in listOf("http", "https", "about")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (!isBlockedHost(uri.host.orEmpty())) return null

                return WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    204,
                    "Blocked by Nox",
                    emptyMap<String, String>(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                if (url != null) {
                    tab.url = url
                    if (activeTabId == tab.id && !addressBar.hasFocus()) {
                        addressBar.setText(url)
                    }
                    saveTabs()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) {
                    tab.url = url
                    if (activeTabId == tab.id && !addressBar.hasFocus()) {
                        addressBar.setText(url)
                    }
                    saveTabs()
                }
            }
        }
        return view
    }

    private fun activateTab(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        activeTabId = id

        webView?.let { oldView ->
            webContainer.removeView(oldView)
            oldView.stopLoading()
            oldView.onPause()
            oldView.destroy()
        }
        webView = null
        webContainer.removeAllViews()
        addressBar.setText(if (tab.url == HOME_URL) "" else tab.url)
        pageTitle.text = tab.title.ifBlank { "Nox Browser" }

        if (tab.url == HOME_URL) {
            showHomePage()
        } else {
            val newView = createWebView(tab)
            webView = newView
            webContainer.addView(
                newView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
            newView.loadUrl(tab.url)
        }

        updateTabsButton()
        saveTabs()
    }

    private fun showHomePage() {
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(background)
        }
        val logo = TextView(this).apply {
            text = "nox"
            textSize = 46f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(foreground)
            gravity = Gravity.CENTER
        }
        val tagline = TextView(this).apply {
            text = "Less noise. More web."
            textSize = 14f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(18))
        }
        val searchHint = Button(this).apply {
            text = "Tap the address bar to search"
            textSize = 14f
            setTextColor(accent)
            setBackgroundColor(surface)
            isAllCaps = false
            setOnClickListener {
                addressBar.requestFocus()
                val inputManager =
                    getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.showSoftInput(
                    addressBar,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }
        }
        home.addView(logo)
        home.addView(tagline)
        home.addView(searchHint)
        webContainer.addView(
            home,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun navigateFromAddress() {
        val raw = addressBar.text.toString().trim()
        if (raw.isBlank()) return

        val destination = normalizeInput(raw)
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        tab.url = destination
        tab.title = ""
        pageTitle.text = "Loading…"

        val inputManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(addressBar.windowToken, 0)
        addressBar.clearFocus()

        val currentView = webView
        if (currentView != null) {
            // Reuse the active WebView for normal address-bar navigation so its
            // back/forward history is not discarded unnecessarily.
            currentView.loadUrl(destination)
            saveTabs()
        } else {
            activateTab(tab.id)
        }
    }

    private fun normalizeInput(input: String): String {
        val hasWhitespace = input.any { it.isWhitespace() }
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(input)
        val looksLikeHost = input.contains(".") && !hasWhitespace && !input.startsWith(".")

        return when {
            input.startsWith("https://", ignoreCase = true) ||
                input.startsWith("http://", ignoreCase = true) -> input
            hasScheme -> "https://www.google.com/search?q=" + Uri.encode(input)
            looksLikeHost -> "https://$input"
            else -> "https://www.google.com/search?q=" + Uri.encode(input)
        }
    }

    private fun goHome() {
        val tab = tabs.firstOrNull { it.id == activeTabId } ?: return
        tab.url = HOME_URL
        tab.title = ""
        activateTab(tab.id)
    }

    private fun createTab(url: String, activate: Boolean) {
        val tab = BrowserTab(id = nextId++, url = url)
        tabs.add(tab)
        if (activate) activateTab(tab.id) else saveTabs()
    }

    private fun showTabPicker() {
        val labels = tabs.mapIndexed { index, tab ->
            val title = tab.title.ifBlank {
                if (tab.url == HOME_URL) "New tab" else tab.url
            }
            (index + 1).toString() + ". " + title
        }.toMutableList()

        labels.add("+ New tab")
        labels.add("Close current tab")

        android.app.AlertDialog.Builder(this)
            .setTitle("Tabs (" + tabs.size + ")")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < tabs.size -> activateTab(tabs[which].id)
                    which == tabs.size -> createTab(HOME_URL, activate = true)
                    else -> closeCurrentTab()
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun closeCurrentTab() {
        if (tabs.size <= 1) {
            val onlyTab = tabs.first()
            onlyTab.url = HOME_URL
            onlyTab.title = ""
            activateTab(onlyTab.id)
            return
        }

        val oldIndex = tabs.indexOfFirst { it.id == activeTabId }
        tabs.removeAll { it.id == activeTabId }
        val nextIndex = (oldIndex - 1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)
        activateTab(tabs[nextIndex].id)
    }

    private fun updateTabsButton() {
        if (::tabsButton.isInitialized) tabsButton.text = tabs.size.toString()
    }

    private fun saveTabs() {
        if (!::prefs.isInitialized) return

        val array = JSONArray()
        tabs.forEach { tab ->
            array.put(
                JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.url)
                    put("title", tab.title)
                }
            )
        }

        prefs.edit()
            .putString("tabs", array.toString())
            .putLong("activeTabId", activeTabId)
            .putLong("nextId", nextId)
            .apply()
        updateTabsButton()
    }

    private fun restoreTabs() {
        val raw = prefs.getString("tabs", null) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val url = item.optString("url", HOME_URL)
                if (url.startsWith("http://") ||
                    url.startsWith("https://") ||
                    url == HOME_URL
                ) {
                    val id = item.optLong("id", nextId)
                    tabs.add(
                        BrowserTab(
                            id = id,
                            url = url,
                            title = item.optString("title", "")
                        )
                    )
                    nextId = maxOf(nextId, id + 1L)
                }
            }

            activeTabId = prefs.getLong(
                "activeTabId",
                tabs.firstOrNull()?.id ?: -1L
            )
            nextId = maxOf(
                nextId,
                prefs.getLong("nextId", 1L),
                (tabs.maxOfOrNull { it.id } ?: 0L) + 1L
            )
        } catch (_: Exception) {
            tabs.clear()
            activeTabId = -1L
            nextId = 1L
        }
    }

    private fun isBlockedHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        if (normalized.isBlank()) return false
        return BLOCKED_HOSTS.any { domain ->
            normalized == domain || normalized.endsWith("." + domain)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val currentView = webView
        if (currentView != null && currentView.canGoBack()) {
            currentView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        webView?.onPause()
        saveTabs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.let { currentView ->
            webContainer.removeView(currentView)
            currentView.stopLoading()
            currentView.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "nox://home"
        private val BLOCKED_HOSTS = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adnxs.com",
            "scorecardresearch.com",
            "connect.facebook.net"
        )
    }
}
