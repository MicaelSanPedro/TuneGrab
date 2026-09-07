package com.tunegrab.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Tela de relatório de erro. Usada em dois cenários:
 *  1. crash do app (via TuneGrabApp): mostra o stack trace para copiar;
 *  2. falha de download (ação "Ver detalhes" da notificação): mostra o
 *     erro técnico do download com botão de copiar.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val fromNotification = intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)
        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: getString(R.string.err_generic_short)

        findViewById<TextView>(R.id.tvCrashTitle).setText(
            if (fromNotification) R.string.err_details_title else R.string.crash_title
        )
        findViewById<TextView>(R.id.tvCrashSubtitle).apply {
            setText(if (fromNotification) R.string.crash_subtitle_error else R.string.crash_subtitle)
        }

        val message = findViewById<TextView>(R.id.tvCrashReport)
        message.text = report

        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("TuneGrab erro", report))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            if (fromNotification) finish() else finishAffinity()
        }
    }

    companion object {
        const val EXTRA_REPORT = "report"
        const val EXTRA_FROM_NOTIFICATION = "from_notification"
    }
}
