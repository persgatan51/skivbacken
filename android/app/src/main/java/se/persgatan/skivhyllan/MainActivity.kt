package se.persgatan.skivhyllan

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Appen visar webbappen (GitHub Pages). Skalet lägger till:
 *  - kameran/bildväljaren för <input type="file">,
 *  - App.scanBarcode(): Googles streckkodsskanner (snabbskanning av många skivor),
 *  - App.saveBackup()/loadBackup(): backupfil via Androids filväljare (t.ex. Google Drive),
 *  - App.scan(): läser streckkod och text i fotot med ML Kit (gratis, i telefonen),
 *  - att Spotify-länkar öppnas i Spotify-appen,
 *  - att Spotify-inloggningen sker inne i appen så att svaret kommer tillbaka hit,
 *  - App.takePending(): foto/backup som blev kvar om Android stängde appen medan kameran eller filväljaren var öppen,
 *  - App.share(): telefonens dela-meny (t.ex. för önskelistan),
 *  - att appen laddar om sidan i stället för att stängas om WebViews ritprocess kraschar.
 *
 * Bryggan App.* svarar bara när sidan som visas är webbappen (inte t.ex. Spotifys inloggningssidor).
 */
class MainActivity : Activity() {

    companion object {
        const val START_URL = "https://persgatan51.github.io/skivbacken/"
        const val HOST = "persgatan51.github.io"
        const val REQ_FILE = 42
        const val REQ_SAVE = 43
        const val REQ_OPEN = 44
        // Sidor som ska visas inne i appen (Spotify-inloggningen). Allt annat öppnas utanför.
        val INSIDE = setOf(HOST, "accounts.spotify.com", "challenge.spotify.com", "accounts.scdn.co")
    }

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("skivbacken", MODE_PRIVATE) }
    private var pendingSaveJson: String? = null
    private var pendingSaveId: String? = null
    private var pendingOpenId: String? = null
    // Värd för sidan som visas just nu. Bryggan används bara när det är webbappen.
    @Volatile private var pageHost: String? = null
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
            ).build()
        )
    }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createWeb()
        // Fotot som kameran skulle spara, om appen stängts av Android medan kameran var öppen.
        savedInstanceState?.getString("photoUri")?.let { photoUri = Uri.parse(it) }
        // restoreState ger null om det sparade tillståndet inte gick att återställa; ladda då sidan på nytt
        // i stället för att visa en vit skärm.
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) loadStart()
    }

    // ?v= gör att sidan alltid hämtas färsk vid start (GitHub Pages cachar annars i upp till 10 min).
    private fun loadStart() = web.loadUrl(START_URL + "?v=" + System.currentTimeMillis())

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb() {
        pageHost = null
        fileCallback = null   // hörde till en WebView som inte finns längre; ett foto tas då emot via takePending
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host in INSIDE) return false
                openExternal(uri)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageHost = url?.let { Uri.parse(it).host }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                pageHost = url?.let { Uri.parse(it).host }
            }
            // WebViews ritprocess har kraschat eller stängts av Android (oftast för att minnet tog slut).
            // Utan detta stänger Android hela appen. Skapa en ny WebView och ladda sidan igen.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (view === web && !isFinishing && !isDestroyed) {
                    createWeb()
                    loadStart()
                    Toast.makeText(this@MainActivity, "Appen laddades om", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = if (params.isCaptureEnabled) {
                    val dir = File(cacheDir, "photos").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, "skiva-${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    photoUri = uri
                    Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    photoUri = null
                    params.createIntent()
                }
                try {
                    startActivityForResult(intent, REQ_FILE)
                } catch (_: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                }
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "App")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE || requestCode == REQ_OPEN) { onBackupResult(requestCode, resultCode, data); return }
        if (requestCode != REQ_FILE) return
        val cb = fileCallback
        if (cb == null) {
            // Appen har startats om sedan kameran/bildväljaren öppnades: spara resultatet till webbappen.
            val uri = if (resultCode == RESULT_OK) photoUri ?: data?.data else null
            photoUri = null
            if (uri != null) setPending("photo", uri.toString())
            return
        }
        fileCallback = null
        val result: Array<Uri>? = when {
            resultCode != RESULT_OK -> null
            photoUri != null -> arrayOf(photoUri!!)
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        cb.onReceiveValue(result)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
        photoUri?.let { outState.putString("photoUri", it.toString()) }
    }

    /* ---------- Resultat som kommer efter att Android stängt och startat om appen ---------- */
    private fun setPending(type: String, uri: String) {
        prefs.edit().putString("pendingType", type).putString("pendingUri", uri).apply()
        // Om webbappen redan är laddad: be den hämta resultatet direkt (annars gör den det vid start).
        web.evaluateJavascript("window.__checkPending && window.__checkPending()", null)
    }

    /** Läser in fotot nedskalat (max 2000 px) och vänt rätt enligt EXIF, som JPEG i base64. */
    private fun photoB64(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw Exception("Kunde inte läsa fotot")
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }
        val out = if (rot != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true) else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Låt webbappen stänga öppna rutor först; annars vanlig bakåt.
        web.evaluateJavascript("(window.__back && window.__back()) ? 'handled' : 'no'") { r ->
            if (r?.contains("handled") != true) {
                if (web.canGoBack()) web.goBack() else finishAfterTransition()
            }
        }
    }

    private fun openExternal(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }

    private fun reply(id: String, ok: Boolean, data: String) = runOnUiThread {
        web.evaluateJavascript(
            "window.__cb && window.__cb(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(data)})", null
        )
    }

    /** Läser streckkoder och textrader (med texthöjd, så att webbappen vet vad som är störst). */
    private fun scan(b64: String): String {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("Kunde inte läsa bilden")
        val image = InputImage.fromBitmap(bmp, 0)
        val codes = Tasks.await(barcodeScanner.process(image))
        val text = Tasks.await(textRecognizer.process(image))
        val barcodes = JSONArray()
        codes.mapNotNull { it.rawValue }.distinct().forEach { barcodes.put(it) }
        val lines = JSONArray()
        for (block in text.textBlocks) for (line in block.lines) {
            lines.put(JSONObject().put("t", line.text).put("h", line.boundingBox?.height() ?: 0))
        }
        return JSONObject().put("barcodes", barcodes).put("lines", lines).toString()
    }

    /* ---------- Backup: en JSON-fil på valfri plats, t.ex. Google Drive ---------- */
    private fun writeTo(uri: Uri, json: String) {
        val bytes = json.toByteArray()
        val wt = try { contentResolver.openOutputStream(uri, "wt") } catch (_: Exception) { null }
        if (wt != null) { wt.use { it.write(bytes) }; return }
        // "wt" (skriv och töm filen) stöds inte överallt. Med "w" töms inte alltid filen, så att gammal text
        // kan ligga kvar i slutet och göra backupen trasig. Töm den själva och kontrollera resultatet.
        val ok = try {
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                // Strömmen stängs inte här; filen stängs när pfd stängs.
                val fos = FileOutputStream(pfd.fileDescriptor)
                fos.channel.truncate(0); fos.channel.position(0); fos.write(bytes); fos.flush()
            } != null
        } catch (_: Exception) { false }
        if (!ok) (contentResolver.openOutputStream(uri, "w") ?: throw Exception("Kan inte skriva filen")).use { it.write(bytes) }
        val back = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (back != null && !back.contentEquals(bytes)) throw Exception("Filen blev inte rätt sparad. Välj en ny plats för backupen.")
    }
    private fun nameOf(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null } ?: "backupfilen"

    private fun keep(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("backupUri", uri.toString()).apply(); true
    } catch (_: Exception) { false }
    // Filnamnet sparas så att App.backupName() kan svara direkt utan att fråga Drive (som kan vara långsamt).
    private fun rememberName(uri: Uri): String = nameOf(uri).also { prefs.edit().putString("backupName", it).apply() }

    private fun pickSave(json: String, id: String) {
        pendingSaveJson = json; pendingSaveId = id
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "skivbacken-backup.json")
        try { startActivityForResult(intent, REQ_SAVE) } catch (e: Exception) { reply(id, false, "Kunde inte öppna filväljaren") }
    }
    private fun pickOpen(id: String) {
        pendingOpenId = id
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(intent, REQ_OPEN) } catch (e: Exception) { reply(id, false, "Kunde inte öppna filväljaren") }
    }
    private fun onBackupResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode == REQ_SAVE) {
            val id = pendingSaveId
            if (id == null) {
                // Appen startades om medan filväljaren var öppen: kom ihåg filen och låt webbappen spara igen.
                if (resultCode == RESULT_OK && uri != null && keep(uri)) setPending("save", uri.toString())
                return
            }
            val json = pendingSaveJson ?: ""
            pendingSaveId = null; pendingSaveJson = null
            if (resultCode != RESULT_OK || uri == null) { reply(id, false, "cancel"); return }
            keep(uri)
            worker.execute {
                try { writeTo(uri, json); reply(id, true, rememberName(uri)) }
                catch (e: Exception) { reply(id, false, "Kunde inte spara: ${e.message}") }
            }
        } else {
            val id = pendingOpenId
            if (id == null) {
                if (resultCode == RESULT_OK && uri != null) { keep(uri); setPending("load", uri.toString()) }
                return
            }
            pendingOpenId = null
            if (resultCode != RESULT_OK || uri == null) { reply(id, false, "cancel"); return }
            keep(uri)   // fortsatta backuper sparas i samma fil
            worker.execute { rememberName(uri) }
            worker.execute {
                try {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    reply(id, true, text)
                } catch (e: Exception) { reply(id, false, "Kunde inte läsa filen: ${e.message}") }
            }
        }
    }

    inner class Bridge {
        // Bara webbappen får använda bryggan, inte andra sidor som visas i appen (t.ex. Spotifys inloggning).
        private fun trusted() = pageHost == HOST

        @JavascriptInterface fun version(): String = if (trusted()) "5" else "0"

        /** mode: "auto" = bara om en fil redan är vald, "save" = samma fil som förut (annars välj), "pick" = välj ny plats. */
        @JavascriptInterface fun saveBackup(json: String, id: String, mode: String) {
            if (!trusted()) return
            val saved = prefs.getString("backupUri", null)
            if (mode != "pick" && saved != null) {
                worker.execute {
                    val uri = Uri.parse(saved)
                    try { writeTo(uri, json); reply(id, true, rememberName(uri)) }
                    catch (e: Exception) {
                        if (mode == "auto") reply(id, false, "nouri") else runOnUiThread { pickSave(json, id) }
                    }
                }
            } else if (mode == "auto") reply(id, false, "nouri")
            else runOnUiThread { pickSave(json, id) }
        }
        @JavascriptInterface fun loadBackup(id: String) { if (trusted()) runOnUiThread { pickOpen(id) } }
        @JavascriptInterface fun backupName(): String =
            if (!trusted() || prefs.getString("backupUri", null) == null) "" else prefs.getString("backupName", null) ?: "backupfilen"

        /** Resultat som blev kvar efter att Android stängt appen. Svarar med JSON {type, data}. */
        @JavascriptInterface fun takePending(id: String) {
            if (!trusted()) return
            val type = prefs.getString("pendingType", null)
            val uriStr = prefs.getString("pendingUri", null)
            prefs.edit().remove("pendingType").remove("pendingUri").apply()
            if (type == null || uriStr == null) { reply(id, true, "{}"); return }
            worker.execute {
                val res = JSONObject().put("type", type)
                try {
                    val uri = Uri.parse(uriStr)
                    when (type) {
                        "photo" -> res.put("data", photoB64(uri))
                        "load" -> res.put("data", contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: "")
                        else -> res.put("data", "")
                    }
                } catch (e: Exception) {
                    res.put("type", "error").put("data", "Kunde inte läsa ${if (type == "photo") "fotot" else "filen"}: ${e.message}")
                }
                reply(id, true, res.toString())
            }
        }

        /** Googles streckkodsskanner. Svarar med siffrorna, "cancel" eller ett fel. */
        @JavascriptInterface fun scanBarcode(id: String) { if (trusted()) runOnUiThread {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this@MainActivity, options).startScan()
                .addOnSuccessListener { reply(id, true, it.rawValue ?: "") }
                .addOnCanceledListener { reply(id, false, "cancel") }
                .addOnFailureListener { e -> reply(id, false, "fail:" + (e.message ?: "")) }
        } }
        @JavascriptInterface fun scan(b64: String, id: String) {
            if (!trusted()) return
            worker.execute {
                try { reply(id, true, this@MainActivity.scan(b64)) }
                catch (e: Exception) { reply(id, false, "Kunde inte läsa av bilden: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        /** Öppnar telefonens dela-meny med en text (v5). */
        @JavascriptInterface fun share(text: String, title: String) {
            if (!trusted()) return
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
                try { startActivity(Intent.createChooser(send, title)) } catch (_: Exception) {}
            }
        }
        // Bara http(s)- och spotify-länkar öppnas utanför appen.
        @JavascriptInterface fun openExternal(url: String) {
            if (!trusted()) return
            val uri = Uri.parse(url)
            if (uri.scheme !in setOf("https", "http", "spotify")) return
            runOnUiThread { this@MainActivity.openExternal(uri) }
        }
    }
}
