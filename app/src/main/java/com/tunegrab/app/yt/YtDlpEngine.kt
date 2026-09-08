package com.tunegrab.app.yt

import android.content.Context
import android.util.Log
import com.tunegrab.app.R
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException

/**
 * Motor de download yt-dlp EMBUTIDO no app (python + yt-dlp + ffmpeg dentro do
 * APK — a mesma engine usada pelo app Seal). É o "plano A" do download: o
 * yt-dlp é mantido semanalmente contra as mudanças do YouTube (rotação de
 * clients do innertube, desafios de JavaScript resolvidos com o QuickJS
 * embutido, retries de fragmento), coisa que nenhum extrator fixo consegue
 * acompanhar sozinho.
 *
 * Além disso, na primeira execução de cada versão do app o yt-dlp é
 * auto-atualizado para a última versão estável ([UpdateChannel.STABLE]) — é
 * isso que mantém o download funcionando quando o YouTube muda alguma coisa.
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
                    val status = YoutubeDL.updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
                    Log.i(TAG, "update do yt-dlp: $status (agora ${versionName(ctx)})")
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
     * Baixa [videoUrl] com o yt-dlp para [outDir] (limpo antes) e devolve o
     * arquivo produzido. Bloqueante — chamar de thread de IO.
     */
    fun download(
        videoUrl: String,
        preset: Preset,
        outDir: File,
        onProgress: (percent: Float, etaSeconds: Long) -> Unit
    ): File {
        outDir.deleteRecursively()
        outDir.mkdirs()

        val req = YoutubeDLRequest(videoUrl).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--socket-timeout", "20")
            addOption("--retries", "5")
            addOption("--fragment-retries", "5")
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
                }
                is Preset.Mp4 -> {
                    val h = preset.maxHeight.coerceAtLeast(144)
                    // TODOS os ramos são limitados à altura pedida: o app nunca
                    // baixa MAIS do que foi escolhido. Áudio sempre m4a (AAC
                    // entra no MP4 sem drama; Opus no MP4 é aposta).
                    // ≤1080p: h264/mp4 primeiro (compatibilidade máxima);
                    // >1080p: o YouTube não tem h264 nessas alturas (só
                    // VP9/AV1) — VP9 vem antes do AV1 por compatibilidade
                    // de decode, e o merge sai remuxado no MP4 (VP9+AAC).
                    val chain = if (h > 1080) {
                        "bv*[vcodec^=vp9][height<=$h]+ba[ext=m4a]" +
                            "/bv*[height<=$h]+ba[ext=m4a]" +
                            "/b[height<=$h]"
                    } else {
                        "bv*[ext=mp4][height<=$h]+ba[ext=m4a]" +
                            "/bv*[vcodec^=vp9][height<=$h]+ba[ext=m4a]" +
                            "/bv*[height<=$h]+ba[ext=m4a]" +
                            "/b[ext=mp4][height<=$h]" +
                            "/b[height<=$h]"
                    }
                    addOption("-f", chain)
                    addOption("-S", "res:$h")
                    addOption("--merge-output-format", "mp4")
                }
            }
        }

        // forma posicional de propósito: o execute() tem duas sobrecargas e a
        // forma com trailing lambda pode gerar ambiguidade de resolução
        YoutubeDL.execute(req, null, { progress, eta, _ ->
            try {
                onProgress(progress, eta)
            } catch (ignored: Throwable) {
                // callback de notificação nunca pode derrubar o download
            }
        })

        val produced = outDir.listFiles()
            ?.filter { it.isFile && it.length() > MIN_BYTES }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("yt-dlp terminou sem produzir arquivo")
        return produced
    }

    /** Resposta menor que isso é página de erro, não mídia. */
    private const val MIN_BYTES = 16L * 1024L
}
