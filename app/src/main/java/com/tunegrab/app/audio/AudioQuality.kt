package com.tunegrab.app.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Guarda de qualidade: lê o bitrate REAL do arquivo produzido e garante que
 * o MP3 entregue esteja no bitrate escolhido pelo usuário.
 *
 * Por que isto existe: a conversão MP3 pode vir de caminhos diferentes
 * (pós-processador do yt-dlp/ffmpeg embutido ou LAME interno) e em nenhuma
 * delas o usuário deve receber um "320 kbps" menor que o pedido no arquivo
 * final. O teste é direto na estrutura do arquivo (frame header / duração),
 * não em promessa de configuração.
 */
object AudioQuality {

    /**
     * Bitrate real (kbps) do arquivo de áudio, lido via MediaExtractor:
     * usa KEY_BIT_RATE quando o container reporta; senão estima por
     * tamanho × 8 ÷ duração. null = não conseguiu medir (não bloquear).
     */
    fun actualBitrateKbps(file: File): Int? {
        return try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("audio/")) continue
                    if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        return fmt.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                    }
                    val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        fmt.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    if (durUs > 0 && file.length() > 0) {
                        return ((file.length() * 8_000_000L) / durUs / 1000L).toInt()
                    }
                }
                null
            } finally {
                extractor.release()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Garante o bitrate do MP3: mede o arquivo e, se estiver abaixo do
     * pedido (tolerância de 8% — margem de frame header/cabeçalho), re-encoda
     * no LAME interno no bitrate exato. Devolve o MESMO caminho [file]
     * (re-encode em lugar, para a limpeza do serviço continuar válida).
     * Nunca lança: falha de medição/conversão mantém o arquivo original —
     * o download não pode morrer por causa da verificação.
     *
     * @param onProgress fração 0..1 (só é usada quando re-encoda)
     */
    fun ensureMp3Bitrate(
        file: File,
        requestedKbps: Int,
        title: String,
        onProgress: (Float) -> Unit
    ): File {
        if (requestedKbps <= 0 || !file.exists() || file.length() == 0L) return file
        val actual = actualBitrateKbps(file) ?: return file
        if (actual >= requestedKbps * 92 / 100) return file

        // veio abaixo do pedido → re-encode honesto no bitrate exato (LAME CBR)
        val fixed = File(file.parentFile, "${file.nameWithoutExtension}_chk.mp3")
        try {
            Mp3Converter.convert(file, fixed, requestedKbps, title, onProgress)
            if (fixed.length() > file.length() / 10 && replaceOriginal(fixed, file)) {
                return file
            }
            fixed.delete()
        } catch (t: Throwable) {
            fixed.delete()
        }
        return file
    }

    /** move [from] para [to] (que é apagado antes). true se o lugar foi tomado. */
    private fun replaceOriginal(from: File, to: File): Boolean {
        to.delete()
        return from.renameTo(to)
    }
}
