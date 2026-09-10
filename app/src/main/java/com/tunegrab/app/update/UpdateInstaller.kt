package com.tunegrab.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fase 2 do update automático: BAIXA e INSTALA o APK da release dentro do app.
 *
 * Fluxo: card "Baixar atualização" → APK vai pra pasta própria do app
 * (Android/data/.../files/updates, sem permissão de armazenamento) com barra
 * de progresso no próprio card → "Instalar agora" abre o instalador do
 * sistema (a confirmação final do Android é inescapável por design — é o
 * sistema que assina embaixo, não o app).
 *
 * Seguranças:
 *  - rede móvel pede confirmação antes de gastar dados (~100MB por APK);
 *  - download parcial é apagado em qualquer falha/cancelamento;
 *  - o APK baixado só vale se o tamanho bater com o asset da release;
 *  - a assinatura da instalação é verificada pelo Android (nosso keystore).
 */
object UpdateInstaller {

    private const val HTTP_MIN_OK = 200

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Onde o APK da versão X fica guardado (pasta privada do app). */
    fun apkFile(context: Context, info: UpdateChecker.UpdateInfo): File =
        File(File(context.getExternalFilesDir(null), "updates"), "tunegrab-${info.version}.apk")

    /**
     * Limpeza: APKs de versões ANTIGAS saem da pasta de updates quando um
     * download novo começa (a pasta fica só com o APK corrente — nunca
     * acumula ~100MB por versão). A pasta INTEIRA é app-specific
     * (Android/data/…/files/updates): o Android apaga tudo quando o app é
     * desinstalado, como o autor exigiu.
     */
    private fun cleanOldApks(keep: File) {
        val dir = keep.parentFile ?: return
        dir.listFiles()?.forEach { f -> if (f.isFile && f != keep) f.delete() }
    }

    /** Já tem o APK desta versão baixado e completo? */
    fun isReady(context: Context, info: UpdateChecker.UpdateInfo): Boolean {
        val f = apkFile(context, info)
        return if (info.apkSize > 0L) {
            f.isFile && f.length() == info.apkSize
        } else {
            f.isFile && f.length() > 1_000_000L // sem tamanho conhecido: heuristicazinha
        }
    }

    /** Rede móvel (cobrada)? false = Wi-Fi/dados ilimitados/sem rede ativa. */
    fun isOnMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Baixa o APK com progresso (0–100). Apaga o parcial em qualquer falha. */
    suspend fun download(
        context: Context,
        info: UpdateChecker.UpdateInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (info.apkUrl.isBlank()) throw IOException("release sem APK")
        val target = apkFile(context, info)
        target.parentFile?.mkdirs() ?: throw IOException("pasta de update indisponível")
        cleanOldApks(target)
        try {
            val response = http.newCall(Request.Builder().url(info.apkUrl).build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("resposta sem corpo")
                val total = if (body.contentLength() > 0) body.contentLength() else info.apkSize
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (target.length() <= 0L) throw IOException("arquivo vazio")
            if (info.apkSize > 0L && target.length() != info.apkSize) {
                throw IOException("tamanho inesperado (${target.length()} ≠ ${info.apkSize})")
            }
            target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /** O aparelho pode instalar direto, ou precisa da autorização de fontes desconhecidas? */
    fun needsInstallPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return false // Android 7/8-: permissão global
        return !context.packageManager.canRequestPackageInstalls()
    }

    /** Tela do sistema "Instalar apps desconhecidos" já apontando pro TuneGrab. */
    fun unknownSourcesScreen(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** Abre o instalador do sistema com o APK baixado. */
    fun installIntent(context: Context, info: UpdateChecker.UpdateInfo): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile(context, info)
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun formatSize(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)
}
