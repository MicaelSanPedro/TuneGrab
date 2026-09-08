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
        val stream: VideoStream,
        /** Altura escolhida no seletor (pode ser > 720p: o plano A junta
         *  vídeo+áudio com ffmpeg). [stream] é a faixa combinada usada só
         *  pelo plano B, que não sabe juntar (limitado a 720p). */
        val height: Int,
        override val videoUrl: String? = null
    ) : DownloadRequest()
}

/** Preferências de formato/qualidade (última escolha + padrões das Configurações). */
object FormatPrefs {
    private const val NAME = "tunegrab_settings"
    private const val KEY_LAST_FORMAT = "last_format"
    private const val KEY_LAST_Q = "last_quality_"
    private const val KEY_MP3_BITRATE = "mp3_bitrate"
    private const val KEY_M4A_PICK = "m4a_pick"
    private const val KEY_MP4_PICK = "mp4_pick"

    const val FORMAT_MP3 = "mp3"
    const val FORMAT_M4A = "m4a"
    const val FORMAT_OPUS = "opus"
    const val FORMAT_MP4 = "mp4"
    const val PICK_BEST = "best"
    const val PICK_SMALL = "small"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun lastFormat(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_FORMAT, FORMAT_MP3) ?: FORMAT_MP3

    fun lastQuality(ctx: Context, format: String): String? =
        prefs(ctx).getString(KEY_LAST_Q + format, null)

    fun remember(ctx: Context, format: String, quality: String) {
        prefs(ctx).edit()
            .putString(KEY_LAST_FORMAT, format)
            .putString(KEY_LAST_Q + format, quality)
            .apply()
    }

    fun mp3DefaultBitrate(ctx: Context): Int = prefs(ctx).getInt(KEY_MP3_BITRATE, 320)
    fun setMp3DefaultBitrate(ctx: Context, kbps: Int) = prefs(ctx).edit().putInt(KEY_MP3_BITRATE, kbps).apply()
    fun m4aPick(ctx: Context): String = prefs(ctx).getString(KEY_M4A_PICK, PICK_BEST) ?: PICK_BEST
    fun setM4aPick(ctx: Context, pick: String) = prefs(ctx).edit().putString(KEY_M4A_PICK, pick).apply()
    fun mp4Pick(ctx: Context): String = prefs(ctx).getString(KEY_MP4_PICK, PICK_BEST) ?: PICK_BEST
    fun setMp4Pick(ctx: Context, pick: String) = prefs(ctx).edit().putString(KEY_MP4_PICK, pick).apply()
}

/**
 * Seletor de formato (MP3 / M4A / MP4) e qualidade, exibido como bottom sheet
 * logo depois da busca. Cada formato tem suas próprias qualidades, e a
 * pré-seleção vem das Configurações (padrão por formato) ou da última escolha.
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
        selectFormat(currentFormat, rememberInitialQuality = true)

        binding.sheetCancel.setOnClickListener { dialog.dismiss() }
        binding.sheetDownload.setOnClickListener { confirm() }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    // ---------- chips de formato ----------

    private fun buildFormatChips() {
        binding.sheetFormatGroup.removeAllViews()
        addFormatChip(FormatPrefs.FORMAT_MP3, "MP3", mp3Source != null,
            context.getString(R.string.hint_mp3_unavailable))
        addFormatChip(FormatPrefs.FORMAT_M4A, "M4A", m4aStreams.isNotEmpty(),
            context.getString(R.string.hint_m4a_unavailable))
        addFormatChip(FormatPrefs.FORMAT_OPUS, "OPUS (original)", opusStreams.isNotEmpty(),
            context.getString(R.string.hint_opus_unavailable))
        addFormatChip(FormatPrefs.FORMAT_MP4, "MP4", videoOptions.isNotEmpty(),
            context.getString(R.string.hint_mp4_unavailable))
    }

    private fun addFormatChip(format: String, label: String, available: Boolean, hint: String) {
        val chip = newChip(binding.sheetFormatGroup, label)
        chip.tag = format
        chip.isChecked = format == currentFormat
        chip.isEnabled = available
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) selectFormat(format, rememberInitialQuality = false)
        }
        if (!available) {
            // guarda o motivo para mostrar quando o usuário tocar no chip desabilitado
            chip.contentDescription = hint
            chip.setOnClickListener { binding.sheetFormatHint.text = hint }
        }
        binding.sheetFormatGroup.addView(chip)
    }

    private fun selectFormat(format: String, rememberInitialQuality: Boolean) {
        currentFormat = format
        for (i in 0 until binding.sheetFormatGroup.childCount) {
            val c = binding.sheetFormatGroup.getChildAt(i) as? Chip ?: continue
            if (c.tag == format) c.isChecked = true
        }
        buildQualityChips(format, rememberInitialQuality)
    }

    // ---------- chips de qualidade ----------

    private fun buildQualityChips(format: String, rememberInitialQuality: Boolean) {
        binding.sheetQualityGroup.removeAllViews()
        binding.sheetFormatHint.text = when (format) {
            FormatPrefs.FORMAT_MP3 -> context.getString(R.string.hint_mp3)
            FormatPrefs.FORMAT_M4A -> context.getString(R.string.hint_m4a)
            FormatPrefs.FORMAT_OPUS -> context.getString(R.string.hint_opus)
            else -> mp4Heights.maxOrNull()?.takeIf { it > 0 }
                ?.let { context.getString(R.string.hint_mp4_max, it) }
                ?: context.getString(R.string.hint_mp4)
        }

        // (valor, rótulo, disponível) — MP4 usa a escada com disponibilidade
        // real do vídeo; formatos de áudio sempre têm todas as opções
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

        val availableValues = qualities.filter { it.third }.map { it.first }
        val remembered = if (rememberInitialQuality) {
            FormatPrefs.lastQuality(context, format)
        } else null
        // última escolha só vale se ainda existir nesta lista (ex.: 4K
        // lembrado de outro vídeo não pode vir pré-selecionado aqui)
        val preferred = remembered?.takeIf { it in availableValues }
            ?: defaultQualityFor(format, availableValues)

        qualities.forEach { (value, label, available) ->
            val chip = newChip(binding.sheetQualityGroup, label)
            chip.tag = value
            chip.isChecked = value == preferred
            chip.isEnabled = available
            if (available) {
                chip.setOnCheckedChangeListener { _, checked ->
                    if (checked) binding.sheetQualityGroup.tag = value
                }
            } else {
                // guarda o motivo para mostrar quando o usuário tocar no chip
                val why = context.getString(
                    R.string.hint_mp4_height_unavailable,
                    mp4Heights.maxOrNull() ?: 0
                )
                chip.contentDescription = why
                chip.setOnClickListener { binding.sheetFormatHint.text = why }
            }
            binding.sheetQualityGroup.addView(chip)
        }
        // guarda a seleção inicial para o caso do usuário não tocar em nada
        binding.sheetQualityGroup.tag = preferred
    }

    /**
     * Escada padrão de MP4 (720p/1080p/4K...): cada degrau SÓ fica
     * habilitado se a extração mostra que o vídeo realmente tem essa
     * resolução — a certeza começa na escolha. Degraus acima do máximo do
     * vídeo continuam visíveis, mas desabilitados (com o motivo no toque).
     * Alturas não-padrão do vídeo (ex.: 1072p) entram como degraus extras.
     */
    private fun mp4Entries(): List<Triple<String, String, Boolean>> {
        val max = mp4Heights.maxOrNull() ?: return emptyList()
        val standard = listOf(2160, 1440, 1080, 720, 480, 360)
        val rungs = (standard + mp4Heights.filter { it > 0 && it !in standard && it <= max })
            .distinct()
            .sortedDescending()
        return rungs.mapNotNull { h ->
            when {
                h <= max -> Triple("$h", mp4Label(h), true)
                h in standard && h <= 2160 -> Triple("$h", mp4Label(h), false)
                else -> null
            }
        }
    }

    private fun mp4Label(h: Int): String = when (h) {
        2160 -> "4K (2160p)"
        4320 -> "8K (4320p)"
        else -> "${h}p"
    }

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
            else -> if (FormatPrefs.mp4Pick(context) == FormatPrefs.PICK_SMALL) {
                values.last()
            } else {
                values.first()
            }
        }
    }

    private fun selectedQuality(): String? =
        binding.sheetQualityGroup.tag as? String

    // ---------- confirmação ----------

    private fun confirm() {
        val quality = selectedQuality() ?: return
        FormatPrefs.remember(context, currentFormat, quality)
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
                val stream = videoOptions.filter { it.height <= chosen }
                    .maxByOrNull { it.height }
                    ?: videoOptions.minByOrNull { it.height }
                    ?: return
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
