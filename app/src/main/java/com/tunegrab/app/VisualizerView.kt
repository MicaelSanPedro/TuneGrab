package com.tunegrab.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * VISUALIZADOR DE ÁUDIO REAL (v0.18.7) — as "barrinhas de DJ" do player.
 *
 * NÃO é simulação: usa o android.media.audiofx.Visualizer ligado ao MIX DE
 * SAÍDA global (audio session 0) — o MESMO som que sai no alto-falante, seja
 * do PlaybackService (música), do ExoPlayer (vídeo DASH) ou do VideoView.
 * O Android entrega o FFT (espectro de frequências, de verdade) e esta view
 * agrupa as frequências em bandas logarítmicas (graves à esquerda, agudos à
 * direita, como no mixer), com ataque instantâneo, decaimento suave e o
 * "pico" caindo de volta — a dança é do próprio áudio que está tocando.
 *
 * Quando a faixa PAUSA, o mix silencia, o FFT zera e as barras caem sozinhas
 * em ~1 segundo — nenhum estado de player precisa ser consultado (e nenhum
 * player é acoplado aqui).
 *
 * Requer a permissão RECORD_AUDIO — exigência do ANDROID para qualquer leitura
 * de espectro de saída; o app não grava som nem usa microfone. Sem permissão
 * ou sem suporte no aparelho: attach() devolve false (a activity esconde a
 * view) — NUNCA uma animação falsa no lugar.
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var viz: Visualizer? = null

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

    /** Callback de captura do FFT — roda na thread da view (looper da main). */
    private val captureListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveformCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            // só FFT interessa aqui
        }

        override fun onFftCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft != null && fft.size >= 8) ingestFft(fft)
            postInvalidateOnAnimation()
        }
    }

    /**
     * Liga a captura no mix de saída (session 0). Devolve false se o aparelho
     * negar (sem permissão, engine sem slots, OEM exótico) — a activity esconde
     * as barras nesse caso. Chamar só com RECORD_AUDIO concedida.
     */
    fun attach(): Boolean {
        if (viz != null) return true
        available = try {
            val v = Visualizer(0) // 0 = output mix global: pega o SOM REAL
            val range = v.captureSizeRange
            if (range != null && range.size >= 2) {
                // maior janela = mais resolução de frequência (1024 → 512 bins)
                v.captureSize = range[1].coerceAtMost(2048)
            }
            v.setDataCaptureListener(
                captureListener,
                Visualizer.getMaxCaptureRate(), // ~20 capturas/s — DJ de verdade
                false, // waveform off
                true   // FFT on
            )
            v.enabled = true
            viz = v
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Visualizador indisponível neste aparelho", t)
            false
        }
        invalidate()
        return available
    }

    /** Solta o Visualizer (recurso escasso do engine — release é obrigatório). */
    fun detach() {
        val v = viz
        viz = null
        available = false
        if (v != null) {
            try {
                v.enabled = false
            } catch (ignored: Throwable) {
            }
            try {
                v.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * App foi pro fundo (onStop): desliga o effect e economiza bateria —
     * a view nem está visível. Voltou (onResume): religa se já estava ligada.
     */
    fun setHostPaused(paused: Boolean) {
        val v = viz ?: return
        try {
            v.enabled = !paused
        } catch (ignored: Throwable) {
        }
    }

    /** True depois de um attach() bem-sucedido (ainda sem detach). */
    var available = false
        private set

    // ---------- FFT → bandas ----------

    /**
     * Converte o FFT cru do Android em níveis por banda:
     *  - bins agrupados em escala LOGARÍTMICA (86 Hz → ~14 kHz): graves
     *    dominam em escala linear e o espectro fica torto — log é a cara de DJ;
     *  - magnitude = sqrt(re² + im²) normalizada, curva perceptual (^0.75)
     *    pra agudos fracos aparecerem;
     *  - ataque INSTANTÂNEO (barra sobe na pancada do grave) e decaimento
     *    constante (cai suave) — o combo clássico dos visualizers;
     *  - pico com gravidade acumulada: sobe junto e volta caindo devagar.
     */
    private fun ingestFft(fft: ByteArray) {
        val bins = fft.size / 2 // k = 1..bins-1 vira (re, im) em fft[2k], fft[2k+1]
        if (bins < MIN_BIN + 2) return
        var bandStart = MIN_BIN
        for (i in 0 until BARS) {
            val bandEnd = bandEdge(i, bins)
            var max = 0f
            var k = bandStart
            while (k < bandEnd && k < bins) {
                val re = fft[2 * k].toInt()
                val im = fft[2 * k + 1].toInt()
                val mag = sqrt((re * re + im * im).toFloat()) / 128f
                if (mag > max) max = mag
                k++
            }
            bandStart = bandEnd
            val target = (max * GAIN).pow(0.75f).coerceIn(0f, 1f)
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

    /** Borda (exclusiva) da banda i em bins — log do MIN_BIN até o último bin. */
    private fun bandEdge(i: Int, bins: Int): Int {
        val lo = ln(MIN_BIN.toFloat())
        val hi = ln((bins - 1).toFloat())
        val edge = exp(lo + (hi - lo) * (i + 1) / BARS).toInt()
        // nunca menor que a banda anterior +1 (bandas vivas mesmo no agudo fino)
        return edge.coerceAtLeast(MIN_BIN + i + 1).coerceAtMost(bins - 1)
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
    }

    override fun onDetachedFromWindow() {
        // rede de segurança: o Visualizer não pode sobreviver à janela
        detach()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "VisualizerView"
        private const val BARS = 28          // bandas de frequência
        private const val MIN_BIN = 2        // começa fora do DC (~86 Hz)
        private const val GAIN = 1.6f        // ganho pré-curva (música a volume normal)
        private const val DECAY = 0.06f      // queda por captura (~20/s → ~0,8s)
        private const val GRAVITY = 0.006f   // aceleração do pontinho de pico
        private const val MAX_FALL = 0.05f   // velocidade terminal do pico
        private const val GAP_DP = 2.5f      // respiro entre barras
        private const val MIN_BAR_DP = 2f    // altura da pista
        private const val PEAK_DP = 2f       // altura do pontinho de pico
    }
}
