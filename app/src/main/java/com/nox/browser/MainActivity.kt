package com.nox.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
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
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
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
    private lateinit var pageTitle: TextView
    private lateinit var webContainer: LinearLayout
    private lateinit var tabsButton: Button
    private lateinit var topChrome: LinearLayout
    private lateinit var controls: LinearLayout

    private var webView: WebView? = null
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTabId: Long = -1L
    private var nextId: Long = 1L
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
            setBackgroundColor(bgColor)
            setPadding(dp(8), dp(6), dp(8), dp(3))
        }

        topChrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(2))
        }
        val brand = TextView(this).apply {
            text = "nox"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(fgColor)
            setPadding(dp(4), 0, dp(10), 0)
            letterSpacing = -0.04f
        }
        pageTitle = TextView(this).apply {
            text = "LESS NOISE. MORE WEB."
            textSize = 10f
            letterSpacing = 0.09f
            setTextColor(secondaryColor)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(brand)
        brandRow.addView(pageTitle, LinearLayout.LayoutParams(0, dp(38), 1f))
        topChrome.addView(brandRow)

        addressBar = EditText(this).apply {
            hint = "Pesquisar ou digitar endereço"
            setHintTextColor(secondaryColor)
            setTextColor(fgColor)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(16), 0, dp(14), 0)
            background = glassBackground(glassFill, 23f)
            setSelectAllOnFocus(true)
            includeFontPadding = false
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
            topMargin = dp(5)
            bottomMargin = dp(9)
            marginStart = dp(2)
            marginEnd = dp(2)
        }
        topChrome.addView(addressBar, addressParams)
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassBackground(0xE80D0E11.toInt(), 25f)
            elevation = dp(5).toFloat()
        }
        controls.addView(makeButton("‹", "Voltar") {
            webView?.let { if (it.canGoBack()) it.goBack() }
        })
        controls.addView(makeButton("›", "Avançar") {
            webView?.let { if (it.canGoForward()) it.goForward() }
        })
        controls.addView(makeButton("↻", "Recarregar") { webView?.reload() })
        controls.addView(makeButton("⌂", "Início") { goHome() })
        tabsButton = makeButton(tabs.size.toString(), "Abas abertas") { showTabPicker() }
        controls.addView(tabsButton)
        controls.addView(makeButton("+", "Nova aba") { createTab(HOME_URL, activate = true) })
        val controlsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(2)
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
            background = glassBackground(0x3AFFFFFF, 18f)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(43), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { action() }
        }
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
                val delta = y - oldY
                val now = SystemClock.uptimeMillis()
                if (now - lastChromeToggleAt > 260L && abs(delta) > dp(7)) {
                    if (delta > 0 && y > dp(45)) {
                        setChromeVisible(false)
                    } else if (delta < 0 || y <= dp(4)) {
                        setChromeVisible(true)
                    }
                }
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
        val searchHint = TextView(this).apply {
            text = "⌕   Pesquisar ou digitar endereço"
            textSize = 14f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = glassBackground(glassFill, 24f)
            setOnClickListener {
                addressBar.requestFocus()
                val inputManager =
                    getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
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
