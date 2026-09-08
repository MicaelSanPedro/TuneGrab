package com.tunegrab.app.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tunegrab.app.DownloadsActivity
import com.tunegrab.app.LibraryActivity
import com.tunegrab.app.MainActivity
import com.tunegrab.app.R
import com.tunegrab.app.SettingsActivity

/**
 * Barra de navegação inferior compartilhada pelas 4 telas
 * (Início · Downloads · Músicas · Configurações).
 *
 * Padrão multi-activity: cada tela é uma Activity própria com a mesma barra;
 * navegar = abrir a activity de destino com CLEAR_TOP|SINGLE_TOP (a pilha
 * fica enxuta e o botão "voltar" do sistema continua natural).
 */
object BottomNav {

    fun setup(nav: BottomNavigationView, activity: AppCompatActivity, currentItemId: Int) {
        // marcado ANTES do listener: não dispara navegação no onCreate
        nav.selectedItemId = currentItemId
        nav.setOnItemSelectedListener { item ->
            navigate(activity, item.itemId)
            true
        }
    }

    private fun navigate(activity: AppCompatActivity, itemId: Int) {
        val target = when (itemId) {
            R.id.navHome -> MainActivity::class.java
            R.id.navDownloads -> DownloadsActivity::class.java
            R.id.navLibrary -> LibraryActivity::class.java
            R.id.navSettings -> SettingsActivity::class.java
            else -> null
        } ?: return
        if (target == activity.javaClass) return
        val intent = Intent(activity, target)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
        // sem animação: a troca de aba fica instantânea
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
