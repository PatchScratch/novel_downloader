package com.ayati.noveldownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var btnPaste: Button
    private lateinit var btnClear: Button
    private lateinit var siteBadge: TextView
    private lateinit var btnMain: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusLine: TextView
    private lateinit var doneCard: View
    private lateinit var doneFile: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnShare: Button
    private lateinit var logToggle: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView

    private val detectExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var pythonReady = false
    private var detectedUrl: String? = null
    private var pendingStart = false

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        if (pendingStart) { pendingStart = false; startDownload() }
    }

    private val writePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (pendingStart) { pendingStart = false; maybeRequestNotifThenStart() }
        } else {
            pendingStart = false
            Toast.makeText(this, getString(R.string.toast_no_write),
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url_input)
        btnPaste = findViewById(R.id.btn_paste)
        btnClear = findViewById(R.id.btn_clear)
        siteBadge = findViewById(R.id.site_badge)
        btnMain = findViewById(R.id.btn_main)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        statusLine = findViewById(R.id.status_line)
        doneCard = findViewById(R.id.done_card)
        doneFile = findViewById(R.id.done_file)
        btnOpen = findViewById(R.id.btn_open)
        btnShare = findViewById(R.id.btn_share)
        logToggle = findViewById(R.id.log_toggle)
        logScroll = findViewById(R.id.log_scroll)
        logView = findViewById(R.id.log_view)

        statusLine.text = getString(R.string.python_init)
        thread {
            PyBridge.ensureStarted(applicationContext)
            pythonReady = true
            runOnUiThread {
                if (!DownloadState.ui.value.isRunning) statusLine.text = ""
                onUrlChanged()
            }
        }

        urlInput.doAfterTextChanged { onUrlChanged() }

        btnPaste.setOnClickListener {
            val clip = getSystemService(ClipboardManager::class.java)
                .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            val url = Regex("""https?://\\S+""").find(clip)?.value
            if (url == null) {
                Toast.makeText(this, getString(R.string.toast_no_url), Toast.LENGTH_SHORT).show()
            } else {
                urlInput.setText(url)
            }
        }

        btnClear.setOnClickListener { urlInput.setText("") }

        btnMain.setOnClickListener {
            if (DownloadState.ui.value.isRunning) {
                startService(Intent(this, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_CANCEL))
                btnMain.isEnabled = false
            } else {
                pendingStart = true
                maybeRequestWriteThenStart()
            }
        }

        logToggle.setOnClickListener {
            val open = logScroll.visibility == View.VISIBLE
            logScroll.visibility = if (open) View.GONE else View.VISIBLE
            logToggle.text = getString(if (open) R.string.log_closed else R.string.log_open)
        }

        btnOpen.setOnClickListener { firstSavedFile()?.let { openFile(it) } }
        btnShare.setOnClickListener { firstSavedFile()?.let { shareFile(it) } }

        lifecycleScope.launch {
            DownloadState.ui.collect { render(it) }
        }
        lifecycleScope.launch {
            DownloadState.logLines.collect { lines ->
                logView.text = lines.joinToString("\\n")
                if (logScroll.visibility == View.VISIBLE) {
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://\\S+""").find(text)?.value ?: return
        urlInput.setText(url)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == R.id.action_settings) {
            showSettingsDialog()
            true
        } else {
            super.onOptionsItemSelected(item)
        }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val keys = arrayOf("horizontal", "kobo", "use_site_cover", "save_txt")
        val labels = arrayOf(
            getString(R.string.setting_horizontal),
            getString(R.string.setting_kobo),
            getString(R.string.setting_site_cover),
            getString(R.string.setting_save_txt),
        )
        val checked = BooleanArray(keys.size) { prefs.getBoolean(keys[it], false) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                prefs.edit().putBoolean(keys[which], isChecked).apply()
            }
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun firstSavedFile(): DownloadState.SavedFile? =
        DownloadState.ui.value.savedFiles.firstOrNull()

    private fun openFile(file: DownloadState.SavedFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(file.uri), file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.toast_no_reader),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun shareFile(file: DownloadState.SavedFile) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(file.mime)
            .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse(file.uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, file.name))
    }

    private fun onUrlChanged() {
        val text = urlInput.text.toString().trim()
        detectedUrl = null
        if (!pythonReady || text.isEmpty()) {
            siteBadge.visibility = View.GONE
            updateMainButton()
            return
        }
        detectExecutor.submit {
            val json = try {
                JSONObject(PyBridge.module.callAttr("detect", text).toString())
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (text != urlInput.text.toString().trim()) return@runOnUiThread
                renderBadge(text, json)
                updateMainButton()
            }
        }
    }

    private fun renderBadge(input: String, json: JSONObject?) {
        siteBadge.visibility = View.VISIBLE
        when {
            json == null ->
                siteBadge.text = getString(R.string.badge_error)
            json.optBoolean("needs_playwright") ->
                siteBadge.text = getString(R.string.badge_hameln)
            !json.isNull("site") -> {
                detectedUrl = json.optString("normalized_url", input).ifEmpty { input }
                siteBadge.text = "◉ ${json.optString(\"display_name\")}"
            }
            Regex("""^https?://\\S+$""").matches(input) &&
                    !input.contains("syosetu.org") -> {
                detectedUrl = input
                siteBadge.text = getString(R.string.badge_short)
            }
            else ->
                siteBadge.text = getString(R.string.badge_unsupported)
        }
    }

    private fun updateMainButton() {
        val ui = DownloadState.ui.value
        btnMain.isEnabled = ui.isRunning || (pythonReady && detectedUrl != null)
    }

    private fun maybeRequestWriteThenStart() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            maybeRequestNotifThenStart()
        }
    }

    private fun maybeRequestNotifThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pendingStart = false
            startDownload()
        }
    }

    private fun startDownload() {
        val url = detectedUrl ?: return
        val intent = Intent(this, DownloadService::class.java)
            .putExtra(DownloadService.EXTRA_URL, url)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(ui: DownloadState.Ui) {
        updateMainButton()
        btnMain.text = getString(if (ui.isRunning) R.string.cancel else R.string.download)

        val done = ui.phase == DownloadState.Phase.DONE && ui.savedFiles.isNotEmpty()
        doneCard.visibility = if (done) View.VISIBLE else View.GONE
        if (done) {
            doneFile.text = ui.savedFiles.joinToString("\\n") { it.name }
        }

        when (ui.phase) {
            DownloadState.Phase.IDLE -> {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
            }
            DownloadState.Phase.PREPARING, DownloadState.Phase.SAVING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressText.visibility = View.GONE
                statusLine.text = ui.statusLine
            }
            DownloadState.Phase.DOWNLOADING -> {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.max = ui.total.coerceAtLeast(1)
                progressBar.progress = ui.n
                progressText.visibility = View.VISIBLE
                progressText.text = getString(R.string.progress_chapters, ui.n, ui.total)
                statusLine.text = ui.statusLine
            }
            DownloadState.Phase.DONE, DownloadState.Phase.CANCELLED -> {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                statusLine.text = ui.statusLine
            }
            DownloadState.Phase.ERROR -> {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
                statusLine.text = ui.statusLine
                logScroll.visibility = View.VISIBLE
                logToggle.text = getString(R.string.log_open)
            }
        }
    }
}
