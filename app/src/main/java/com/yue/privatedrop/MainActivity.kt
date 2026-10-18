package com.yue.privatedrop

import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Call
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
import java.util.concurrent.Executors
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
    private lateinit var btnPause: Button

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 传输串行化：单线程队列，进度行永远对应正在执行的任务。 */
    private val transferExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var connected = false
    @Volatile private var current: Transfer? = null
    private val pid = UUID.randomUUID().toString().replace("-", "").take(16)

    /** 一次上传/下载的状态：暂停/取消 + 速度统计。 */
    private class Transfer(val label: String) {
        @Volatile var paused = false
        @Volatile var cancelled = false
        var call: Call? = null
        private var lastB = 0L
        private var lastT = 0L

        /** 在阻塞循环里周期调用：暂停时挂起，取消时抛异常。 */
        fun gate() {
            while (paused && !cancelled) Thread.sleep(120)
            if (cancelled) throw IOException("已取消")
        }

        /** 计算瞬时速度（字节/秒），返回 null 表示间隔太短不用刷新。 */
        @Synchronized fun tick(bytes: Long): Double? {
            val now = System.currentTimeMillis()
            val dt = now - lastT
            if (lastT == 0L) { lastB = bytes; lastT = now; return null }
            if (dt < 300) return null
            val speed = (bytes - lastB) * 1000.0 / dt
            lastB = bytes; lastT = now
            return speed.coerceAtLeast(0.0)
        }
    }

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
        btnPause = findViewById(R.id.btnPause)

        // targetSdk 35 强制 edge-to-edge：
        // 底部避让导航栏/键盘；顶部由各界面自定（登录页图片全屏出血，聊天页避开状态栏）
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime: Insets = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            chatRoot.setPadding(0, bars.top, 0, 0)
            // 键盘弹出时消息滚动到底部
            if (ime.bottom > 0) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            // 状态栏图标明暗随界面切换：登录页（深色图）用浅图标，聊天页（浅底）用深图标
            val w = window
            WindowCompat.getInsetsController(w, w.decorView)
                .isAppearanceLightStatusBars = loginRoot.visibility != View.VISIBLE
            WindowInsetsCompat.CONSUMED
        }

        val prefs = getSharedPreferences("privatedrop", MODE_PRIVATE)
        etServer.setText(prefs.getString("server", "http://8.141.122.46:8765"))
        etToken.setText(prefs.getString("token", ""))
        etNick.setText(prefs.getString("nick", ""))

        btnConnect.setOnClickListener { connect() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendText() }
        findViewById<Button>(R.id.btnFile).setOnClickListener {
            if (!connected) toast("先连接服务器") else filePicker.launch("*/*")
        }
        btnPause.setOnClickListener {
            val t = current ?: return@setOnClickListener
            t.paused = !t.paused
            btnPause.text = if (t.paused) "继续" else "暂停"
        }
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            current?.let { t ->
                t.cancelled = true
                t.call?.cancel()
            }
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

    // ---------- 传输队列与进度 UI ----------
    private fun startTransfer(label: String, block: (Transfer) -> Unit) {
        val t = Transfer(label)
        current = t
        runOnUiThread {
            progressRow.visibility = View.VISIBLE
            pb.progress = 0
            tvProgress.text = label
            btnPause.text = "暂停"
        }
        transferExecutor.submit {
            try {
                block(t)
                runOnUiThread {
                    if (current === t) progressRow.visibility = View.GONE
                    toast(if (t.cancelled) "已取消" else "$label 完成")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (current === t) progressRow.visibility = View.GONE
                    toast("${label}失败：${e.message}")
                }
            }
        }
    }

    private fun updateUi(t: Transfer, label: String, frac: Double, bytes: Long) {
        val speed = t.tick(bytes)
        if (speed == null && frac < 1.0) return
        runOnUiThread {
            if (current !== t) return@runOnUiThread
            progressRow.visibility = View.VISIBLE
            pb.progress = (frac * 100).toInt().coerceIn(0, 100)
            tvProgress.text = if (frac < 1.0) "$label ${(frac * 100).toInt()}% · ${fmtSize(speed ?: 0.0)}/s"
                              else "$label 100%"
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
        val txtColor = ContextCompat.getColor(context,
            if (mine) R.color.on_primary_container else R.color.on_surface)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context,
                if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other)
            setPadding(dp(12), dp(7), dp(12), dp(6))
        }
        val tvMsg = TextView(this).apply {
            textSize = 15f
            setText(text)
            setTextColor(txtColor)
            maxWidth = dp(260)
        }
        val tvTime = TextView(this).apply {
            text = timeStr(ts); textSize = 10f; gravity = Gravity.END
            setTextColor(txtColor); alpha = 0.55f
        }
        col.addView(tvMsg); col.addView(tvTime)
        col.tag = text
        col.setOnLongClickListener { v ->
            showMsgMenu(v, v.tag as String)
            true
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!mine) wrap.addView(nickLabel(nick))
        wrap.addView(col)
        addRow(wrap, mine)
    }

    /** 长按文字消息：复制 / 引用。 */
    private fun showMsgMenu(v: View, text: String) {
        val pm = PopupMenu(this, v)
        pm.menu.add("复制")
        pm.menu.add("引用")
        pm.setOnMenuItemClickListener { item ->
            when (item.title) {
                "复制" -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("msg", text))
                    toast("已复制")
                }
                "引用" -> {
                    val quoted = text.lines().joinToString("\n") { "> $it" }
                    etInput.setText("$quoted\n")
                    etInput.setSelection(etInput.text.length)
                    etInput.requestFocus()
                }
            }
            true
        }
        pm.show()
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
        startTransfer("上传") { t ->
            val cache = File(cacheDir, "upload.bin")
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    cache.outputStream().use { input.copyTo(it) }
                }
                val name = queryName(uri)
                val req = Request.Builder()
                    .url(api("/api/upload?nick=${enc(nick())}&name=${enc(name)}"))
                    .post(ProgressRequestBody(cache) { sent, frac ->
                        t.gate()
                        updateUi(t, "↑ $name", frac, sent)
                    })
                    .build()
                val call = http.newCall(req)
                t.call = call
                call.execute().close()
            } finally {
                cache.delete()
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
        startTransfer("下载") { t ->
            val call = http.newCall(Request.Builder().url(api("/api/file/$fid")).build())
            t.call = call
            call.execute().use { resp ->
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
                        t.gate()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) updateUi(t, "↓ $name", got.toDouble() / total, got)
                    }
                }
            }
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun fmtSize(n: Double): String = when {
        n < 1024 -> "%.0f B".format(n)
        n < 1048576 -> "%.1f KB/s".format(n / 1024)
        n < 1073741824 -> "%.1f MB/s".format(n / 1048576)
        else -> "%.2f GB/s".format(n / 1073741824)
    }
    private fun fmtSize(n: Long): String = fmtSize(n.toDouble())

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** 分块写入 + 进度回调的请求体（阻塞式，配合 gate() 支持暂停/取消）。 */
private class ProgressRequestBody(
    private val file: File,
    private val onChunk: (sent: Long, frac: Double) -> Unit,
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
                onChunk(sent, if (total > 0) sent.toDouble() / total else 1.0)
            }
        }
    }
}
