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
 * AS "BARRINHAS DE DJ" do player (v0.18.9) — espectro REAL, sem permissão.
 *
 * A fonte continua sendo o SpectrumProcessor injetado no ExoPlayer do
 * PlaybackService (v0.18.8): ele ESPIA o PCM que o próprio app está tocando
 * (antes de sair no alto-falante) e publica as 28 bandas de frequência no
 * SpectrumBus. Esta view SÓ desenha, com três truques novos:
 *
 *  1. ESPELHO GRAVE NO CENTRO (o pedido "divide os lados entre agudo e
 *     grave, sem mostrar que está dividido"): as 28 bandas fluem do CENTRO
 *     (banda 0 = sub-grave) para as PONTAS (banda 27 = agudo) e refletidas
 *     — cada metade da tela é o espelho da outra. Não existe emenda
 *     visível porque o espectro é contínuo: a transição grave→agudo acontece
 *     devagar, banda a banda, no meio do caminho. A pancada do kick faz o
 *     CENTRO explodir simétrico — cara de mesa de DJ de clube;
 *  2. MOTOR DE BATIDA: a energia do grave (bandas 0..5) é comparada com a
 *     média lenta dela mesma. Passou da média com folga = PANCADA — as
 *     bandas graves ganham um "soco" de ganho que decai rápido (~0,3s),
 *     então a batida SALTA na tela em vez de só balançar;
 *  3. BARRAS MAIS ALTAS: ganho 2.2 com curva ^0.7 (era 1.6/^0.75) — o
 *     nível médio sobe pra perto do topo e o agudo fraco aparece.
 *
 * Além disso: ataque INSTANTÂNEO (a barra sobe na pancada), decaimento
 * constante (~0,8s de cauda) e pontinho de PICO com gravidade acumulada
 * caindo de volta — o combo clássico dos visualizers.
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

    /** Média lenta da energia do grave — a "linha do groove" (referência). */
    private var bassAvg = 0f

    /** Envolvente da pancada (0..MAX_PUNCH) — o soco que decai rápido. */
    private var punch = 0f

    /** Momento da última batida detectada (respiro entre socos). */
    private var lastBeatMs = 0L

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
     * Consome o SpectrumBus e roda o motor de batida. Dado "velho"
     * (> STALE_MS sem publicação nova — faixa pausada/encerrada) = alvo
     * zero → as barras caem sozinhas pelo decay (e a batida esvazia).
     */
    private fun ingest() {
        val bus = SpectrumBus.bands
        val fresh = bus != null &&
            (SystemClock.elapsedRealtime() - SpectrumBus.stampMs) < STALE_MS

        // ---- motor de batida: grave atual vs. groove (média lenta) ----
        if (fresh) {
            var sum = 0f
            for (i in 0 until BASS_BANDS) sum += bus!![i]
            val bass = sum / BASS_BANDS
            bassAvg += (bass - bassAvg) * BASS_SMOOTH
            val spike = bass - bassAvg
            val now = SystemClock.elapsedRealtime()
            if (bass > BASS_FLOOR &&
                spike > bassAvg * BEAT_RATIO + BEAT_MIN_SPIKE &&
                now - lastBeatMs > BEAT_GAP_MS
            ) {
                // pancada: o soco soma (dois kicks colados batem mais forte)
                punch = (punch + spike * PUNCH_GAIN).coerceAtMost(MAX_PUNCH)
                lastBeatMs = now
            }
        }
        punch *= PUNCH_DECAY
        if (punch < 0.01f) punch = 0f

        // ---- alvo de cada banda: nível bruto × ganho (com soco no grave) ----
        for (i in 0 until BARS) {
            val raw = if (fresh && i < bus!!.size) bus[i] else 0f
            // peso do soco: total nas bandas do kick, sumindo até BEAT_BANDS
            val beatW = if (i < BEAT_BANDS) 1f - i / (BEAT_BANDS + 4f) else 0f
            val gain = GAIN * (1f + TILT_HI * i / (BARS - 1)) * (1f + punch * beatW)
            val target = if (fresh) (raw * gain).pow(CURVE).coerceIn(0f, 1f) else 0f
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
        val columns = COLUMNS
        val barW = (w - gap * (columns - 1)) / columns
        val minH = MIN_BAR_DP * density
        val peakH = PEAK_DP * density

        // banda i desenha DUAS colunas espelhadas: a esquerda (BARS-1-i) e a
        // direita (BARS+i) — banda 0 nas duas do centro, banda 27 nas pontas
        for (i in 0 until BARS) {
            val leftX = (BARS - 1 - i) * (barW + gap)
            val rightX = (BARS + i) * (barW + gap)
            val radius = barW / 2f

            // grade apagada (a "pista") — mostra onde as barras nascem
            barRect.set(leftX, h - minH, leftX + barW, h.toFloat())
            canvas.drawRoundRect(barRect, radius, radius, idlePaint)
            barRect.set(rightX, h - minH, rightX + barW, h.toFloat())
            canvas.drawRoundRect(barRect, radius, radius, idlePaint)

            // barra viva — altura REAL do nível da banda (simétrica)
            val level = display[i]
            if (level > 0.01f) {
                val barH = minH + (h - minH) * level
                barRect.set(leftX, h - barH, leftX + barW, h.toFloat())
                canvas.drawRoundRect(barRect, radius, radius, barPaint)
                barRect.set(rightX, h - barH, rightX + barW, h.toFloat())
                canvas.drawRoundRect(barRect, radius, radius, barPaint)
            }

            // pontinho de pico (cai com gravidade) — espelhado também
            if (peaks[i] > 0.02f) {
                val peakY = h - minH - (h - minH) * peaks[i] - peakH
                barRect.set(leftX, peakY, leftX + barW, peakY + peakH)
                canvas.drawRoundRect(barRect, peakH / 2f, peakH / 2f, peakPaint)
                barRect.set(rightX, peakY, rightX + barW, peakY + peakH)
                canvas.drawRoundRect(barRect, peakH / 2f, peakH / 2f, peakPaint)
            }
        }

        // loop de frames: enquanto a view está "attachada" e visível, ela
        // mesma se redesenha (~60 fps — suave e barato: 56 rects por frame)
        if (running) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        // rede de segurança: nada de loop de desenho sobrevivendo à janela
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val BARS = 28          // bandas de frequência (igual ao processador)
        private const val COLUMNS = BARS * 2 // colunas desenhadas (espelho: 56)
        private const val GAIN = 2.2f        // ganho pré-curva — barras ALTAS (era 1.6)
        private const val CURVE = 0.7f       // curva perceptual mais generosa (era 0.75)
        private const val TILT_HI = 0.25f    // reforço extra de agudo na ponta do espelho
        private const val DECAY = 0.02f      // queda por frame @60fps (~0,8s de cauda)
        private const val GRAVITY = 0.002f   // aceleração do pontinho de pico
        private const val MAX_FALL = 0.016f  // velocidade terminal do pico
        private const val STALE_MS = 150L    // sem publicação nova = silêncio
        private const val GAP_DP = 1.5f      // respiro entre barras (56 colunas: fino)
        private const val MIN_BAR_DP = 2f    // altura da pista
        private const val PEAK_DP = 2f       // altura do pontinho de pico

        // ---- motor de batida ----
        private const val BASS_BANDS = 6     // ~86–230 Hz: kick + corpo do bumbo
        private const val BASS_SMOOTH = 0.05f// média lenta do groove (adapta em ~0,3s)
        private const val BASS_FLOOR = 0.06f // silêncio não dispara batida
        private const val BEAT_RATIO = 0.28f // spike mínimo = 28% acima do groove
        private const val BEAT_MIN_SPIKE = 0.015f
        private const val BEAT_GAP_MS = 110L // respiro entre socos (máx ~9/s)
        private const val PUNCH_GAIN = 2.4f  // spike → intensidade do soco
        private const val MAX_PUNCH = 1.15f  // soco máximo (não pinar a barra pra sempre)
        private const val PUNCH_DECAY = 0.90f// decaimento do soco por frame (~0,3s de cauda)
        private const val BEAT_BANDS = 12    // peso do soco some até ~1,3 kHz (só no grave)
    }
}
