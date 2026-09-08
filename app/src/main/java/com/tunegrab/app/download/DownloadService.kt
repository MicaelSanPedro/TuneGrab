package com.tunegrab.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.tunegrab.app.CrashReportActivity
import com.tunegrab.app.R
import com.tunegrab.app.audio.AudioQuality
import com.tunegrab.app.audio.Mp3Converter
import com.tunegrab.app.video.VideoQuality
import com.tunegrab.app.yt.DownloaderImpl
import com.tunegrab.app.yt.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano que baixa a faixa selecionada e salva na pasta
 * configurada (Downloads/TuneGrab ou a escolhida), com notificação de
 * progresso em fases, VELOCIDADE e ações de PAUSAR/CANCELAR (continuar
 * depois é um clique — os parciais ficam guardados).
 *
 * Um download de cada vez: novos pedidos entram numa FILA interna (a Central
 * mostra a ordem); pausa/cancelamento agem no download ATIVO na hora.
 *
 * PLANO A — motor yt-dlp EMBUTIDO (quando o intent tem EXTRA_VIDEO_URL):
 * yt-dlp faz extração + download + conversão sozinho (é a engine mantida
 * semanalmente contra as mudanças do YouTube). Progresso 0–100 direto dele;
 * a velocidade vem da linha do próprio yt-dlp ("at 2.35MiB/s"). Pausar mata
 * o processo via processId e a retomada reencontra os parciais no diretório
 * estável (o --continue do yt-dlp é o padrão).
 *
 * PLANO B — URL direta (NewPipe + PoToken), se o plano A falhar:
 *  - MODO_DIRETO (M4A/MP4): download → arquivo final (0–100%)
 *  - MODO_MP3:              download (0–60%) → conversão LAME (60–99%) → salvar
 *
 * No plano B o download vai primeiro para um arquivo .part no cache e só
 * depois é publicado, com retomada (Range HTTP) por até [MAX_ATTEMPTS] vezes.
 * URLs bloqueadas (HTTP 403/4xx) não são repetidas: repetir não resolve.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------- pausa / cancelamento / fila ----------

    private val stateLock = Any()
    private val pending = ArrayDeque<JobSpec>()
    private var busy = false

    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var activeFile: String? = null
    @Volatile private var ytDlpProcessId: String? = null
    @Volatile private var currentCall: Call? = null

    /** Pausa durante a fase de DOWNLOAD: mantém parciais (.part/.src) para retomar. */
    @Volatile private var keepPartials = false

    /** Última notificação de progresso — reexibida em starts de controle. */
    @Volatile private var lastProgressNotif: Notification? = null

    // velocidade do plano B (janela móvel); a do plano A vem da linha do yt-dlp
    private var speedBps = 0L
    private var speedMarkMs = 0L
    private var speedMarkDone = 0L

    /** Download enfileirado: o intent original + o startId que o trouxe. */
    private class JobSpec(val startId: Int, val intent: Intent, val isResume: Boolean)

    /** Sinais internos de parada — nunca são "falha" nem disparam retentativa. */
    private class DownloadPaused : Exception()
    private class DownloadCancelled : Exception()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // cada startForegroundService EXIGE startForeground imediato (contrato do
        // Android) — em ação de controle sem trabalho, é notificação neutra + stop
        startForeground(NOTIF_ID, lastProgressNotif ?: idleNotification())

        when (intent?.action) {
            ACTION_PAUSE -> {
                if (activeFile != null) {
                    pauseRequested = true
                    killActive()
                } else {
                    idleNow(startId)
                }
            }
            ACTION_CANCEL -> if (intent != null) {
                handleCancelIntent(intent, startId)
            } else {
                idleNow(startId)
            }
            ACTION_RESUME -> {
                val paused = DownloadRegistry.paused
                if (paused == null) {
                    idleNow(startId)
                } else {
                    enqueue(JobSpec(startId, resumeJobIntent(paused), isResume = true))
                }
            }
            else -> {
                val i = intent ?: run {
                    idleNow(startId)
                    return START_NOT_STICKY
                }
                val fileName = i.getStringExtra(EXTRA_FILE)
                if (fileName.isNullOrBlank() ||
                    (i.getStringExtra(EXTRA_URL).isNullOrBlank() &&
                        i.getStringExtra(EXTRA_VIDEO_URL).isNullOrBlank())
                ) {
                    idleNow(startId)
                    return START_NOT_STICKY
                }
                val title = i.getStringExtra(EXTRA_TITLE) ?: fileName
                // espelho para a Central de Downloads (não afeta o download)
                DownloadBus.start(title, fileName)
                if (busy) DownloadBus.progress(fileName, getString(R.string.dl_queued), 0, true)
                enqueue(JobSpec(startId, i, isResume = false))
            }
        }
        return START_NOT_STICKY
    }

    // ---------- fila (um download de cada vez; a Central mostra a ordem) ----------

    private fun enqueue(spec: JobSpec) {
        synchronized(stateLock) { pending.addLast(spec) }
        pump()
    }

    private fun pump() {
        val spec = synchronized(stateLock) {
            if (busy) return
            if (pending.isEmpty()) return
            busy = true
            pending.removeFirst()
        }
        scope.launch {
            try {
                runDownload(spec)
            } finally {
                val drained = synchronized(stateLock) {
                    busy = false
                    pending.isEmpty()
                }
                if (drained) {
                    // fila vazia: remove o foreground (fim/falha/pausa já saiu em NOTIF_ID+1)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(spec.startId)
                } else {
                    pump()
                }
            }
        }
    }

    private fun handleCancelIntent(intent: Intent, startId: Int) {
        val target = intent.getStringExtra(EXTRA_FILE)
        val current = activeFile
        when {
            current != null && (target == null || target == current) -> {
                // cancela o download EM EXECUÇÃO (o fluxo reage no próximo ponto)
                cancelRequested = true
                killActive()
            }
            target != null && removeFromQueue(target) -> {
                // cancela um item que ainda está NA FILA (nem começou)
                DownloadRegistry.clearIf(target)
                DownloadBus.cancelled(target)
                idleNow(startId)
            }
            else -> {
                DownloadRegistry.clearIf(target)
                idleNow(startId)
            }
        }
    }

    private fun removeFromQueue(fileName: String): Boolean = synchronized(stateLock) {
        val it = pending.iterator()
        var removed = false
        while (it.hasNext()) {
            if (it.next().intent.getStringExtra(EXTRA_FILE) == fileName) {
                it.remove()
                removed = true
            }
        }
        removed
    }

    /** Nada para fazer agora → encerra o foreground (nunca derruba trabalho alheio). */
    private fun idleNow(startId: Int) {
        val nothing = synchronized(stateLock) { !busy && pending.isEmpty() }
        if (nothing) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    /** Interrompe o trabalho ATIVO de verdade: mata o python do yt-dlp e o stream OkHttp. */
    private fun killActive() {
        ytDlpProcessId?.let { id ->
            runCatching { YoutubeDL.destroyProcessById(id) }
        }
        currentCall?.cancel()
    }

    /** Lança Pausado/Cancelado se o usuário pediu — nos pontos de checagem do fluxo. */
    private fun checkStop() {
        if (cancelRequested) throw DownloadCancelled()
        if (pauseRequested) throw DownloadPaused()
    }

    private fun resumeJobIntent(p: DownloadRegistry.PausedDownload): Intent =
        Intent(this, DownloadService::class.java).apply {
            putExtra(EXTRA_URL, p.url)
            putExtra(EXTRA_FILE, p.fileName)
            putExtra(EXTRA_TITLE, p.title)
            putExtra(EXTRA_MIME, p.mime)
            putExtra(EXTRA_MODE, p.mode)
            putExtra(EXTRA_BITRATE, p.bitrate)
            p.videoUrl?.let { putExtra(EXTRA_VIDEO_URL, it) }
            p.engineFormat?.let { putExtra(EXTRA_FORMAT, it) }
            if (p.maxHeight > 0) putExtra(EXTRA_MAX_HEIGHT, p.maxHeight)
        }

    // ---------- o download em si (um JobSpec por vez) ----------

    private fun runDownload(spec: JobSpec) {
        val intent = spec.intent
        val url = intent.getStringExtra(EXTRA_URL)
        val fileName = intent.getStringExtra(EXTRA_FILE) ?: "audio.m4a"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: fileName
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "audio/mp4"
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DIRECT
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 320)
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        val engineFormat = intent.getStringExtra(EXTRA_FORMAT)
        val maxHeight = intent.getIntExtra(EXTRA_MAX_HEIGHT, 0)

        activeFile = fileName
        DownloadBus.setActive(fileName)
        DownloadRegistry.clearIf(fileName)
        DownloadBus.start(title, fileName)
        DownloadBus.progress(
            fileName,
            if (spec.isResume) getString(R.string.dl_resuming) else getString(R.string.dl_preparing),
            0,
            true
        )
        notificationProgress(fileName, 0, indeterminate = true).let { n ->
            lastProgressNotif = n
            startForeground(NOTIF_ID, n)
        }

        var tmpSource: File? = null
        var tmpOut: File? = null
        var tmpPart: File? = null
        try {
            checkStop()

            // ---------- PLANO A: yt-dlp embutido ----------
            if (!videoUrl.isNullOrBlank() && !engineFormat.isNullOrBlank()) {
                try {
                    val (savedUri, qualityNote) = runYtDlp(
                        videoUrl, engineFormat, bitrate, maxHeight,
                        fileName, title, spec.isResume
                    )
                    notifyFinished(title, fileName, savedUri, qualityNote)
                    return
                } catch (e: Exception) {
                    if (e is DownloadPaused || e is DownloadCancelled) throw e
                    // pausa/cancelamento mataram o yt-dlp no meio: NÃO cair no plano B
                    if (pauseRequested) throw DownloadPaused()
                    if (cancelRequested) throw DownloadCancelled()
                    if (url.isNullOrBlank()) throw e
                    Log.w(TAG, "yt-dlp falhou; tentando o plano B (URL direta)", e)
                    // honestidade: se era vídeo >720p, o plano B (faixa combinada)
                    // não alcança a altura pedida — avisar na notificação
                    val phaseMsg = if (engineFormat == "mp4" && maxHeight > 720) {
                        getString(R.string.notif_phase_fallback_720p)
                    } else {
                        getString(R.string.notif_phase_fallback)
                    }
                    showPhase(fileName, phaseMsg, 0, indeterminate = true)
                }
            }

            // ---------- PLANO B: URL direta (NewPipe + PoToken) ----------
            // (chegou aqui ⇒ url é não-nula: ou não havia plano A, ou o plano A
            // falhou e rethrow teria acontecido se url fosse nula)
            val legacyUrl = url ?: throw IOException("sem URL direta para o plano B")
            resetSpeed()
            val savedUri: Uri = if (mode == MODE_MP3) {
                val cache = File(applicationContext.cacheDir, "convert").apply { mkdirs() }
                // hash da URL no nome do .part: garante que a retomada só
                // aconteça com a MESMA faixa (qualidade) escolhida antes
                val src = File(cache, "$fileName.${legacyUrl.hashCode().toString(36)}.src")
                    .also { tmpSource = it }
                val mp3 = File(cache, fileName).also { tmpOut = it }

                downloadWithRetries(src, legacyUrl, fileName) { done, total ->
                    // download = 0–60% do total
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 400) {
                        lastNotify = now
                        trackSpeed(done)
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_download),
                            if (total > 0) (done * 60 / total).toInt().coerceIn(0, 60) else 0,
                            indeterminate = total <= 0,
                            speed = speedText()
                        )
                    }
                }
                checkStop()

                // conversão = 60–99%
                showPhase(fileName, getString(R.string.notif_phase_convert), 60)
                Mp3Converter.convert(src, mp3, bitrate, title) { p ->
                    showPhase(
                        fileName,
                        getString(R.string.notif_phase_convert),
                        60 + (p * 39).toInt().coerceAtMost(39)
                    )
                }
                checkStop()

                // guarda de bitrate: garante que o MP3 final está no bitrate
                // pedido (leitura do arquivo; re-encode só se vier abaixo)
                AudioQuality.ensureMp3Bitrate(mp3, bitrate, title) { p ->
                    showPhase(
                        fileName,
                        getString(R.string.notif_phase_convert),
                        60 + (p * 39).toInt().coerceAtMost(39)
                    )
                }
                checkStop()

                showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
                publish(mp3.inputStream().buffered(), mp3.length(), fileName, "audio/mpeg")
            } else {
                val parts = File(applicationContext.cacheDir, "parts").apply { mkdirs() }
                val part = File(parts, "$fileName.${legacyUrl.hashCode().toString(36)}.part")
                    .also { tmpPart = it }

                downloadWithRetries(part, legacyUrl, fileName) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 400) {
                        lastNotify = now
                        trackSpeed(done)
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_download),
                            pct(done, total),
                            indeterminate = total <= 0,
                            speed = speedText()
                        )
                    }
                }
                checkStop()

                showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
                publish(part.inputStream().buffered(), part.length(), fileName, mime)
            }
            notifyFinished(title, fileName, savedUri)
        } catch (e: DownloadPaused) {
            // parciais preservados (plano A: dir estável; plano B: .part/.src)
            keepPartials = true
            DownloadRegistry.paused = DownloadRegistry.PausedDownload(
                fileName, title, url, mime, mode, bitrate, videoUrl, engineFormat, maxHeight
            )
            DownloadBus.paused(fileName)
            notifyPaused(fileName)
        } catch (e: DownloadCancelled) {
            DownloadBus.cancelled(fileName)
            notifyCancelled(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Download falhou: $fileName", e)
            notifyFailed(fileName, e)
        } finally {
            if (!keepPartials) {
                tmpSource?.delete()
                tmpOut?.delete()
                tmpPart?.delete()
            }
            pauseRequested = false
            cancelRequested = false
            keepPartials = false
            ytDlpProcessId = null
            currentCall = null
            lastProgressNotif = null
            activeFile = null
            DownloadBus.setActive(null)
        }
    }

    /**
     * PLANO A: yt-dlp embutido — prepara o motor (1ª vez), baixa, converte,
     * VERIFICA a qualidade no arquivo e publica. Bloqueante; thread de IO.
     * Devolve (uri salva, nota de qualidade para a notificação final).
     */
    private fun runYtDlp(
        videoUrl: String,
        format: String,
        bitrate: Int,
        maxHeight: Int,
        fileName: String,
        title: String,
        isResume: Boolean
    ): Pair<Uri, String?> {
        YtDlpEngine.ensureReady(applicationContext) { statusMsg ->
            showPhase(fileName, statusMsg, 0, indeterminate = true)
        }
        checkStop()
        // diretório ESTÁVEL por pedido (não por timestamp): a retomada reencontra
        // os parciais do yt-dlp (.part/.ytdl) e continua de onde parou
        val outDir = File(
            applicationContext.cacheDir,
            "ytdlp/${Integer.toHexString("$videoUrl|$format|$maxHeight|$bitrate".hashCode())}"
        )
        try {
            val preset = when (format) {
                "mp3" -> YtDlpEngine.Preset.Mp3(bitrate)
                "m4a" -> YtDlpEngine.Preset.M4a(preferBitrate = bitrate.takeIf { it in 1..512 })
                "opus" -> YtDlpEngine.Preset.Opus(preferBitrate = bitrate.takeIf { it in 1..512 })
                else -> YtDlpEngine.Preset.Mp4(maxHeight = if (maxHeight > 0) maxHeight else 1080)
            }
            val produced0 = YtDlpEngine.download(
                videoUrl,
                preset,
                outDir,
                clean = !isResume,
                onProcessId = { ytDlpProcessId = it }
            ) { progress, _, line ->
                val now = System.currentTimeMillis()
                if (now - lastNotify > 400) {
                    lastNotify = now
                    if (progress >= 100f) {
                        // pós-processamento (converter/remuxar) — sem porcentagem
                        showPhase(fileName, getString(R.string.notif_phase_convert), 100, indeterminate = true)
                    } else {
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_download),
                            progress.toInt().coerceIn(0, 99),
                            indeterminate = false,
                            speed = speedFromLine(line)
                        )
                    }
                }
            }
            ytDlpProcessId = null
            checkStop()

            // GUARDAS DE QUALIDADE: mede o ARQUIVO produzido e garante que a
            // qualidade pedida está nele — MP3: bitrate real (re-encode se veio
            // abaixo); MP4: resolução real do vídeo. O resultado vai para a
            // notificação final — confirmação honesta, nunca rótulo falso.
            var produced = produced0
            var qualityNote: String? = null
            when (preset) {
                is YtDlpEngine.Preset.Mp3 -> {
                    produced = AudioQuality.ensureMp3Bitrate(produced, bitrate, title) { p ->
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_convert),
                            60 + (p * 39).toInt().coerceAtMost(39),
                            indeterminate = false
                        )
                    }
                    qualityNote = AudioQuality.actualBitrateKbps(produced)
                        ?.let { getString(R.string.notif_quality_audio, it) }
                }
                is YtDlpEngine.Preset.Mp4 -> {
                    val actual = VideoQuality.actualHeight(produced)
                    qualityNote = when {
                        actual == null -> null
                        // tolerância de 24px: alturas não-padrão não são "menor"
                        actual + 24 >= preset.maxHeight ->
                            getString(R.string.notif_quality_video_ok, actual)
                        else ->
                            getString(R.string.notif_quality_video_lower, preset.maxHeight, actual)
                    }
                }
                else -> {}
            }

            showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
            checkStop()
            val finalName = sanitizeFileName(produced.name)
            val finalMime = when (produced.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus", "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                "mp4" -> "video/mp4"
                // 1440p/4K sai em Matroska (VP9/AV1 + AAC) — v0.9.1
                "mkv" -> "video/x-matroska"
                else -> "application/octet-stream"
            }
            val saved = publish(produced.inputStream().buffered(), produced.length(), finalName, finalMime)
            return saved to qualityNote
        } finally {
            // pausa preserva os parciais no dir estável para a retomada continuar;
            // término, falha e cancelamento limpam
            if (!pauseRequested) outDir.deleteRecursively()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }

    override fun onDestroy() {
        scope.cancel()
        // serviço destruído com download rodando (ex.: app forçado a parar):
        // mata o python/OkHttp para não deixar processo órfão comendo rede
        killActive()
        super.onDestroy()
    }

    private var lastNotify = 0L

    // ---------- download com retomada ----------

    /**
     * Baixa a URL para [target], retomando de onde parou em até
     * [MAX_ATTEMPTS] tentativas. Erros de rede (conexão caiu) disparam
     * nova tentativa com Range; URL bloqueada (HTTP 4xx) aborta na hora.
     */
    private fun downloadWithRetries(
        target: File,
        url: String,
        label: String,
        onProgress: (Long, Long) -> Unit
    ) {
        var attempt = 1
        resetSpeed()
        while (true) {
            try {
                downloadOnce(target, url, onProgress)
                return
            } catch (e: BlockedStreamException) {
                // 403/4xx: a URL nasceu bloqueada — repetir não resolve
                throw e
            } catch (e: DownloadPaused) {
                throw e
            } catch (e: DownloadCancelled) {
                throw e
            } catch (e: Exception) {
                // o kill da pausa/cancelamento chega como IOException genérica:
                // traduz para o sinal certo em vez de "retomar"
                if (cancelRequested) throw DownloadCancelled()
                if (pauseRequested) throw DownloadPaused()
                if (attempt >= MAX_ATTEMPTS) throw e
                Log.w(TAG, "download caiu na tentativa $attempt (${e.message}); retomando")
                showPhase(
                    label,
                    getString(R.string.notif_retry, attempt + 1, MAX_ATTEMPTS),
                    0,
                    indeterminate = true
                )
                attempt++
                SystemClock.sleep(1500L * attempt) // backoff: 3s, 4,5s…
            }
        }
    }

    private fun downloadOnce(
        target: File,
        url: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val resumeFrom = if (target.exists()) target.length() else 0L
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
        if (resumeFrom > 0) rb.header("Range", "bytes=$resumeFrom-")

        val call = client.newCall(rb.build())
        currentCall = call
        try {
            call.execute().use { resp ->
                val append: Boolean = when {
                    resp.code == 206 -> true // retomada aceita pelo servidor
                    resp.code == 200 -> {    // servidor ignorou o Range: recomeça
                        target.delete()
                        false
                    }
                    resp.code == 416 && resumeFrom > 0 -> { // intervalo inválido: parte corrompida
                        target.delete()
                        throw IOException("arquivo local rejeitado pelo servidor (HTTP 416); recomeçando")
                    }
                    else -> throw BlockedStreamException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("resposta sem corpo")
                val total = resumeFrom + body.contentLength().coerceAtLeast(0)
                var done = if (append) resumeFrom else 0L
                FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                            // reação imediata entre chunks (pausar/cancelar)
                            if (pauseRequested) throw DownloadPaused()
                            if (cancelRequested) throw DownloadCancelled()
                        }
                        out.flush()
                    }
                }
            }
        } finally {
            currentCall = null
        }
        // resposta de erro do googlevideo às vezes vem com 200 e corpo minúsculo
        if (target.length() < MIN_BYTES) {
            target.delete()
            throw IOException("arquivo baixado incompleto (${target.length()} bytes)")
        }
    }

    /** HTTP 4xx na URL do stream: bloqueio do YouTube, não falha de rede. */
    private class BlockedStreamException(message: String) : IOException(message)

    /** Publica o arquivo final: na pasta escolhida (se houver) ou em Downloads/TuneGrab. */
    private fun publish(
        input: InputStream,
        total: Long,
        fileName: String,
        mime: String,
        onProgress: (Long) -> Unit = {}
    ): Uri {
        // pasta escolhida pelo usuário (Configurações → Pasta de download);
        // se ela estiver indisponível, cai de volta no padrão automaticamente
        val tree = SaveLocation.customTree(applicationContext)
        if (tree != null) {
            try {
                return saveToTree(input, tree, fileName, mime, onProgress)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao salvar na pasta escolhida; usando o padrão", e)
            }
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(input, fileName, mime, onProgress)
        } else {
            saveLegacy(input, fileName, onProgress)
        }
    }

    /** Salva na pasta escolhida via SAF (funciona do Android 7 ao mais novo). */
    private fun saveToTree(
        input: InputStream,
        tree: Uri,
        fileName: String,
        mime: String,
        onProgress: (Long) -> Unit
    ): Uri {
        val dir = DocumentFile.fromTreeUri(applicationContext, tree)
            ?: throw IOException("pasta escolhida indisponível")
        // nome único dentro da pasta (mesma regra do MediaStore: "arquivo (1).ext")
        var name = fileName
        var i = 1
        while (dir.findFile(name) != null) {
            val base = fileName.substringBeforeLast('.')
            val ext = fileName.substringAfterLast('.', "")
            name = if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext"
            i++
        }
        val doc = dir.createFile(mime, name)
            ?: throw IOException("não foi possível criar o arquivo na pasta escolhida")
        try {
            applicationContext.contentResolver.openOutputStream(doc.uri)?.use { out ->
                copy(input, out, onProgress)
            } ?: throw IOException("stream de saída indisponível")
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        return doc.uri
    }

    private fun saveViaMediaStore(
        input: InputStream,
        fileName: String,
        mime: String,
        onProgress: (Long) -> Unit
    ): Uri {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TuneGrab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("não foi possível criar o arquivo")
        try {
            resolver.openOutputStream(uri)?.use { out -> copy(input, out, onProgress) }
                ?: throw IOException("stream de saída indisponível")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        input: InputStream,
        fileName: String,
        onProgress: (Long) -> Unit
    ): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TuneGrab"
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("não foi possível criar a pasta")
        val target = uniqueFile(dir, fileName)
        try {
            target.outputStream().use { out -> copy(input, out, onProgress) }
            MediaScannerConnection.scanFile(applicationContext, arrayOf(target.absolutePath), null, null)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return Uri.fromFile(target)
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val name = if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, name)
            i++
        }
        return candidate
    }

    private fun copy(input: InputStream, out: OutputStream, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            done += n
            onProgress(done)
        }
        out.flush()
    }

    // ---------- Notificações ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun baseBuilder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

    private fun notificationProgress(name: String, percent: Int, indeterminate: Boolean): Notification =
        baseBuilder(getString(R.string.notif_downloading, name), "$percent%")
            .setProgress(100, percent, indeterminate)
            .addAction(
                0,
                getString(R.string.notif_action_pause),
                servicePendingIntent(ACTION_PAUSE, null, RC_PAUSE)
            )
            .addAction(
                0,
                getString(R.string.notif_action_cancel),
                servicePendingIntent(ACTION_CANCEL, name, RC_CANCEL)
            )
            .build()

    /** Notificação neutra para starts de controle sem trabalho pendente. */
    private fun idleNotification(): Notification =
        baseBuilder(getString(R.string.app_name), getString(R.string.dl_preparing)).build()

    private fun servicePendingIntent(action: String, targetFile: String?, requestCode: Int): PendingIntent {
        val i = Intent(this, DownloadService::class.java).setAction(action)
        targetFile?.let { i.putExtra(EXTRA_FILE, it) }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, requestCode, i, flags)
        } else {
            PendingIntent.getService(this, requestCode, i, flags)
        }
    }

    private fun showPhase(
        name: String,
        phase: String,
        percent: Int,
        indeterminate: Boolean = false,
        speed: String? = null
    ) {
        val p = percent.coerceIn(0, 100)
        val text = buildString {
            append(phase)
            append(" · ").append(p).append('%')
            if (!indeterminate && speed != null) append(" · ").append(speed)
        }
        val n = baseBuilder(getString(R.string.notif_downloading, name), text)
            .setProgress(100, p, indeterminate)
            .build()
        lastProgressNotif = n
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        // espelho para a Central de Downloads (não afeta o download)
        DownloadBus.progress(name, phase, p, indeterminate, speed)
    }

    private fun notifyPaused(fileName: String) {
        lastProgressNotif = null
        val b = baseBuilder(getString(R.string.notif_paused_title), fileName)
            .setOngoing(false)
            .addAction(
                0,
                getString(R.string.notif_action_resume),
                servicePendingIntent(ACTION_RESUME, null, RC_RESUME)
            )
            .addAction(
                0,
                getString(R.string.notif_action_cancel),
                servicePendingIntent(ACTION_CANCEL, fileName, RC_CANCEL)
            )
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, b.build())
    }

    private fun notifyCancelled(fileName: String) {
        lastProgressNotif = null
        val b = baseBuilder(getString(R.string.notif_cancelled_title), fileName)
            .setOngoing(false)
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, b.build())
    }

    private fun notifyFinished(title: String, fileName: String, uri: Uri, qualityNote: String? = null) {
        DownloadBus.finished(fileName)
        val text = if (qualityNote != null) "$fileName · $qualityNote" else fileName
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_done))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, b.build())
    }

    /**
     * Falha com o erro REAL à vista: texto expansível na notificação e ação
     * "Ver detalhes" que abre a tela de relatório com botão de copiar —
     * para nunca mais ficarmos cegos diante de um "Falha no download".
     */
    private fun notifyFailed(fileName: String, e: Exception) {
        val userMsg = friendlyFailure(e)
        DownloadBus.failed(fileName, userMsg)
        val tech = buildString {
            appendLine(fileName)
            appendLine()
            appendLine(userMsg)
            appendLine()
            append("Erro: ${e.javaClass.simpleName}")
            e.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
            appendLine()
            append("TuneGrab ${appVersion()} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
        val detailsIntent = Intent(this, CrashReportActivity::class.java).apply {
            putExtra(CrashReportActivity.EXTRA_REPORT, tech)
            putExtra(CrashReportActivity.EXTRA_FROM_NOTIFICATION, true)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            detailsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_failed))
            .setContentText("$fileName — $userMsg")
            .setStyle(NotificationCompat.BigTextStyle().bigText(tech))
            .setContentIntent(pending)
            .addAction(0, getString(R.string.notif_view_details), pending)
            .setOngoing(false)
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, b.build())
    }

    /** Traduz erros técnicos para mensagens que o usuário entende. */
    private fun friendlyFailure(e: Exception): String = when {
        e is BlockedStreamException -> getString(R.string.err_blocked_403)
        e is IOException -> getString(R.string.err_network)
        else -> e.message ?: getString(R.string.err_generic_short)
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun pct(done: Long, total: Long): Int =
        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0

    // ---------- velocidade ----------

    private fun resetSpeed() {
        speedBps = 0L
        speedMarkMs = 0L
        speedMarkDone = 0L
    }

    /** Janela móvel simples (plano B): bytes/s desde a última marca ≥ 400 ms. */
    private fun trackSpeed(done: Long) {
        if (speedMarkMs == 0L) {
            speedMarkMs = System.currentTimeMillis()
            speedMarkDone = done
            return
        }
        val now = System.currentTimeMillis()
        val dt = now - speedMarkMs
        if (dt < 400) return
        val bps = (done - speedMarkDone) * 1000L / dt
        // suavização: 75% histórico + 25% atual — sem serrilhado a cada chunk
        speedBps = if (speedBps == 0L) bps else (speedBps * 3 + bps) / 4
        speedMarkMs = now
        speedMarkDone = done
    }

    private fun speedText(): String? = if (speedBps > 0) formatSpeed(speedBps) else null

    /** Velocidade no texto do yt-dlp: "[download] 45.3% of 123MiB at 2.35MiB/s ETA 01:23". */
    private fun speedFromLine(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val m = SPEED_REGEX.find(line) ?: return null
        val v = m.groupValues[1].toFloatOrNull() ?: return null
        if (v <= 0f) return null
        val mult = when (m.groupValues[2]) {
            "K" -> 1024f
            "M" -> 1024f * 1024f
            "G" -> 1024f * 1024f * 1024f
            else -> 1f
        }
        return formatSpeed((v * mult).toLong())
    }

    private fun formatSpeed(bps: Long): String =
        Formatter.formatShortFileSize(applicationContext, bps) + "/s"

    companion object {
        private const val TAG = "TuneGrab"
        private const val CHANNEL_ID = "tunegrab_downloads"
        private const val NOTIF_ID = 100
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_MODE = "mode"
        const val EXTRA_BITRATE = "bitrate"

        /** Ações de controle (notificação + Central de Downloads). */
        const val ACTION_PAUSE = "com.tunegrab.app.action.PAUSE"
        const val ACTION_RESUME = "com.tunegrab.app.action.RESUME"
        const val ACTION_CANCEL = "com.tunegrab.app.action.CANCEL"
        private const val RC_PAUSE = 11
        private const val RC_CANCEL = 12
        private const val RC_RESUME = 13

        /** "at 2.35MiB/s" na linha de progresso do yt-dlp (velocidade do plano A). */
        private val SPEED_REGEX = Regex("at\\s+([\\d.]+)\\s*([KMGT]?)iB/s")

        /** Plano A: URL do vídeo para o motor yt-dlp embutido + preset de formato. */
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_FORMAT = "engine_format"
        const val EXTRA_MAX_HEIGHT = "engine_max_height"

        /** Tentativas de download (a primeira + 2 retomadas). */
        private const val MAX_ATTEMPTS = 3

        /** Resposta menor que isso é página de erro, não mídia. */
        private const val MIN_BYTES = 16L * 1024L

        const val MODE_DIRECT = "direct"
        const val MODE_MP3 = "mp3"

        /** Download direto (M4A ou MP4). */
        fun intent(
            context: Context,
            title: String,
            url: String,
            fileName: String,
            mime: String
        ): Intent = Intent(context, DownloadService::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_FILE, fileName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MIME, mime)
            putExtra(EXTRA_MODE, MODE_DIRECT)
        }

        /** Download + conversão MP3 no dispositivo. */
        fun mp3Intent(context: Context, title: String, url: String, fileName: String, bitrateKbps: Int): Intent =
            Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE, fileName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MIME, "audio/mpeg")
                putExtra(EXTRA_MODE, MODE_MP3)
                putExtra(EXTRA_BITRATE, bitrateKbps)
            }

        /** Pausar / cancelar: o download ATIVO (ou, no cancelamento, o item na fila). */
        fun controlIntent(context: Context, action: String, targetFile: String? = null): Intent =
            Intent(context, DownloadService::class.java).apply {
                setAction(action)
                targetFile?.let { putExtra(EXTRA_FILE, it) }
            }

        /** Retomar o download pausado (parâmetros vivem no DownloadRegistry). */
        fun resumeIntent(context: Context): Intent =
            Intent(context, DownloadService::class.java).setAction(ACTION_RESUME)
    }
}
