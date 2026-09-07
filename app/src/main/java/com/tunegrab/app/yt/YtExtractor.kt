package com.tunegrab.app.yt

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Wrapper em torno do NewPipeExtractor para buscar informações
 * de vídeos do YouTube e listar faixas de áudio baixáveis.
 */
object YtExtractor {

    @Volatile
    private var ready = false

    fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(DownloaderImpl, Localization.DEFAULT)
            ready = true
        }
    }

    fun fetch(url: String): StreamInfo {
        init()
        val service = ServiceList.YouTube
        val linkHandler = try {
            service.streamLHFactory.fromUrl(normalize(url))
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("URL do YouTube não reconhecida")
        return StreamInfo.getInfo(service, linkHandler)
    }

    /**
     * Filtra e ordena as faixas de áudio disponíveis:
     * - prioriza URLs diretas (progressivas) — sem HLS;
     * - prioriza M4A (melhor compatibilidade com players);
     * - ordena por bitrate decrescente;
     * - remove duplicatas (formato + bitrate + trilha).
     */
    fun audioOptions(info: StreamInfo): List<AudioStream> {
        val direct = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
        }
        val pool = direct.ifEmpty { info.audioStreams.filter { s -> s.isUrl } }
        return pool
            .distinctBy { Triple(it.format?.name, it.averageBitrate, it.audioTrackName) }
            .sortedWith(
                compareByDescending<AudioStream> { it.format?.name == "M4A" }
                    .thenByDescending { it.averageBitrate }
            )
            .take(6)
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim().trim('"', '\'', '>', '<')
        return if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
    }
}
