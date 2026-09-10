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
import android.view.animation.PathInterpolator
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

    /** Deslize lateral do nome (v0.19.4): o texto SÓ anda pra frente/trás na
     * horizontal — nunca nasce de cima pra baixo (o singleLine do label mata
     * a quebra de linha que fazia o texto "subir" durante a abertura). */
    private val labelSlide = dp(10)

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
            // v0.19.4: SEM quebra de linha — com a largura animando de 0, um
            // TextView multilinha empilhava o texto e "desmontava de cima pra
            // baixo" durante a abertura (o bug que o autor filmou). Uma linha,
            // altura constante, o movimento vira 100% horizontal.
            isSingleLine = true
            alpha = 0f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT)
        }
        item.addView(icon, LayoutParams(dp(22), dp(22)))
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
                // v0.19.4: gap 6→10dp — com 6dp o nome nascia colado no ícone
                // (o logo do YouTube encosta na borda da caixa, os glifos do
                // Material Symbols têm folga própria); 10dp nivela todos.
                dp(10),
                // mutate(): instância própria por aba — a pill do fecho anima
                // alpha individual enquanto a do outro lado nasce opaca
                ContextCompat.getDrawable(context, R.drawable.bg_nav_item)?.mutate()
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
        tab.label.translationX = if (selected) 0f else -labelSlide
        tab.pill?.alpha = 255
        tab.item.background = if (selected) tab.pill else null
        tab.icon.setColorFilter(if (selected) colorActive else colorIdle)
        tab.item.requestLayout()
    }

    /**
     * O EMPURRÃO (v0.19.4): a largura do label anima (0 ↔ medida do texto) e
     * o requestLayout a cada frame reflowa o LinearLayout — os vizinhos
     * deslizam suave. O NOME SÓ ANDA PRA LADO: singleLine + translationX de
     * -10dp → 0 abrindo (o texto desliza pra fora do ícone) e 0 → -10dp
     * fechando, sem NENHUMA componente vertical. Mais lento e mais redondo
     * que antes (420ms/300ms com as curvas "emphasized" do Material 3: abrir
     * desacelera no fim, fechar acelera e sai) — a pill acompanha os DOIS
     * sentidos (nasce crescendo, morre encolhendo com fade) em vez de sumir
     * seca no primeiro frame do fecho.
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
        val fromTx = tab.label.translationX
        val toTx = if (open) 0f else -labelSlide
        val fromColor = if (open) colorIdle else colorActive
        val toColor = if (open) colorActive else colorIdle
        val fromPill = tab.pill?.alpha ?: 255
        val toPill = if (open) 255 else 0
        // abrindo: pill entra já no primeiro frame (cresce junto); fechando:
        // ela PERMANECE e encolhe com o item — sai só no fim da animação
        if (open) tab.item.background = tab.pill
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (open) 420L else 300L
            interpolator =
                if (open) PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
                else PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addUpdateListener { v ->
                val f = v.animatedValue as Float
                lp.width = fromWidth + ((toWidth - fromWidth) * f).roundToInt()
                lp.marginStart = fromGap + ((toGap - fromGap) * f).roundToInt()
                tab.label.alpha = fromAlpha + (toAlpha - fromAlpha) * f
                tab.label.translationX = fromTx + (toTx - fromTx) * f
                tab.icon.setColorFilter(argb.evaluate(f, fromColor, toColor) as Int)
                tab.pill?.alpha = (fromPill + (toPill - fromPill) * f).roundToInt()
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
                        tab.label.translationX = toTx
                        tab.icon.setColorFilter(toColor)
                        tab.pill?.alpha = toPill
                        if (!open) tab.item.background = null
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
