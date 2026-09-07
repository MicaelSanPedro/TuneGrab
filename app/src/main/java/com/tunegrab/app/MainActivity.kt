package com.tunegrab.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tunegrab.app.databinding.ActivityMainBinding
import com.tunegrab.app.download.DownloadService
import com.tunegrab.app.yt.YtExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var busy = false

    /** Ação guardada enquanto o usuário responde o pedido de permissão. */
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = pendingAction
            pendingAction = null
            // prossegue mesmo se negar: o download funciona, só a notificação é afetada
            action?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDownload.setOnClickListener { onDownloadClicked() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.tilUrl.setEndIconOnClickListener { pasteFromClipboard() }
        binding.inputUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                onDownloadClicked(); true
            } else {
                false
            }
        }
        setStatus(getString(R.string.status_idle))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        binding.inputUrl.setText(shared)
        // link compartilhado do YouTube → abre o seletor de formato direto
        onDownloadClicked()
    }

    private fun extractUrl(): String? =
        Regex("https?://\\S+").find(binding.inputUrl.text?.toString().orEmpty())
            ?.value
            ?.trim('"', '\'', ')', '>', '<')

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputUrl.setText(text.trim())
    }

    /**
     * Fluxo principal: busca as informações do vídeo e abre o seletor
     * de formato (MP3 / M4A / MP4) e qualidade.
     */
    private fun onDownloadClicked() {
        if (busy) return
        val url = extractUrl()
        if (url.isNullOrBlank()) {
            setStatus(getString(R.string.err_invalid_url))
            return
        }
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        setStatus(getString(R.string.status_fetching))

        lifecycleScope.launch {
            try {
                val si = withContext(Dispatchers.IO) { YtExtractor.fetch(url) }
                val audio = withContext(Dispatchers.IO) { YtExtractor.audioOptions(si) }
                val video = withContext(Dispatchers.IO) { YtExtractor.videoOptions(si) }
                if (audio.isEmpty() && video.isEmpty()) {
                    setStatus(getString(R.string.err_no_audio))
                    return@launch
                }
                setStatus(getString(R.string.status_pick_format))
                FormatPickerSheet(this@MainActivity, si, audio, video) { request ->
                    ensurePermissionsThen { start(request) }
                }.show()
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao buscar vídeo", t)
                setStatus(getString(R.string.err_generic, friendlyError(t)))
            } finally {
                binding.progress.visibility = View.GONE
                setBusy(false)
            }
        }
    }

    private fun start(request: DownloadRequest) {
        when (request) {
            is DownloadRequest.Mp3 -> startMp3(request)
            is DownloadRequest.M4a -> startDirect(
                request.title,
                request.stream,
                suffix = request.stream.format?.suffix ?: "m4a",
                mime = request.stream.format?.mimeType ?: "audio/mp4"
            )
            is DownloadRequest.Mp4 -> startDirect(
                request.title,
                request.stream,
                suffix = "mp4",
                mime = request.stream.format?.mimeType ?: "video/mp4"
            )
        }
    }

    private fun startMp3(request: DownloadRequest.Mp3) {
        val url = request.source.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = sanitize(request.title) + ".mp3"
        val intent = DownloadService.mp3Intent(this, request.title, url, fileName, request.bitrateKbps)
        launchService(intent)
        setStatus(getString(R.string.status_converting, "${request.bitrateKbps}"))
    }

    private fun startDirect(title: String, stream: Any, suffix: String, mime: String) {
        val url = when (stream) {
            is AudioStream -> stream.url
            is VideoStream -> stream.url
            else -> null
        }
        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = sanitize(title) + "." + suffix
        val intent = DownloadService.intent(this, title, url, fileName, mime)
        launchService(intent)
        setStatus(getString(R.string.status_downloading))
    }

    private fun launchService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun ensurePermissionsThen(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) {
            pendingAction = action
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        action()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        binding.btnDownload.isEnabled = !b
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "audio" }

    private fun friendlyError(t: Throwable): String = when (t) {
        is ReCaptchaException -> getString(R.string.err_recaptcha)
        is ContentNotAvailableException -> getString(R.string.err_unavailable)
        is ParsingException, is IllegalArgumentException -> getString(R.string.err_invalid_url)
        else -> "${t.javaClass.simpleName}: ${t.message ?: getString(R.string.err_generic_short)}"
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}
