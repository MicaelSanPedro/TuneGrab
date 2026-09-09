package com.tunegrab.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Loader de abertura: logo + "Bem-vindo" em cursiva e, NO RODAPÉ, a versão
 * da build (v0.18.8, pedido do autor — "a partir de agora o nome da versão
 * deve ficar embaixo, na tela de bem-vindo"). A assinatura "feito por
 * micaelsan" agora mora no rodapé do Início, ao lado do número da versão.
 * Puramente visual — nada de rede aqui. Depois de ~2,6s abre a MainActivity
 * e sai da pilha (voltar no splash fecha o app, como se espera).
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
        val version = findViewById<TextView>(R.id.txtVersion)

        // versão SEMPRE atual (vem do BuildConfig da build — nunca desatualiza)
        version.text = "v" + BuildConfig.VERSION_NAME

        // entrada suave: logo surge, o "Bem-vindo" cresce e assenta,
        // a versão aparece por último no rodapé
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

        version.animate().alpha(1f)
            .setStartDelay(650).setDuration(600)
            .start()

        // sai sozinho; se o usuário apertou voltar antes, não empurra nada
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainActivity::class.java))
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
