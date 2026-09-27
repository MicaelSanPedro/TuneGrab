package com.tunegrab.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * GERADOR DE LETRA (v0.22.7, pedido do autor: "Faça o gerador de letra";
 * v0.22.8 conserta a busca que não achava NADA):
 * busca a letra da faixa na LRCLIB (lrclib.net — API pública, GRÁTIS, SEM
 * chave, SEM servidor nosso — a mesma religião "sem servidor" do app). O
 * pedido sai DAQUI do aparelho, direto pra API, e volta com:
 *  - syncedLyrics: letra com marcação de tempo (formato LRC, a dos players
 *    de karaoke) → a tela acende a linha no tempo do som;
 *  - plainLyrics: letra simples (sem tempo) → só o texto.
 *
 * FIX v0.22.8 (autor: "Não pegou em nenhuma música"): a URL da API estava
 * SEM o prefixo /api — o site respondia 200 com a PÁGINA HTML do site e o
 * JSON.parse morria em silêncio: TODA música virava "não achou". Agora vai
 * pra /api/get e /api/search de verdade. A escada também ganhou dois degraus
 * provados em sonda real (28/28 com nomes sujos do YouTube): cada degrau só
 * vale se TROUXE LETRA (registro achado sem letra não para a escada) e há um
 * degrau final só-com-o-nome-da-faixa (acha "Favela Vive 2", "Tudo Bem").
 *
 * O nome do arquivo do YouTube nunca vem limpo ("Artista - Música (Official
 * Video).mp3"), então o buildQuery LAVA o título: tira a extensão, tira os
 * blocos de lixo entre parênteses/colchetes (official video, lyrics, ao
 * vivo, slowed+reverb, feat...), arranca os blocos SEM palavra-chave que
 * sobraram NO FIM ("(Marília Canta Thomaz)", "[Ultra Records]") e quebra no
 * " - " pra separar artista da faixa. Arquivo LOCAL com metadados embutidos
 * (o yt-dlp grava título/artista na hora do download)? Esses valem MAIS que
 * o nome do arquivo.
 */
data class LyricLine(val timeMs: Int, val text: String)

/**
 * O que a busca devolve: [synced] é a letra com tempo (null = só texto),
 * [plain] é o texto puro e [instrumental] marca faixa sem voz.
 */
data class LyricsResult(
    val synced: List<LyricLine>?,
    val plain: String,
    val instrumental: Boolean,
    val trackName: String,
    val artistName: String
)

/** Quem a gente procura: artista + faixa (+ duração do arquivo, ajuda o casamento exato). */
data class TrackQuery(val artist: String, val title: String, val durationMs: Int)

object LyricsFetcher {

    // ⚠️ /api é OBRIGATÓRIO: sem ele o site responde 200 com HTML (página do
    // site), não com JSON — foi o bug da v0.22.7 que zerou TUDO.
    private const val API = "https://lrclib.net/api"

    /** A API pede User-Agent identificando o app — honestidade básica. */
    private val UA: String
        get() = "TuneGrab/${BuildConfig.VERSION_NAME} (+https://github.com/MicaelSanPedro/TuneGrab)"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // Cache de memória (LRU): voltar na mesma faixa NÃO refaz a busca — a
    // letra já está aqui. 24 faixas de letra cabem de sobra em RAM.
    private val cache = LinkedHashMap<String, LyricsResult>(32, 0.75f, true)

    private fun lookup(key: String): LyricsResult? = synchronized(cache) { cache[key] }

    private fun remember(key: String, res: LyricsResult) = synchronized(cache) {
        cache[key] = res
        while (cache.size > 24) cache.remove(cache.keys.first())
    }

    // ---------- lavagem do título ----------

    private val EXT = Regex(
        """\.(mp3|m4a|opus|ogg|oga|wav|flac|mp4|mkv|webm|m4v)\s*$""",
        RegexOption.IGNORE_CASE
    )

    // Blocos de lixo entre parênteses/colchetes: (Official Video), [Lyrics],
    // (Áudio Oficial), (Ao Vivo), (feat. X), (Slowed + Reverb)... tudo que o
    // YouTube coloca no título e que só atrapalha o casamento com a letra
    private val JUNK = Regex(
        """\s*[\(\[][^\)\]]*(official|oficial|vide[oó]|[áa]udios?|lyrics?|letras?|clipe?|clip|visuali[sz]er|remaster(?:izado)?|explicit|h\.?q|4k|2160p|1080p|720p|karaok[êe]|instrumental|fanmade|legendad[oa]|tradu[cç][ãa]o|sub\s*esp|legendas?|ao\s+vivo|live|feat\.?|ft\.?|part\.?|slowed|reverb|lofi|lo-fi|nightcore|sped\s*up|speed\s*up|extended|remix)[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )

    // Blocos SEM palavra-chave que sobraram NO FIM do título — nome de álbum
    // ou gravadora que o YouTube cola depois da música: "Todo Mundo Vai
    // Sofrer (Marília Canta Thomaz)", "Hear Me Now [Ultra Records]". Só no
    // fim (o que está no meio pode ser parte do nome da música) e enquanto
    // sobrar título de verdade (o casamento exato precisa do nome limpo).
    private val TRAILING_BLOCK = Regex("""(?:\s*[\(\[][^\)\]]*[\)\]])+\s*$""")

    // Separador que sobrou solto no fim depois da lavagem ("Música -")
    private val DANGLE_SEP = Regex("""\s+[-–—]\s*$""")

    // "Artista - Música" (também aceita – e —)
    private val SEP = Regex("""\s+[-–—]\s+""")

    private val SPACES = Regex("""\s+""")

    /**
     * Monta a busca a partir do que o app tem: o título que aparece na tela
     * (nome do arquivo, quase sempre) e, se for arquivo LOCAL, os metadados
     * embutidos (título/artista de verdade). [durationMs] vem do player
     * (0 = não sabe) e vai junto pra API afinar o casamento.
     */
    fun buildQuery(
        ctx: Context,
        displayTitle: String,
        localUri: String?,
        durationMs: Int
    ): TrackQuery {
        var name = EXT.replace(displayTitle.trim(), "")
        name = JUNK.replace(name, " ")
        // blocos sem palavra-chave no FIM (nome de álbum/gravadora): tira
        // enquanto sobrar título de verdade — nunca deixa o nome em branco
        while (true) {
            val stripped = TRAILING_BLOCK.replace(name, "").trim()
            if (stripped.isNotEmpty() && stripped != name.trim()) name = stripped else break
        }
        var artist = ""
        var title = SPACES.replace(name, " ").trim()
        title = DANGLE_SEP.replace(title, "").trim()
        val sep = SEP.find(title)
        if (sep != null) {
            artist = title.substring(0, sep.range.first).trim()
            title = title.substring(sep.range.last + 1).trim()
        }
        // Metadados embutidos valem MAIS que o nome do arquivo (só em arquivo
        // local — stream remoto não tem metadado pra ler)
        if (localUri != null && !localUri.startsWith("http")) {
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, Uri.parse(localUri))
                    val eArtist = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ARTIST
                    )?.trim().orEmpty()
                    val eTitle = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_TITLE
                    )?.trim().orEmpty()
                    if (eArtist.isNotEmpty()) artist = eArtist
                    if (eTitle.isNotEmpty()) title = eTitle
                } finally {
                    try {
                        r.release()
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
                // arquivo podre/sumido: fica o nome lavado mesmo
            }
        }
        return TrackQuery(artist, title, durationMs)
    }

    // ---------- busca ----------

    /**
     * Procura a letra. Devolve null = "não achou" (a tela explica); LANÇA
     * IOException = rede/sem internet (a tela pede pra tentar de novo).
     */
    suspend fun fetch(q: TrackQuery): LyricsResult? = withContext(Dispatchers.IO) {
        if (q.title.isBlank()) return@withContext null
        val key = "${q.artist.lowercase()}|${q.title.lowercase()}|${q.durationMs / 1000}"
        lookup(key)?.let { return@withContext it }
        val durSec = (q.durationMs / 1000).takeIf { it > 0 }
        // Escada de busca (v0.22.8, provada em sonda real 28/28):
        //  1. casamento exato artista+faixa (SEM duration no pedido — a
        //     duração do arquivo vira de nome de vídeo, e duração errada aqui
        //     derruba o exato; ela vale no ESCORE do degrau de baixo);
        //  2. busca artista+faixa;
        //  3. busca solta q="artista faixa";
        //  4. busca só-com-a-faixa (achou "Favela Vive 2" e "Tudo Bem" —
        //     nomes comuns que a busca cheia engole).
        // Cada degrau só vale se TROUXE LETRA: registro achado sem letra
        // (plainLyrics/syncedLyrics null) NÃO para a escada — antes, parava.
        // Sem artista (título sem " - ")? Vai direto pros degraus de busca.
        val res: LyricsResult? = if (q.artist.isBlank()) {
            ladder(durSec, "q" to q.title)
        } else {
            get(q)?.let(::parse)
                ?: ladder(durSec, "track_name" to q.title, "artist_name" to q.artist)
                ?: ladder(durSec, "q" to "${q.artist} ${q.title}")
                ?: ladder(durSec, "track_name" to q.title)
        }
        if (res != null) remember(key, res)
        res
    }

    /**
     * Um degrau de /api/search: pede, escolhe o melhor candidato e DEVOLVE
     * só se veio letra de verdade (parse null = degrau escada abaixo).
     */
    private fun ladder(durSec: Int?, vararg params: Pair<String, String>): LyricsResult? {
        val arr = search(*params) ?: return null
        val best = pick(arr, durSec) ?: return null
        return parse(best)
    }

    /** /api/get — casamento exato; 404/sem-JSON devolve null (cai pra busca). */
    private fun get(q: TrackQuery): JSONObject? {
        val url = StringBuilder(API)
            .append("/get?track_name=").append(enc(q.title))
            .append("&artist_name=").append(enc(q.artist))
        val req = Request.Builder().url(url.toString()).header("User-Agent", UA).build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (ignored: JSONException) {
            null
        }
    }

    /** /api/search — lista de candidatos; quem escolhe é o pick().
     *  IOException NÃO é engolida: rede caída sobe pra tela de Tentar de novo. */
    private fun search(vararg params: Pair<String, String>): JSONArray? {
        val url = StringBuilder(API).append("/search?")
        params.forEachIndexed { i, (k, v) ->
            if (i > 0) url.append('&')
            url.append(k).append('=').append(enc(v))
        }
        val req = Request.Builder().url(url.toString()).header("User-Agent", UA).build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val body = r.body?.string() ?: return null
                JSONArray(body)
            }
        } catch (ignored: JSONException) {
            null
        }
    }

    /**
     * Escolhe o melhor candidato da lista: duração perto da faixa que está
     * tocando vale mais, letra sincronizada vale mais que só texto, e
     * candidato SEM letra nenhuma nem entra.
     */
    private fun pick(arr: JSONArray?, durSec: Int?): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hasSync = o.optString("syncedLyrics").isNotBlank()
            val hasPlain = o.optString("plainLyrics").isNotBlank()
            val instrumental = o.optBoolean("instrumental", false)
            if (!hasSync && !hasPlain && !instrumental) continue
            val d = o.optDouble("duration", 0.0).toInt()
            var score = 0
            if (durSec != null) {
                score += when {
                    d == durSec -> 4
                    Math.abs(d - durSec) <= 10 -> 2
                    else -> 0
                }
            }
            if (hasSync) score += 2
            if (hasPlain) score += 1
            if (score > bestScore) {
                bestScore = score
                best = o
            }
        }
        return best ?: arr.optJSONObject(0)
    }

    private fun parse(o: JSONObject): LyricsResult? {
        val syncedRaw = o.optString("syncedLyrics").takeIf { it.isNotBlank() }
        val plain = o.optString("plainLyrics")
        val instrumental = o.optBoolean("instrumental", false)
        val synced = syncedRaw?.let(::parseLrc)?.takeIf { it.size >= 3 }
        if (!instrumental && synced == null && plain.isBlank()) return null
        return LyricsResult(
            synced = synced,
            plain = plain,
            instrumental = instrumental,
            trackName = o.optString("trackName"),
            artistName = o.optString("artistName")
        )
    }

    // ---------- parser LRC (a marcação de tempo dos players de karaoke) ----------

    // [mm:ss] [mm:ss.xx] [mm:ss:xx] — os três sabores que existem por aí
    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>""")
    private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
    private val META_TAG = Regex(
        """^\[(ti|ar|al|by|au|length|offset|re|ve|tool|hash):""",
        RegexOption.IGNORE_CASE
    )

    /**
     * LRC → lista ordenada de (tempo, linha). Uma linha pode ter VÁRIAS
     * marcas de tempo no começo (refrão repetido), tags de metadado [ar:..]
     * são ignoradas, tags de palavra <mm:ss> (letra "enriched") são
     * arrancadas do texto e o offset global é aplicado.
     */
    private fun parseLrc(raw: String): List<LyricLine> {
        var offset = 0
        val out = ArrayList<LyricLine>()
        raw.lineSequence().forEach { raw0 ->
            val line = raw0.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("[offset:", ignoreCase = true)) {
                OFFSET_TAG.find(line)?.let {
                    offset = it.groupValues[1].toIntOrNull() ?: 0
                }
                return@forEach
            }
            if (META_TAG.containsMatchIn(line)) return@forEach
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = WORD_TAG
                .replace(line.substring(tags.last().range.last + 1), "")
                .trim()
            tags.forEach { t ->
                val min = t.groupValues[1].toIntOrNull() ?: return@forEach
                val sec = t.groupValues[2].toIntOrNull() ?: return@forEach
                val fracRaw = t.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0
                    1 -> fracRaw.toInt() * 100
                    2 -> fracRaw.toInt() * 10
                    else -> fracRaw.take(3).toInt()
                }
                var ms = min * 60_000 + sec * 1_000 + frac - offset
                if (ms < 0) ms = 0
                out.add(LyricLine(ms, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
