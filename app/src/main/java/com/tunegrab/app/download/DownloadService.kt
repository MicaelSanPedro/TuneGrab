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
import com.tunegrab.app.CrashReportActivity
import com.tunegrab.app.R
import com.tunegrab.app.audio.Mp3Converter
import com.tunegrab.app.yt.DownloaderImpl
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
 * Downloads/TuneGrab, com notificação de progresso em fases:
 *
 *  - MODO_DIRETO (M4A/MP4): download → arquivo final (0–100%)
 *  - MODO_MP3:              download (0–60%) → conversão LAME (60–99%) → salvar
 *
 * O download vai primeiro para um arquivo .part no cache e só depois é
 * publicado. Se a conexão cair no meio, ele RETOMA de onde parou
 * (Range HTTP) por até [MAX_ATTEMPTS] vezes — rede móvel oscila muito.
 * URLs bloqueadas (HTTP 403/4xx) não são repetidas: repetir não resolve,
 * o problema é a URL, e o usuário é avisado com a mensagem certa.
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
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, notificationProgress(fileName, 0, indeterminate = true))

        scope.launch {
            var tmpSource: File? = null
            var tmpOut: File? = null
            var tmpPart: File? = null
            try {
                val savedUri: Uri = if (mode == MODE_MP3) {
                    val cache = File(applicationContext.cacheDir, "convert").apply { mkdirs() }
                    // hash da URL no nome do .part: garante que a retomada só
                    // aconteça com a MESMA faixa (qualidade) escolhida antes
                    val src = File(cache, "$fileName.${url.hashCode().toString(36)}.src")
                        .also { tmpSource = it }
                    val mp3 = File(cache, fileName).also { tmpOut = it }

                    downloadWithRetries(src, url, fileName) { done, total ->
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

                    showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
                    publish(mp3.inputStream().buffered(), mp3.length(), fileName, "audio/mpeg")
                } else {
                    val parts = File(applicationContext.cacheDir, "parts").apply { mkdirs() }
                    val part = File(parts, "$fileName.${url.hashCode().toString(36)}.part")
                        .also { tmpPart = it }

                    downloadWithRetries(part, url, fileName) { done, total ->
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

    /** Publica o arquivo final em Downloads/TuneGrab (MediaStore API 29+ / File API 24–28). */
    private fun publish(
        input: InputStream,
        total: Long,
        fileName: String,
        mime: String,
        onProgress: (Long) -> Unit = {}
    ): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(input, fileName, mime, onProgress)
        } else {
            saveLegacy(input, fileName, onProgress)
        }
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
        val n = baseBuilder(getString(R.string.notif_downloading, name), "$phase · $percent%")
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun notifyFinished(title: String, fileName: String, uri: Uri) {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_done))
            .setContentText(fileName)
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
        private const val EXTRA_BITRATE = "bitrate"

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
