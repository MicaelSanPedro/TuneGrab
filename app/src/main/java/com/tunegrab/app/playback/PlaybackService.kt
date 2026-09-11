package com.tunegrab.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.yt.DownloaderImpl

/**
 * Música em segundo plano: foreground service (tipo mediaPlayback) que é o
 * DONO do player. A PlayerActivity só é a "cara" — binding para ler
 * posição/duração e mandar play/pause/seek. Quando a activity fecha (ou o
 * app vai para o fundo), o áudio CONTINUA, com notificação de mídia
 * (MediaStyle) com botões reproduzir/pausar e fechar.
 *
 * Uma faixa por vez: abrir outra música substitui a atual; abrir um VÍDEO
 * chama stopNow() para não sobrepor sons.
 *
 * v0.18.8: o motor interno agora é o ExoPlayer (Media3) em vez do
 * MediaPlayer — MESMA API pública do serviço, MESMA notificação, MESMA
 * MediaSession, foco de áudio agora nativo do ExoPlayer. O motivo da troca:
 * as barrinhas de DJ de verdade — o ExoPlayer deixa o SpectrumProcessor
 * ESPIAR o PCM que está saindo pelo alto-falante (dentro do próprio app),
 * o que dá o espectro real do som SEM permissão nenhuma.
 */
class PlaybackService : Service() {

    /** Faixa da fila: uri + título de exibição. */
    data class Track(val uri: Uri, val title: String)

    private var player: ExoPlayer? = null
    private var session: MediaSessionCompat? = null
    private var uri: Uri? = null
    private var trackTitle: String = ""

    // FILA da Biblioteca (v0.19.0, pedido do autor: passar pra próxima e
    // voltar pra anterior): a aba Músicas entrega a lista de faixas visíveis
    // (na ordem da tela) + o índice da faixa aberta. A faixa que ACABA pula
    // sozinha pra próxima; sem fila (áudio remoto, handoff de vídeo) a
    // navegação fica desligada e o comportamento é o de sempre.
    private var queue: List<Track> = emptyList()
    private var queueIndex = -1

    // REPRODUÇÃO AUTOMÁTICA (v0.19.16, pedido do autor): com a fila INTEIRA
    // tocada até o fim, volta pra primeira faixa e segue tocando em ciclo.
    // A PlayerActivity manda o estado no ACTION_PLAY e atualiza ao vivo
    // (toggle no player vale só pra sessão); sem fila, não muda NADA.
    @Volatile
    private var autoplay = false

    // posição inicial da faixa (handoff do vídeo: saiu do app com vídeo
    // tocando e o áudio segue aqui DE ONDE parou)
    private var pendingStartMs = 0

    /** Espectro REAL (v0.18.8): espia o PCM na saída do player — as
     *  barrinhas do player de música leem daqui (SpectrumBus), sem
     *  permissão nenhuma. Uma instância por serviço, reusada a cada faixa. */
    private val spectrum = SpectrumProcessor()

    /**
     * Renderers de fábrica com o AudioSink padrão + o SpectrumProcessor
     * injetado: o MESMO pipeline que leva o som ao alto-falante entrega o
     * PCM pras barrinhas. As flags de float/playback-params são repassadas
     * como no sink de fábrica (o processador entende 16-bit e float).
     */
    private val renderersFactory: RenderersFactory =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink =
                DefaultAudioSink.Builder(this@PlaybackService)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(spectrum))
                    .build()
        }

    /**
     * Fontes: http(s) com o User-Agent da extração (googlevideo RECUSA UA
     * estranho — mesmo tratamento que o player de vídeo SEMPRE teve);
     * esquemas locais (content://, file://) o DefaultDataSource resolve.
     */
    private val mediaSourceFactory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(DownloaderImpl.USER_AGENT)
            .setAllowCrossProtocolRedirects(true) // googlevideo redireciona http<->https
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
    }

    // ExoPlayer cuida do foco de áudio (pausa na perda; em perda transitória
    // retoma sozinho quando o som de fora libera)
    private val mediaAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // fim da faixa COM fila: pula sozinho pra próxima (player de
                // música de verdade); na última faixa, ou CICLA pro começo
                // (reprodução automática ligada), ou para em "pausado no
                // fim" — play volta do zero, como sempre
                when {
                    hasNext() -> goTo(queueIndex + 1)
                    autoplay && queue.isNotEmpty() -> goTo(0)
                    else -> refreshMediaState()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // cobre pausa por foco/buffering: a notificação acompanha o
            // estado REAL do som
            refreshMediaState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "falha na reprodução", error)
            stopNow()
        }
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    // ---------- API para a PlayerActivity (binding) ----------

    fun isPlaying(): Boolean = try {
        player?.isPlaying == true
    } catch (ignored: IllegalStateException) {
        false
    }

    fun position(): Int = try {
        player?.currentPosition?.toInt() ?: 0
    } catch (ignored: IllegalStateException) {
        0
    }

    fun duration(): Int = try {
        val d = player?.duration ?: 0L
        if (d == C.TIME_UNSET) 0 else d.toInt().coerceAtLeast(0)
    } catch (ignored: IllegalStateException) {
        0
    }

    fun toggle() {
        if (isPlaying()) {
            pause()
        } else {
            val p = player
            if (p == null && uri != null) {
                // faixa parada (fechar na notificação): toca de novo do zero
                createChannel()
                startForeground(NOTIF_ID, buildNotification(playing = false))
                startTrack()
            } else {
                resume()
            }
        }
    }

    fun seekTo(ms: Int) {
        try {
            player?.seekTo(ms.toLong())
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    // ---------- FILA (v0.19.0): próxima / anterior ----------

    fun hasNext(): Boolean = queue.isNotEmpty() && queueIndex < queue.size - 1

    /** Existe fila da Biblioteca? (v0.19.16) O botão de reprodução
     *  automática no player só é vivo com fila — sem ela não há ciclo. */
    fun hasQueue(): Boolean = queue.isNotEmpty()

    /** Estado atual do ciclo (a PlayerActivity puxa ao conectar, pra não
     *  pisar no que o serviço já está segurando da sessão). */
    fun autoplay(): Boolean = autoplay

    /** Liga/desliga o ciclo AO VIVO (toggle no player, só nesta sessão). */
    fun setAutoplay(on: Boolean) {
        autoplay = on
    }

    /** Anterior sempre responde: antes da 1ª faixa (ou depois de 3s tocando)
     *  reinicia a atual — comportamento clássico dos players de música. */
    fun hasPrevious(): Boolean = queue.isNotEmpty() &&
        (queueIndex > 0 || position() > PREVIOUS_RESTART_MS)

    fun skipNext() {
        if (!hasNext()) return
        goTo(queueIndex + 1)
    }

    fun skipPrevious() {
        if (queue.isEmpty()) return
        if (position() > PREVIOUS_RESTART_MS) {
            seekTo(0)
        } else if (queueIndex > 0) {
            goTo(queueIndex - 1)
        } else {
            seekTo(0)
        }
    }

    /** Título da faixa corrente — a PlayerActivity usa pra sincronizar o
     *  cartão de arte e o título quando a fila pula de faixa. */
    fun currentTitle(): String = trackTitle

    fun currentUri(): Uri? = uri

    /** Entra na faixa do índice pedido: troca uri/título, prepara o player
     *  do zero e refaz notificação/sessão. Funciona também com o player
     *  morto (faixa pula pela NOTIFICAÇÃO depois de fechada — o serviço
     *  revive igual no toggle). */
    private fun goTo(index: Int) {
        val t = queue.getOrNull(index) ?: return
        queueIndex = index
        uri = t.uri
        trackTitle = t.title
        if (player == null) {
            // serviço revivido pela notificação: foreground JÁ (contrato do
            // startForegroundService) antes de preparar o áudio
            createChannel()
            startForeground(NOTIF_ID, buildNotification(playing = false))
        }
        startTrack()
    }

    // ---------- ciclo do serviço ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                // fila (v0.19.0): extras opcionais — sem eles, fila vazia
                // (áudio remoto, handoff de vídeo): navegação desligada
                val uris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS)
                val titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES)
                val idx = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
                queue = if (uris != null && titles != null && idx >= 0 &&
                    uris.size == titles.size
                ) {
                    uris.mapNotNull { u -> u.toUriOrNull() }
                        .zip(titles) { u, t -> Track(u, t) }
                        .takeIf { it.size == uris.size }
                        ?: emptyList() // uri podre na lista = fila inteira fora
                } else {
                    emptyList()
                }
                queueIndex = if (queue.isNotEmpty() && idx < queue.size) idx else -1
                uri = intent.data
                trackTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
                pendingStartMs = intent.getIntExtra(EXTRA_START_MS, 0)
                // reprodução automática (v0.19.16): o padrão vem da activity
                // (que lê a preferência das Configurações)
                autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
                createChannel()
                // foreground JÁ (contrato do startForegroundService); a
                // notificação "carregando" é trocada quando o áudio prepara
                startForeground(NOTIF_ID, buildNotification(playing = false))
                startTrack()
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> skipNext()
            ACTION_PREVIOUS -> skipPrevious()
            ACTION_STOP -> stopNow()
            else -> {
                // reinício sem ação (ex.: processo revivido): nada a tocar
                if (player == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        try {
            session?.release()
        } catch (ignored: Throwable) {
        }
        session = null
        running = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // usuário FECHOU o app dos recentes: PARA TUDO — sem isso o serviço
        // sobrevivia sozinho tocando áudio fantasma (só morria na forçar-parada)
        stopNow()
    }

    /** Toca a faixa em [uri], substituindo o que estiver tocando. */
    private fun startTrack() {
        releasePlayer()
        val u = uri
        if (u == null) {
            stopNow()
            return
        }
        val p = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player = p
        try {
            // o serviço toca SÓ o áudio (o handoff do vídeo passa arquivo
            // muxado): sem faixa de vídeo selecionada, decoder de vídeo nem
            // acorda — bateria poupada
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            p.setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus= */ true)
            p.addListener(playerListener)
            val item = MediaItem.fromUri(u)
            // handoff do vídeo: retoma da posição em que estava
            if (pendingStartMs > 0) {
                p.setMediaItem(item, pendingStartMs.toLong())
                pendingStartMs = 0
            } else {
                p.setMediaItem(item)
            }
            p.playWhenReady = true
            p.prepare()
        } catch (t: Throwable) {
            Log.w(TAG, "falha ao preparar faixa", t)
            stopNow()
        }
    }

    private fun pause() {
        try {
            player?.pause()
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    private fun resume() {
        val p = player ?: return
        try {
            if (p.playbackState == Player.STATE_ENDED) {
                // "pausado no fim": play volta do zero (mesmo comportamento
                // de antes da migração)
                p.seekTo(0)
                p.play()
            } else if (!p.isPlaying) {
                p.play()
            }
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    /** Para tudo e remove a notificação. O serviço só morre de vez quando
     *  ninguém mais estiver bound. */
    private fun stopNow() {
        releasePlayer()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Throwable) {
        }
        stopSelf()
        try {
            session?.release()
        } catch (ignored: Throwable) {
        }
        session = null
    }

    private fun releasePlayer() {
        // sem dado novo, as barrinhas caem sozinhas na view
        SpectrumBus.clear()
        try {
            player?.release()
        } catch (ignored: Throwable) {
        }
        player = null
    }

    // ---------- MediaSession + notificação ----------

    private fun ensureSession(): MediaSessionCompat {
        session?.let { return it }
        val s = MediaSessionCompat(this, "TuneGrabPlayback")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            override fun onSkipToNext() = skipNext()
            override fun onSkipToPrevious() = skipPrevious()
            override fun onStop() = stopNow()
        })
        s.isActive = true
        session = s
        return s
    }

    private fun refreshMediaState() {
        val playing = isPlaying()
        val s = ensureSession()
        s.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_SEEK_TO
                        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    position().toLong(),
                    if (playing) 1f else 0f
                )
                .build()
        )
        s.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.app_name))
                // duração no cartão de mídia (Android 13+ mostra a barra)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration().toLong())
                .build()
        )
        // atualiza a notificação (serviço em foreground só enquanto há faixa)
        if (player != null) {
            startForeground(NOTIF_ID, buildNotification(playing))
        }
    }

    private fun buildNotification(playing: Boolean) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_music_note)
        .setContentTitle(trackTitle)
        .setContentText(getString(R.string.app_name))
        .setContentIntent(contentIntent())
        // fila (v0.19.0): anterior e próxima na notificação/cartão de mídia
        // (só aparecem úteis quando existem — nas pontas da fila o sistema
        // entrega o toque e o serviço simplesmente não pula)
        .addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.player_previous),
            serviceIntent(5, ACTION_PREVIOUS)
        )
        .addAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.player_pause else R.string.player_play),
            serviceIntent(1, ACTION_TOGGLE)
        )
        .addAction(
            R.drawable.ic_skip_next,
            getString(R.string.player_next),
            serviceIntent(6, ACTION_NEXT)
        )
        .addAction(
            R.drawable.ic_close,
            getString(R.string.player_close),
            serviceIntent(2, ACTION_STOP)
        )
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(ensureSession().sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        // arrastável: FECHAR O CARTÃO PARA O SOM (deleteIntent abaixo) — o
        // áudio fantasma que só morria na forçar-parada acabou
        .setOngoing(false)
        .setDeleteIntent(serviceIntent(4, ACTION_STOP))
        .setOnlyAlertOnce(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, requestCode, i, flags)
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getService(this, requestCode, i, flags)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        3,
        Intent(this, PlayerActivity::class.java)
            .setData(uri)
            .putExtra(PlayerActivity.EXTRA_TITLE, trackTitle)
            .putExtra(PlayerActivity.EXTRA_IS_VIDEO, false)
            // tocou no cartão: se a PlayerActivity ainda existe, só traz à
            // frente; se não, abre em modo áudio CONECTADA ao serviço que já
            // está tocando (EXTRA_FROM_CARD) — NUNCA reinicia do zero
            .putExtra(PlayerActivity.EXTRA_FROM_CARD, true)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_playback),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_playback_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val ACTION_PLAY = "com.tunegrab.app.playback.PLAY"
        const val ACTION_TOGGLE = "com.tunegrab.app.playback.TOGGLE"
        const val ACTION_STOP = "com.tunegrab.app.playback.STOP"
        const val ACTION_NEXT = "com.tunegrab.app.playback.NEXT"
        const val ACTION_PREVIOUS = "com.tunegrab.app.playback.PREVIOUS"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_MS = "start_ms"
        const val EXTRA_QUEUE_URIS = "queue_uris"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_INDEX = "queue_index"
        const val EXTRA_AUTOPLAY = "autoplay"

        /** Acima disso, "anterior" reinicia a faixa em vez de voltar. */
        private const val PREVIOUS_RESTART_MS = 3000
        private const val CHANNEL_ID = "tunegrab_playback"
        private const val NOTIF_ID = 200

        /** True entre onCreate/onDestroy — evita startForegroundService órfão. */
        private var running = false

        /** "content://..." → Uri sem estourar exceção (uri podre na fila = fora). */
        private fun String.toUriOrNull(): Uri? = try {
            Uri.parse(this).takeIf { it.toString().isNotBlank() }
        } catch (ignored: Throwable) {
            null
        }

        /**
         * Toca (ou substitui) a faixa em [uri] — áudio segue em 2º plano.
         * [startMs] retoma no meio da faixa (miniplayer: quando o usuário sai
         * do app com um VÍDEO tocando, o som continua aqui de onde parou).
         * [queueUris]/[queueTitles]/[queueIndex]: fila da Biblioteca (v0.19.0)
         * — permite próxima/anterior e o avanço automático no fim da faixa.
         */
        fun play(
            ctx: Context,
            uri: Uri,
            title: String,
            startMs: Int = 0,
            queueUris: List<String>? = null,
            queueTitles: List<String>? = null,
            queueIndex: Int = -1,
            autoplay: Boolean = false
        ) {
            val i = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_START_MS, startMs)
                .putExtra(EXTRA_AUTOPLAY, autoplay)
            if (!queueUris.isNullOrEmpty() && !queueTitles.isNullOrEmpty() && queueIndex >= 0) {
                i.putStringArrayListExtra(EXTRA_QUEUE_URIS, ArrayList(queueUris))
                i.putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(queueTitles))
                i.putExtra(EXTRA_QUEUE_INDEX, queueIndex)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Para a reprodução atual (usado ao abrir um VÍDEO). */
        fun stopNow(ctx: Context) {
            if (running) {
                ctx.startForegroundService(
                    Intent(ctx, PlaybackService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }

    init {
        running = true
    }
}
