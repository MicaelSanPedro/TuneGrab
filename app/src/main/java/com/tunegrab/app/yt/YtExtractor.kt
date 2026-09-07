package com.tunegrab.app.yt

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import com.tunegrab.app.yt.potoken.TuneGrabPoTokenProvider

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
            // PoTokens via BotGuard (WebView): destrava o bot-check do YouTube
            // ("Sign in to confirm you're not a bot") e os streams bloqueados (403).
            YoutubeStreamExtractor.setPoTokenProvider(TuneGrabPoTokenProvider)
            // iOS client com poToken devolve URLs diretas de áudio/vídeo
            YoutubeStreamExtractor.setFetchIosClient(true)
            NewPipe.init(DownloaderImpl, Localization.DEFAULT)
            ready = true
        }
    }

    fun fetch(url: String): StreamInfo {
        init()
        val service = ServiceList.YouTube
        val cleanUrl = normalize(url)
        // valida que a URL pertence ao YouTube antes de extrair
        try {
            service.streamLHFactory.fromUrl(cleanUrl) ?: throw IllegalArgumentException(
                "URL do YouTube não reconhecida"
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("URL do YouTube não reconhecida")
        }
        return StreamInfo.getInfo(service, cleanUrl)
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

    /**
     * Faixas de vídeo MP4 COM áudio embutido (progressivas, ex.: 360p/720p).
     * São baixáveis direto, sem remux. Ordena por resolução decrescente.
     */
    fun videoOptions(info: StreamInfo): List<VideoStream> {
        return info.videoStreams
            .filter {
                !it.isVideoOnly &&
                    it.format == MediaFormat.MPEG_4 &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    !it.url.isNullOrBlank() &&
                    it.height > 0
            }
            .sortedByDescending { it.height }
            .distinctBy { it.height }
            .take(4)
    }

    /**
     * Melhor faixa de áudio-fonte para conversão MP3:
     * M4A (AAC decodifica em qualquer aparelho via MediaCodec) e,
     * na falta dela, a melhor disponível.
     */
    fun bestMp3Source(info: StreamInfo): AudioStream? {
        val options = audioOptions(info)
        return options.firstOrNull { it.format == MediaFormat.M4A }
            ?: options.firstOrNull()
    }
}
