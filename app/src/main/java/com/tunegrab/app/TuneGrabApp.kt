package com.tunegrab.app

import android.app.Application
import android.content.Intent
import android.util.Log

/**
 * Application class que instala um handler global de crashes:
 * captura qualquer Throwable não tratado, monta um relatório
 * e abre a [CrashReportActivity] para o usuário ver/copiar o erro,
 * em vez de apenas fechar o app sem explicação.
 */
class TuneGrabApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
