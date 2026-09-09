package com.tunegrab.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tunegrab.app.databinding.ActivityCookieLoginBinding

/**
 * LOGIN DO YOUTUBE no próprio app (v0.18.6): abre o youtube.com num WebView,
 * o usuário loga na conta DELE, e ao tocar em "Salvar cookies" o app captura
 * a sessão do android.webkit.CookieManager e grava o cookies.txt privado que
 * o yt-dlp passa a usar via --cookies ([YtCookies]).
 *
 * Por que resolve o bot-check: com a sessão logada o YouTube não trata a
 * extração anônima como bot — é a recomendação oficial do yt-dlp wiki
 * (o mesmo erro do print do micaelsan manda usar cookies).
 *
 * Notas de privacidade: os cookies ficam SÓ no arquivo privado do app
 * (filesDir), nunca em log, e seguem só para o processo do yt-dlp. O status
 * no rodapé avisa se a sessão logada já foi detectada (SID/HSID/SAPISID).
 * Google às vezes reclama de login em WebView ("este navegador pode não ser
 * seguro") — nesse caso o usuário usa o caminho B: importa o cookies.txt
 * exportado no PC (botão na aba Config.).
 */
class CookieLoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityCookieLoginBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCookieLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // youtube.com precisa de DOM storage e de popups/redirects do
            // login do Google; o UA padrão do WebView costuma passar
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
        // cookies de terceiros: o login do Google navega por contas compartilhadas
        cm.setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false // deixa TUDO rodar dentro (login do Google incluído)

            override fun onPageFinished(view: WebView, url: String) {
                refreshState()
            }
        }

        b.btnSaveCookies.setOnClickListener {
            val header = cm.getCookie(COOKIE_URL)
            if (header.isNullOrBlank()) {
                Toast.makeText(this, R.string.cookie_none, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = YtCookies.saveFromWebView(applicationContext, header)
            if (ok) {
                Toast.makeText(
                    this,
                    if (YtCookies.headerHasLogin(header)) R.string.cookie_saved_login
                    else R.string.cookie_saved_anon,
                    Toast.LENGTH_LONG
                ).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, R.string.cookie_save_failed, Toast.LENGTH_LONG).show()
            }
        }

        if (savedInstanceState == null) {
            b.webView.loadUrl(START_URL)
        } else {
            b.webView.restoreState(savedInstanceState)
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        CookieManager.getInstance().flush()
        refreshState()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onBackPressed() {
        // volta dentro do YouTube primeiro (o login tem várias etapas)
        if (b.webView.canGoBack()) b.webView.goBack() else super.onBackPressed()
    }

    /** Rodapé: sessão logada detectada? (checagem a cada página carregada) */
    private fun refreshState() {
        val header = try {
            CookieManager.getInstance().getCookie(COOKIE_URL)
        } catch (_: Throwable) {
            null
        }
        val logged = YtCookies.headerHasLogin(header)
        b.tvCookieState.text = getString(
            if (logged) R.string.cookie_state_login else R.string.cookie_state_anon
        )
        b.btnSaveCookies.visibility = View.VISIBLE
    }

    companion object {
        /** URL inicial e de captura: os cookies de sessão do YouTube vivem
         *  no escopo www.youtube.com (SID/HSID/SAPISID em .youtube.com). */
        private const val START_URL = "https://www.youtube.com/"
        private const val COOKIE_URL = "https://www.youtube.com"
    }
}
