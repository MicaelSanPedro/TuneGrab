package com.tunegrab.app.video

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Guarda de resolução: lê a altura REAL da trilha de vídeo do arquivo
 * produzido, do mesmo jeito que [com.tunegrab.app.audio.AudioQuality] faz
 * para o bitrate do MP3.
 *
 * Por que isto existe: o usuário escolhe 720p/1080p/4K e o app precisa
 * PROVAR que o arquivo salvo está na resolução escolhida — teste direto na
 * estrutura do arquivo (container), não em promessa de configuração.
 * Se o vídeo não tinha a resolução pedida (ex.: pedido 4K, vídeo só até
 * 1080p), a notificação final diz a resolução real — honestidade em vez de
 * rótulo falso.
 */
object VideoQuality {

    /**
     * Altura real (px) da maior trilha de vídeo do arquivo, lida via
     * MediaExtractor. Respeita rotação (vídeo em retrato gravado em
     * paisagem: 90°/270° troca largura↔altura). null = não conseguiu medir
     * (não bloquear: verificação nunca pode derrubar o download).
     */
    fun actualHeight(file: File): Int? {
        return try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var best: Int? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue
                    val height = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
                        fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    } else continue
                    val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                        fmt.getInteger(MediaFormat.KEY_ROTATION)
                    } else 0
                    val effective = if (rotation == 90 || rotation == 270) {
                        if (fmt.containsKey(MediaFormat.KEY_WIDTH)) {
                            fmt.getInteger(MediaFormat.KEY_WIDTH)
                        } else height
                    } else height
                    if (best == null || effective > best) best = effective
                }
                best
            } finally {
                extractor.release()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
