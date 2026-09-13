package com.tunegrab.app.access

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tunegrab.app.MainActivity
import com.tunegrab.app.OnboardingActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.ActivityLockBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trava de acesso (v0.20.0): tela de convite do app. O Splash manda pra cá
 * quando o aparelho ainda não foi liberado (e links compartilhados que
 * tentarem pular o Splash batem no guard da MainActivity). Um campo, um
 * botão: a senha que o autor distribuiu.
 *
 * Regras cravadas com o autor:
 *  - A checagem é REMOTA (lista de hashes no repo) — a 1ª liberação PRECISA
 *    de internet; sem rede, mensagem clara e tenta de novo;
 *  - Acertou → AccessGate.markUnlocked → esse aparelho NUNCA mais pede
 *    senha (só limpando os dados do app);
 *  - Errou → mensagem e tentar de novo NA HORA (sem punição, sem atraso).
 *
 * Depois de liberar, o roteamento é o MESMO do Splash: tutorial na 1ª vez,
 * barra montada nas seguintes.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Já liberado (ex.: voltou pra cá depois de uma rotação de rota)?
        // Nada de pedir senha de novo — segue o jogo direto.
        if (AccessGate.isUnlocked(this)) {
            routeNext()
            return
        }
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnUnlock.setOnClickListener { submit() }
        binding.txtPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.txtPassword.requestFocus()
    }

    private fun submit() {
        val typed = binding.txtPassword.text?.toString().orEmpty()
        if (typed.isBlank()) {
            showStatus(getString(R.string.lock_err_empty), failed = true)
            return
        }
        setState(checking = true)
        lifecycleScope.launch {
            val verdict = AccessGate.verify(typed)
            setState(checking = false)
            when (verdict) {
                is AccessGate.Verdict.Accepted -> {
                    AccessGate.markUnlocked(applicationContext, verdict.id)
                    showStatus(getString(R.string.lock_ok), failed = false)
                    delay(500) // um respiro pro "aceita ✓" ser lido
                    if (!isFinishing && !isDestroyed) routeNext()
                }
                AccessGate.Verdict.Empty ->
                    showStatus(getString(R.string.lock_err_empty), true)
                AccessGate.Verdict.Wrong ->
                    showStatus(getString(R.string.lock_err_wrong), true)
                AccessGate.Verdict.Offline ->
                    showStatus(getString(R.string.lock_err_offline), true)
                AccessGate.Verdict.NoActive ->
                    showStatus(getString(R.string.lock_err_noactive), true)
                AccessGate.Verdict.Failed ->
                    showStatus(getString(R.string.lock_err_generic), true)
            }
        }
    }

    /** Durante a checagem nada é clicável — e o erro anterior some. */
    private fun setState(checking: Boolean) {
        binding.btnUnlock.isEnabled = !checking
        binding.txtPassword.isEnabled = !checking
        binding.progress.visibility = if (checking) View.VISIBLE else View.GONE
        if (checking) binding.txtStatus.visibility = View.GONE
    }

    private fun showStatus(message: String, failed: Boolean) {
        binding.txtStatus.text = message
        binding.txtStatus.setTextColor(
            getColor(if (failed) R.color.state_failed else R.color.state_done)
        )
        binding.txtStatus.visibility = View.VISIBLE
    }

    /** Mesmo roteamento do Splash: tutorial na 1ª vez, barra nas seguintes. */
    private fun routeNext() {
        val next = if (OnboardingActivity.isDone(this)) MainActivity::class.java
        else OnboardingActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
