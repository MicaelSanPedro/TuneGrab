package com.tunegrab.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.tunegrab.app.databinding.ActivityOnboardingBinding
import kotlin.math.roundToInt

/**
 * Tutorial de primeira abertura (v0.19.6, pedido do autor: "add um tutorial,
 * pra quando abrir o app pela primeira vez, de como usar — bem explicado e
 * bonito"): 5 páginas deslizáveis no ViewPager2 — Bem-vindo · Cole o link ·
 * Aba YouTube · Central de Downloads · Biblioteca — cada uma com a ilustração
 * no MESMO gradiente do ícone do app (violeta → fúcsia), título grande e
 * texto direto ao ponto.
 *
 * O Splash roteia pra cá ANTES da barra na 1ª abertura (pref "onboarding");
 * quem já abriu o app nunca mais vê — e a aba Config. ganhou "Ver tutorial
 * de novo", que reabre este tour (EXTRA_FROM_SETTINGS: termina SEM abrir a
 * MainActivity, que já está atrás). Pular e Começar marcam a pref — fechar
 * o app no MEIO do tour não marca nada, então quem não terminou vê de novo
 * na próxima abertura.
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Page(val iconRes: Int, val titleRes: Int, val bodyRes: Int)

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        Page(R.drawable.ic_music_note, R.string.onb1_title, R.string.onb1_body),
        Page(R.drawable.ic_paste, R.string.onb2_title, R.string.onb2_body),
        Page(R.drawable.ic_youtube, R.string.onb3_title, R.string.onb3_body),
        Page(R.drawable.ic_download, R.string.onb4_title, R.string.onb4_body),
        Page(R.drawable.ic_library_music, R.string.onb5_title, R.string.onb5_body)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = PageAdapter()
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                binding.btnNext.text =
                    if (position == pages.lastIndex) getString(R.string.onb_start)
                    else getString(R.string.onb_next)
                // na última o "Pular" não tem função — some (mas mantém o
                // espaço, pra barra de ação não pular de altura)
                binding.btnSkip.visibility =
                    if (position == pages.lastIndex) View.INVISIBLE else View.VISIBLE
            }
        })
        buildDots()

        binding.btnSkip.setOnClickListener { finishOnboarding() }
        binding.btnNext.setOnClickListener {
            val pos = binding.pager.currentItem
            if (pos >= pages.lastIndex) finishOnboarding()
            else binding.pager.setCurrentItem(pos + 1, true)
        }
    }

    /** Bolinhas do indicador: a ativa estica pra pílula de 20dp violeta
     *  (animateLayoutChanges no XML anima a troca de largura de graça). */
    private fun buildDots() {
        pages.forEach { _ ->
            binding.dots.addView(
                View(this),
                LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        updateDots(0)
    }

    private fun updateDots(active: Int) {
        for (i in 0 until binding.dots.childCount) {
            val dot = binding.dots.getChildAt(i)
            val isActive = i == active
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(if (isActive) 20 else 8)
            }
            dot.setBackgroundResource(
                if (isActive) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive
            )
        }
    }

    private fun finishOnboarding() {
        markDone(this)
        if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            finish() // "ver de novo": a Main já está atrás, só sair daqui
        } else {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_onboarding_page, parent, false)
            )

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val p = pages[position]
            holder.img.setImageResource(p.iconRes)
            holder.title.setText(p.titleRes)
            holder.body.setText(p.bodyRes)
        }
    }

    private class PageHolder(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.imgIcon)
        val title: TextView = v.findViewById(R.id.txtTitle)
        val body: TextView = v.findViewById(R.id.txtBody)
    }

    companion object {
        private const val PREFS = "onboarding"
        private const val KEY_DONE = "tutorial_done"
        const val EXTRA_FROM_SETTINGS = "from_settings"

        /** 1ª abertura de verdade? O Splash pergunta aqui antes de montar a barra. */
        fun isDone(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

        private fun markDone(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply()
        }
    }
}
