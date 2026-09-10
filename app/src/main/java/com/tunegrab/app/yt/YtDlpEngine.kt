package com.tunegrab.app.yt

import android.content.Context
import android.util.Log
import com.tunegrab.app.R
import com.tunegrab.app.YtCookies
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Motor de download yt-dlp EMBUTIDO no app (python + yt-dlp + ffmpeg dentro do
 * APK — a mesma engine usada pelo app Seal). É o "plano A" do download: o
 * yt-dlp é mantido semanalmente contra as mudanças do YouTube (rotação de
 * clients do innertube, desafios de JavaScript resolvidos com o QuickJS
 * embutido, retries de fragmento), coisa que nenhum extrator fixo consegue
 * acompanhar sozinho.
 *
 * Além disso, na primeira execução de cada versão do app o yt-dlp é
 * auto-atualizado pelo canal NIGHTLY ([UpdateChannel.NIGHTLY]) — o yt-dlp
 * publica correção contra as mudanças do YouTube praticamente TODO DIA nesse
 * canal (a stable às vezes fica semanas parada). É isso que mantém o download
 * funcionando quando o YouTube muda alguma coisa.
 *
 * A API é bloqueante: chamar de threads de IO (o DownloadService já faz isso).
 */
object YtDlpEngine {

    private const val TAG = "TuneGrabYtDlp"

    private val lock = Any()

    @Volatile private var ready = false
    @Volatile private var updateChecked = false

    /** O que baixar e como transformar, derivado da escolha no seletor. */
    sealed class Preset {
        data class Mp3(val bitrateKbps: Int) : Preset()
        data class M4a(val preferBitrate: Int?) : Preset()
        data class Opus(val preferBitrate: Int?) : Preset()
        data class Mp4(val maxHeight: Int) : Preset()
    }

    /**
     * Prepara o motor (extrai python/yt-dlp para o disco — alguns segundos,
     * uma vez só) e, na primeira execução de cada versão do app, atualiza o
     * yt-dlp. Falha de update NÃO é fatal: a versão embutida no APK funciona.
     */
    fun ensureReady(ctx: Context, onStatus: (String) -> Unit) {
        synchronized(lock) {
            if (!ready) {
                onStatus(ctx.getString(R.string.notif_phase_engine_prepare))
                YoutubeDL.init(ctx)
                FFmpeg.init(ctx)
                ready = true
                Log.i(TAG, "yt-dlp pronto (versão embutida ${versionName(ctx)})")
            }
            if (!updateChecked) {
                updateChecked = true
                try {
                    onStatus(ctx.getString(R.string.notif_phase_engine_update))
                    val status = YoutubeDL.updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
                    Log.i(TAG, "update do yt-dlp (nightly): $status (agora ${versionName(ctx)})")
                } catch (t: Throwable) {
                    Log.w(TAG, "update do yt-dlp falhou; seguindo com a versão embutida", t)
                }
            }
        }
    }

    private fun versionName(ctx: Context): String? = try {
        YoutubeDL.versionName(ctx)
    } catch (t: Throwable) {
        null
    }

    /**
     * Baixa [videoUrl] com o yt-dlp para [outDir] e devolve o arquivo
     * produzido. Bloqueante — chamar de thread de IO.
     *
     * @param clean false na RETOMADA de um download pausado: mantém os
     *   arquivos parciais (.part/.ytdl) no diretório — o yt-dlp continua
     *   de onde parou (comportamento padrão --continue). true limpa antes.
     * @param onProcessId entrega o ID do processo python logo antes de
     *   executar — é com ele que o DownloadService PAUSA/CANCELA no meio
     *   (YoutubeDL.destroyProcessById → execute lança CanceledException).
     * @param onProgress percentual, ETA e a LINHA BRUTA do yt-dlp (de lá
     *   vem a velocidade "at 2.35MiB/s").
     */
    fun download(
        videoUrl: String,
        preset: Preset,
        outDir: File,
        clean: Boolean = true,
        onProcessId: (String) -> Unit = {},
        onProgress: (percent: Float, etaSeconds: Long, line: String?) -> Unit
    ): File {
        if (clean) outDir.deleteRecursively()
        outDir.mkdirs()

        // O request é montado A CADA TENTATIVA porque o client do innertube
        // é rotacionado: tentativa 0 usa o default do yt-dlp (que hoje se
        // auto-protege — escolhe clients tipo visionos e cai no HLS, que não
        // exige PO Token) e os retries forçam clients ALTERNATIVOS COMPROVADOS
        // por download real de ponta a ponta (2026-09-08, yt-dlp 2026.08.19,
        // mesmo com 429 na cabeça):
        //   BAIXAM: default, visionos, tv_embedded
        //   NÃO baixam: android_vr (403 — GVS exige PO Token), mweb,
        //   web, web_embedded, ios, tv, tv_simply, web_safari (zero formatos)
        fun buildRequest(client: String?): YoutubeDLRequest = YoutubeDLRequest(videoUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--socket-timeout", "20")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
            // espaça as chamadas ao innertube — mitiga o bot-check na origem
            addOption("--sleep-requests", "1")
            // COOKIES do login do YouTube (v0.18.6): com a sessão salva o
            // YouTube deixa de pedir verificação anti-bot na origem — a cura
            // recomendada pelo próprio yt-dlp. NO-OP sem cookies: o fluxo
            // anônimo de sempre (rotação/retries) segue 100% idêntico.
            YtCookies.applyTo(this)
            if (client != null) {
                addOption("--extractor-args", "youtube:player_client=$client")
            }
            addOption("-o", File(outDir, "%(title)s.%(ext)s").absolutePath)
            when (preset) {
                is Preset.Mp3 -> {
                    addOption("-f", "ba/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    // NUMÉRICO, sem sufixo "K": versões do yt-dlp que não
                    // stripam o sufixo tratariam "320K" como qualidade VBR
                    // inválida e o ffmpeg sairia no padrão (128k). O número
                    // puro mapeia direto para "-b:a 320k" (CBR real).
                    addOption("--audio-quality", "${preset.bitrateKbps}")
                    // ID3 com título/artista, como o yt-dlp faz no desktop
                    addOption("--add-metadata")
                    // CAPA NO ARQUIVO (v0.19.0, pedido do autor): thumbnail
                    // do vídeo vira capa ID3 (webp → jpg automático pelo
                    // ffmpeg embutido). Falha de capa não derruba o download
                    // (rede de segurança no catch abaixo).
                    addOption("--embed-thumbnail")
                }
                is Preset.M4a -> {
                    // TOLERÂNCIA +16: o YouTube reporta abr fracionário (ex.: itag 140
                    // = 129,5 kbps) e o NewPipe arredonda para 129 — sem tolerância o
                    // filtro "abr<=129" perde o stream exato e cai no fallback (que
                    // pode ser um Opus 70k re-encodado = qualidade PIOR). Com a
                    // tolerância, o M4A original é baixado SEM reconversão.
                    val q = preset.preferBitrate
                    val filter = if (q != null && q > 0) {
                        val cap = q + 16
                        "ba[ext=m4a][abr<=$cap]/ba[ext=m4a]/ba/best"
                    } else {
                        "ba[ext=m4a]/ba/best"
                    }
                    addOption("-f", filter)
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    // CAPA + TÍTULO/ARTISTA dentro do arquivo (v0.19.0, pedido
                    // do autor — "independente do formato"): capa no átomo
                    // covr do MP4 via ffmpeg embutido. Falha de capa não
                    // derruba o download (rede de segurança no catch abaixo).
                    addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                }
                is Preset.Opus -> {
                    // mesma tolerância do M4A (abr reportado: 158 vs chip "160")
                    val q = preset.preferBitrate
                    val filter = if (q != null && q > 0) {
                        val cap = q + 16
                        "ba[ext=webm][abr<=$cap]/ba[ext=webm]/ba/best"
                    } else {
                        "ba[ext=webm]/ba/best"
                    }
                    addOption("-f", filter)
                    addOption("-x")
                    addOption("--audio-format", "opus")
                    // CAPA + METADADOS (v0.19.0, pedido do autor): o -x
                    // --audio-format opus repacka o webm em OGG/Opus — e o
                    // OGG aceita a capa embutida (METADATA_BLOCK_PICTURE via
                    // ffmpeg embutido). Falha de capa não derruba o download
                    // (rede de segurança no catch abaixo).
                    addOption("--embed-thumbnail")
                    addOption("--embed-metadata")
                }
                is Preset.Mp4 -> {
                    // TETO 4K: 8K (4320p) saiu do app — arquivo gigante para
                    // uma resolução que quase nenhum aparelho reproduz liso.
                    // (nota: num retry com client alternativo que sirva menos
                    // formatos, a cadeia de fallback termina em /b[height<=h] —
                    // pior caso baixa menos que o pedido em vez de falhar.)
                    val h = preset.maxHeight.coerceAtLeast(144).coerceAtMost(2160)
                    // TODOS os ramos são limitados à altura pedida: o app nunca
                    // baixa MAIS do que foi escolhido. Áudio sempre m4a (AAC
                    // entra em qualquer contêiner sem drama).
                    //
                    // CONTÊINER É AQUI QUE NÃO PODE ERRAR: o YouTube só serve
                    // VP9/AV1 acima de 1080p — e VP9/AV1 dentro de MP4 fica
                    // ILEGÍVEL para o Android (MediaExtractor/Google Photos/
                    // players tratam como arquivo corrompido: tela preta,
                    // "não é possível reproduzir"). Por isso >1080p é mesclado
                    // em MKV — VP9/AV1 + AAC no Matroska é o par nativo (o
                    // yt-dlp do desktop faz o mesmo) e abre em qualquer player.
                    // ≤1080p segue em MP4/H.264, que é universal de verdade.
                    //
                    // Pedido >1080 num vídeo que NÃO tem nada acima de 1080
                    // (clamp, ex.: 4K num vídeo de 1080p): a cadeia cai no
                    // ramo h264/mp4 ≤1080 (saído em MKV, mesma altura que um
                    // pedido 1080p). A notificação final confirma a altura real
                    // do arquivo salvo.
                    val chain: String
                    if (h > 1080) {
                        chain = "bv*[vcodec^=vp9][height>1080][height<=$h]+ba[ext=m4a]" +
                            "/bv*[height>1080][height<=$h]+ba[ext=m4a]" +
                            "/bv*[ext=mp4][height<=1080]+ba[ext=m4a]" +
                            "/bv*[vcodec^=vp9][height<=$h]+ba[ext=m4a]" +
                            "/bv*[height<=$h]+ba[ext=m4a]" +
                            "/b[ext=mp4][height<=1080]" +
                            "/b[height<=$h]"
                        addOption("--merge-output-format", "mkv")
                    } else {
                        chain = "bv*[ext=mp4][height<=$h]+ba[ext=m4a]" +
                            "/bv*[vcodec^=vp9][height<=$h]+ba[ext=m4a]" +
                            "/bv*[height<=$h]+ba[ext=m4a]" +
                            "/b[ext=mp4][height<=$h]" +
                            "/b[height<=$h]"
                        addOption("--merge-output-format", "mp4")
                    }
                    addOption("-f", chain)
                    addOption("-S", "res:$h")
                }
            }
        }

        // forma posicional de propósito: o execute() tem duas sobrecargas e a
        // forma com trailing lambda pode gerar ambiguidade de resolução.
        // O processId é o gancho de pausa/cancelamento do service.
        val processId = UUID.randomUUID().toString()
        onProcessId(processId)

        var attempt = 0
        while (true) {
            try {
                YoutubeDL.execute(buildRequest(clientFor(attempt)), processId, { progress, eta, line ->
                    try {
                        onProgress(progress, eta, line)
                    } catch (ignored: Throwable) {
                        // callback de notificação nunca pode derrubar o download
                    }
                })
                return outDir.listFiles()
                    ?.filter { it.isFile && it.length() > MIN_BYTES }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw IOException("yt-dlp terminou sem produzir arquivo")
            } catch (e: YoutubeDL.CanceledException) {
                throw e // pausa/cancelamento pedidos pelo usuário: NUNCA re-tentar
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                // REDE DE SEGURANÇA DA CAPA (v0.19.0): a capa/metadados são
                // bônus — se o postprocessor de embedding falhar (thumbnail
                // estranha, ffmpeg resmungando) MAS o áudio já estiver em pé
                // no diretório, ENTREGA o arquivo em vez de falhar. Sem isso,
                // um vídeo sem thumbnail jogaria fora o áudio bom.
                if (COVER_FAIL_MARKERS.any { it in msg }) {
                    val produced = outDir.listFiles()
                        ?.filter { it.isFile && it.length() > MIN_BYTES }
                        ?.maxByOrNull { it.lastModified() }
                    if (produced != null) {
                        Log.w(
                            TAG,
                            "capa/metadados falharam ($msg); entregando o áudio sem capa: ${produced.name}"
                        )
                        return produced
                    }
                }
                attempt++
                if (attempt <= BOT_CHECK_RETRIES && BOT_CHECK_MARKERS.any { it in msg }) {
                    // O bot-check do YouTube ("Sign in to confirm you're not a
                    // bot") depende da reputação do IP E do client do innertube
                    // escolhido na extração: para além do processo novo (novo
                    // desafio), o retry força OUTRO client comprovado por
                    // download real (visionos, tv_embedded). Os parciais são
                    // apagados: trocar de client pode mudar o formato escolhido
                    // e retomar bytes de OUTRO formato corromperia o arquivo.
                    outDir.listFiles()?.forEach { it.delete() }
                    Log.w(
                        TAG,
                        "bot-check do YouTube (tentativa $attempt/${BOT_CHECK_RETRIES + 1}, client=${clientFor(attempt) ?: "default"}); re-tentando",
                        e
                    )
                    Thread.sleep(1500L * attempt)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Rotação de clients do innertube entre tentativas — SOMENTE clients que
     * comprovadamente BAIXAM de ponta a ponta (2026-09-08): visionos e
     * tv_embedded. O android_vr, histórico queridinho anti-bot-check, agora
     * serve formato que EXIGE PO Token do GVS e dá HTTP 403 no download —
     * fora da rotação. Tentativa 0 = default do yt-dlp (se auto-protege com
     * HLS); cada retry é também um processo novo (novo desafio BotGuard).
     */
    private val RETRY_CLIENTS = listOf("visionos", "tv_embedded")
    private fun clientFor(attempt: Int): String? = RETRY_CLIENTS.getOrNull(attempt - 1)

    /**
     * Re-tentativas extras quando o yt-dlp cai no bot-check do YouTube.
     * Marcadores minúsculos contra a MENSAGEM do erro (o yt-dlp devolve
     * "Sign in to confirm you're not a bot" e variações; 429 é o rate-limit
     * que antecede o desafio; "requested format is not available" pode ser
     * o YouTube entregando a lista de formatos VAZIA por desconfiança —
     * outro client costuma devolver os formatos). Pausa/cancelamento nunca
     * passam por aqui.
     */
    private const val BOT_CHECK_RETRIES = 2
    private val BOT_CHECK_MARKERS = listOf(
        "not a bot",
        "sign in to confirm",
        "confirm you're not",
        "too many requests",
        "http error 429",
        "requested format is not available"
    )

    /** Erros de capa/metadados NÃO falham o download: com o áudio em pé no
     *  diretório, ele é entregue sem capa (a capa é bônus, não produto). */
    private val COVER_FAIL_MARKERS = listOf(
        "thumbnail",
        "atomicparsley",
        "embed",
        "metadata"
    )

    /** Resposta menor que isso é página de erro, não mídia. */
    private const val MIN_BYTES = 16L * 1024L
}
