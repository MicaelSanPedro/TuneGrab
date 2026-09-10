package com.tunegrab.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Loader de abertura: logo + "Bem-vindo" em cursiva e, NO RODAPÉ, a versão
 * da build AO LADO da assinatura "feito por Micael San" (v0.18.10 — o nome
 * voltou pra tela de bem-vindo a pedido do autor, com o letreiro de led;
 * continua no rodapé do Início também). O número vem do BuildConfig da
 * build — nunca desatualiza. Puramente visual — nada de rede aqui. Depois
 * de ~2,6s abre a MainActivity e sai da pilha (voltar no splash fecha o
 * app, como se espera).
 *
 * Links compartilhados/abertos (ACTION_SEND/VIEW) continuam indo direto
 * para a MainActivity — o splash só aparece na abertura pelo ícone.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<View>(R.id.imgLogo)
        val welcome = findViewById<TextView>(R.id.txtWelcome)
        val footer = findViewById<View>(R.id.footerGroup)
        val version = findViewById<TextView>(R.id.txtVersion)

        // rodapé: "v0.18.10  ·  feito por" + nome em LedTextView (no XML) —
        // versão SEMPRE atual (vem do BuildConfig da build — nunca desatualiza)
        version.text = "v" + BuildConfig.VERSION_NAME +
            "  ·  " + getString(R.string.splash_made_by)

        // entrada suave: logo surge, o "Bem-vindo" cresce e assenta,
        // o rodapé (versão + assinatura) aparece por último
        logo.alpha = 0f
        logo.animate().alpha(1f).setDuration(500).start()

        welcome.alpha = 0f
        welcome.scaleX = 0.86f
        welcome.scaleY = 0.86f
        welcome.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(220).setDuration(700)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        footer.animate().alpha(1f)
            .setStartDelay(650).setDuration(600)
            .start()

        // sai sozinho; se o usuário apertou voltar antes, não empurra nada
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                // v0.19.6: 1ª abertura vai primeiro pro TUTORIAL (pedido do
                // autor) — só depois dele é que a barra montada aparece.
                // Links compartilhados/abertos não passam por aqui de
                // propósito: quem mandou um link sabe o que está fazendo.
                val next = if (OnboardingActivity.isDone(this)) MainActivity::class.java
                else OnboardingActivity::class.java
                startActivity(Intent(this, next))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, SPLASH_MS)
    }

    private companion object {
        const val SPLASH_MS = 2600L
    }
}
