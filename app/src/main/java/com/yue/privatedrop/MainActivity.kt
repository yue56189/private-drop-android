package com.yue.privatedrop

import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var etNick: EditText
    private lateinit var etInput: EditText
    private lateinit var llMessages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tvQuote: TextView
    private lateinit var btnConnect: Button
    private lateinit var loginRoot: View
    private lateinit var chatRoot: View
    private lateinit var progressRow: View
    private lateinit var pb: ProgressBar
    private lateinit var tvProgress: TextView

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var connected = false
    private val pid = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun base() = etServer.text.toString().trim().trimEnd('/')
    private fun token() = etToken.text.toString().trim()
    private fun nick() = etNick.text.toString().trim()
    private fun api(path: String) = "${base()}$path${if (path.contains('?')) '&' else '?'}t=${token()}"

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && connected) upload(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServer = findViewById(R.id.etServer)
        etToken = findViewById(R.id.etToken)
        etNick = findViewById(R.id.etNick)
        etInput = findViewById(R.id.etInput)
        llMessages = findViewById(R.id.llMessages)
        scroll = findViewById(R.id.scroll)
        tvQuote = findViewById(R.id.tvQuote)
        btnConnect = findViewById(R.id.btnConnect)
        loginRoot = findViewById(R.id.loginRoot)
        chatRoot = findViewById(R.id.chatRoot)
        progressRow = findViewById(R.id.progressRow)
        pb = findViewById(R.id.pb)
        tvProgress = findViewById(R.id.tvProgress)

        val prefs = getSharedPreferences("privatedrop", MODE_PRIVATE)
        etServer.setText(prefs.getString("server", "http://8.141.122.46:8765"))
        etToken.setText(prefs.getString("token", ""))
        etNick.setText(prefs.getString("nick", ""))

        btnConnect.setOnClickListener { connect() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btnFile).setOnClickListener {
            if (!connected) toast("先连接服务器") else filePicker.launch("*/*")
        }
    }

    private fun savePrefs() {
        getSharedPreferences("privatedrop", MODE_PRIVATE).edit()
            .putString("server", base()).putString("token", token()).putString("nick", nick())
            .apply()
    }

    private fun connect() {
        if (nick().isEmpty()) { toast("先填昵称"); return }
        connected = false
        llMessages.removeAllViews()
        btnConnect.isEnabled = false
        toast("连接中…")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val hist = http.newCall(Request.Builder().url(api("/api/history")).build())
                    .execute().use { resp ->
                        if (resp.code == 401) throw IOException("口令错误")
                        resp.body!!.string()
                    }
                savePrefs()
                val messages = JSONObject(hist).getJSONArray("messages")
                for (i in 0 until messages.length()) {
                    val m = messages.getJSONObject(i)
                    runOnUiThread { render(m) }
                }
                runOnUiThread {
                    connected = true
                    btnConnect.isEnabled = true
                    loginRoot.visibility = View.GONE
                    chatRoot.visibility = View.VISIBLE
                    toast("已连接")
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                loadQuote()

                while (connected) {
                    try {
                        http.newCall(Request.Builder()
                            .url(api("/api/stream?nick=${enc(nick())}&pid=$pid"))
                            .header("Accept", "text/event-stream")
                            .build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("SSE ${resp.code}")
                            val src = resp.body!!.source()
                            while (true) {
                                val line = src.readUtf8Line() ?: break
                                if (line.startsWith("data: ")) {
                                    val ev = JSONObject(line.substring(6))
                                    if (ev.optString("type") == "message") {
                                        val m = ev.getJSONObject("message")
                                        runOnUiThread { render(m) }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!connected) break
                    }
                    if (!connected) break
                    Thread.sleep(3000)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connected = false
                    btnConnect.isEnabled = true
                    toast("连接失败：${e.message}")
                }
            }
        }
    }

    private fun loadQuote() {
        try {
            val q = JSONObject(http.newCall(Request.Builder().url(api("/api/quote")).build())
                .execute().body!!.string())
            runOnUiThread {
                tvQuote.text = "每日格言：${q.getString("text")} —— ${q.getString("from")}"
                tvQuote.visibility = View.VISIBLE
            }
        } catch (_: Exception) { }
    }

    // ---------- 传输进度 ----------
    private fun showProgress(label: String) {
        progressRow.visibility = View.VISIBLE
        pb.progress = 0
        tvProgress.text = label
    }

    private fun updateProgress(label: String, frac: Double) {
        runOnUiThread {
            progressRow.visibility = View.VISIBLE
            pb.progress = (frac * 100).toInt().coerceIn(0, 100)
            tvProgress.text = "$label ${ (frac * 100).toInt() }%"
        }
    }

    private fun hideProgress() = runOnUiThread { progressRow.visibility = View.GONE }

    /** 带进度回调的 RequestBody。 */
    private class ProgressRequestBody(
        private val file: File,
        private val onProgress: (Double) -> Unit,
    ) : RequestBody() {
        private val media = "application/octet-stream".toMediaType()

        override fun contentType() = media
        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = file.length()
            var sent = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    if (total > 0) onProgress(sent.toDouble() / total)
                }
            }
        }
    }

    private fun render(m: JSONObject) {
        val kind = m.optString("kind")
        val nick = m.optString("nick")
        val mine = nick == nick()
        when (kind) {
            "system" -> addCenter(m.optString("text"))
            "file" -> {
                val f = m.optJSONObject("file") ?: return
                addFileCard(nick, f.optString("id"), f.optString("name"),
                    f.optLong("size"), mine, m.optLong("ts"))
            }
            else -> addBubble(nick, m.optString("text"), mine, m.optLong("ts"))
        }
    }

    private fun timeStr(ts: Long): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(ts * 1000))

    private fun nickLabel(nick: String): TextView = TextView(this).apply {
        text = nick; textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
        setPadding(dp(20), dp(4), 0, dp(2))
    }

    private fun addBubble(nick: String, text: String, mine: Boolean, ts: Long) {
        val tv = TextView(this).apply {
            textSize = 15f
            setText("$text   ${timeStr(ts)}")
            setTextColor(ContextCompat.getColor(context,
                if (mine) R.color.on_primary_container else R.color.on_surface))
            background = ContextCompat.getDrawable(context,
                if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = dp(280)
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!mine) wrap.addView(nickLabel(nick))
        wrap.addView(tv)
        addRow(wrap, mine)
    }

    private fun addFileCard(nick: String, fid: String, name: String, size: Long, mine: Boolean, ts: Long) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tvName = TextView(this).apply {
            text = name; textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            maxEms = 18; ellipsize = TextUtils.TruncateAt.END; maxLines = 1
            setTextColor(ContextCompat.getColor(context, R.color.on_surface))
        }
        val tvMeta = TextView(this).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            text = "${fmtSize(size)} · ${timeStr(ts)} · 点击保存"
        }
        col.addView(tvName); col.addView(tvMeta)
        col.background = ContextCompat.getDrawable(this, R.drawable.bg_file_card)
        col.setPadding(dp(12), dp(8), dp(12), dp(8))
        col.setOnClickListener { download(fid, name) }

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!mine) wrap.addView(nickLabel(nick))
        wrap.addView(col)
        addRow(wrap, mine)
    }

    private fun addCenter(text: String) {
        val tv = TextView(this).apply {
            setText(text); textSize = 11f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
        }
        addRow(tv, centered = true)
    }

    private fun addRow(v: View, mine: Boolean = false, centered: Boolean = false) {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        when {
            centered -> { lp.gravity = Gravity.CENTER_HORIZONTAL; lp.setMargins(0, dp(4), 0, dp(4)) }
            mine -> { lp.gravity = Gravity.END; lp.setMargins(dp(48), dp(2), dp(4), dp(2)) }
            else -> { lp.gravity = Gravity.START; lp.setMargins(dp(4), dp(2), dp(48), dp(2)) }
        }
        llMessages.addView(v, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun sendText() {
        val t = etInput.text.toString().trim()
        if (t.isEmpty() || !connected) return
        etInput.setText("")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(api("/api/send"))
                    .post(JSONObject().put("nick", nick()).put("text", t).toString()
                        .toRequestBody("application/json".toMediaType()))
                    .build()).execute().close()
            } catch (e: Exception) {
                runOnUiThread { toast("发送失败：${e.message}") }
            }
        }
    }

    private fun upload(uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            var cache: File? = null
            try {
                val tmp = File(cacheDir, "upload.bin")
                cache = tmp
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val name = queryName(uri)
                val short = if (name.length > 14) name.take(14) + "…" else name
                http.newCall(Request.Builder()
                    .url(api("/api/upload?nick=${enc(nick())}&name=${enc(name)}"))
                    .post(ProgressRequestBody(tmp) { updateProgress("↑ $short", it) })
                    .build()).execute().close()
                tmp.delete()
                cache = null
                hideProgress()
                runOnUiThread { toast("上传完成") }
            } catch (e: Exception) {
                cache?.delete()
                hideProgress()
                runOnUiThread { toast("上传失败：${e.message}") }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return "file"
    }

    private fun download(fid: String, name: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val short = if (name.length > 14) name.take(14) + "…" else name
                http.newCall(Request.Builder().url(api("/api/file/$fid")).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val total = resp.body!!.contentLength()
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("无法写入下载目录")
                        contentResolver.openOutputStream(uri)!!.use { out ->
                            val src = resp.body!!.byteStream()
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (total > 0) updateProgress("↓ $short", got.toDouble() / total)
                            }
                        }
                    }
                hideProgress()
                runOnUiThread { toast("已保存到下载：$name") }
            } catch (e: Exception) {
                hideProgress()
                runOnUiThread { toast("下载失败：${e.message}") }
            }
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1 shl 20 -> "%.1f KB".format(n / 1024.0)
        n < 1 shl 30 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
