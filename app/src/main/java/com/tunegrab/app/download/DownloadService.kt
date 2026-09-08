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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Serviço em primeiro plano que baixa a faixa selecionada e salva em
 * Downloads/TuneGrab, com notificação de progresso em fases.
 *
 * PLANO A — motor yt-dlp EMBUTIDO (quando o intent tem EXTRA_VIDEO_URL):
 * yt-dlp faz extração + download + conversão sozinho (é a engine mantida
 * semanalmente contra as mudanças do YouTube). Progresso 0–100 direto dele.
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val fileName = intent?.getStringExtra(EXTRA_FILE) ?: "audio.m4a"
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: fileName
        val mime = intent?.getStringExtra(EXTRA_MIME) ?: "audio/mp4"
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_DIRECT
        val bitrate = intent?.getIntExtra(EXTRA_BITRATE, 320) ?: 320
        val videoUrl = intent?.getStringExtra(EXTRA_VIDEO_URL)
        val engineFormat = intent?.getStringExtra(EXTRA_FORMAT)
        val maxHeight = intent?.getIntExtra(EXTRA_MAX_HEIGHT, 0) ?: 0
        if (url.isNullOrBlank() && videoUrl.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, notificationProgress(fileName, 0, indeterminate = true))
        // espelho para a Central de Downloads (não afeta o download)
        DownloadBus.start(title, fileName)

        scope.launch {
            var tmpSource: File? = null
            var tmpOut: File? = null
            var tmpPart: File? = null
            try {
                // ---------- PLANO A: yt-dlp embutido ----------
                if (!videoUrl.isNullOrBlank() && !engineFormat.isNullOrBlank()) {
                    try {
                        val (savedUri, qualityNote) =
                            runYtDlp(videoUrl, engineFormat, bitrate, maxHeight, fileName, title)
                        notifyFinished(title, fileName, savedUri, qualityNote)
                        return@launch
                    } catch (e: Exception) {
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
                            showPhase(
                                fileName,
                                getString(R.string.notif_phase_download),
                                if (total > 0) (done * 60 / total).toInt().coerceIn(0, 60) else 0,
                                indeterminate = total <= 0
                            )
                        }
                    }

                    // conversão = 60–99%
                    showPhase(fileName, getString(R.string.notif_phase_convert), 60)
                    Mp3Converter.convert(src, mp3, bitrate, title) { p ->
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_convert),
                            60 + (p * 39).toInt().coerceAtMost(39)
                        )
                    }

                    // guarda de bitrate: garante que o MP3 final está no bitrate
                    // pedido (leitura do arquivo; re-encode só se vier abaixo)
                    AudioQuality.ensureMp3Bitrate(mp3, bitrate, title) { p ->
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_convert),
                            60 + (p * 39).toInt().coerceAtMost(39)
                        )
                    }

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
                            showPhase(
                                fileName,
                                getString(R.string.notif_phase_download),
                                pct(done, total),
                                indeterminate = total <= 0
                            )
                        }
                    }

                    showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
                    publish(part.inputStream().buffered(), part.length(), fileName, mime)
                }
                notifyFinished(title, fileName, savedUri)
            } catch (e: Exception) {
                Log.e(TAG, "Download falhou: $fileName", e)
                notifyFailed(fileName, e)
            } finally {
                tmpSource?.delete()
                tmpOut?.delete()
                tmpPart?.delete()
                // IMPORTANTE: remove a notificação de progresso da barra.
                // Antes usávamos STOP_FOREGROUND_DETACH, que mantinha a notificação
                // de progresso presa (ex.: “99%”) mesmo depois do download terminar.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
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
        title: String
    ): Pair<Uri, String?> {
        YtDlpEngine.ensureReady(applicationContext) { statusMsg ->
            showPhase(fileName, statusMsg, 0, indeterminate = true)
        }
        val outDir = File(applicationContext.cacheDir, "ytdlp/${System.currentTimeMillis()}")
        try {
            val preset = when (format) {
                "mp3" -> YtDlpEngine.Preset.Mp3(bitrate)
                "m4a" -> YtDlpEngine.Preset.M4a(preferBitrate = bitrate.takeIf { it in 1..512 })
                "opus" -> YtDlpEngine.Preset.Opus(preferBitrate = bitrate.takeIf { it in 1..512 })
                else -> YtDlpEngine.Preset.Mp4(maxHeight = if (maxHeight > 0) maxHeight else 1080)
            }
            val produced0 = YtDlpEngine.download(videoUrl, preset, outDir) { progress, _ ->
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
                            indeterminate = false
                        )
                    }
                }
            }

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
            val finalName = sanitizeFileName(produced.name)
            val finalMime = when (produced.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus", "ogg" -> "audio/ogg"
                "webm" -> "audio/webm"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            val saved = publish(produced.inputStream().buffered(), produced.length(), finalName, finalMime)
            return saved to qualityNote
        } finally {
            outDir.deleteRecursively()
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }

    override fun onDestroy() {
        scope.cancel()
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
        while (true) {
            try {
                downloadOnce(target, url, onProgress)
                return
            } catch (e: BlockedStreamException) {
                // 403/4xx: a URL nasceu bloqueada — repetir não resolve
                throw e
            } catch (e: Exception) {
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

        client.newCall(rb.build()).execute().use { resp ->
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
                    }
                    out.flush()
                }
            }
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
            .build()

    private fun showPhase(name: String, phase: String, percent: Int, indeterminate: Boolean = false) {
        val p = percent.coerceIn(0, 100)
        val n = baseBuilder(getString(R.string.notif_downloading, name), "$phase · $p%")
            .setProgress(100, p, indeterminate)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        // espelho para a Central de Downloads (não afeta o download)
        DownloadBus.progress(name, phase, p, indeterminate)
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
    }
}
