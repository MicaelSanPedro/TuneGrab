package com.tunegrab.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Tela exibida quando o app sofre um crash: mostra o stack trace
 * e permite copiá-lo para a área de transferência para reportar o bug.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val report = intent.getStringExtra(EXTRA_REPORT)
            ?: getString(R.string.err_generic_short)

        val message = findViewById<TextView>(R.id.tvCrashReport)
        message.text = report

        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("TuneGrab crash", report))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            finishAffinity()
        }
    }

    companion object {
        const val EXTRA_REPORT = "report"
    }
}
