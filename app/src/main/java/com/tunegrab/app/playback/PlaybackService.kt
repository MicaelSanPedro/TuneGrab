package com.tunegrab.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.yt.DownloaderImpl

/**
 * Música em segundo plano: foreground service (tipo mediaPlayback) que é o
 * DONO do MediaPlayer. A PlayerActivity só é a "cara" — binding para ler
 * posição/duração e mandar play/pause/seek. Quando a activity fecha (ou o
 * app vai para o fundo), o áudio CONTINUA, com notificação de mídia
 * (MediaStyle) com botões reproduzir/pausar e fechar.
 *
 * Uma faixa por vez: abrir outra música substitui a atual; abrir um VÍDEO
 * chama stopNow() para não sobrepor sons.
 */
class PlaybackService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSessionCompat? = null
    private var uri: Uri? = null
    private var trackTitle: String = ""
    private var focusRequested = false

    // posição inicial da faixa (handoff do vídeo: saiu do app com vídeo
    // tocando e o áudio segue aqui DE ONDE parou)
    private var pendingStartMs = 0

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        player?.currentPosition ?: 0
    } catch (ignored: IllegalStateException) {
        0
    }

    fun duration(): Int = try {
        player?.duration ?: 0
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
            player?.seekTo(ms)
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    // ---------- ciclo do serviço ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                uri = intent.data
                trackTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
                pendingStartMs = intent.getIntExtra(EXTRA_START_MS, 0)
                createChannel()
                // foreground JÁ (contrato do startForegroundService); a
                // notificação "carregando" é trocada quando o áudio prepara
                startForeground(NOTIF_ID, buildNotification(playing = false))
                startTrack()
            }
            ACTION_TOGGLE -> toggle()
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
        abandonFocus()
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
        val p = MediaPlayer()
        player = p
        try {
            // googlevideo RECUSA User-Agent estranho (mesmo tratamento do
            // VideoView do player): sem isso o stream remoto leva rejeição
            // e o miniplayer morre antes de nascer
            if (u.scheme == "http" || u.scheme == "https") {
                p.setDataSource(this, u, mapOf("User-Agent" to DownloaderImpl.USER_AGENT))
            } else {
                p.setDataSource(this, u)
            }
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setOnPreparedListener { mp ->
                requestFocus()
                try {
                    // handoff do vídeo: retoma da posição em que estava
                    if (pendingStartMs > 0) {
                        mp.seekTo(pendingStartMs)
                        pendingStartMs = 0
                    }
                    mp.start()
                } catch (ignored: IllegalStateException) {
                }
                refreshMediaState()
            }
            p.setOnCompletionListener {
                // fim da faixa: para em "pausado no fim" — play volta do zero
                refreshMediaState()
            }
            p.setOnErrorListener { _, _, _ ->
                stopNow()
                true
            }
            p.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "falha ao preparar faixa", t)
            stopNow()
        }
    }

    private fun pause() {
        try {
            player?.takeIf { it.isPlaying }?.pause()
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    private fun resume() {
        val p = player ?: return
        try {
            if (!p.isPlaying) {
                requestFocus()
                p.start()
            }
        } catch (ignored: IllegalStateException) {
        }
        refreshMediaState()
    }

    /** Para tudo e remove a notificação. O serviço só morre de vez quando
     *  ninguém mais estiver bound. */
    private fun stopNow() {
        releasePlayer()
        abandonFocus()
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
        try {
            player?.release()
        } catch (ignored: Throwable) {
        }
        player = null
    }

    // ---------- foco de áudio ----------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }

    private fun requestFocus() {
        if (focusRequested) return
        focusRequested = audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (focusRequested) {
            audioManager.abandonAudioFocus(focusListener)
            focusRequested = false
        }
    }

    // ---------- MediaSession + notificação ----------

    private fun ensureSession(): MediaSessionCompat {
        session?.let { return it }
        val s = MediaSessionCompat(this, "TuneGrabPlayback")
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
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
        .addAction(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.player_pause else R.string.player_play),
            serviceIntent(1, ACTION_TOGGLE)
        )
        .addAction(
            R.drawable.ic_close,
            getString(R.string.player_close),
            serviceIntent(2, ACTION_STOP)
        )
        .setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(ensureSession().sessionToken)
                .setShowActionsInCompactView(0)
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
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_MS = "start_ms"
        private const val CHANNEL_ID = "tunegrab_playback"
        private const val NOTIF_ID = 200

        /** True entre onCreate/onDestroy — evita startForegroundService órfão. */
        private var running = false

        /**
         * Toca (ou substitui) a faixa em [uri] — áudio segue em 2º plano.
         * [startMs] retoma no meio da faixa (miniplayer: quando o usuário sai
         * do app com um VÍDEO tocando, o som continua aqui de onde parou).
         */
        fun play(ctx: Context, uri: Uri, title: String, startMs: Int = 0) {
            val i = Intent(ctx, PlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_START_MS, startMs)
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
