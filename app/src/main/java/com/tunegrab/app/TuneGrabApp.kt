package com.tunegrab.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tunegrab.app.yt.YtDlpEngine
import com.tunegrab.app.yt.potoken.PoTokenManager
import kotlin.concurrent.thread

/**
 * Application class que instala um handler global de crashes:
 * captura qualquer Throwable não tratado, monta um relatório
 * e abre a [CrashReportActivity] para o usuário ver/copiar o erro,
 * em vez de apenas fechar o app sem explicação.
 */
class TuneGrabApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // contexto do gerador de PoTokens (BotGuard via WebView)
        PoTokenManager.init(this)
        // pré-aquece o motor yt-dlp em segundo plano: extrai python/yt-dlp e
        // atualiza a versão UMA vez, para o primeiro download sair sem espera
        thread(name = "ytdlp-warmup") {
            try {
                YtDlpEngine.ensureReady(this) { /* status silencioso no warm-up */ }
            } catch (t: Throwable) {
                Log.w("TuneGrab", "warm-up do yt-dlp falhou; será tentado no download", t)
            }
        }
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Crash não tratado", throwable)
                val report = buildString {
                    appendLine("TuneGrab crash")
                    appendLine(
                        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • ${Build.MODEL}"
                    )
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine(Log.getStackTraceString(throwable))
                }
                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(CrashReportActivity.EXTRA_REPORT, report)
                }
                startActivity(intent)
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                }
            } catch (_: Exception) {
            }
            systemHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}
