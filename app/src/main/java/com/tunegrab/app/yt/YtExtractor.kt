package com.tunegrab.app.yt

import okhttp3.OkHttpClient
import okhttp3.Request
import android.net.Uri
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
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

    /**
     * Normaliza o link antes da extração: garante o esquema e — v0.19.11 —
     * CANONICALIZA o link de vídeo.
     *
     * O bug do "Link inválido" no botão Baixar da aba YouTube: o YouTube
     * mobile appenda contexto de mix em QUALQUER vídeo aberto pelo feed
     * (watch?v=X&list=RD…&start_radio=1) e o link handler do extractor
     * REJEITA watch com list= ("URL not accepted") — todo vídeo vindo da
     * navegação da aba (e toda colagem com list= no bolso) virava erro
     * honesto demais: "Link inválido". O v= manda: o rebuild limpo
     * https://www.youtube.com/watch?v=ID derruba o contexto que só serve
     * de ruído (pp/si/t/utm/radio). Playlist PURA (path /playlist?list=)
     * NUNCA passa por aqui: o auto-detect da Home a roteia pro fluxo de
     * playlist ANTES do fetch (e watch?v=X&list=Y já é vídeo único por
     * design da v0.19.3). /live/, /embed/, /v/ passam como estão — o
     * handler aceita e a canonicalização por segmento não se aplica.
     */
    private fun normalize(url: String): String {
        val trimmed = url.trim().trim('"', '\'', '>', '<')
        val withScheme = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
        return canonicalVideoUrl(withScheme) ?: withScheme
    }

    /** Link de vídeo canônico a partir de watch?v=, youtu.be/ID ou
     *  /shorts/ID — null quando não é dessas formas (URL segue como está). */
    private fun canonicalVideoUrl(url: String): String? = try {
        val u = Uri.parse(url)
        val segments = u.pathSegments
        when {
            u.path == "/watch" ->
                u.getQueryParameter("v")?.takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
            (u.host ?: "").lowercase() == "youtu.be" ->
                segments.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
            u.path?.startsWith("/shorts/") == true ->
                segments.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?.let { "https://www.youtube.com/watch?v=$it" }
            else -> null
        }
    } catch (t: Throwable) {
        null
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

    // ---------- playlists (v0.13.0) ----------

    /** Teto de segurança: playlist gigante não derruba o app nem martela o
     *  YouTube — mostra os primeiros 100 e avisa quantos ficaram de fora. */
    const val MAX_PLAYLIST_ITEMS = 100

    /** Metadados da playlist + itens, só o que a fila precisa. */
    class PlaylistMeta(
        val title: String,
        val uploader: String?,
        val thumbnail: String?,
        val items: List<Item>,
        /** Quantos vídeos a playlist tem DE VERDADE (pode passar do teto). */
        val totalFound: Int
    ) {
        data class Item(val url: String, val name: String, val durationSec: Long)
    }

    /** URL de playlist PURA (youtube.com/playlist?list=…). Link de vídeo com
     *  list= no bolso (watch?v=X&list=Y) NÃO entra — continua baixando o
     *  vídeo único de sempre, sem mudar nada no fluxo atual. */
    fun isPlaylistUrl(raw: String): Boolean = try {
        val u = Uri.parse(raw.trim())
        u.path?.endsWith("/playlist") == true &&
            !u.getQueryParameter("list").isNullOrBlank()
    } catch (t: Throwable) {
        false
    }

    /**
     * ID de playlist embutido em QUALQUER link (watch?v=X&list=Y,
     * youtu.be/X?list=Y, /playlist?list=Y…). null quando o link não carrega
     * list= — é vídeo puro. Usado pelo seletor Vídeo/Playlist do Início
     * (v0.14.0): no modo Playlist, um link de vídeo com lista no bolso vira
     * a playlist inteira sem o usuário precisar caçar o link /playlist.
     */
    fun playlistIdOf(raw: String): String? = try {
        Uri.parse(raw.trim()).getQueryParameter("list")?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /**
     * Busca os metadados da playlist (título, canal, itens). Só LISTA —
     * nada é baixado aqui. Cada vídeo entra depois na fila normal pelo
     * mesmo caminho de sempre (extração por vídeo + DownloadService).
     */
    fun fetchPlaylist(raw: String): PlaylistMeta {
        init()
        val service = ServiceList.YouTube
        val cleanUrl = normalize(raw)
        try {
            service.playlistLHFactory.fromUrl(cleanUrl)
                ?: throw IllegalArgumentException("URL de playlist não reconhecida")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("URL de playlist não reconhecida")
        }
        val info = PlaylistInfo.getInfo(service, cleanUrl)
        val items = info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { !it.url.isNullOrBlank() }
            .map { PlaylistMeta.Item(it.url, it.name ?: "", it.duration) }
        return PlaylistMeta(
            title = info.name ?: "",
            uploader = info.uploaderName,
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            items = items.take(MAX_PLAYLIST_ITEMS),
            totalFound = items.size
        )
    }
}
