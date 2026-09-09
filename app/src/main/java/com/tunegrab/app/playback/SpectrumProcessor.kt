package com.tunegrab.app.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ônibus do espectro (v0.18.8): o SpectrumProcessor (dentro do pipeline de
 * áudio do PlaybackService) publica as bandas de frequência calculadas do
 * PCM REAL que está tocando, e o VisualizerView do player lê daqui — sem
 * permissão nenhuma (o som é lido DENTRO do próprio app, antes de sair no
 * alto-falante; nada de microfone, nada de android.media.audiofx).
 */
object SpectrumBus {

    /** Nível bruto (0..~1.3) das 28 bandas — quem desenha aplica curva/decay. */
    @Volatile
    var bands: FloatArray? = null
        private set

    /** Momento da última publicação — view considera dado "velho" e decai. */
    @Volatile
    var stampMs: Long = 0L
        private set

    fun publish(levels: FloatArray) {
        bands = levels
        stampMs = SystemClock.elapsedRealtime()
    }

    fun clear() {
        bands = null
    }
}

/**
 * A FONTE REAL das barrinhas de DJ (v0.18.8) — sem permissão nenhuma.
 *
 * AudioProcessor injetado no DefaultAudioSink do ExoPlayer do
 * PlaybackService: TODO byte de PCM que o player toca passa por aqui
 * (é o MESMO caminho que leva o som ao alto-falante), então o espectro é
 * do áudio de verdade — não é simulação. O processador:
 *  1. mistura os canais em mono e guarda numa fila circular (2048 amostras);
 *  2. a cada ~1024 amostras novas (~23 ms @ 44,1 kHz) aplica janela Hann e
 *     roda um FFT radix-2 próprio (2048 pontos, sem dependência externa);
 *  3. agrupa os bins em 28 bandas LOGARÍTMICAS (86 Hz → ~22 kHz — log é a
 *     cara de mixer de DJ; linear deixa os graves dominando tudo) e publica
 *     no SpectrumBus.
 *
 * Pausou? O ExoPlayer para de chamar queueInput, nenhuma publicação nova
 * chega e as barras caem sozinhas na view (decay) — nenhum estado consultado.
 * Formato passa INTACTO (o app só ESPIA o PCM, nunca altera o som).
 */
class SpectrumProcessor : BaseAudioProcessor() {

    /** PCM mono da janela corrente (fila circular). */
    private val mono = FloatArray(FFT_N)

    /** Janela de Hann pré-calculada (evita cos() no hot path). */
    private val window = FloatArray(FFT_N)

    private val fftRe = FloatArray(FFT_N)
    private val fftIm = FloatArray(FFT_N)

    private var writePos = 0
    private var filled = 0
    private var channels = 1
    private var pcmFloat = false
    private var sinceLastFft = 0

    /** Bandas publicadas (array reutilizado — só publish copia pra fora). */
    private val bands = FloatArray(BARS)

    init {
        for (i in 0 until FFT_N) {
            window[i] = 0.5f * (1f - cos(2.0 * Math.PI * i / FFT_N).toFloat())
        }
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.pcmEncoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        pcmFloat = enc == C.ENCODING_PCM_FLOAT
        resetState()
        return inputAudioFormat // pass-through: o som sai exatamente como entra
    }

    override fun queueInput(inputBuffer: ByteBuffer): ByteBuffer {
        val remaining = inputBuffer.remaining()
        if (remaining > 0) {
            // duplicate() lê SEM mover a posição do original — o put() abaixo
            // é quem repassa os bytes intactos pra saída
            val dup = inputBuffer.duplicate()
            dup.order(ByteOrder.LITTLE_ENDIAN)
            ingest(dup)
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        } else {
            replaceOutputBuffer(0)
        }
        return outputBuffer
    }

    override fun onQueueEndOfStream() {
        SpectrumBus.clear()
        super.onQueueEndOfStream()
    }

    override fun onFlush() {
        sinceLastFft = 0
        super.onFlush()
    }

    override fun onReset() {
        resetState()
        super.onReset()
    }

    private fun resetState() {
        writePos = 0
        filled = 0
        sinceLastFft = 0
        java.util.Arrays.fill(mono, 0f)
        SpectrumBus.clear()
    }

    /** Consome o buffer (jÁ posicionado, little-endian) misturando em mono. */
    private fun ingest(dup: ByteBuffer) {
        val ch = channels
        if (pcmFloat) {
            val fb = dup.asFloatBuffer()
            val frame = FloatArray(ch)
            while (fb.remaining() >= ch) {
                for (c in 0 until ch) frame[c] = fb.get()
                pushMono(frame, ch)
            }
        } else {
            val sb = dup.asShortBuffer()
            val frame = FloatArray(ch)
            while (sb.remaining() >= ch) {
                for (c in 0 until ch) frame[c] = sb.get() / 32768f
                pushMono(frame, ch)
            }
        }
    }

    private fun pushMono(frame: FloatArray, ch: Int) {
        var sum = 0f
        for (c in 0 until ch) sum += frame[c]
        mono[writePos] = sum / ch
        writePos = (writePos + 1) % FFT_N
        if (filled < FFT_N) filled++
        sinceLastFft++
        if (sinceLastFft >= HOP) {
            sinceLastFft = 0
            if (filled >= FFT_N) computeBands()
        }
    }

    /** FFT radix-2 (Cooley-Tukey iterativo, bit-reversal) sobre a janela. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            val half = len shr 1
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                var k = start
                val end = start + half
                while (k < end) {
                    val k2 = k + half
                    val vRe = re[k2] * curRe - im[k2] * curIm
                    val vIm = re[k2] * curIm + im[k2] * curRe
                    val uRe = re[k]
                    val uIm = im[k]
                    re[k] = uRe + vRe
                    im[k] = uIm + vIm
                    re[k2] = uRe - vRe
                    im[k2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                    k++
                }
                start += len
            }
            len = len shl 1
        }
    }

    /**
     * Janela corrente → FFT → 28 bandas log. Magnitude normalizada (senoide
     * em escala cheia ≈ 1,0) com leve reforço de agudos (tilt) — sem ele a
     * metade direita do mixer ficava quase zerada em música normal.
     */
    private fun computeBands() {
        // fila circular → ordem cronológica (rotacionada; o MÓDULO do
        // espectro não muda com rotação circular)
        var src = writePos
        for (i in 0 until FFT_N) {
            fftRe[i] = mono[src] * window[i]
            fftIm[i] = 0f
            src = (src + 1) % FFT_N
        }
        fft(fftRe, fftIm)

        val bins = FFT_N / 2
        var bandStart = MIN_BIN
        for (i in 0 until BARS) {
            val bandEnd = bandEdge(i, bins)
            var max = 0f
            var k = bandStart
            while (k < bandEnd && k < bins) {
                val re = fftRe[k]
                val im = fftIm[k]
                val mag = sqrt(re * re + im * im) * INV_NORM
                if (mag > max) max = mag
                k++
            }
            bandStart = bandEnd
            val tilt = 1f + TILT * i / (BARS - 1)
            bands[i] = (max * tilt).coerceAtLeast(0f)
        }
        SpectrumBus.publish(bands.copyOf())
    }

    /** Borda (exclusiva) da banda i em bins — log do MIN_BIN ao último bin. */
    private fun bandEdge(i: Int, bins: Int): Int {
        val lo = ln(MIN_BIN.toFloat())
        val hi = ln((bins - 1).toFloat())
        val edge = exp(lo + (hi - lo) * (i + 1) / BARS).toInt()
        // nunca menor que a banda anterior +1 (bandas vivas mesmo no agudo fino)
        return edge.coerceAtLeast(MIN_BIN + i + 1).coerceAtMost(bins - 1)
    }

    companion object {
        private const val FFT_N = 2048     // pontos do FFT (~46 Hz de resolução)
        private const val HOP = 1024       // FFT novo a cada ~23 ms @ 44,1 kHz
        private const val BARS = 28        // bandas de frequência (igual à view)
        private const val MIN_BIN = 2      // começa fora do DC (~86 Hz)
        private const val INV_NORM = 4f / FFT_N // senoide full-scale ≈ 1.0
        private const val TILT = 1.1f      // reforço de agudos (espectro vivo)
    }
}
