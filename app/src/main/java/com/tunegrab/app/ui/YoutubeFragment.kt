package com.tunegrab.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.tunegrab.app.MainActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentYoutubeBinding

/**
 * Aba YOUTUBE (v0.19.2, pedido do autor: "um navegador abrindo o youtube pra
 * poder pegar as músicas de lá mesmo"): WebView embutida abrindo o YouTube
 * mobile. Quando o usuário está numa página de VÍDEO (watch, shorts ou
 * youtu.be), o botão BAIXAR flutua no canto — ele manda o link pro MESMO
 * fluxo da aba Início (handleSharedUrl: extração, seletor de formato, motor
 * de download e a ida automática pra Central). Nada de motor novo: é o
 * caminho de sempre, só que o link chega pelo navegador.
 *
 * Detalhes do design:
 *  - Navegação PRESA no YouTube (youtube.com/youtu.be + google.com pra
 *    login/consent); qualquer outra coisa abre no navegador do sistema.
 *  - O botão de VOLTAR do sistema recua o HISTÓRICO da web antes de sair.
 *  - O estado da navegação sobrevive à troca de abas (as abas são fragments
 *    substituídos — o histórico volta pelo saveState/restoreState).
 *  - Os cookies são os do sistema: quem logou no YouTube em Config. >
 *    Login do YouTube entra AQUI já logado (mesma CookieManager).
 */
class YoutubeFragment : Fragment() {

    private var _binding: FragmentYoutubeBinding? = null
    private val binding get() = _binding!!

    /** Volta o histórico da web; fica ligado só quando dá pra voltar. */
    private var webBack: OnBackPressedCallback? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentYoutubeBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val web = binding.webYt

        web.settings.apply {
            javaScriptEnabled = true       // o YouTube inteiro depende de JS
            domStorageEnabled = true       // preferências de player/consent
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false        // nada de file:// na navegação
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        web.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.background))

        // VOLTAR do sistema: recua a web ANTES de sair da aba (e antes de
        // fechar o app — o dispatcher da activity não tem callback próprio)
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (binding.webYt.canGoBack()) binding.webYt.goBack()
            }
        }
        webBack = back
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, back)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (isYouTubeInternal(url)) return false // a web mesma carrega
                // link de fora (play store, redes sociais, tel:, mailto:…):
                // abre no navegador do sistema — não sequestra o app
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (ignored: Throwable) {
                    true
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean
            ) {
                syncUi(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                syncUi(view)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressYt.progress = newProgress
                binding.progressYt.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        binding.btnGrab.setOnClickListener { grabCurrent() }

        // Retoma a navegação de onde parou (a troca de aba destrói o fragment;
        // o histórico fica guardado no companion e volta aqui)
        val saved = savedWebState
        var restored = false
        if (saved != null) {
            // restoreState devolve WebBackForwardList? — null = não restaurou
            restored = try {
                web.restoreState(saved) != null
            } catch (ignored: Throwable) {
                false
            }
        }
        if (!restored || web.url.isNullOrBlank()) {
            web.loadUrl(lastUrl ?: HOME_URL)
        }
    }

    /** Atualiza o que depende da página corrente: botão voltar da web e o
     *  BAIXAR (só existe em página de vídeo). O doUpdateVisitedHistory é
     *  quem manda no YouTube — a navegação de lá é SPA (History API). */
    private fun syncUi(view: WebView) {
        webBack?.isEnabled = view.canGoBack()
        lastUrl = view.url ?: lastUrl
        binding.btnGrab.visibility =
            if (videoIdOf(view.url) != null) View.VISIBLE else View.GONE
    }

    /** BAIXAR: manda o link atual pro MESMO fluxo da aba Início (o
     *  handleSharedUrl preenche o campo e dispara a busca/seletor de
     *  formato — e o download em si leva pra Central automaticamente). */
    private fun grabCurrent() {
        val url = binding.webYt.url ?: return
        if (videoIdOf(url) == null) return
        (activity as? MainActivity)?.openDownloadFor(url)
    }

    /** True pra navegação que deve acontecer DENTRO da aba: YouTube inteiro
     *  (vídeos, shorts, results, consent) + domínios do Google que o login e
     *  o consentimento usam. Todo o resto é do navegador do sistema. */
    private fun isYouTubeInternal(u: Uri): Boolean {
        if ((u.scheme?.lowercase() ?: "") !in listOf("http", "https")) return false
        val host = (u.host ?: "").lowercase()
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host == "google.com" || host.endsWith(".google.com")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val web = binding.webYt
        // guarda o histórico pra retomar quando a aba voltar
        try {
            val b = Bundle()
            web.saveState(b)
            savedWebState = b
        } catch (ignored: Throwable) {
        }
        lastUrl = web.url ?: lastUrl
        web.stopLoading()
        webBack = null
        // destroy() exige a view fora da árvore — remove antes
        (web.parent as? ViewGroup)?.removeView(web)
        try {
            web.destroy()
        } catch (ignored: Throwable) {
        }
        _binding = null
    }

    private fun videoIdOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val u = Uri.parse(url)
            val host = (u.host ?: "").lowercase()
            when {
                host == "youtu.be" ->
                    u.pathSegments.firstOrNull()?.takeIf { it.length >= 5 }
                host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                    u.path == "/watch" ->
                        u.getQueryParameter("v")?.takeIf { it.length >= 5 }
                    u.path?.startsWith("/shorts/") == true ->
                        u.pathSegments.getOrNull(1)?.takeIf { it.length >= 5 }
                    else -> null
                }
                else -> null
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    companion object {
        private const val HOME_URL = "https://m.youtube.com/"

        /** Histórico da WebView entre trocas de aba (as abas são fragments
         *  substituídos — sem isso, voltar pra aba recarregava do zero). */
        private var savedWebState: Bundle? = null

        /** Última página vista (rede de segurança do restoreState). */
        private var lastUrl: String? = null
    }
}
