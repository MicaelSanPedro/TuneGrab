package com.tunegrab.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.tunegrab.app.playback.SpectrumBus
import kotlin.math.pow

/**
 * AS "BARRINHAS DE DJ" do player (v0.18.8) — espectro REAL, sem permissão.
 *
 * Diferente da v0.18.7 (que dependia do android.media.audiofx.Visualizer +
 * RECORD_AUDIO e em muitos aparelhos voltava vazio), aqui a fonte é o
 * SpectrumProcessor injetado no ExoPlayer do PlaybackService: ele ESPIA o
 * PCM que o próprio app está tocando (antes de sair no alto-falante) e
 * publica as 28 bandas de frequência no SpectrumBus. Esta view SÓ desenha:
 *  - ataque INSTANTÂNEO (a barra sobe na pancada do grave) e decaimento
 *    constante — o combo clássico dos visualizers;
 *  - pontinho de PICO com gravidade acumulada, caindo de volta (mesa de DJ);
 *  - bandas LOGARÍTMICAS (graves à esquerda, agudos à direita — log é a cara
 *    de mixer; linear deixa o espectro torto);
 *  - curva perceptual (^0.75) pra agudos fracos aparecerem.
 *
 * Sem dado novo (música pausada, player fechado), os alvos viram zero após
 * um instante e as barras CAEM SOZINHAS — nenhum estado de player é
 * consultado, e NUNCA entra animação falsa no lugar do espectro real.
 * Sem permissão nenhuma: o som é lido por dentro do app, não do microfone.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Nível suavizado de cada banda (0..1) — o que o desenho mostra. */
    private val display = FloatArray(BARS)

    /** Pico de cada banda (o pontinho de DJ que cai devagar). */
    private val peaks = FloatArray(BARS)

    /** Velocidade de queda de cada pico (gravidade acumulada). */
    private val peakVel = FloatArray(BARS)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6E7E9F0.toInt() // on_background translúcido — legível sobre o gradiente
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30A78BFA // grade "apagada": mostra onde as barras nascem
    }
    private val barRect = RectF()
    private var gradient: LinearGradient? = null
    private var gradientW = 0
    private var gradientH = 0

    /** Loop de frames ligado entre attach() e detach()/setHostPaused(true). */
    private var running = false

    /**
     * Liga o desenho (as barras dançam com o SpectrumBus que o
     * PlaybackService alimenta). NÃO precisa de permissão — sempre dá certo.
     */
    fun attach(): Boolean {
        running = true
        invalidate()
        return true
    }

    /** Para o loop de frames (a view sai da tela / activity morre). */
    fun detach() {
        running = false
    }

    /**
     * App foi pro fundo (onStop): para o loop (bateria); voltou (onResume):
     * religa. O SOM não é afetado — só o desenho.
     */
    fun setHostPaused(paused: Boolean) {
        running = !paused
        if (running) invalidate()
    }

    /**
     * Consome o SpectrumBus: alvo de cada banda = nível bruto publicado pelo
     * SpectrumProcessor com curva perceptual. Dado "velho" (> STALE_MS sem
     * publicação nova — faixa pausada/encerrada) = alvo zero → as barras
     * caem sozinhas pelo decay.
     */
    private fun ingest() {
        val bus = SpectrumBus.bands
        val fresh = bus != null &&
            (SystemClock.elapsedRealtime() - SpectrumBus.stampMs) < STALE_MS
        for (i in 0 until BARS) {
            val target = if (fresh && i < bus!!.size) {
                (bus[i] * GAIN).pow(0.75f).coerceIn(0f, 1f)
            } else {
                0f
            }
            val d = display[i]
            display[i] = if (target > d) target else (d - DECAY).coerceAtLeast(0f)
            if (target >= peaks[i]) {
                peaks[i] = target
                peakVel[i] = 0f
            } else {
                peakVel[i] = (peakVel[i] + GRAVITY).coerceAtMost(MAX_FALL)
                peaks[i] = (peaks[i] - peakVel[i]).coerceAtLeast(0f)
            }
        }
    }

    // ---------- desenho ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        if (gradient == null || gradientW != w || gradientH != h) {
            // gradiente do app: violeta escuro (base) → violeta → fúcsia (topo)
            gradient = LinearGradient(
                0f, h.toFloat(), 0f, 0f,
                intArrayOf(0xFF6D28D9.toInt(), 0xFFA78BFA.toInt(), 0xFFF472B6.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            barPaint.shader = gradient
            gradientW = w
            gradientH = h
        }

        ingest()

        val density = resources.displayMetrics.density
        val gap = GAP_DP * density
        val barW = (w - gap * (BARS - 1)) / BARS
        val minH = MIN_BAR_DP * density
        val peakH = PEAK_DP * density

        for (i in 0 until BARS) {
            val left = i * (barW + gap)
            // grade apagada (a "pista") — mostra onde as barras nascem
            barRect.set(left, h - minH, left + barW, h.toFloat())
            canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, idlePaint)
            // barra viva — altura REAL do nível da banda
            val level = display[i]
            val barH = minH + (h - minH) * level
            if (level > 0.01f) {
                barRect.set(left, h - barH, left + barW, h.toFloat())
                canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, barPaint)
            }
            // pontinho de pico (cai com gravidade) — só se há sinal
            if (peaks[i] > 0.02f) {
                val peakY = h - minH - (h - minH) * peaks[i] - peakH
                barRect.set(left, peakY, left + barW, peakY + peakH)
                canvas.drawRoundRect(barRect, peakH / 2f, peakH / 2f, peakPaint)
            }
        }

        // loop de frames: enquanto a view está "attachada" e visível, ela
        // mesma se redesenha (~60 fps — suave e barato: 28 rects por frame)
        if (running) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        // rede de segurança: nada de loop de desenho sobrevivendo à janela
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val BARS = 28          // bandas de frequência (igual ao processador)
        private const val GAIN = 1.6f        // ganho pré-curva (música a volume normal)
        private const val DECAY = 0.02f      // queda por frame @60fps (~0,8s de cauda)
        private const val GRAVITY = 0.002f   // aceleração do pontinho de pico
        private const val MAX_FALL = 0.016f  // velocidade terminal do pico
        private const val STALE_MS = 150L    // sem publicação nova = silêncio
        private const val GAP_DP = 2.5f      // respiro entre barras
        private const val MIN_BAR_DP = 2f    // altura da pista
        private const val PEAK_DP = 2f       // altura do pontinho de pico
    }
}
