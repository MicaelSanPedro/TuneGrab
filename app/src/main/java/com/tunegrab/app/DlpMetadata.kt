package com.tunegrab.app

import android.content.Context
import android.util.Log
import com.tunegrab.app.yt.YtDlpEngine
import com.tunegrab.app.yt.YtExtractor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * MODO DE RESGATE (v0.18.4): metadados via yt-dlp EMBUTIDO.
 *
 * Por que existe: a extração normal (NewPipe + PoToken/BotGuard) depende de
 * dois elos frágeis — o WebView do aparelho e o limite por IP do GenerateIT
 * do YouTube. Quando um dos dois cai, TODO vídeo vira "verificação
 * anti-bot" e o app parece morto, mesmo com o motor de download (yt-dlp)
 * 100% funcional. O diagnóstico de 2026-09-10 provou que o protocolo
 * server-side do BotGuard continua intacto (prova com JSDOM: token real,
 * pot mintado) — ou seja, a falha é de reputação/rate-limit do IP ou do
 * WebView local, e o yt-dlp (client rotation + HLS + auto-update nightly)
 * extrai numa boa onde o NewPipe é bloqueado.
 *
 * O que ele faz: roda o yt-dlp com --dump-single-json SÓ PARA LER
 * metadados (título, canal, duração, thumbnail, bitrates/alturas dos
 * formatos) — nada é baixado aqui. O download em si sai pelo mesmo Plano A
 * de sempre (DownloadService + EXTRA_VIDEO_URL), exatamente como a fila de
 * playlist já faz desde a v0.14.0 quando a extração de um item falha.
 *
 * Este arquivo NÃO mexe no motor: chama só API pública (ensureReady +
 * execute), sem alterar nenhuma linha de yt/, download/ ou do
 * DownloadService. É chamado da UI (HomeFragment) como fallback honesto
 * quando o bot-check bloqueia a rota normal.
 */
object DlpMetadata {

    private const val TAG = "TuneGrabDlpMeta"

    /** Metadados do vídeo no formato que o seletor de resgate precisa. */
    class VideoMeta(
        val title: String,
        val uploader: String?,
        val thumbnailUrl: String?,
        val durationSec: Long,
        /** URL CANÔNICA do vídeo — é ela que o Plano A baixa. */
        val webUrl: String,
        /** Bitrates reais de áudio M4A (desc) — alimenta os chips de qualidade. */
        val m4aBitrates: List<Int>,
        /** Bitrates reais de áudio Opus/WebM (desc). */
        val opusBitrates: List<Int>,
        /** Alturas de vídeo detectadas (desc, teto 2160 como no motor). */
        val heights: List<Int>
    )

    /**
     * Metadados de UM vídeo via yt-dlp. Bloqueante — chamar de thread de IO.
     * Lança a exceção real do yt-dlp quando a extração falha (a UI mostra o
     * motivo de verdade em vez de um erro cego).
     */
    fun video(ctx: Context, url: String): VideoMeta {
        val appCtx = ctx.applicationContext
        // prepare/atualiza o motor uma vez — o mesmo yt-dlp que baixa (e o
        // auto-update nightly é justamente o que mantém a extração viva)
        YtDlpEngine.ensureReady(appCtx) { }
        val json = dumpJson(url, flatPlaylist = false)
        return parseVideo(json)
    }

    /**
     * Metadados da PLAYLIST (--flat-playlist: só a lista, rápido). Devolve o
     * MESMO [YtExtractor.PlaylistMeta] do fluxo normal — a fila inteira
     * (PlaylistSheet → runPlaylist → fallback por item) funciona igual,
     * sem nenhuma diferença para o resto do app.
     */
    fun playlist(ctx: Context, url: String): YtExtractor.PlaylistMeta {
        val appCtx = ctx.applicationContext
        YtDlpEngine.ensureReady(appCtx) { }
        val json = dumpJson(url, flatPlaylist = true)
        val entries = json.optJSONArray("entries") ?: JSONArray()
        val items = ArrayList<YtExtractor.PlaylistMeta.Item>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            var u = e.optString("url", "")
            if (u.isBlank() || !u.startsWith("http")) {
                // entrada plana às vezes traz só o id
                val id = e.optString("id", "")
                if (id.isNotBlank()) u = "https://www.youtube.com/watch?v=$id"
            }
            if (u.isBlank()) continue
            val name = e.optString("title", "").ifBlank { "video" }
            val dur = e.optDouble("duration", 0.0).toLong()
            items.add(YtExtractor.PlaylistMeta.Item(u, name, dur))
            // mesmo teto do fluxo normal (playlist gigante não derruba o app)
            if (items.size >= YtExtractor.MAX_PLAYLIST_ITEMS) break
        }
        return YtExtractor.PlaylistMeta(
            title = json.optString("title", "").ifBlank { "playlist" },
            uploader = json.optString("uploader", "").ifBlank { null },
            thumbnail = json.optString("thumbnail", "").ifBlank { null },
            items = items,
            totalFound = items.size
        )
    }

    // ---------- internals ----------

    /**
     * yt-dlp --dump-single-json (leitura de metadados, NADA baixa). Mesmas
     * medidas anti-bot-check do motor: socket timeout curto, espaçamento de
     * requests e — desde a v0.18.5 — ROTAÇÃO DE CLIENTS entre tentativas
     * (default → visionos → tv_embedded), a MESMA lista comprovada de ponta
     * a ponta pelo motor de download (YtDlpEngine, prova 2026-09-08). Sem
     * isso, o client default bot-checked virava erro cego na tela do usuário.
     * O stdout do yt-dlp com -J é EXATAMENTE o JSON (logs vão para o stderr,
     * que o execute() separa em response.err).
     */
    private fun dumpJson(url: String, flatPlaylist: Boolean): JSONObject {
        var attempt = 0
        while (true) {
            val client = clientFor(attempt)
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-warnings")
                addOption("--socket-timeout", "20")
                // espaça as chamadas ao innertube — mesma higiene do motor
                addOption("--sleep-requests", "1")
                // COOKIES do login do YouTube (v0.18.6) — mesmo gancho do
                // motor; no-op quando o usuário não salvou sessão
                YtCookies.applyTo(this)
                if (client != null) {
                    addOption("--extractor-args", "youtube:player_client=$client")
                }
                if (flatPlaylist) {
                    addOption("--flat-playlist")
                } else {
                    // watch?v=X&list=Y no modo vídeo: só o vídeo, como sempre
                    addOption("--no-playlist")
                }
                addOption("--dump-single-json")
            }
            // forma posicional (request, processId, callback) — a mesma que o
            // motor usa para evitar ambiguidade de sobrecarga no execute().
            // ProcessId NOVO a cada tentativa: cada retry é um processo novo
            // (novo desafio de extração), igual ao motor.
            val processId = UUID.randomUUID().toString()
            try {
                val response = YoutubeDL.execute(req, processId, { _, _, _ -> })
                val out = response.out.trim()
                if (out.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "metadados ok (client=${client ?: "default"}, ${out.length} bytes, flat=$flatPlaylist)"
                    )
                    return JSONObject(out)
                }
                // stdout vazio com erro do YouTube na saída de erro: embute a
                // mensagem real — se for bot-check, o catch abaixo rotaciona
                throw IllegalStateException(
                    "yt-dlp não devolveu metadados" +
                        response.err.take(200).let { if (it.isBlank()) "" else ": $it" }
                )
            } catch (e: Exception) {
                attempt++
                val msg = ((e.message ?: "") + " " + (e.cause?.message ?: "")).lowercase()
                if (attempt <= RETRY_CLIENTS.size && BOT_CHECK_MARKERS.any { it in msg }) {
                    Log.w(
                        TAG,
                        "bot-check na leitura de metadados (tentativa $attempt/${RETRY_CLIENTS.size + 1}, próximo client=${clientFor(attempt) ?: "default"}); re-tentando",
                        e
                    )
                    // respiro crescente entre tentativas — martelar piora a
                    // reputação do IP (lição da v0.3.2)
                    Thread.sleep(1500L * attempt)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Rotação de clients do innertube entre tentativas — os MESMOS clients
     * comprovados pelo motor de download (YtDlpEngine.RETRY_CLIENTS, prova
     * de download real 2026-09-08): tentativa 0 = default do yt-dlp (que se
     * auto-protege), retries forçam visionos e tv_embedded.
     */
    private val RETRY_CLIENTS = listOf("visionos", "tv_embedded")

    private fun clientFor(attempt: Int): String? = RETRY_CLIENTS.getOrNull(attempt - 1)

    /**
     * Marcadores do bot-check na mensagem do erro (mesma família do motor +
     * recaptcha do desafio web). Contra a mensagem minúscula.
     */
    private val BOT_CHECK_MARKERS = listOf(
        "not a bot",
        "sign in to confirm",
        "confirm you're not",
        "too many requests",
        "http error 429",
        "requested format is not available",
        "recaptcha"
    )

    private fun parseVideo(json: JSONObject): VideoMeta {
        val formats = json.optJSONArray("formats") ?: JSONArray()
        val m4a = mutableSetOf<Int>()
        val opus = mutableSetOf<Int>()
        val heights = mutableSetOf<Int>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val acodec = f.optString("acodec", "none")
            val vcodec = f.optString("vcodec", "none")
            val ext = f.optString("ext", "")
            val hasAudio = acodec != "none" && acodec.isNotBlank()
            val hasVideo = vcodec != "none" && vcodec.isNotBlank()
            if (hasAudio && !hasVideo) {
                val abr = f.optDouble("abr", 0.0).toInt()
                if (abr > 0) {
                    if (ext == "m4a" || acodec.startsWith("mp4a")) m4a.add(abr)
                    if (ext == "webm" || acodec.contains("opus")) opus.add(abr)
                }
            }
            if (hasVideo) {
                val h = f.optInt("height", 0)
                // mesmo teto do motor: acima de 2160 não vira degrau
                if (h in 144..2160) heights.add(h)
            }
        }
        val id = json.optString("id", "")
        val webUrl = json.optString("webpage_url", "").ifBlank {
            if (id.isNotBlank()) "https://www.youtube.com/watch?v=$id" else ""
        }
        if (webUrl.isBlank()) {
            throw IllegalStateException("yt-dlp não devolveu a URL do vídeo")
        }
        val title = json.optString("title", "").ifBlank { "video" }
        val uploader = json.optString("uploader", "").ifBlank {
            json.optString("channel", "").ifBlank { null }
        }
        val thumbnail = json.optString("thumbnail", "").ifBlank { null }
        return VideoMeta(
            title = title,
            uploader = uploader,
            thumbnailUrl = thumbnail,
            durationSec = json.optDouble("duration", 0.0).toLong(),
            webUrl = webUrl,
            m4aBitrates = m4a.sortedDescending(),
            opusBitrates = opus.sortedDescending(),
            heights = heights.sortedDescending()
        )
    }
}
