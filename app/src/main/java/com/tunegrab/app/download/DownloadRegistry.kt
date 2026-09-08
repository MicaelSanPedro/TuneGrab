package com.tunegrab.app.download

/**
 * Estado do download PAUSADO esperando "Continuar".
 *
 * Vive só em memória (o mesmo tempo de vida do [DownloadBus]): se o processo
 * do app morrer, o botão Continuar deixa de existir — comportamento honesto,
 * porque a retomada depende dos arquivos parciais no cache, que o Android
 * também pode limpar. Cancelar/limpar zera.
 */
object DownloadRegistry {

    /** Todos os parâmetros necessários para relançar o MESMO download. */
    data class PausedDownload(
        val fileName: String,
        val title: String,
        /** Plano B: URL direta do stream (pode expirar com o tempo — falha honesta). */
        val url: String?,
        val mime: String,
        /** MODE_DIRECT ou MODE_MP3 (mesmos valores do DownloadService). */
        val mode: String,
        val bitrate: Int,
        /** Plano A: URL do vídeo + preset do motor yt-dlp. */
        val videoUrl: String?,
        val engineFormat: String?,
        val maxHeight: Int
    )

    @Volatile
    var paused: PausedDownload? = null

    fun clearIf(fileName: String?) {
        if (paused?.fileName == fileName) paused = null
    }
}
