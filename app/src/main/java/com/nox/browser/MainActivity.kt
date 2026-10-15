package com.nox.browser

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ListView
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.TextWatcher
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {
    private data class BrowserTab(
        val id: Long,
        var url: String,
        var title: String = "",
        var previewPath: String = ""
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var addressBar: EditText
    private var suggestionPopup: PopupWindow? = null
    private var suggestionTask: Runnable? = null
    private lateinit var pageTitle: TextView
    private lateinit var webContainer: LinearLayout
    private lateinit var tabsButton: Button
    private lateinit var topChrome: LinearLayout
    private lateinit var controls: LinearLayout

    private var webView: WebView? = null
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: Long = -1L
    private var nextId: Long = 1L
    private var desktopMode: Boolean = false
    private var chromeHidden = false
    private var lastScrollY = 0
    private var lastChromeToggleAt = 0L

    private val bgColor = Color.BLACK
    private val surfaceColor = Color.rgb(16, 17, 21)
    private val fgColor = Color.rgb(244, 246, 250)
    private val secondaryColor = Color.rgb(159, 164, 176)
    private val accentColor = Color.rgb(164, 207, 255)
    private val glassFill = 0xD916171B.toInt()
    private val glassStroke = 0x36FFFFFF
    private val previewDir: File
        get() = File(filesDir, "tab_previews")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        prefs = getSharedPreferences("nox_session", Context.MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktopMode", false)
        applyImmersiveStatusBar()
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
        // Internal title holder used by WebView callbacks; intentionally not shown in the UI.
        pageTitle = TextView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = Color.TRANSPARENT.toDrawableCompat()
        }
        val searchIcon = TextView(this).apply {
            text = "⌕"
            textSize = 23f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            contentDescription = "Pesquisar"
            setPadding(dp(5), 0, dp(8), 0)
        }
        addressBar = EditText(this).apply {
            hint = "Pesquisar"
            setHintTextColor(secondaryColor)
            setTextColor(fgColor)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(0, 0, dp(4), 0)
            background = null
            setSelectAllOnFocus(true)
            includeFontPadding = false
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                    dismissSuggestions()
                    navigateFromAddress()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    scheduleSuggestions(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnFocusChangeListener { _, focused -> if (!focused) dismissSuggestions() }
        }
        addressRow.addView(searchIcon)
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makeButton("↻", "Recarregar") { webView?.reload() }, LinearLayout.LayoutParams(dp(36), dp(40)))
        actions.addView(makeButton("⋮", "Ferramentas") { showSettingsPanel() }, LinearLayout.LayoutParams(dp(36), dp(40)))
        addressRow.addView(actions)
        topChrome.addView(addressRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { marginStart = dp(4); marginEnd = dp(4) })
        root.addView(topChrome)

        webContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        root.addView(
            webContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }
        controls.addView(makeButton("‹", "Voltar") {
            webView?.let { if (it.canGoBack()) it.goBack() }
        })
        controls.addView(makeButton("›", "Avançar") {
            webView?.let { if (it.canGoForward()) it.goForward() }
        })
        controls.addView(makeButton("⌂", "Início") { goHome() })
        tabsButton = makeButton(tabs.size.toString(), "Abas abertas") { showTabPicker() }
        controls.addView(tabsButton)
        controls.addView(makeButton("+", "Nova aba") { createTab(HOME_URL, activate = true) })
        val controlsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply {
            topMargin = dp(2)
            bottomMargin = dp(1)
            marginStart = dp(1)
            marginEnd = dp(1)
        }
        root.addView(controls, controlsParams)

        setContentView(root)
    }

    private fun makeButton(label: String, description: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            contentDescription = description
            textSize = 19f
            setTextColor(if (label == "+") accentColor else fgColor)
            background = Color.TRANSPARENT.toDrawableCompat()
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(35), 1f).apply {
                marginStart = dp(0)
                marginEnd = dp(0)
            }
            setOnClickListener { action() }
        }
    }

    private fun showSettingsPanel() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(0xFF08090B.toInt())
                cornerRadii = floatArrayOf(24f,24f,24f,24f,24f,24f,0f,0f)
                setStroke(dp(1), 0x28FFFFFF)
            }
        }
        panel.addView(TextView(this).apply { text = "Ferramentas"; textSize = 21f; setTextColor(fgColor); setPadding(0,0,0,dp(12)) })
        fun item(label: String, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label; textSize = 15f; setTextColor(fgColor); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12),0,dp(12),0); isClickable = true
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        item(if (desktopMode) "▣   Desativar modo desktop" else "▣   Modo desktop") { toggleDesktopMode() }
        item("↓   Downloads") { showDownloadsPanel() }
        item("◷   Histórico") { showHistoryPanel() }
        item("⚙   Configurações") { android.widget.Toast.makeText(this, "Nox Browser • AMOLED • cookies de terceiros bloqueados", android.widget.Toast.LENGTH_LONG).show() }
        item("⌂   Página inicial") { goHome() }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, -2)
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        prefs.edit().putBoolean("desktopMode", desktopMode).apply()
        webView?.let { view ->
            view.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this)
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = desktopMode
            view.reload()
        }
        android.widget.Toast.makeText(this, if (desktopMode) "Modo desktop ativado" else "Modo mobile ativado", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showDownloadsPanel() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); setBackgroundColor(Color.BLACK) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Downloads"; textSize = 24f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(fgColor) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(TextView(this).apply { text = "Fechar"; textSize = 14f; setTextColor(accentColor); gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(4), dp(8)); setOnClickListener { dialog.dismiss() } })
        panel.addView(header)
        panel.addView(TextView(this).apply { text = "Arquivos baixados pelo Nox Browser"; textSize = 12f; setTextColor(secondaryColor); setPadding(0, 0, 0, dp(12)) })
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val records = try { JSONArray(getSharedPreferences("nox_downloads", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (records.length() == 0) list.addView(TextView(this).apply { text = "Ainda não há downloads do Nox.\n\nQuando você baixar um arquivo por uma página, ele aparecerá aqui."; textSize = 14f; setTextColor(secondaryColor); setPadding(dp(4), dp(24), dp(4), dp(24)) })
        for (i in records.length() - 1 downTo 0) {
            val record = records.optJSONObject(i) ?: continue
            val id = record.optLong("id", -1L)
            val name = record.optString("name", "Arquivo")
            val mime = record.optString("mime", "application/octet-stream")
            var status = -1
            var downloaded = 0L
            var total = -1L
            try {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    }
                }
            } catch (_: Exception) {}
            val statusText = when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> if (total > 0) "Concluído · " + formatBytes(total) else "Concluído"
                DownloadManager.STATUS_RUNNING -> if (total > 0) "Baixando · " + ((downloaded * 100) / total).coerceIn(0, 100) + "%" else "Baixando…"
                DownloadManager.STATUS_PENDING -> "Na fila"
                DownloadManager.STATUS_PAUSED -> "Pausado"
                DownloadManager.STATUS_FAILED -> "Falhou"
                else -> "Download indisponível"
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply { setColor(0xFF0B0C0F.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x22FFFFFF) }
                isClickable = true
                setOnClickListener {
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri != null) try {
                            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(intent)
                        } catch (_: Exception) { android.widget.Toast.makeText(this@MainActivity, "Não há aplicativo para abrir esse arquivo.", android.widget.Toast.LENGTH_SHORT).show() }
                    } else android.widget.Toast.makeText(this@MainActivity, statusText, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(TextView(this@MainActivity).apply { text = name; textSize = 14f; setTextColor(fgColor); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            details.addView(TextView(this@MainActivity).apply { text = statusText; textSize = 12f; setTextColor(secondaryColor); setPadding(0, dp(4), 0, 0) })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "×"; textSize = 22f; setTextColor(secondaryColor); gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(8), dp(8))
                setOnClickListener {
                    if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PAUSED) manager.remove(id)
                    val store = getSharedPreferences("nox_downloads", Context.MODE_PRIVATE)
                    val old = try { JSONArray(store.getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
                    val updated = JSONArray()
                    for (j in 0 until old.length()) { val item = old.optJSONObject(j) ?: continue; if (item.optLong("id", -2L) != id) updated.put(item) }
                    store.edit().putString("items", updated.toString()).apply()
                    dialog.dismiss(); showDownloadsPanel()
                }
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, -1)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
    }

    private fun enqueueDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = Uri.parse(url)
        if (uri.scheme !in listOf("http", "https")) {
            android.widget.Toast.makeText(this, "Esse tipo de download não é compatível.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val suggested = URLUtil.guessFileName(url, contentDisposition, mimeType).replace("/", "_").replace("\\", "_").ifBlank { "arquivo" }
            val fileName = "Nox_" + System.currentTimeMillis() + "_" + suggested
            val request = DownloadManager.Request(uri).setTitle(suggested).setDescription("Download iniciado no Nox Browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            val store = getSharedPreferences("nox_downloads", Context.MODE_PRIVATE)
            val records = try { JSONArray(store.getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            records.put(JSONObject().apply { put("id", id); put("name", suggested); put("url", url); put("mime", mimeType ?: "application/octet-stream") })
            store.edit().putString("items", records.toString()).apply()
            android.widget.Toast.makeText(this, "Download iniciado: " + suggested, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, "Não foi possível iniciar o download.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun recordHistory(tab: BrowserTab, url: String) {
        if (url == HOME_URL || !url.startsWith("http")) return
        val history = getSharedPreferences("nox_history", Context.MODE_PRIVATE)
        val items = try { JSONArray(history.getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (items.length() > 0 && items.optJSONObject(items.length() - 1)?.optString("url") == url) return
        items.put(JSONObject().apply { put("url", url); put("title", tab.title) })
        while (items.length() > 300) items.remove(0)
        history.edit().putString("items", items.toString()).apply()
    }

    private fun showHistoryPanel() {
        val history = try { JSONArray(getSharedPreferences("nox_history", Context.MODE_PRIVATE).getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); setBackgroundColor(Color.BLACK) }
        panel.addView(TextView(this).apply { text = "Histórico"; textSize = 23f; setTextColor(fgColor) })
        val scroll = ScrollView(this)
        val entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (history.length() == 0) entries.addView(TextView(this).apply { text = "Ainda não há páginas no histórico."; textSize = 14f; setTextColor(secondaryColor); setPadding(0, dp(18), 0, dp(18)) })
        for (i in history.length() - 1 downTo 0) {
            val item = history.optJSONObject(i) ?: continue
            val url = item.optString("url")
            entries.addView(TextView(this).apply {
                text = item.optString("title").ifBlank { url } + "\n" + url
                textSize = 13f; setTextColor(fgColor); setPadding(0, dp(13), 0, dp(13))
                setOnClickListener {
                    dialog.dismiss()
                    val tab = tabs.firstOrNull { it.id == activeTabId } ?: return@setOnClickListener
                    tab.url = url; activateTab(tab.id)
                }
            })
        }
        scroll.addView(entries); panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout(-1, -1)
    }

    private fun applyImmersiveStatusBar() {
        window.statusBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }


    private fun dismissSuggestions() {
        suggestionTask?.let { addressBar.removeCallbacks(it) }
        suggestionPopup?.dismiss()
        suggestionPopup = null
    }

    private fun scheduleSuggestions(query: String) {
        suggestionTask?.let { addressBar.removeCallbacks(it) }
        if (!addressBar.hasFocus() || query.trim().length < 2) {
            dismissSuggestions()
            return
        }
        val task = Runnable { fetchSuggestions(query.trim()) }
        suggestionTask = task
        addressBar.postDelayed(task, 280L)
    }

    private fun fetchSuggestions(query: String) {
        Thread {
            val results = mutableListOf<String>()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://suggestqueries.google.com/complete/search?client=firefox&q=" + Uri.encode(query)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NoxBrowser/1.5")
                }
                val data = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONArray(data)
                val values = json.optJSONArray(1)
                if (values != null) for (i in 0 until minOf(values.length(), 7)) {
                    val value = values.optString(i)
                    if (value.isNotBlank()) results.add(value)
                }
            } catch (_: Exception) {
                // Suggestions are optional; navigation works without them.
            } finally { connection?.disconnect() }
            runOnUiThread {
                if (addressBar.hasFocus() && addressBar.text.toString().trim() == query && results.isNotEmpty()) {
                    showSuggestions(results)
                } else if (results.isEmpty()) dismissSuggestions()
            }
        }.start()
    }

    private fun showSuggestions(items: List<String>) {
        suggestionPopup?.dismiss()
        val list = ListView(this).apply {
            setBackgroundColor(0xFF101115.toInt())
            divider = android.graphics.drawable.ColorDrawable(0xFF292A30.toInt())
            dividerHeight = dp(1)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, items).also { adapter ->
                adapter.setDropDownViewResource(android.R.layout.simple_list_item_1)
            }
            setOnItemClickListener { _, _, position, _ ->
                val chosen = items[position]
                addressBar.setText(chosen)
                addressBar.setSelection(chosen.length)
                dismissSuggestions()
                navigateFromAddress()
            }
        }
        val popup = PopupWindow(list, addressBar.width.coerceAtLeast(dp(240)), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            isFocusable = false
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(0xFF101115.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x44FFFFFF)
            })
            elevation = dp(8).toFloat()
        }
        suggestionPopup = popup
        popup.showAsDropDown(addressBar, -dp(26), dp(3))
    }

    private fun glassBackground(fill: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            setColor(fill)
            setStroke(dp(1), glassStroke)
        }
    }

    private fun setChromeVisible(visible: Boolean, animate: Boolean = true) {
        if (chromeHidden == !visible) return
        chromeHidden = !visible
        lastChromeToggleAt = SystemClock.uptimeMillis()
        val top = topChrome
        val bottom = controls

        if (visible) {
            top.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
            if (animate) {
                top.translationY = -dp(12).toFloat()
                top.alpha = 0f
                bottom.translationY = dp(12).toFloat()
                bottom.alpha = 0f
                top.animate().translationY(0f).alpha(1f).setDuration(170L).start()
                bottom.animate().translationY(0f).alpha(1f).setDuration(170L).start()
            } else {
                top.translationY = 0f
                top.alpha = 1f
                bottom.translationY = 0f
                bottom.alpha = 1f
            }
        } else {
            if (animate) {
                top.animate().translationY(-dp(12).toFloat()).alpha(0f)
                    .setDuration(140L).withEndAction {
                        if (chromeHidden) top.visibility = View.GONE
                    }.start()
                bottom.animate().translationY(dp(12).toFloat()).alpha(0f)
                    .setDuration(140L).withEndAction {
                        if (chromeHidden) bottom.visibility = View.GONE
                    }.start()
            } else {
                top.visibility = View.GONE
                bottom.visibility = View.GONE
            }
        }
    }

    private fun createWebView(tab: BrowserTab): WebView {
        val view = object : WebView(this) {
            override fun onScrollChanged(
                x: Int,
                y: Int,
                oldX: Int,
                oldY: Int
            ) {
                super.onScrollChanged(x, y, oldX, oldY)
                // Keep browser controls visible while scrolling; no immersive auto-hide.
                lastScrollY = y
            }
        }
        view.setBackgroundColor(Color.BLACK)
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
            useWideViewPort = true
            loadWithOverviewMode = desktopMode
            userAgentString = if (desktopMode) DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(this@MainActivity)
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

        view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        })
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
                    recordHistory(tab, url)
                    if (activeTabId == tab.id && !addressBar.hasFocus()) {
                        addressBar.setText(url)
                    }
                    saveTabs()
                }
                val finishedView = view ?: return
                finishedView.postDelayed({
                    if (webView === finishedView && tabs.any { it.id == tab.id }) {
                        capturePreview(tab, finishedView)
                    }
                }, 450L)
            }
        }
        return view
    }

    private fun capturePreview(tab: BrowserTab, view: WebView) {
        if (view.width <= 0 || view.height <= 0 || tab.url == HOME_URL) return
        try {
            if (!previewDir.exists()) previewDir.mkdirs()
            val width = 320
            val height = 480
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            canvas.save()
            canvas.scale(width.toFloat() / view.width, height.toFloat() / view.height)
            view.draw(canvas)
            canvas.restore()
            val file = File(previewDir, "tab_" + tab.id + ".jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 48, output)
            }
            bitmap.recycle()
            tab.previewPath = file.absolutePath
            saveTabs()
        } catch (_: Exception) {
            // A failed preview must never affect tab/session persistence.
        }
    }

    private fun activateTab(id: Long) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        captureActivePreview()
        setChromeVisible(true, animate = false)
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
        lastScrollY = 0

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
            setBackgroundColor(Color.BLACK)
        }
        val logo = TextView(this).apply {
            text = "nox"
            textSize = 49f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            letterSpacing = -0.045f
        }
        val tagline = TextView(this).apply {
            text = "LESS NOISE. MORE WEB."
            textSize = 11f
            letterSpacing = 0.14f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(25))
        }
        val searchHint = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            addView(TextView(this@MainActivity).apply { text = "⌕"; textSize = 21f; setTextColor(secondaryColor); setPadding(0, 0, dp(12), 0) })
            addView(TextView(this@MainActivity).apply { text = "Pesquisar ou digitar endereço"; textSize = 14f; setTextColor(secondaryColor) })
            setOnClickListener {
                addressBar.requestFocus()
                addressBar.setSelection(addressBar.text.length)
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        home.addView(logo)
        home.addView(tagline)
        home.addView(
            searchHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
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
        pageTitle.text = "Carregando…"

        val inputManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(addressBar.windowToken, 0)
        addressBar.clearFocus()

        val currentView = webView
        if (currentView != null) {
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
            looksLikeHost -> "https://" + input
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
        val dialog = Dialog(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(2), dp(12))
        }
        val heading = TextView(this).apply {
            text = "Abas"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(fgColor)
        }
        val count = TextView(this).apply {
            text = tabs.size.toString() + " abertas"
            textSize = 12f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
        }
        val done = TextView(this).apply {
            text = "Concluído"
            textSize = 14f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = glassBackground(0x301F80FF, 18f)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(heading)
        header.addView(count, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(12)
        })
        header.addView(done)
        page.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(grid)
        page.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val newTabButton = TextView(this).apply {
            text = "+     Nova aba"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(fgColor)
            gravity = Gravity.CENTER
            background = glassBackground(0xE81D1F25.toInt(), 23f)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                dialog.dismiss()
                createTab(HOME_URL, activate = true)
            }
        }
        page.addView(
            newTabButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(12)
                bottomMargin = dp(2)
            }
        )

        dialog.setContentView(page)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        populateTabGrid(grid, count, dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun populateTabGrid(
        grid: LinearLayout,
        countLabel: TextView,
        dialog: Dialog
    ) {
        grid.removeAllViews()
        countLabel.text = tabs.size.toString() + " abertas"

        val rows = tabs.toList().chunked(2)
        rows.forEach { rowTabs ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowTabs.forEach { tab ->
                row.addView(
                    createTabCard(tab, dialog, grid, countLabel),
                    LinearLayout.LayoutParams(0, dp(232), 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(7))
                    }
                )
            }
            if (rowTabs.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(232), 1f))
            }
            grid.addView(row)
        }
        if (tabs.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Nenhuma aba aberta"
                setTextColor(secondaryColor)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, dp(40))
            }
            grid.addView(empty)
        }
    }

    private fun createTabCard(
        tab: BrowserTab,
        dialog: Dialog,
        grid: LinearLayout,
        countLabel: TextView
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(8))
            background = glassBackground(
                if (tab.id == activeTabId) 0xE8262A32.toInt() else 0xD9101115.toInt(),
                18f
            )
            elevation = if (tab.id == activeTabId) dp(3).toFloat() else 0f
            isClickable = true
            isFocusable = true
        }

        val previewFrame = FrameLayout(this).apply {
            background = glassBackground(0xFF090A0D.toInt(), 12f)
            clipToOutline = true
        }
        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Prévia da página"
            setBackgroundColor(0xFF090A0D.toInt())
        }
        val path = tab.previewPath
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists()) {
                val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bitmap != null) thumbnail.setImageBitmap(bitmap)
            }
        }
        if (thumbnail.drawable == null) {
            thumbnail.setImageDrawable(null)
            thumbnail.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF14161C.toInt(), 0xFF050506.toInt())
            )
        }
        previewFrame.addView(
            thumbnail,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = glassBackground(0xD91A1B20.toInt(), 18f)
            elevation = dp(3).toFloat()
            contentDescription = "Fechar aba"
            setOnClickListener {
                closeTab(tab.id)
                populateTabGrid(grid, countLabel, dialog)
            }
        }
        previewFrame.addView(
            close,
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(5), dp(5), 0)
            }
        )
        card.addView(
            previewFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(151)
            )
        )

        val title = TextView(this).apply {
            text = tab.title.ifBlank {
                if (tab.url == HOME_URL) "Nova aba" else tab.url
            }
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(fgColor)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(3), dp(8), dp(3), 0)
        }
        val host = TextView(this).apply {
            text = if (tab.url == HOME_URL) "Nox" else hostLabel(tab.url)
            textSize = 10f
            setTextColor(secondaryColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(3), dp(3), dp(3), 0)
        }
        card.addView(title)
        card.addView(host)
        card.setOnClickListener {
            dialog.dismiss()
            activateTab(tab.id)
        }
        return card
    }

    private fun hostLabel(url: String): String {
        return try {
            Uri.parse(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }

    private fun closeTab(id: Long) {
        if (tabs.size <= 1) {
            val onlyTab = tabs.firstOrNull() ?: return
            onlyTab.url = HOME_URL
            onlyTab.title = ""
            onlyTab.previewPath = ""
            activateTab(onlyTab.id)
            return
        }

        val oldIndex = tabs.indexOfFirst { it.id == id }
        if (oldIndex < 0) return
        val wasActive = activeTabId == id
        tabs.removeAll { it.id == id }
        File(previewDir, "tab_" + id + ".jpg").delete()

        if (wasActive) {
            val nextIndex = (oldIndex - 1).coerceAtLeast(0).coerceAtMost(tabs.lastIndex)
            activateTab(tabs[nextIndex].id)
        } else {
            saveTabs()
            updateTabsButton()
        }
    }

    private fun captureActivePreview() {
        val active = tabs.firstOrNull { it.id == activeTabId } ?: return
        val current = webView ?: return
        capturePreview(active, current)
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
                    put("previewPath", tab.previewPath)
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
                            title = item.optString("title", ""),
                            previewPath = item.optString("previewPath", "")
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

    private fun Int.toDrawableCompat(): android.graphics.drawable.Drawable =
        android.graphics.drawable.ColorDrawable(this)

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
        captureActivePreview()
        webView?.onPause()
        saveTabs()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveStatusBar()
        webView?.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveStatusBar()
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
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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
