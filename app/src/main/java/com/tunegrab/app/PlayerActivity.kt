package com.tunegrab.app

import android.app.PictureInPictureParams
import android.app.PendingIntent
import android.app.RemoteAction
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // FILA da Biblioteca (v0.19.0, pedido do autor: próxima/anterior): a aba
    // Músicas entrega a lista de faixas visíveis + o índice da aberta — o
    // PlaybackService pula faixas (botões, notificação e auto-advance) e
    // esta activity acompanha pelo tick. Sem fila, os botões ficam apagados.
    private var queueUris: List<String> = emptyList()
    private var queueTitles: List<String> = emptyList()
    private var queueIndex = -1

    // CAPA (v0.19.0): bitmap embutido no arquivo cobre o cartão de arte;
    // coverUri guarda de qual faixa é a capa atual (não recarrega à toa)
    private var coverUri: Uri? = null
    private var coverJob: Job? = null

    // serviço de reprodução (áudio)
    private var playback: PlaybackService? = null
    private var bound = false

    // MINIPLAYER (v0.18.0): vídeo REMOTO ou LOCAL continua como áudio no
    // cartão de mídia do sistema quando o app vai pro fundo. O player HD
    // (v0.17.1, ExoPlayer) é só o vídeo REMOTO DASH até 720p — morre junto
    // com a activity; o som em 2º plano é sempre do PlaybackService.
    private var exoPlayer: ExoPlayer? = null

    // stream muxado de sempre (vídeo+áudio juntos): se as faixas DASH falharem
    // na hora de tocar (o YouTube anda bloqueando sem aviso), o player cai pra
    // cá SOZINHO em vez de morrer no erro
    private var fallbackUri: Uri? = null

    // MINIPLAYER (v0.18.0): saiu do app com vídeo tocando? O som passa pro
    // PlaybackService (foreground service) DE ONDE PAROU e vira o cartão de
    // mídia do sistema (lá embaixo no shade/tela de bloqueio). Ao voltar, o
    // vídeo retoma da posição em que o som do serviço estava.
    private var handoff = false
    private var handoffPos = 0
    private var handoffUri: Uri? = null

    // MINIPLAYER DE VÍDEO — PiP (v0.18.2): janelinha flutuante igual YouTube
    // Premium. inPip = está na janelinha agora; pipActive = já esteve;
    // pipJustExited = saiu da janelinha (resolve no onResume=expandiu ou
    // no onStop=fechou o X)
    private var inPip = false
    private var pipActive = false
    private var pipJustExited = false

    // dimensões reais do vídeo (caminho VideoView) — a janelinha PiP usa a
    // proporção certa (retrato vira janelinha em pé)
    private var lastVideoW = 0
    private var lastVideoH = 0

    // aberto pelo CARTÃO DE MÍDIA do sistema: o serviço JÁ está tocando esta
    // faixa — a UI só conecta nele (reiniciar do zero era o bug do "vídeo
    // recomeça da primeira tela")
    private var fromCard = false

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
        fromCard = intent.getBooleanExtra(EXTRA_FROM_CARD, false)
        queueUris = intent.getStringArrayListExtra(PlaybackService.EXTRA_QUEUE_URIS)
            ?: emptyList()
        queueTitles = intent.getStringArrayListExtra(PlaybackService.EXTRA_QUEUE_TITLES)
            ?: emptyList()
        queueIndex = intent.getIntExtra(PlaybackService.EXTRA_QUEUE_INDEX, -1)
        binding.tvTitle.text = title

        binding.btnClose.setOnClickListener { closePlayer() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else closePlayer()
            }
        })

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL)
        fallbackUri = intent.getStringExtra(EXTRA_FALLBACK_URL)?.let(Uri::parse)
        when {
            // HD (v0.17.1): DASH do YouTube — vídeo até 720p + áudio separados,
            // MERGIDOS no ExoPlayer; se falhar na hora de tocar, cai pro muxed
            isVideo && videoUrl != null && audioUrl != null ->
                setupExoVideo(videoUrl, audioUrl)
            isVideo -> setupVideo(uri)
            else -> setupAudio(uri, title)
        }
        // botão da janelinha PiP: liga o comando play/pause nesta activity
        if (isVideo) {
            pipToggleHook = { togglePipPlayback() }
        }
    }

    /**
     * FECHAR O PLAYER (v0.19.1, pedido do autor: "quando eu clico na seta de
     * voltar estando no player de áudio, a música continua tocando"): a seta
     * do player e o gesto/botão de voltar do sistema agora ENCERRAM o som do
     * player de música — PlaybackService.stopNow() para o ExoPlayer, tira a
     * notificação e encerra o foreground service. O resto do miniplayer NÃO
     * muda: apertar Home/trocar de app com o player aberto continua tocando
     * em 2º plano (é o propósito dele) — o pedido é sobre FECHAR o player.
     * VÍDEO: só finish() — o som do vídeo já morre com a activity (o handoff
     * pro serviço ignora activity em isFinishing, nada de som fantasma).
     */
    private fun closePlayer() {
        if (!isVideo) {
            PlaybackService.stopNow(this)
        }
        finish()
    }

    // ---------- vídeo ----------

    private fun setupVideo(uri: Uri) {
        // não sobrepor a música em segundo plano
        PlaybackService.stopNow(this)

        // miniplayer: o próprio arquivo (muxado remoto ou vídeo local) tem o
        // áudio que o PlaybackService toca quando o app vai pro fundo
        handoffUri = uri

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
            // usa para nunca deformar o vídeo na tela cheia (e a janelinha
            // PiP usa a proporção certa)
            if (mp.getVideoWidth() > 0 && mp.getVideoHeight() > 0) {
                lastVideoW = mp.getVideoWidth()
                lastVideoH = mp.getVideoHeight()
                binding.videoView.setVideoSize(lastVideoW, lastVideoH)
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
        // botão da janelinha PiP já nasce certo (vídeo começou a tocar)
        refreshPipParams(true)
        // fim do vídeo DENTRO da janelinha PiP: fecha a janelinha (igual
        // YouTube — sem vídeo parado eterno flutuando na tela)
        binding.videoView.setOnCompletionListener {
            if (inPip) stopVideoCompletely()
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
     * VÍDEO HD (v0.17.1): stream DASH do YouTube — faixa de vídeo (até 720p,
     * SEM áudio) + faixa de áudio separadas, tocando em sincronia via
     * MergingMediaSource. LÊ direto da rede, sem salvar NADA. Se as faixas
     * DASH falharem na hora de tocar (o YouTube anda bloqueando sem aviso —
     * o teste de URL de 2 bytes passa, mas o request completo leva 403), o
     * player CAI SOZINHO pro muxed de sempre (VideoView) — o erro só aparece
     * se o muxed também falhar. O botão de tela cheia continua girando o APP
     * (mesma setFullscreen do vídeo local).
     */
    private fun setupExoVideo(videoUrl: String, audioUrl: String) {
        // não sobrepor a música em segundo plano
        PlaybackService.stopNow(this)

        // miniplayer: o som em 2º plano usa a faixa de ÁUDIO separada (mesma
        // que o ExoPlayer está tocando sincronizada com o vídeo)
        handoffUri = Uri.parse(audioUrl)

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
                    Log.e(TAG, "Falha no stream DASH", error)
                    // NUNCA morre no erro: solta o Exo e volta pro muxed de
                    // sempre (VideoView). Sem toast aqui — se o muxed também
                    // falhar, o erro real aparece lá
                    exoPlayer?.release()
                    exoPlayer = null
                    binding.playerView.player = null
                    binding.playerView.visibility = View.GONE
                    val fb = fallbackUri
                    if (fb == null) {
                        finish()
                        return
                    }
                    fallbackUri = null
                    setupVideo(fb)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    // vídeo acabou DENTRO da janelinha PiP: fecha a janelinha
                    // (igual YouTube — nada de vídeo congelado flutuando)
                    if (playbackState == Player.STATE_ENDED && inPip) {
                        stopVideoCompletely()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // botão da janelinha PiP acompanha o estado real
                    // (inclusive pausa por buffering/perda de foco)
                    refreshPipParams(isPlaying)
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

        // Aberto pelo CARTÃO DE MÍDIA (EXTRA_FROM_CARD)? O serviço JÁ está
        // tocando esta faixa — só conecta a UI nele. Reiniciar do zero era o
        // bug do "a faixa recomeça" ao tocar no cartão.
        if (!fromCard) {
            PlaybackService.play(
                this,
                uri,
                title,
                queueUris = queueUris,
                queueTitles = queueTitles,
                queueIndex = queueIndex
            )
        }
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
                PlaybackService.play(
                    this,
                    u,
                    initialTitle,
                    queueUris = queueUris,
                    queueTitles = queueTitles,
                    queueIndex = queueIndex
                )
            }
            binding.btnPlay.postDelayed({ updatePosition() }, 100)
        }

        // FILA (v0.19.0): anterior/próxima conversam direto com o serviço —
        // se a faixa pulou (inclusive pela notificação), o tick sincroniza
        binding.btnNext.setOnClickListener {
            playback?.skipNext()
            binding.btnPlay.postDelayed({ updatePosition() }, 100)
        }
        binding.btnPrev.setOnClickListener {
            playback?.skipPrevious()
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
        setupVisualizer()
        loadCover(uri)
    }

    /**
     * Barrinhas de DJ (v0.18.8): espectro REAL SEM PERMISSÃO NENHUMA — o
     * SpectrumProcessor dentro do ExoPlayer do PlaybackService espia o PCM
     * que o próprio app toca e publica no SpectrumBus; a view só desenha.
     * Pref desligada? Player fica sem barras, como sempre. Pausou? O PCM
     * para de fluir e as barras caem sozinhas.
     */
    private fun setupVisualizer() {
        if (!FormatPrefs.visualizerOn(this)) {
            binding.visualizer.visibility = View.GONE
            return
        }
        binding.visualizer.visibility = View.VISIBLE
        binding.visualizer.attach()
    }

    /**
     * CAPA DA FAIXA (v0.19.0, pedido do autor: "a capa aparecer no lugar
     * daquele ícone genérico"): lê a imagem embutida no ARQUIVO de áudio
     * (MediaMetadataRetriever entende M4A/MP3/OGG-Opus — os três formatos
     * que o TuneGrab baixa, agora com capa embutida pelo yt-dlp). Com capa,
     * o bitmap cobre o cartão de arte (clip no contorno arredondado do
     * bg_album_art); sem capa, volta o ícone musical genérico. Corrotina de
     * IO — a leitura nunca trava o player — e só pra arquivo LOCAL (stream
     * remoto não tem capa embutida pra ler).
     */
    private fun loadCover(source: Uri) {
        if (source.scheme == "http" || source.scheme == "https") return
        if (coverUri == source) return
        coverUri = source
        coverJob?.cancel()
        coverJob = lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(applicationContext, source)
                    r.embeddedPicture
                } catch (ignored: Throwable) {
                    null
                } finally {
                    try {
                        r.release()
                    } catch (ignored: Throwable) {
                    }
                }
            }
            if (coverUri != source || isFinishing || isDestroyed) return@launch
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) {
                binding.imgArt.setImageBitmap(bmp)
                binding.imgArt.clipToOutline = true
                binding.imgArtIcon.visibility = View.GONE
            } else {
                // sem capa (ou ilegível): gradiente violeta + ícone de sempre
                binding.imgArt.setImageDrawable(null)
                binding.imgArtIcon.visibility = View.VISIBLE
            }
        }
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

        // FILA (v0.19.0): botões vivos só quando existe pra onde ir — e se a
        // faixa pulou (notificação, botão, auto-advance no fim da faixa), o
        // título e a capa daqui acompanham o que o serviço está tocando
        val hasNext = svc.hasNext()
        val hasPrev = svc.hasPrevious()
        if (binding.btnNext.isEnabled != hasNext) {
            binding.btnNext.isEnabled = hasNext
            binding.btnNext.alpha = if (hasNext) 1f else 0.35f
        }
        if (binding.btnPrev.isEnabled != hasPrev) {
            binding.btnPrev.isEnabled = hasPrev
            binding.btnPrev.alpha = if (hasPrev) 1f else 0.35f
        }
        if (svc.currentTitle() != binding.tvTitle.text.toString()) {
            binding.tvTitle.text = svc.currentTitle()
        }
        val trackUri = svc.currentUri()
        if (trackUri != null) loadCover(trackUri)
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

    /**
     * MINIPLAYER (v0.18.0): saiu do app com vídeo tocando (sem fechar) — o
     * áudio passa pro PlaybackService na MESMA posição e continua como cartão
     * de mídia do sistema (lá embaixo), igual à música. O vídeo fica pausado
     * aqui; ao voltar, o onResume devolve o som pra tela.
     */
    private fun handoffToService(posMs: Int) {
        val u = handoffUri ?: return
        handoffPos = posMs
        try {
            PlaybackService.play(this, u, binding.tvTitle.text.toString(), posMs)
        } catch (ignored: Throwable) {
            // início de serviço bloqueado (OEM agressivo): o vídeo só pausa —
            // NUNCA crasha na saída do app
            return
        }
        // bind pra ler a posição do serviço quando o usuário voltar
        if (!bound) {
            try {
                bindService(
                    Intent(this, PlaybackService::class.java),
                    serviceConn,
                    Context.BIND_AUTO_CREATE
                )
                bound = true
            } catch (ignored: Throwable) {
            }
        }
        handoff = true
    }

    /**
     * Entrega o som do vídeo pro serviço (miniplayer) se estiver tocando.
     * Guardas embutidas: só vídeo, sem finish pendente, sem handoff repetido.
     */
    private fun tryVideoHandoff() {
        if (!isVideo || isFinishing || handoff) return
        val exo = exoPlayer
        val playing = if (exo != null) exo.isPlaying else try {
            binding.videoView.isPlaying
        } catch (ignored: Throwable) {
            false
        }
        if (!playing) return
        val pos = if (exo != null) exo.currentPosition.toInt() else try {
            binding.videoView.currentPosition
        } catch (ignored: Throwable) {
            0
        }
        handoffToService(pos)
    }

    // ---------- MINIPLAYER DE VÍDEO — PiP (v0.18.2) ----------

    /**
     * MINIPLAYER IGUAL YOUTUBE PREMIUM (v0.18.2): saiu do app com o vídeo
     * tocando? A janelinha flutuante (picture-in-picture) assume — o MESMO
     * player continua na janelinha, na MESMA posição, com vídeo de verdade
     * (nada de handoff, nada de recomeçar do zero). Fechar a janelinha (X)
     * PARA o vídeo; expandir volta pro player normal. Sem PiP (Android < 8
     * ou desligado nas configurações), o handoff de áudio (cartão de mídia)
     * segue como rede de segurança.
     */
    private fun tryEnterPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val exo = exoPlayer
        val playing = if (exo != null) exo.isPlaying else try {
            binding.videoView.isPlaying
        } catch (ignored: Throwable) {
            false
        }
        // vídeo pausado: não abre janelinha (igual YouTube)
        if (!playing) return false
        return try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio())
                // botão play/pause DENTRO da janelinha (igual YouTube Premium)
                .setActions(pipActions(playing))
                .build()
            enterPictureInPictureMode(params)
        } catch (ignored: Throwable) {
            // sem suporte/permitido no aparelho: cai pro handoff de áudio
            false
        }
    }

    /** Ação da janelinha: play/pause sem expandir (RemoteAction → receiver). */
    private fun pipActions(playing: Boolean): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val toggle = PendingIntent.getBroadcast(
            this,
            1001,
            Intent(this, PipActionReceiver::class.java)
                .setAction(PipActionReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = getString(if (playing) R.string.player_pause else R.string.player_play)
        return listOf(
            RemoteAction(
                Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                label,
                label,
                toggle
            )
        )
    }

    /** Refaz os parâmetros do PiP (ícone do botão acompanha o estado real). */
    private fun refreshPipParams(playing: Boolean) {
        if (Build.VERSION.SDK_INT < 26 || !isVideo) return
        try {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(pipAspectRatio())
                    .setActions(pipActions(playing))
                    .build()
            )
        } catch (ignored: Throwable) {
        }
    }

    /** Botão da janelinha apertado: pausa/retoma SEM expandir o vídeo. */
    private fun togglePipPlayback() {
        if (!isVideo) return
        val exo = exoPlayer
        if (exo != null) {
            val play = !exo.isPlaying
            if (play) exo.play() else exo.pause()
            refreshPipParams(play)
        } else {
            val playing = try {
                binding.videoView.isPlaying
            } catch (ignored: Throwable) {
                false
            }
            if (playing) binding.videoView.pause() else binding.videoView.start()
            refreshPipParams(!playing)
        }
    }

    /** Proporção REAL do vídeo (retrato vira janelinha em pé), presa nos
     *  limites que o PiP aceita (entre 1:2.39 e 2.39:1). */
    private fun pipAspectRatio(): Rational {
        var w = lastVideoW
        var h = lastVideoH
        exoPlayer?.videoSize?.takeIf { it.height > 0 && it.width > 0 }?.let {
            w = it.width
            h = it.height
        }
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val r = Rational(w, h)
        val f = r.toFloat()
        return when {
            f > 2.39f -> Rational(239, 100)
            f < 1f / 2.39f -> Rational(100, 239)
            else -> r
        }
    }

    /** Dentro da janelinha: só o vídeo — o sistema dá fechar/expandir. */
    private fun enterPipUi() {
        binding.btnClose.visibility = View.GONE
        binding.btnFullscreen.visibility = View.GONE
        binding.playerView.useController = false
        binding.playerView.hideController()
    }

    /** Voltou pra tela normal (expandiu a janelinha): controles de volta. */
    private fun exitPipUi() {
        binding.btnClose.visibility = View.VISIBLE
        if (isVideo) {
            binding.btnFullscreen.visibility = View.VISIBLE
            binding.playerView.useController = true
        }
    }

    /** Para o vídeo DE VERDADE (janelinha fechada / fim do vídeo no PiP) —
     *  sem áudio fantasma: solta os players e mata qualquer som remanescente. */
    private fun stopVideoCompletely() {
        try {
            binding.videoView.stopPlayback()
        } catch (ignored: Throwable) {
        }
        exoPlayer?.release()
        exoPlayer = null
        binding.playerView.player = null
        try {
            PlaybackService.stopNow(this)
        } catch (ignored: Throwable) {
        }
        finish()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            pipActive = true
            enterPipUi()
        } else if (pipActive) {
            // saiu da janelinha: ou EXPANDIU (resolve no onResume) ou FECHOU
            // (resolve no onStop — para tudo)
            pipJustExited = true
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isVideo) return // música já vive no PlaybackService
        // MINIPLAYER DE VÍDEO (v0.18.2): janelinha flutuante (PiP) primeiro —
        // o mesmo player segue nela, na posição exata. Aqui o app AINDA é
        // foreground, o melhor momento de transição. Se não der PiP (Android
        // < 8 ou desligado nas configurações), cai pro handoff de áudio
        // (cartão de mídia) — e o onStop segue como rede de segurança para
        // os casos em que o onUserLeaveHint nem é chamado.
        if (tryEnterPip()) return
        tryVideoHandoff()
    }

    override fun onResume() {
        super.onResume()
        // voltou pra tela: as barrinhas voltam a dançar (o attach persiste;
        // aqui só religa a captura desligada no onStop)
        binding.visualizer.setHostPaused(false)
        if (pipJustExited) {
            // EXPANDIU a janelinha PiP: volta pra tela normal — o handoff
            // NUNCA aconteceu (o player seguiu tocando na janelinha), não
            // mexe em posição nem no serviço
            pipJustExited = false
            exitPipUi()
        }
        if (!handoff) return
        handoff = false
        // posição mais fresca: o serviço (que continuou tocando) senão a do
        // momento do handoff — lida ANTES de parar o serviço
        val pos = playback?.position()?.takeIf { it > 0 } ?: handoffPos
        PlaybackService.stopNow(this)
        val exo = exoPlayer
        if (exo != null) {
            exo.seekTo(pos.toLong())
            exo.playWhenReady = true
        } else {
            // caminho muxado/local (ou fallback do DASH)
            binding.videoView.seekTo(pos)
            binding.videoView.start()
        }
    }

    override fun onStop() {
        super.onStop()
        // app pro fundo: desliga a captura do espectro (bateria); ao voltar,
        // o onResume religa — o som em si segue do jeito que estava
        binding.visualizer.setHostPaused(true)
        if (pipJustExited || inPip) {
            // Saiu da janelinha e NÃO voltou pra tela = usuário FECHOU o PiP
            // (X ou arrastou pra fora): PARA TUDO, igual YouTube Premium —
            // sem áudio fantasma. (pipJustExited: o mode-changed(false)
            // chegou antes do onStop; inPip ainda true: fechou sem o
            // callback, ou apagou a tela com a janelinha ativa.)
            pipJustExited = false
            stopVideoCompletely()
            return
        }
        // rede de segurança do handoff (o normal é no onUserLeaveHint); o
        // guard de dentro de tryVideoHandoff evita handoff duplicado
        tryVideoHandoff()
        // ÁUDIO: NÃO pausa mais — o PlaybackService segue em 2º plano com a
        // notificação de mídia (é o propósito desta versão).
        // VÍDEO: pausa a tela (o som já está/estará no PlaybackService).
        binding.videoView.pause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tick)
        // loop de desenho das barrinhas para com a activity
        binding.visualizer.detach()
        // botão do PiP desliga com a activity (receiver vira no-op)
        pipToggleHook = null
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
        private const val TAG = "PlayerActivity"
        const val EXTRA_TITLE = "title"
        const val EXTRA_IS_VIDEO = "is_video"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_FALLBACK_URL = "fallback_url"

        /** Aberto pelo cartão de mídia do sistema: o serviço já está tocando
         *  esta faixa — a activity só conecta a UI (NÃO reinicia do zero). */
        const val EXTRA_FROM_CARD = "from_card"

        // gancho do botão da janelinha PiP: o PipActionReceiver (mesmo
        // processo) repassa o comando play/pause pra activity viva
        private var pipToggleHook: (() -> Unit)? = null

        /** Chamado pelo PipActionReceiver quando o botão do PiP é apertado. */
        fun dispatchPipToggle() {
            pipToggleHook?.invoke()
        }

        /** Abre o player tocando um VÍDEO direto da rede (sem baixar nada). */
        fun remoteVideo(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, true)

        /**
         * Abre o player em HD (v0.17.1): vídeo DASH (até 720p, video-only) +
         * áudio separados — o ExoPlayer junta os dois. O fallbackUrl (muxado
         * de sempre) é a rede de segurança: se o stream DASH falhar na hora de
         * tocar, o player cai pra ele SOZINHO em vez de morrer no erro. O
         * setData no vídeo mantém o contrato do onCreate (intent.data não nulo).
         */
        fun remoteVideoHd(
            ctx: Context,
            videoUrl: String,
            audioUrl: String,
            title: String,
            fallbackUrl: String? = null
        ): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(videoUrl))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, true)
                .putExtra(EXTRA_VIDEO_URL, videoUrl)
                .putExtra(EXTRA_AUDIO_URL, audioUrl)
                .putExtra(EXTRA_FALLBACK_URL, fallbackUrl)

        /** Abre o player tocando só o ÁUDIO da rede (fallback do modo remote). */
        fun remoteAudio(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_IS_VIDEO, false)
    }
}
