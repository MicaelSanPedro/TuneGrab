package com.tunegrab.app.yt

import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wrapper em torno do NewPipeExtractor para buscar informações
 * de vídeos do YouTube e listar faixas de áudio baixáveis.
 */
object YtExtractor {

    @Volatile
    private var ready = false

    /** Cliente enxuto só para o teste rápido de URLs ("belisca" 2 bytes). */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
     * - só URLs diretas progressivas (HLS/m3u8 não serve para download de arquivo);
     * - prioriza M4A (melhor compatibilidade com players);
     * - ordena por bitrate decrescente;
     * - remove duplicatas (formato + bitrate + trilha).
     */
    fun audioOptions(info: StreamInfo): List<AudioStream> {
        return info.audioStreams
            .filter {
                it.isUrl &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    !it.url.isNullOrBlank()
            }
            .distinctBy { Triple(it.format?.name, it.averageBitrate, it.audioTrackName) }
            .sortedWith(
                compareByDescending<AudioStream> { it.format?.name == "M4A" }
                    .thenByDescending { it.averageBitrate }
            )
            .take(6)
    }

    /**
     * Testa se a URL do stream responde de verdade — o YouTube às vezes
     * entrega URLs que rejeitam o download com HTTP 403 (streams bloqueados).
     * Faz um GET com Range de 2 bytes: barato e fecha a conexão em seguida.
     */
    fun probeOk(url: String): Boolean = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
            .header("Range", "bytes=0-1")
            .build()
        probeClient.newCall(req).execute().use { resp ->
            resp.code == 206 || resp.code == 200
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Mantém só as opções cuja URL responde. Testa em paralelo (até 6 por vez)
     * para não transformar a busca em fila. Preserva a ordem original, que já
     * é a ordem de preferência (melhor qualidade primeiro).
     */
    fun <T> filterWorking(options: List<T>, urlOf: (T) -> String?): List<T> {
        if (options.isEmpty()) return options
        val pool = Executors.newFixedThreadPool(options.size.coerceIn(1, 6))
        try {
            val futures = options.map { opt ->
                pool.submit(Callable { opt to urlOf(opt)?.let(::probeOk) })
            }
            return futures.mapNotNull { f ->
                try {
                    val pair = f.get(15, TimeUnit.SECONDS)
                    if (pair.second == true) pair.first else null
                } catch (t: Throwable) {
                    null
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Opções de áudio com URL testada de verdade (só as que respondem). */
    fun workingAudio(info: StreamInfo): List<AudioStream> =
        filterWorking(audioOptions(info)) { it.url }

    /** Opções de vídeo com URL testada de verdade (só as que respondem). */
    fun workingVideo(info: StreamInfo): List<VideoStream> =
        filterWorking(videoOptions(info)) { it.url }

    /**
     * Todas as RESOLUÇÕES que o vídeo oferece (combinadas + faixas DASH
     * vídeo-only, ex.: 720p/1080p/1440p/2160p), deduplicadas, decrescentes.
     *
     * Sem teto artificial: alturas acima de 720p/1080p existem como faixas
     * separadas do áudio e o plano A (yt-dlp) junta vídeo+áudio com o ffmpeg
     * embutido. Acima de 1080p o YouTube só entrega VP9/AV1 — o plano A
     * remuxa no MP4 (VP9+AAC) e a compatibilidade de decode é boa em Android
     * moderno (VP9 tem decoder no framework desde o 5.0). Metadado, não
     * precisa de probe de URL.
     */
    fun videoHeights(info: StreamInfo): List<Int> =
        info.videoStreams
            .map { it.height }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()

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
