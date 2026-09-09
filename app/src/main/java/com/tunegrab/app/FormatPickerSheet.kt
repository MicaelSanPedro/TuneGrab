package com.tunegrab.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tunegrab.app.databinding.SheetFormatPickerBinding
import com.tunegrab.app.yt.YtExtractor
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** Pedido de download escolhido pelo usuário no seletor. */
sealed class DownloadRequest {
    abstract val title: String

    /** URL do vídeo (não do stream) — usada pelo motor yt-dlp (plano A). */
    abstract val videoUrl: String?

    data class Mp3(
        override val title: String,
        val source: AudioStream,
        val bitrateKbps: Int,
        override val videoUrl: String? = null
    ) : DownloadRequest()

    data class M4a(
        override val title: String,
        val stream: AudioStream,
        override val videoUrl: String? = null
    ) : DownloadRequest()

    /** Áudio Opus no container WebM — o formato que o YouTube entrega sem
     *  bloqueio, mesmo quando o M4A está indisponível (legado da v0.1.2). */
    data class Webm(
        override val title: String,
        val stream: AudioStream,
        override val videoUrl: String? = null
    ) : DownloadRequest()

    data class Mp4(
        override val title: String,
        /** Faixa combinada usada só pelo plano B, que não sabe juntar
         *  (limitado a 720p). Pode ser null quando a extração não devolveu
         *  faixas progressivas — o plano A (yt-dlp) baixa só com a URL. */
        val stream: VideoStream?,
        /** Altura escolhida no seletor (pode ser > 720p: o plano A junta
         *  vídeo+áudio com ffmpeg). */
        val height: Int,
        override val videoUrl: String? = null
    ) : DownloadRequest()
}

/** Preferências de formato (último TIPO escolhido) + padrões das Configurações. */
object FormatPrefs {
    private const val NAME = "tunegrab_settings"
    private const val KEY_LAST_FORMAT = "last_format"
    private const val KEY_MP3_BITRATE = "mp3_bitrate"
    private const val KEY_M4A_PICK = "m4a_pick"
    private const val KEY_INPUT_MODE = "input_mode"
    private const val KEY_VISUALIZER = "visualizer"

    const val FORMAT_MP3 = "mp3"
    const val FORMAT_M4A = "m4a"
    const val FORMAT_OPUS = "opus"
    const val FORMAT_MP4 = "mp4"
    const val PICK_BEST = "best"
    const val PICK_SMALL = "small"

    /** Seletor do Início (v0.14.0): vídeo único ou playlist. */
    const val MODE_VIDEO = "video"
    const val MODE_PLAYLIST = "playlist"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun lastFormat(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_FORMAT, FORMAT_MP3) ?: FORMAT_MP3

    /** Lembra só o TIPO (Áudio • MP3 / M4A / Opus / Vídeo) — a QUALIDADE
     *  pré-selecionada é sempre a padrão do formato (v0.10.1). */
    fun remember(ctx: Context, format: String) {
        prefs(ctx).edit().putString(KEY_LAST_FORMAT, format).apply()
    }

    fun mp3DefaultBitrate(ctx: Context): Int = prefs(ctx).getInt(KEY_MP3_BITRATE, 320)
    fun setMp3DefaultBitrate(ctx: Context, kbps: Int) = prefs(ctx).edit().putInt(KEY_MP3_BITRATE, kbps).apply()
    fun m4aPick(ctx: Context): String = prefs(ctx).getString(KEY_M4A_PICK, PICK_BEST) ?: PICK_BEST
    fun setM4aPick(ctx: Context, pick: String) = prefs(ctx).edit().putString(KEY_M4A_PICK, pick).apply()

    /** Modo do seletor do Início (vídeo/playlist) — sobrevive a fechar o app. */
    fun lastInputMode(ctx: Context): String =
        prefs(ctx).getString(KEY_INPUT_MODE, MODE_VIDEO) ?: MODE_VIDEO
    fun rememberMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_INPUT_MODE, mode).apply()
    }

    /** Barrinhas de DJ (v0.18.7): visualizador de espectro no player de
     *  áudio. Padrão LIGADO — a primeira exibição pede a permissão. */
    fun visualizerOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_VISUALIZER, true)
    fun setVisualizerOn(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VISUALIZER, on).apply()
    }
}

/**
 * Seletor de formato e qualidade, exibido como bottom sheet logo depois da
 * busca. Os tipos ficam claros ("Áudio • MP3", "Vídeo"…) e cada um tem suas
 * próprias qualidades. A pré-seleção é SEMPRE a qualidade padrão do tipo
 * (vídeo: 1080p; áudio: 320 kbps no MP3), marcada com "(padrão)" — a última
 * escolha não é mais lembrada (v0.10.1).
 */
class FormatPickerSheet(
    private val context: Context,
    private val info: StreamInfo,
    private val audioOptions: List<AudioStream>,
    private val videoOptions: List<VideoStream>,
    private val onConfirm: (DownloadRequest) -> Unit
) {

    private var currentFormat: String = FormatPrefs.lastFormat(context)

    private val m4aStreams: List<AudioStream> =
        audioOptions.filter { it.format?.name == "M4A" }
    private val opusStreams: List<AudioStream> =
        audioOptions.filter {
            it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS
        }
    private val mp3Source: AudioStream? =
        audioOptions.firstOrNull { it.format?.name == "M4A" } ?: audioOptions.firstOrNull()

    /** Resoluções que o vídeo de fato oferece (combinadas + DASH, até 4K/8K). */
    private val mp4Heights: List<Int> = YtExtractor.videoHeights(info)

    private val dialog = BottomSheetDialog(context)
    private val binding = SheetFormatPickerBinding.inflate(LayoutInflater.from(context))

    fun show() {
        binding.sheetTitle.text = info.name
        binding.sheetAuthor.text =
            context.getString(R.string.meta_line, info.uploaderName, formatDuration(info.duration))
        info.thumbnails.maxByOrNull { it.height }?.let {
            binding.sheetThumb.load(it.url) { crossfade(true) }
        }

        buildFormatChips()
        selectFormat(currentFormat)

        binding.sheetCancel.setOnClickListener { dialog.dismiss() }
        binding.sheetDownload.setOnClickListener { confirm() }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    // ---------- chips de formato ----------

    private fun buildFormatChips() {
        binding.sheetFormatGroup.removeAllViews()
        addFormatChip(FormatPrefs.FORMAT_MP3, "Áudio • MP3", mp3Source != null,
            context.getString(R.string.hint_mp3_unavailable))
        addFormatChip(FormatPrefs.FORMAT_M4A, "Áudio • M4A", m4aStreams.isNotEmpty(),
            context.getString(R.string.hint_m4a_unavailable))
        addFormatChip(FormatPrefs.FORMAT_OPUS, "Áudio • Opus", opusStreams.isNotEmpty(),
            context.getString(R.string.hint_opus_unavailable))
        // Vídeo sempre liberado: o plano A (yt-dlp) baixa só com a URL do
        // vídeo, sem depender de faixas progressivas na extração. Até 1080p
        // sai em MP4; 1440p/4K sai em MKV (VP9/AV1 dentro de MP4 o Android
        // não lê — v0.9.1).
        addFormatChip(FormatPrefs.FORMAT_MP4, "Vídeo", true,
            context.getString(R.string.hint_mp4_unavailable))
    }

    private fun addFormatChip(format: String, label: String, available: Boolean, hint: String) {
        val chip = newChip(binding.sheetFormatGroup, label)
        chip.tag = format
        chip.isChecked = format == currentFormat
        chip.isEnabled = available
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) selectFormat(format)
        }
        if (!available) {
            // guarda o motivo para mostrar quando o usuário tocar no chip desabilitado
            chip.contentDescription = hint
            chip.setOnClickListener { binding.sheetFormatHint.text = hint }
        }
        binding.sheetFormatGroup.addView(chip)
    }

    private fun selectFormat(format: String) {
        currentFormat = format
        for (i in 0 until binding.sheetFormatGroup.childCount) {
            val c = binding.sheetFormatGroup.getChildAt(i) as? Chip ?: continue
            if (c.tag == format) c.isChecked = true
        }
        buildQualityChips(format)
    }

    // ---------- chips de qualidade ----------

    private fun buildQualityChips(format: String) {
        binding.sheetQualityGroup.removeAllViews()
        binding.sheetFormatHint.text = when (format) {
            FormatPrefs.FORMAT_MP3 -> context.getString(R.string.hint_mp3)
            FormatPrefs.FORMAT_M4A -> context.getString(R.string.hint_m4a)
            FormatPrefs.FORMAT_OPUS -> context.getString(R.string.hint_opus)
            else -> context.getString(R.string.hint_mp4)
        }

        // (valor, rótulo, disponível) — desde v0.8.1 TODOS os valores são
        // habilitados sempre (o motor adapta ao máximo do vídeo); o booleano
        // segue na estrutura por compatibilidade com os formatos de áudio
        val qualities: List<Triple<String, String, Boolean>> = when (format) {
            FormatPrefs.FORMAT_MP3 -> listOf("320", "256", "192", "128").map {
                Triple(it, context.getString(R.string.q_kbps, it), true)
            }
            FormatPrefs.FORMAT_M4A -> m4aStreams
                .sortedByDescending { it.averageBitrate }
                .map {
                    Triple(
                        "${it.averageBitrate}",
                        context.getString(R.string.q_kbps, "${it.averageBitrate}"),
                        true
                    )
                }
                .distinctBy { it.first }
            FormatPrefs.FORMAT_OPUS -> opusStreams
                .sortedByDescending { it.averageBitrate }
                .map {
                    Triple(
                        "${it.averageBitrate}",
                        context.getString(R.string.q_kbps, "${it.averageBitrate}"),
                        true
                    )
                }
                .distinctBy { it.first }
            else -> mp4Entries()
        }

        // TODAS as opções ficam habilitadas sempre: se o vídeo não tiver a
        // resolução pedida, o motor baixa na maior disponível (≤ a escolhida)
        // e a notificação final confirma a resolução real do arquivo salvo.
        // Pré-seleção: sempre a qualidade PADRÃO do tipo (v0.10.1) — a opção
        // padrão ganha o rótulo "(padrão)" para o usuário enxergar de onde parte.
        val availableValues = qualities.filter { it.third }.map { it.first }
        val preferred = defaultQualityFor(format, availableValues)

        qualities.forEach { (value, label, _) ->
            val chip = newChip(binding.sheetQualityGroup, label)
            chip.tag = value
            if (value == preferred) {
                chip.text = context.getString(R.string.q_default, label)
            }
            chip.isChecked = value == preferred
            chip.isEnabled = true
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) binding.sheetQualityGroup.tag = value
            }
            binding.sheetQualityGroup.addView(chip)
        }
        // guarda a seleção inicial para o caso do usuário não tocar em nada
        binding.sheetQualityGroup.tag = preferred
    }

    /**
     * Escada padrão de vídeo (360p → 4K): TODOS os degraus ficam habilitados
     * sempre — escolheu 4K num vídeo de 1080p? O motor baixa em 1080p (o
     * máximo que o vídeo tem) e a notificação confirma a resolução real.
     * Alturas não-padrão detectadas na extração (ex.: 1072p) entram como
     * degraus extras; a detecção nunca bloqueia nada. TETO 4K: 8K saiu do
     * app (v0.9.1) — altura acima de 2160 nunca vira degrau.
     */
    private fun mp4Entries(): List<Triple<String, String, Boolean>> {
        val standard = listOf(2160, 1440, 1080, 720, 480, 360)
        val rungs = (standard + mp4Heights.filter { it in 144..2160 && it !in standard })
            .distinct()
            .sortedDescending()
        return rungs.map { Triple("$it", mp4Label(it), true) }
    }

    private fun mp4Label(h: Int): String = when (h) {
        2160 -> "4K (2160p)"
        else -> "${h}p"
    }

    /** Qualidade padrão de cada tipo — a que já vem pré-selecionada ao abrir
     *  o seletor. Vídeo: 1080p FIXO (v0.10.1); MP3: o bitrate padrão das
     *  Configurações (320 kbps de fábrica); M4A: melhor disponível (ou menor
     *  arquivo, se escolhido nas Configurações); Opus: melhor disponível. */
    private fun defaultQualityFor(format: String, values: List<String>): String {
        if (values.isEmpty()) return ""
        return when (format) {
            FormatPrefs.FORMAT_MP3 -> {
                val def = FormatPrefs.mp3DefaultBitrate(context).toString()
                // cai para a mais próxima caso o padrão não exista na lista
                values.minByOrNull {
                    kotlin.math.abs((it.toIntOrNull() ?: 0) - (def.toIntOrNull() ?: 0))
                } ?: values.first()
            }
            FormatPrefs.FORMAT_M4A -> if (FormatPrefs.m4aPick(context) == FormatPrefs.PICK_SMALL) {
                values.last()
            } else {
                values.first()
            }
            FormatPrefs.FORMAT_OPUS -> values.first()
            // vídeo: 1080p sempre — a escada padrão tem este degrau garantido
            else -> if (values.contains("1080")) "1080" else values.first()
        }
    }

    private fun selectedQuality(): String? =
        binding.sheetQualityGroup.tag as? String

    // ---------- confirmação ----------

    private fun confirm() {
        val quality = selectedQuality() ?: return
        FormatPrefs.remember(context, currentFormat)
        val videoUrl = info.originalUrl ?: info.url
        val request: DownloadRequest = when (currentFormat) {
            FormatPrefs.FORMAT_MP3 -> {
                val src = mp3Source ?: return
                DownloadRequest.Mp3(info.name, src, quality.toIntOrNull() ?: 320, videoUrl)
            }
            FormatPrefs.FORMAT_M4A -> {
                val stream = m4aStreams.firstOrNull {
                    "${it.averageBitrate}" == quality
                } ?: m4aStreams.firstOrNull() ?: return
                DownloadRequest.M4a(info.name, stream, videoUrl)
            }
            FormatPrefs.FORMAT_OPUS -> {
                val stream = opusStreams.firstOrNull {
                    "${it.averageBitrate}" == quality
                } ?: opusStreams.firstOrNull() ?: return
                DownloadRequest.Webm(info.name, stream, videoUrl)
            }
            else -> {
                val chosen = quality.toIntOrNull() ?: return
                // Plano B (URL direta) só sabe baixar faixa COM áudio: usa a
                // combinada mais próxima abaixo da altura escolhida; o plano A
                // (yt-dlp) é quem baixa a altura real (bv+ba, merge ffmpeg).
                // stream pode ficar null (extração sem faixas progressivas):
                // o plano A funciona só com a URL, então MP4 NUNCA é bloqueado.
                val stream = videoOptions.filter { it.height <= chosen }
                    .maxByOrNull { it.height }
                    ?: videoOptions.minByOrNull { it.height }
                DownloadRequest.Mp4(info.name, stream, chosen, videoUrl)
            }
        }
        dialog.dismiss()
        onConfirm(request)
    }

    // ---------- helpers ----------

    private fun newChip(group: ChipGroup, label: String): Chip {
        val chip = LayoutInflater.from(context)
            .inflate(R.layout.chip_audio, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        return chip
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
