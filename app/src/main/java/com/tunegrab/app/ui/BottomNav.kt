package com.tunegrab.app.ui

import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tunegrab.app.R

/**
 * Barra de navegação inferior (Início · YouTube · Downloads · Músicas ·
 * Configurações).
 *
 * PADRÃO SINGLE-ACTIVITY: as 5 abas são fragments dentro da MainActivity e a
 * troca é um replace() com um FADE curtinho (v0.19.2, pedido do autor — a
 * troca seca virou cruzamento de 140ms), sem recriar a janela. Foi
 * exatamente a recriação da activity (multi-activity antigo) que dava aquele
 * "redimensionar + chacoalhar" ao trocar de aba.
 */
object BottomNav {

    fun setup(activity: AppCompatActivity, nav: BottomNavigationView, containerId: Int, defaultItemId: Int) {
        // marcado ANTES do listener: não dispara navegação no onCreate
        nav.selectedItemId = defaultItemId
        nav.setOnItemSelectedListener { item ->
            navigate(activity, nav, containerId, item.itemId)
            true
        }
        // abre a aba inicial DE FATO: selectedItemId antes do listener não
        // dispara navegação, e sem isso o app abria em tela branca até o
        // primeiro toque numa aba. navigate() é idempotente (mesma aba =
        // nada a fazer), então rotação/recriação não recarrega à toa.
        navigate(activity, nav, containerId, defaultItemId)
    }

    private fun navigate(activity: AppCompatActivity, nav: BottomNavigationView, containerId: Int, itemId: Int) {
        val tag = when (itemId) {
            R.id.navHome -> "home"
            R.id.navYouTube -> "youtube"
            R.id.navDownloads -> "downloads"
            R.id.navLibrary -> "library"
            R.id.navSettings -> "settings"
            else -> return
        }
        val fm = activity.supportFragmentManager
        val current = fm.findFragmentById(containerId)
        if (current?.tag == tag) return // mesma aba: nada a fazer

        // teclado fechado antes da troca (evita resize estranho)
        activity.currentFocus?.clearFocus()
        activity.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(activity.currentFocus?.windowToken, 0)

        val fragment = when (itemId) {
            R.id.navHome -> HomeFragment()
            R.id.navYouTube -> YoutubeFragment()
            R.id.navDownloads -> DownloadsFragment()
            R.id.navLibrary -> LibraryFragment()
            R.id.navSettings -> SettingsFragment()
            else -> return
        }
        fm.beginTransaction()
            .setReorderingAllowed(true)
            // FADE RÁPIDO entre abas (v0.19.2): 140ms de cruzamento — só pra
            // não ficar seco, sem atrapalhar quem troca de aba em sequência
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            .replace(containerId, fragment, tag)
            .commit()
    }
}
