package com.tunegrab.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tunegrab.app.R
import kotlin.math.roundToInt

/**
 * Barra de navegação "empurra" (v0.19.3, pedido do autor): a aba clicada
 * CRESCE — ícone + nome LADO A LADO dentro de uma pill — e os outros
 * ícones deslizam pros lados abrindo espaço. O nome não nasce "do nada,
 * sequinho": desliza pra fora com fade enquanto a pill se expande.
 *
 * Por que custom em vez de BottomNavigationView: o componente do Material
 * empilha o rótulo ABAIXO do ícone (labelVisibilityMode) — nada se move na
 * horizontal, não existe empurrão lá. Aqui a largura do label é ANIMADA de
 * 0 até a medida do texto, dentro de um LinearLayout de gravity center:
 * crescer um filho reflowa a barra a cada frame — é ISSO o empurrão, os
 * vizinhos são empurrados de verdade (o último selecionado empurra todos
 * pra esquerda, exatamente o efeito pedido).
 *
 * API enxuta no lugar do BottomNavigationView:
 *  - addTab(itemId, icon, label): monta as 5 abas (BottomNav.setup, em código)
 *  - selectedItemId: lê/troca a aba (MainActivity.openTab usa como antes)
 *  - setOnItemSelectedListener: callback de troca (fun interface, SAM)
 * A troca SEMPRE anima, menos a montagem inicial (select animate=false) —
 * o app abre com a aba Início já em pé, sem chacoalho.
 */
class ExpandingNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callback de troca de aba (mesma semântica do BottomNavigationView). */
    fun interface Listener {
        fun onTabSelected(itemId: Int): Boolean
    }

    private class Tab(
        val itemId: Int,
        val item: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val labelWidth: Int,
        val labelGap: Int,
        val pill: Drawable?
    ) {
        var animator: ValueAnimator? = null
    }

    private val tabs = ArrayList<Tab>(5)
    private var listener: Listener? = null
    private var selectedId = View.NO_ID

    private val colorActive = ContextCompat.getColor(context, R.color.primary)
    private val colorIdle = ContextCompat.getColor(context, R.color.on_surface_variant)
    private val argb = ArgbEvaluator()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** Monta uma aba (chamado pelo BottomNav.setup — ids de res/values/ids.xml). */
    fun addTab(itemId: Int, iconRes: Int, labelRes: Int) {
        val title = context.getString(labelRes)
        val item = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { select(itemId, animate = true) }
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(colorIdle)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = title
            textSize = 12.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(colorActive)
            alpha = 0f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT)
        }
        item.addView(icon, LayoutParams(dp(20), dp(20)))
        item.addView(label)
        addView(
            item,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
        )
        // medida do texto uma vez (animação usa este alvo)
        label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        tabs.add(
            Tab(
                itemId,
                item,
                icon,
                label,
                label.measuredWidth,
                dp(6),
                ContextCompat.getDrawable(context, R.drawable.bg_nav_item)
            )
        )
    }

    fun setOnItemSelectedListener(l: Listener?) {
        listener = l
    }

    /** Aba ativa. Setar por aqui troca a aba COM animação (openTab da Main). */
    var selectedItemId: Int
        get() = selectedId
        set(value) {
            select(value, animate = true)
        }

    /**
     * Seleciona uma aba. animate=false é só a montagem inicial (sem
     * chacoalho ao abrir o app); o callback [Listener] dispara sempre que a
     * aba muda de verdade — no setup o listener entra DEPOIS da seleção
     * inicial, mesmo padrão do BottomNavigationView antigo ("marcado antes
     * do listener: não dispara navegação no onCreate").
     */
    fun select(itemId: Int, animate: Boolean) {
        if (itemId == selectedId) return
        val prev = tabs.firstOrNull { it.itemId == selectedId }
        val next = tabs.firstOrNull { it.itemId == itemId } ?: return
        selectedId = itemId
        if (!animate) {
            tabs.forEach { applyState(it, it.itemId == itemId) }
        } else {
            prev?.let { animateTab(it, open = false) }
            animateTab(next, open = true)
        }
        listener?.onTabSelected(itemId)
    }

    /** Estado final SEM animação (montagem inicial). */
    private fun applyState(tab: Tab, selected: Boolean) {
        tab.animator?.cancel()
        tab.animator = null
        (tab.label.layoutParams as LayoutParams).width =
            if (selected) tab.labelWidth else 0
        (tab.label.layoutParams as LayoutParams).marginStart =
            if (selected) tab.labelGap else 0
        tab.label.alpha = if (selected) 1f else 0f
        tab.item.background = if (selected) tab.pill else null
        tab.icon.setColorFilter(if (selected) colorActive else colorIdle)
        tab.item.requestLayout()
    }

    /**
     * O EMPURRÃO: a largura do label anima (0 ↔ medida do texto) e o
     * requestLayout a cada frame reflowa o LinearLayout — os vizinhos
     * deslizam suave. Abrir dura um pouco mais que fechar (a entrada pede
     * presença; a saída é só recuo). A cor do ícone acompanha (idle →
     * ativo) e a pill já nasce junto com o crescimento.
     */
    private fun animateTab(tab: Tab, open: Boolean) {
        tab.animator?.cancel()
        val lp = tab.label.layoutParams as LayoutParams
        val fromWidth = lp.width
        val toWidth = if (open) tab.labelWidth else 0
        val fromGap = lp.marginStart
        val toGap = if (open) tab.labelGap else 0
        val fromAlpha = tab.label.alpha
        val toAlpha = if (open) 1f else 0f
        val fromColor = if (open) colorIdle else colorActive
        val toColor = if (open) colorActive else colorIdle
        tab.item.background = if (open) tab.pill else null
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (open) 240L else 180L
            interpolator =
                if (open) DecelerateInterpolator(1.6f) else AccelerateInterpolator(1.2f)
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                lp.width = fromWidth + ((toWidth - fromWidth) * f).roundToInt()
                lp.marginStart = fromGap + ((toGap - fromGap) * f).roundToInt()
                tab.label.alpha = fromAlpha + (toAlpha - fromAlpha) * f
                tab.icon.setColorFilter(argb.evaluate(f, fromColor, toColor) as Int)
                tab.item.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: Animator) {
                    tab.animator = null
                    if (!cancelled) {
                        // estado final garantido (dígito a dígito)
                        lp.width = toWidth
                        lp.marginStart = toGap
                        tab.label.alpha = toAlpha
                        tab.icon.setColorFilter(toColor)
                        tab.item.requestLayout()
                    }
                }
            })
            start()
        }
        tab.animator = anim
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
