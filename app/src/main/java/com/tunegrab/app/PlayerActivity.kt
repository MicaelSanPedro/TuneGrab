package com.tunegrab.app

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tunegrab.app.databinding.ActivityPlayerBinding
import java.util.Locale

/**
 * Player embutido do TuneGrab:
 *  - ÁUDIO (MP3/M4A/Opus): capa com a identidade do app, título, barra de
 *    progresso arrastável, play/pause e tempo — MediaPlayer nativo.
 *  - VÍDEO (MP4): VideoView com os controles padrão do sistema.
 *
 * O arquivo é o mesmo que está na aba Músicas — o player não move nem apaga nada.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var mediaPlayer: MediaPlayer? = null
    private var isVideo = false
    private var userSeeking = false

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            tickHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uri: Uri = intent.data ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        binding.tvTitle.text = title

        binding.btnClose.setOnClickListener { finish() }

        if (isVideo) {
            setupVideo(uri)
        } else {
            setupAudio(uri)
        }
    }

    // ---------- vídeo ----------

    private fun setupVideo(uri: Uri) {
        binding.videoView.visibility = View.VISIBLE
        val controller = MediaController(this)
        controller.setAnchorView(binding.videoView)
        binding.videoView.setMediaController(controller)
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            binding.videoView.start()
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.player_err, Toast.LENGTH_SHORT).show()
            finish()
            true
        }
    }

    // ---------- áudio ----------

    private fun setupAudio(uri: Uri) {
        binding.audioControls.visibility = View.VISIBLE

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener { mp ->
                binding.seekBar.max = mp.duration
                binding.tvDuration.text = formatMs(mp.duration)
                updatePosition()
                mp.start()
                binding.btnPlay.setImageResource(R.drawable.ic_pause)
                binding.btnPlay.contentDescription = getString(R.string.player_pause)
            }
            player.setOnCompletionListener {
                binding.btnPlay.setImageResource(R.drawable.ic_play)
                binding.btnPlay.contentDescription = getString(R.string.player_play)
                binding.seekBar.progress = binding.seekBar.max
            }
            player.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, R.string.player_err, Toast.LENGTH_SHORT).show()
                finish()
                true
            }
            player.prepareAsync()
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.player_err, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnPlay.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    binding.btnPlay.setImageResource(R.drawable.ic_play)
                    binding.btnPlay.contentDescription = getString(R.string.player_play)
                } else {
                    mp.start()
                    binding.btnPlay.setImageResource(R.drawable.ic_pause)
                    binding.btnPlay.contentDescription = getString(R.string.player_pause)
                }
            } catch (ignored: IllegalStateException) {
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvPosition.text = formatMs(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val mp = mediaPlayer ?: return
                try {
                    mp.seekTo(sb?.progress ?: 0)
                } catch (ignored: IllegalStateException) {
                }
            }
        })

        tickHandler.post(tick)
    }

    private fun updatePosition() {
        val mp = mediaPlayer ?: return
        if (userSeeking) return
        try {
            binding.seekBar.progress = mp.currentPosition
            binding.tvPosition.text = formatMs(mp.currentPosition)
        } catch (ignored: IllegalStateException) {
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    override fun onStop() {
        super.onStop()
        // saiu do app → pausa (não segura áudio em segundo plano por decisão simples;
        // o arquivo continua lá na aba Músicas para ouvir de novo)
        try {
            mediaPlayer?.pause()
        } catch (ignored: IllegalStateException) {
        }
        binding.videoView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tick)
        try {
            mediaPlayer?.release()
        } catch (ignored: Throwable) {
        }
        mediaPlayer = null
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_VIDEO = "is_video"
    }
}
