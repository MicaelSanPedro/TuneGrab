package com.tunegrab.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tunegrab.app.databinding.ActivityPlayerBinding
import com.tunegrab.app.playback.PlaybackService
import java.util.Locale

/**
 * Player embutido do TuneGrab:
 *  - ÁUDIO (MP3/M4A/Opus): a reprodução vive no PlaybackService (foreground
 *    service com notificação de mídia) — a música CONTINUA tocando quando o
 *    app vai para o fundo ou a tela apaga. Esta activity só é a interface.
 *  - VÍDEO (MP4): VideoView com controles do sistema + botão de TELA CHEIA
 *    que GIRA O APP (activity) para paisagem, com barras do sistema ocultas.
 *
 * O arquivo é o mesmo que está na aba Músicas — o player não move nem apaga nada.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var isVideo = false
    private var userSeeking = false

    // faixa inicial — permite retomar pelo botão play se o serviço foi fechado
    private var initialUri: Uri? = null
    private var initialTitle: String = ""

    // serviço de reprodução (áudio)
    private var playback: PlaybackService? = null
    private var bound = false

    // vídeo em tela cheia (gira o app, não o vídeo)
    private var fullscreen = false

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updatePosition()
            tickHandler.postDelayed(this, 500)
        }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playback = (service as? PlaybackService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        if (isVideo) {
            setupVideo(uri)
        } else {
            setupAudio(uri, title)
        }
    }

    // ---------- vídeo ----------

    private fun setupVideo(uri: Uri) {
        // não sobrepor a música em segundo plano
        PlaybackService.stopNow(this)

        binding.videoView.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.btnFullscreen.setOnClickListener { setFullscreen(!fullscreen) }

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

    /**
     * Tela cheia do VÍDEO: gira o APP (a activity), não o vídeo —
     * SCREEN_ORIENTATION_SENSOR_LANDSCAPE aceita os dois lados horizontais.
     * O VideoView mantém a proporção (nunca estica). Barras do sistema
     * somem no modo tela cheia e voltam ao sair.
     */
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        applySystemBars()
        binding.btnFullscreen.setImageResource(
            if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        )
        binding.btnFullscreen.contentDescription = getString(
            if (on) R.string.player_exit_fullscreen else R.string.player_fullscreen
        )
    }

    private fun applySystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // rotação sem recriar (configChanges no manifest): reaplica imersão
        if (isVideo) applySystemBars()
    }

    // ---------- áudio (via PlaybackService — segundo plano) ----------

    private fun setupAudio(uri: Uri, title: String) {
        binding.audioControls.visibility = View.VISIBLE
        initialUri = uri
        initialTitle = title

        // inicia/retoma o serviço e conecta a UI a ele
        PlaybackService.play(this, uri, title)
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConn,
            Context.BIND_AUTO_CREATE
        )
        bound = true

        binding.btnPlay.setOnClickListener {
            val svc = playback
            if (svc != null) {
                svc.toggle()
            } else {
                // serviço foi fechado pela notificação: retoma a faixa
                val u = initialUri ?: return@setOnClickListener
                PlaybackService.play(this, u, initialTitle)
            }
            binding.btnPlay.postDelayed({ updatePosition() }, 100)
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
                playback?.seekTo(sb?.progress ?: 0)
            }
        })

        tickHandler.post(tick)
    }

    private fun updatePosition() {
        if (isVideo) return
        val svc = playback ?: return
        val dur = svc.duration()
        if (dur > 0 && binding.seekBar.max != dur) {
            binding.seekBar.max = dur
            binding.tvDuration.text = formatMs(dur)
        }
        if (!userSeeking) {
            val pos = svc.position()
            binding.seekBar.progress = pos
            binding.tvPosition.text = formatMs(pos)
        }
        val playing = svc.isPlaying()
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        if (binding.btnPlay.tag != icon) {
            binding.btnPlay.tag = icon
            binding.btnPlay.setImageResource(icon)
            binding.btnPlay.contentDescription = getString(
                if (playing) R.string.player_pause else R.string.player_play
            )
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
        // ÁUDIO: NÃO pausa mais — o PlaybackService segue em 2º plano com a
        // notificação de mídia (é o propósito desta versão).
        // VÍDEO: pausa ao sair do app (comportamento de player de vídeo).
        binding.videoView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tick)
        if (bound) {
            try {
                unbindService(serviceConn)
            } catch (ignored: IllegalArgumentException) {
            }
            bound = false
        }
        // sem stopService: a música continua tocando em segundo plano
        playback = null
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_VIDEO = "is_video"
    }
}
