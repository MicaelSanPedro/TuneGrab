package com.tunegrab.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/**
 * Converte um arquivo de áudio (M4A/AAC ou WebM/Opus, como os que o YouTube
 * serve) em MP3 CBR, 100% no dispositivo:
 *
 * arquivo ─▶ MediaExtractor ─▶ MediaCodec (decode) ─▶ PCM 16-bit
 *          ─▶ LAME (JNI) ─▶ MP3 kbps CBR ─▶ arquivo final
 */
object Mp3Converter {

    /**
     * @param input       arquivo de áudio baixado (fonte)
     * @param output      arquivo .mp3 de destino (será sobrescrito)
     * @param bitrateKbps bitrate alvo do MP3 (ex.: 320)
     * @param title       título da música (vai no ID3)
     * @param onProgress  fração 0..1 baseada no tempo de apresentação
     */
    fun convert(
        input: File,
        output: File,
        bitrateKbps: Int,
        title: String,
        onProgress: (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var handle = 0L
        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val srcFormat = format
                ?: throw IOException("arquivo baixado não tem faixa de áudio reconhecível")
            extractor.selectTrack(trackIndex)

            val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (srcChannels > 2) {
                throw IOException("áudio multicanal ($srcChannels canais) não suportado")
            }
            val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION)) {
                srcFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            // ID3: mantém o título da música no arquivo final
            handle = Mp3Encoder.nativeInit(sampleRate, srcChannels, bitrateKbps, title, "TuneGrab")
            if (handle == 0L) throw IOException("falha ao inicializar o encoder MP3")

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            FileOutputStream(output).use { out ->
                val info = MediaCodec.BufferInfo()
                val timeoutUs = 10_000L
                var sawInputEos = false
                var sawOutputEos = false
                var lastReport = 0f

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIdx = decoder.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val ib = decoder.getInputBuffer(inIdx)
                                ?: throw IOException("buffer de entrada nulo")
                            val sampleSize = extractor.readSampleData(ib, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIdx, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIdx = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outIdx >= 0) {
                            val ob = decoder.getOutputBuffer(outIdx)
                                ?: throw IOException("buffer de saída nulo")
                            if (info.size > 0) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                                val frames =
                                    if (srcChannels == 1) shorts.size else shorts.size / srcChannels
                                if (frames > 0) {
                                    Mp3Encoder.nativeEncode(handle, shorts, frames)?.let { out.write(it) }
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)

                            if (durationUs > 0) {
                                val p =
                                    (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastReport > 0.01f) {
                                    lastReport = p
                                    onProgress(p)
                                }
                            }

                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }

                Mp3Encoder.nativeFlush(handle)?.let { out.write(it) }
                out.flush()
            }

            if (output.length() < 1024L) {
                throw IOException("MP3 gerado ficou vazio — conversão falhou")
            }
        } finally {
            if (handle != 0L) {
                runCatching { Mp3Encoder.nativeClose(handle) }
            }
            runCatching {
                decoder?.stop()
                decoder?.release()
            }
            runCatching { extractor.release() }
        }
    }
}
