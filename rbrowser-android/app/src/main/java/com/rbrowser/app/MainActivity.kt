package com.rbrowser.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var prefs: android.content.SharedPreferences
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    private var injectHandler: ScriptHandler? = null
    private var assetJs: String = ""

    // Estado
    private var deviceKey = "iphone"
    private var blockImages = false
    private var extractMode = false
    private var cookieBanners = true
    private var gpc = true

    // ---- Dispositivos que puede emular ----
    data class Device(
        val key: String, val label: String, val ua: String, val platform: String, val vendor: String,
        val chrome: Boolean, val mobile: Boolean, val touch: Int, val cores: Int, val memory: Int,
        val screen: IntArray, val dpr: Double, val gpu: Array<String>, val appVersion: String
    )

    private val devices = listOf(
        Device("iphone", "iPhone", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1",
            "iPhone", "Apple Computer, Inc.", false, true, 5, 6, 0, intArrayOf(390, 844), 3.0,
            arrayOf("Apple Inc.", "Apple GPU"), "5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"),
        Device("windows", "Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            "Win32", "Google Inc.", true, false, 0, 8, 8, intArrayOf(1920, 1080), 1.0,
            arrayOf("Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)"), "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"),
        Device("mac", "Mac", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15",
            "MacIntel", "Apple Computer, Inc.", false, false, 0, 10, 0, intArrayOf(1512, 982), 2.0,
            arrayOf("Apple Inc.", "Apple GPU"), "5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15"),
        Device("android", "Android", "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            "Linux armv81", "Google Inc.", true, true, 5, 8, 8, intArrayOf(412, 915), 2.625,
            arrayOf("Qualcomm", "Adreno (TM) 730"), "5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
    )

    private fun dev() = devices.first { it.key == deviceKey }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("rb", Context.MODE_PRIVATE)
        deviceKey = prefs.getString("device", "iphone") ?: "iphone"
        cookieBanners = prefs.getBoolean("banners", true)
        gpc = prefs.getBoolean("gpc", true)

        web = findViewById(R.id.web)
        urlBar = findViewById(R.id.url)
        progress = findViewById(R.id.progress)
        assetJs = assets.open("inject.js").bufferedReader().use { it.readText() }

        findViewById<ImageButton>(R.id.back).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<ImageButton>(R.id.reload).setOnClickListener { web.reload() }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu() }
        urlBar.setOnEditorActionListener { _, _, _ -> go(urlBar.text.toString()); true }

        setupWeb()
        applyDevice()
        web.loadUrl("https://duckduckgo.com/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.addJavascriptInterface(Bridge(), "RBNative")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith("http")) { loadMain(u); return true }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(buildScript(), null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                urlBar.setText(url ?: "")
                // Reafirma los interruptores en la página ya cargada.
                view.evaluateJavascript("if(window.__RB){window.__RB.extract=$extractMode;window.__RB.banners=$cookieBanners;if($cookieBanners&&window.__rbKillBanners)window.__rbKillBanners();}", null)
            }
        }
        web.setDownloadListener { url, _, _, _, _ ->
            // Enlaces de descarga: en vez de bajarlos, los ofrecemos para guardar.
            saveLinkDialog(url)
        }
        // Mantener-presionar una imagen: leer su QR o guardar su link.
        web.setOnLongClickListener {
            val r = web.hitTestResult
            if (r.type == WebView.HitTestResult.IMAGE_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val src = r.extra
                if (src != null) { imageMenu(src); return@setOnLongClickListener true }
            }
            false
        }
    }

    private fun buildCfg(): String {
        val d = dev()
        val o = JSONObject()
        o.put("seed", 1013904223)
        o.put("ua", d.ua); o.put("appVersion", d.appVersion)
        o.put("platform", d.platform); o.put("vendor", d.vendor)
        o.put("chrome", d.chrome); o.put("mobile", d.mobile); o.put("touch", d.touch)
        o.put("cores", d.cores); if (d.memory > 0) o.put("memory", d.memory)
        o.put("dpr", d.dpr)
        o.put("screen", JSONArray().put(d.screen[0]).put(d.screen[1]))
        o.put("gpu", JSONArray().put(d.gpu[0]).put(d.gpu[1]))
        o.put("languages", JSONArray().put("en-US").put("en"))
        o.put("extract", extractMode); o.put("banners", cookieBanners); o.put("gpc", gpc)
        return o.toString()
    }

    private fun buildScript(): String = "window.__RBCFG=" + buildCfg() + ";\n" + assetJs

    private fun applyDevice() {
        val d = dev()
        web.settings.userAgentString = d.ua
        web.settings.blockNetworkImage = blockImages
        web.settings.loadsImagesAutomatically = !blockImages
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            injectHandler?.remove()
            injectHandler = WebViewCompat.addDocumentStartJavaScript(web, buildScript(), setOf("*"))
        }
    }

    private fun loadMain(url: String) {
        val headers = HashMap<String, String>()
        if (gpc) { headers["Sec-GPC"] = "1"; headers["DNT"] = "1" }
        if (headers.isEmpty()) web.loadUrl(url) else web.loadUrl(url, headers)
    }

    private fun go(text: String) {
        var t = text.trim()
        if (t.isEmpty()) return
        t = if (Regex("^[a-z]+://").containsMatchIn(t)) t
        else if (Regex("^[^\\s/]+\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(t)) "https://$t"
        else "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        loadMain(t)
        urlBar.clearFocus()
    }

    // ---- Puente desde la página (extractor de links) ----
    inner class Bridge {
        @JavascriptInterface
        fun extracted(url: String?, title: String?, from: String?) {
            if (url.isNullOrEmpty()) return
            main.post { saveLink(url, title ?: "", from ?: ""); toast(getString(R.string.link_saved)) }
        }
    }

    // ---- Links guardados ----
    private fun savedLinks(): JSONArray = try { JSONArray(prefs.getString("links", "[]")) } catch (e: Exception) { JSONArray() }
    private fun saveLink(url: String, title: String, from: String) {
        val arr = savedLinks()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("url") == url) return
        val o = JSONObject().put("url", url).put("title", title).put("from", from).put("at", System.currentTimeMillis())
        val out = JSONArray().put(o)
        for (i in 0 until arr.length()) out.put(arr.get(i))
        prefs.edit().putString("links", out.toString()).apply()
    }
    private fun saveLinkDialog(url: String) {
        AlertDialog.Builder(this).setTitle(url)
            .setPositiveButton(R.string.save_link) { _, _ -> saveLink(url, "", "") ; toast(getString(R.string.link_saved)) }
            .setNeutralButton(R.string.copy) { _, _ -> copy(url) }
            .setNegativeButton(R.string.cancel, null).show()
    }
    private fun showLinks() {
        val arr = savedLinks()
        if (arr.length() == 0) { toast(getString(R.string.no_links)); return }
        val items = Array(arr.length()) { i ->
            val o = arr.getJSONObject(i); val t = o.optString("title"); (if (t.isNotEmpty()) "$t\n" else "") + o.optString("url")
        }
        AlertDialog.Builder(this).setTitle(R.string.saved_links)
            .setItems(items) { _, i -> linkActions(arr.getJSONObject(i).optString("url")) }
            .setPositiveButton(R.string.export) { _, _ -> exportLinks() }
            .setNeutralButton(R.string.clear_all) { _, _ -> prefs.edit().remove("links").apply(); toast(getString(R.string.done)) }
            .setNegativeButton(R.string.close, null).show()
    }
    private fun linkActions(url: String) {
        AlertDialog.Builder(this).setTitle(url)
            .setPositiveButton(R.string.open) { _, _ -> loadMain(url) }
            .setNeutralButton(R.string.copy) { _, _ -> copy(url) }
            .setNegativeButton(R.string.remove) { _, _ -> removeLink(url) }.show()
    }
    private fun removeLink(url: String) {
        val arr = savedLinks(); val out = JSONArray()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("url") != url) out.put(arr.get(i))
        prefs.edit().putString("links", out.toString()).apply()
    }
    private fun exportLinks() {
        val arr = savedLinks(); val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.getJSONObject(i).optString("url")).append("\n")
        copy(sb.toString()); toast(getString(R.string.links_copied))
    }
    private fun extractAll() {
        web.evaluateJavascript("JSON.stringify(window.__rbAllLinks?window.__rbAllLinks():[])") { res ->
            try {
                val list = JSONArray(JSONObject("{\"a\":$res}").getString("a"))
                var n = 0
                for (i in 0 until list.length()) { saveLink(list.getString(i), "", ""); n++ }
                toast(getString(R.string.links_found, n))
            } catch (e: Exception) { toast(getString(R.string.no_links)) }
        }
    }

    // ---- QR ----
    private fun imageMenu(src: String) {
        AlertDialog.Builder(this).setTitle(R.string.image)
            .setItems(arrayOf(getString(R.string.read_qr), getString(R.string.save_image_link))) { _, which ->
                if (which == 0) scanQr(src) else { saveLink(src, "", ""); toast(getString(R.string.link_saved)) }
            }.show()
    }
    private fun scanPageQr() {
        web.evaluateJavascript("JSON.stringify(window.__rbAllImages?window.__rbAllImages():[])") { res ->
            try {
                val list = JSONArray(JSONObject("{\"a\":$res}").getString("a"))
                if (list.length() == 0) { toast(getString(R.string.no_qr)); return@evaluateJavascript }
                scanQrList(ArrayList<String>().apply { for (i in 0 until list.length()) add(list.getString(i)) }, 0)
            } catch (e: Exception) { toast(getString(R.string.no_qr)) }
        }
    }
    private fun scanQrList(urls: List<String>, i: Int) {
        if (i >= urls.size) { toast(getString(R.string.no_qr)); return }
        io.execute {
            val text = decodeQr(urls[i])
            main.post { if (text != null) qrResult(text) else scanQrList(urls, i + 1) }
        }
    }
    private fun scanQr(src: String) {
        toast(getString(R.string.reading_qr))
        io.execute {
            val text = decodeQr(src)
            main.post { if (text != null) qrResult(text) else toast(getString(R.string.no_qr)) }
        }
    }
    private fun decodeQr(src: String): String? {
        return try {
            val bytes = if (src.startsWith("data:")) android.util.Base64.decode(src.substring(src.indexOf(',') + 1), android.util.Base64.DEFAULT)
            else {
                val c = URL(src).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", dev().ua)
                c.inputStream.readBytes()
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))))
            result.text
        } catch (e: Exception) { null }
    }
    private fun qrResult(text: String) {
        val isUrl = Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val b = AlertDialog.Builder(this).setTitle(R.string.qr_code).setMessage(text)
        if (isUrl) b.setPositiveButton(R.string.open) { _, _ -> loadMain(text) }
        b.setNeutralButton(R.string.copy) { _, _ -> copy(text) }
        if (isUrl) b.setNegativeButton(R.string.save_link) { _, _ -> saveLink(text, "", ""); toast(getString(R.string.link_saved)) }
        b.show()
    }

    // ---- Menú ----
    private fun showMenu() {
        val opts = arrayOf(
            getString(R.string.device) + ": " + dev().label,
            (if (extractMode) "✓ " else "") + getString(R.string.extractor),
            getString(R.string.saved_links),
            (if (blockImages) "✓ " else "") + getString(R.string.block_images),
            getString(R.string.scan_page_qr),
            (if (cookieBanners) "✓ " else "") + getString(R.string.reject_cookies),
            (if (gpc) "✓ " else "") + getString(R.string.gpc),
            getString(R.string.clear_all_data)
        )
        AlertDialog.Builder(this).setItems(opts) { _, i ->
            when (i) {
                0 -> pickDevice()
                1 -> { extractMode = !extractMode; web.evaluateJavascript("if(window.__RB)window.__RB.extract=$extractMode;", null); toast(getString(if (extractMode) R.string.extractor_on else R.string.extractor_off)) }
                2 -> showLinks()
                3 -> { blockImages = !blockImages; applyDevice(); web.reload(); toast(getString(if (blockImages) R.string.images_off else R.string.images_on)) }
                4 -> scanPageQr()
                5 -> { cookieBanners = !cookieBanners; prefs.edit().putBoolean("banners", cookieBanners).apply(); web.evaluateJavascript("if(window.__RB){window.__RB.banners=$cookieBanners;if($cookieBanners&&window.__rbKillBanners)window.__rbKillBanners();}", null) }
                6 -> { gpc = !gpc; prefs.edit().putBoolean("gpc", gpc).apply(); applyDevice() }
                7 -> clearAll()
            }
        }.show()
    }
    private fun pickDevice() {
        val labels = devices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.emulate_device)
            .setItems(labels) { _, i ->
                deviceKey = devices[i].key; prefs.edit().putString("device", deviceKey).apply()
                applyDevice(); web.reload(); toast(getString(R.string.now_device, devices[i].label))
            }.show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this).setTitle(R.string.clear_all_data).setMessage(R.string.clear_all_msg)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                web.clearCache(true); web.clearHistory(); web.clearFormData()
                android.webkit.WebStorage.getInstance().deleteAllData()
                toast(getString(R.string.done)); web.reload()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun copy(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", t))
        toast(getString(R.string.copied))
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
