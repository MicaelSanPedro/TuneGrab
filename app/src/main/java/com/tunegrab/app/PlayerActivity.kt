package com.tunegrab.app

import android.media.MediaPlayer
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tunegrab.app.databinding.ActivityPlayerBinding
import com.tunegrab.app.playback.PlaybackService
import com.tunegrab.app.yt.DownloaderImpl
import java.util.Locale

/**
 * Player embutido do TuneGrab:
 *  - ÁUDIO (MP3/M4A/Opus): a reprodução vive no PlaybackService (foreground
 *    service com notificação de mídia) — a música CONTINUA tocando quando o
 *    app vai para o fundo ou a tela apaga. Esta activity só é a interface.
 *  - VÍDEO (MP4/MKV): VideoView com controles do sistema + botão de TELA
 *    CHEIA que GIRA O APP (activity) para paisagem, com barras ocultas. O
 *    vídeo é exibido por FitVideoView, que mantém o aspect ratio real em
 *    qualquer tela (o VideoView padrão esmaga o vídeo na tela cheia). O
 *    MediaPlayer lê Matroska/VP9 nativamente — 4K baixado toca aqui igual
 *    toca no Google Fotos.
 *
 * O arquivo é o mesmo que está na aba Biblioteca — o player não move nem apaga nada.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private var isVideo = false
    private var userSeeking = false

    // stream da REDE (reproduzir sem baixar): mostra spinner de carregamento
    // e fala com o googlevideo com o mesmo User-Agent da extração
    private var isRemote = false

    // faixa inicial — permite retomar pelo botão play se o serviço foi fechado
    private var initialUri: Uri? = null
    private var initialTitle: String = ""

    // serviço de reprodução (áudio)
    private var playback: PlaybackService? = null
    private var bound = false

    // player HD (v0.17.0): ExoPlayer só no vídeo REMOTO (DASH video+áudio);
    // morre junto com a activity — vídeo local e áudio seguem nos caminhos antigos
    private var exoPlayer: ExoPlayer? = null

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

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        when {
            // HD (v0.17.0): DASH do YouTube — vídeo até 1080p + áudio separados,
            // MERGIDOS no ExoPlayer (o MediaPlayer não junta duas faixas)
            isVideo && videoUrl != null && audioUrl != null -> setupExoVideo(videoUrl, audioUrl)
            isVideo -> setupVideo(uri)
            else -> setupAudio(uri, title)
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

        // stream do YouTube (reproduzir sem baixar): alguns servidores do
        // googlevideo recusam User-Agent estranho — usa o mesmo da extração
        isRemote = uri.scheme == "http" || uri.scheme == "https"
        if (isRemote) {
            showBuffering(true)
            binding.videoView.setVideoURI(
                uri,
                mapOf("User-Agent" to DownloaderImpl.USER_AGENT)
            )
        } else {
            binding.videoView.setVideoURI(uri)
        }

        binding.videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            // proporção real do arquivo (lida do MediaPlayer) → o FitVideoView
            // usa para nunca deformar o vídeo na tela cheia
            if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                binding.videoView.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight())
            }
            showBuffering(false)
            // pausa de rede no meio do vídeo (stream remoto) volta a girar o spinner
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> showBuffering(true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> showBuffering(false)
                }
                true
            }
            binding.videoView.start()
        }
        binding.videoView.setOnErrorListener { _, _, _ ->
            showBuffering(false)
            Toast.makeText(
                this,
                if (isRemote) R.string.player_err_stream else R.string.player_err,
                Toast.LENGTH_SHORT
            ).show()
            finish()
            true
        }
    }

    /**
     * VÍDEO HD (v0.17.0): stream DASH do YouTube — faixa de vídeo (até 1080p,
     * SEM áudio) + faixa de áudio separadas, tocando em sincronia via
     * MergingMediaSource. LÊ direto da rede, sem salvar NADA. O botão de tela
     * cheia continua girando o APP (mesma setFullscreen do vídeo local).
     */
    private fun setupExoVideo(videoUrl: String, audioUrl: String) {
        // não sobrepor a música em segundo plano
        PlaybackService.stopNow(this)

        binding.playerView.visibility = View.VISIBLE
        binding.btnFullscreen.visibility = View.VISIBLE
        binding.btnFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        // o botão de tela cheia do próprio controller também gira o app
        binding.playerView.setFullscreenButtonClickListener { setFullscreen(!fullscreen) }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DownloaderImpl.USER_AGENT) // googlevideo recusa UA estranho
            .setAllowCrossProtocolRedirects(true)    // googlevideo redireciona http<->https
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val dataSource = DefaultDataSource.Factory(this, http)
        val videoSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(videoUrl))
        val audioSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(audioUrl))

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaSource(MergingMediaSource(videoSource, audioSource))
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        R.string.player_err_stream,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            })
            playWhenReady = true
            prepare()
        }
        binding.playerView.player = exoPlayer
    }

    /** Spinner "carregando…" centralizado (só existe no stream remoto). */
    private fun showBuffering(on: Boolean) {
        binding.progressBuffer.visibility = if (on) View.VISIBLE else View.GONE
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
        exoPlayer?.pause()
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
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_AUDIO_URL = "audio_url"

        /** Abre o player tocando um VÍDEO direto da rede (sem baixar nada). */
        fun remoteVideo(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, true)

        /**
         * Abre o player em HD (v0.17.0): vídeo DASH (até 1080p, video-only) +
         * áudio separados — o ExoPlayer junta os dois. O setData no vídeo
         * mantém o contrato do onCreate (intent.data não nulo).
         */
        fun remoteVideoHd(ctx: Context, videoUrl: String, audioUrl: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(videoUrl))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, true)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_AUDIO_URL, audioUrl)

        /** Abre o player tocando só o ÁUDIO da rede (fallback do modo remote). */
        fun remoteAudio(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, false)
    }
}
