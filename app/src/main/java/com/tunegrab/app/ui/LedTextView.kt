package com.tunegrab.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView com efeito de "led passando": uma faixa de luz varre o texto em
 * loop, estilo painel de led. A cor base é a textColor normal definida no
 * XML; o brilho é um LinearGradient animado no shader do paint — nenhum
 * bitmap, nenhuma view extra, roda em qualquer API >= 21.
 *
 * Usado na assinatura da SplashActivity ("feito por micaelsan").
 */
class LedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var sweep: ValueAnimator? = null

    // Faixa de luz: lavanda médio → quase branco → lavanda médio (sino de luz)
    private val glowMid = 0xFFC4B5FD.toInt()
    private val glowHot = 0xFFF5F1FF.toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // o shader precisa da largura final do texto — só liga depois do layout
        if (w > 0 && sweep == null) startSweep(w)
    }

    override fun onDetachedFromWindow() {
        sweep?.cancel()
        sweep = null
        paint.shader = null
        super.onDetachedFromWindow()
    }

    private fun startSweep(width: Int) {
        val base = currentTextColor
        val band = width * 0.45f
        sweep = ValueAnimator.ofFloat(-band, width + band).apply {
            duration = 1900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val x = anim.animatedValue as Float
                paint.shader = LinearGradient(
                    x, 0f, x + band, 0f,
                    intArrayOf(base, glowMid, glowHot, glowMid, base),
                    floatArrayOf(0f, 0.30f, 0.50f, 0.70f, 1f),
                    Shader.TileMode.CLAMP
                )
                invalidate()
            }
            start()
        }
    }
}
