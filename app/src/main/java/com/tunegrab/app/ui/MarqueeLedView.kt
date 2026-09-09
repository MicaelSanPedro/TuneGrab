package com.tunegrab.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Letreiro de led ROLANTE: o texto desliza da direita pra esquerda em loop,
 * estilo painel de led de verdade. A faixa de luz fica FIXA no centro do
 * painel — o trecho do texto que passa por ela acende (quase branco) e volta
 * à cor base ao sair. O gradiente é reconstruído a cada frame já compensando
 * a translação do canvas, pra faixa nunca "andar" junto com o texto.
 *
 * Usado no header do Início com o nome do app (v0.16.0).
 */
class MarqueeLedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var animator: ValueAnimator? = null
    private var offset = 0f

    // tom "aceso" da faixa (quase branco lavanda)
    private val hot = 0xFFF5F1FF.toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // troca de aba destrói a view do fragmento: ao voltar pro Início,
        // o letreiro precisa religar mesmo sem passar por onSizeChanged de novo
        if (width > 0 && animator == null) start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        paint.shader = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && animator == null) start()
    }

    private fun start() {
        val textW = paint.measureText(text.toString())
        val travel = width + textW + width * 0.25f // painel + texto + respiro entre as passadas
        animator = ValueAnimator.ofFloat(0f, travel).apply {
            duration = (travel / (SPEED_DP_PER_SEC * resources.displayMetrics.density)).toLong()
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                offset = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val textW = paint.measureText(text.toString())
        val x = width - offset // entra pela direita e sai pela esquerda

        // faixa fixa no centro do PAINEL: o canvas será transladado em x,
        // então o gradiente é deslocado em -x pra ficar parado na view
        val cx = width / 2f - x
        val band = width * 0.30f
        val base = currentTextColor
        paint.shader = LinearGradient(
            cx - band, 0f, cx + band, 0f,
            intArrayOf(base, base, hot, base, base),
            floatArrayOf(0f, 0.38f, 0.50f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.translate(x, 0f)
        super.onDraw(canvas)
        canvas.restore()
    }

    private companion object {
        const val SPEED_DP_PER_SEC = 55f
    }
}
