package com.tunegrab.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
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
            try {
                val savedUri: Uri = if (mode == MODE_MP3) {
                    val cache = File(applicationContext.cacheDir, "convert").apply { mkdirs() }
                    val src = File(cache, "$fileName.src").also { tmpSource = it }
                    val mp3 = File(cache, fileName).also { tmpOut = it }

                    downloadTo(src, url) { frac, _ ->
                        // download = 0–60% do total
                        showPhase(
                            fileName,
                            getString(R.string.notif_phase_download),
                            (frac * 60).toInt(),
                            indeterminate = frac < 0f
                        )
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
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", DownloaderImpl.USER_AGENT)
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("resposta sem corpo")
                        val total = body.contentLength()
                        publish(body.byteStream(), total, fileName, mime) { done ->
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
                        // quando o total não é conhecido, mostra a fase de salvamento
                        if (total <= 0) {
                            showPhase(fileName, getString(R.string.notif_phase_save), 99, indeterminate = true)
                        }
                    }
                }
                notifyFinished(title, fileName, savedUri)
            } catch (e: Exception) {
                notifyFailed(fileName, friendlyFailure(e))
            } finally {
                tmpSource?.delete()
                tmpOut?.delete()
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

    /** Baixa a URL para um arquivo local (usado na conversão MP3). */
    private fun downloadTo(target: File, url: String, onProgress: (Float, Long) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("resposta sem corpo")
            val total = body.contentLength()
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastNotifyLocal = 0L
                while (true) {
                    val n = body.byteStream().read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastNotifyLocal > 400) {
                        lastNotifyLocal = now
                        onProgress(if (total > 0) done.toFloat() / total else -1f, done)
                    }
                }
                out.flush()
            }
            if (target.length() < 1024L) throw IOException("arquivo baixado vazio/incompleto")
        }
    }

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

    private fun notifyFailed(fileName: String, msg: String) {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_failed))
            .setContentText("$fileName — $msg")
            .setOngoing(false)
            .setAutoCancel(true)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, b.build())
    }

    /** Traduz erros técnicos para mensagens que o usuário entende. */
    private fun friendlyFailure(e: Exception): String {
        val msg = e.message ?: return "erro desconhecido"
        return if (msg.contains("HTTP 403") || msg.contains("HTTP 4")) {
            getString(R.string.err_blocked_403)
        } else {
            msg
        }
    }

    private fun pct(done: Long, total: Long): Int =
        if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0

    companion object {
        private const val CHANNEL_ID = "tunegrab_downloads"
        private const val NOTIF_ID = 100
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_BITRATE = "bitrate"

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
