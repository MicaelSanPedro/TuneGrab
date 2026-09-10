package com.tunegrab.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.tunegrab.app.databinding.ActivityMainBinding
import com.tunegrab.app.download.DownloadService
import com.tunegrab.app.ui.BottomNav
import com.tunegrab.app.ui.HomeFragment

/**
 * Hospedeiro das 5 abas (Início · YouTube · Downloads · Músicas ·
 * Configurações). Só cuida da barra de navegação e dos intents de
 * COMPARTILHAR/ABRIR link do YouTube (que caem sempre na aba Início).
 * Todo o resto vive nos fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Link compartilhado que chegou antes da aba Início estar pronta. */
    private var pendingSharedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BottomNav.setup(this, binding.navBar.bottomNav, R.id.fragmentContainer, R.id.navHome)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // v0.19.5: toque em qualquer notificação de download abre a CENTRAL.
        // App fechado: onCreate → a barra já nasce na Central; app aberto:
        // onNewIntent troca a aba (singleTask sempre cai aqui)
        if (intent?.action == DownloadService.ACTION_OPEN_CENTRAL) {
            openTab(R.id.navDownloads)
            return
        }
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val home = supportFragmentManager.findFragmentById(R.id.fragmentContainer) as? HomeFragment
        if (home != null && home.isAdded) {
            home.handleSharedUrl(shared)
        } else {
            pendingSharedUrl = shared
        }
    }

    /** Chamado pela HomeFragment quando ela fica pronta (consome o link pendente). */
    fun consumePendingSharedUrl(): String? = pendingSharedUrl.also { pendingSharedUrl = null }

    /** Abre uma aba pelo id do menu (usado pelo botão de engrenagem da Home). */
    fun openTab(itemId: Int) {
        if (binding.navBar.bottomNav.selectedItemId != itemId) {
            binding.navBar.bottomNav.selectedItemId = itemId
        }
    }

    /**
     * PEGAR DO NAVEGADOR (v0.19.2): o botão "Baixar" da aba YouTube manda o
     * link aqui — cai no MESMO fluxo da aba Início (campo preenchido + busca
     * + seletor de formato + download que leva pra Central sozinho).
     */
    fun openDownloadFor(url: String) {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current is HomeFragment && current.isAdded) {
            current.handleSharedUrl(url)
            return
        }
        pendingSharedUrl = url
        openTab(R.id.navHome)
    }
}
