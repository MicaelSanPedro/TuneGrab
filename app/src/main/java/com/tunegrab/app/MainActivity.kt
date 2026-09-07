package com.tunegrab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.chip.Chip
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var info: StreamInfo? = null
    private var pendingDownload = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (pendingDownload) {
                pendingDownload = false
                startDownload()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        YtExtractor.init()

        binding.btnSearch.setOnClickListener { onSearch() }
        binding.btnDownload.setOnClickListener { onDownloadClicked() }
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
        onSearch()
    }

    private fun onSearch() {
        val raw = binding.inputUrl.text?.toString().orEmpty()
        val url = Regex("https?://\\S+").find(raw)
            ?.value
            ?.trim('"', '\'', ')', '>', '<')
        if (url.isNullOrBlank()) {
            setStatus(getString(R.string.err_invalid_url))
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.btnSearch.isEnabled = false
        binding.btnDownload.isEnabled = false
        binding.cardInfo.visibility = View.GONE
        setStatus(getString(R.string.status_fetching))

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { YtExtractor.fetch(url) }
                val options = withContext(Dispatchers.IO) { YtExtractor.audioOptions(result) }
                showInfo(result, options)
            } catch (e: Exception) {
                setStatus(getString(R.string.err_generic, friendlyError(e)))
            } finally {
                binding.progress.visibility = View.GONE
                binding.btnSearch.isEnabled = true
            }
        }
    }

    private fun showInfo(si: StreamInfo, options: List<AudioStream>) {
        info = si
        binding.cardInfo.visibility = View.VISIBLE
        binding.tvTitle.text = si.name
        binding.tvAuthor.text = getString(R.string.meta_line, si.uploaderName, formatDuration(si.duration))
        si.thumbnails.maxByOrNull { it.height }?.let {
            binding.thumb.load(it.url) { crossfade(true) }
        }

        binding.chipGroup.removeAllViews()
        if (options.isEmpty()) {
            binding.btnDownload.isEnabled = false
            setStatus(getString(R.string.err_no_audio))
            return
        }
        options.forEachIndexed { index, stream ->
            val chip = layoutInflater.inflate(R.layout.chip_audio, binding.chipGroup, false) as Chip
            chip.id = View.generateViewId()
            chip.text = chipLabel(stream)
            chip.isChecked = index == 0
            chip.tag = stream
            binding.chipGroup.addView(chip)
        }
        binding.btnDownload.isEnabled = true
        setStatus(getString(R.string.status_ready))
    }

    private fun chipLabel(stream: AudioStream): String {
        val format = stream.format?.name ?: "AUDIO"
        val kbps = stream.averageBitrate
        return if (kbps > 0) "$format · $kbps kbps" else format
    }

    private fun onDownloadClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = true
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = true
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        startDownload()
    }

    private fun startDownload() {
        val si = info ?: return
        val chip = binding.chipGroup.findViewById<Chip>(binding.chipGroup.checkedChipId)
        val stream = chip?.tag as? AudioStream
        if (stream == null) {
            Toast.makeText(this, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val suffix = stream.format?.suffix ?: "m4a"
        val mime = stream.format?.mimeType ?: "audio/mp4"
        val fileName = sanitize(si.name) + "." + suffix
        val streamUrl = stream.url
        if (streamUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = DownloadService.intent(this, si.name, streamUrl, fileName, mime)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        setStatus(getString(R.string.status_downloading))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "audio" }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ReCaptchaException -> getString(R.string.err_recaptcha)
        is ContentNotAvailableException -> getString(R.string.err_unavailable)
        is ParsingException, is IllegalArgumentException -> getString(R.string.err_invalid_url)
        else -> e.message ?: getString(R.string.err_generic_short)
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }
}
