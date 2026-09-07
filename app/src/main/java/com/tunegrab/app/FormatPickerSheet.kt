package com.tunegrab.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tunegrab.app.databinding.SheetFormatPickerBinding
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** Pedido de download escolhido pelo usuário no seletor. */
sealed class DownloadRequest {
    abstract val title: String

    data class Mp3(
        override val title: String,
        val source: AudioStream,
        val bitrateKbps: Int
    ) : DownloadRequest()

    data class M4a(override val title: String, val stream: AudioStream) : DownloadRequest()
    data class Mp4(override val title: String, val stream: VideoStream) : DownloadRequest()
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
    private val mp3Source: AudioStream? =
        audioOptions.firstOrNull { it.format?.name == "M4A" } ?: audioOptions.firstOrNull()

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
            else -> context.getString(R.string.hint_mp4)
        }

        val qualities: List<Pair<String, String>> = when (format) { // (valor, rótulo)
            FormatPrefs.FORMAT_MP3 -> listOf("320", "256", "192", "128").map {
                it to context.getString(R.string.q_kbps, it)
            }
            FormatPrefs.FORMAT_M4A -> m4aStreams
                .sortedByDescending { it.averageBitrate }
                .map { "${it.averageBitrate}" to context.getString(R.string.q_kbps, "${it.averageBitrate}") }
                .distinctBy { it.first }
            else -> videoOptions
                .sortedByDescending { it.height }
                .map { "${it.height}" to "${it.height}p" }
                .distinctBy { it.first }
        }

        val preferred = when {
            rememberInitialQuality -> FormatPrefs.lastQuality(context, format)
            else -> null
        } ?: defaultQualityFor(format, qualities)

        qualities.forEachIndexed { _, (value, label) ->
            val chip = newChip(binding.sheetQualityGroup, label)
            chip.tag = value
            chip.isChecked = value == preferred
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) binding.sheetQualityGroup.tag = value
            }
            binding.sheetQualityGroup.addView(chip)
        }
        // guarda a seleção inicial para o caso do usuário não tocar em nada
        binding.sheetQualityGroup.tag = preferred
    }

    private fun defaultQualityFor(format: String, qualities: List<Pair<String, String>>): String {
        if (qualities.isEmpty()) return ""
        return when (format) {
            FormatPrefs.FORMAT_MP3 -> {
                val def = FormatPrefs.mp3DefaultBitrate(context).toString()
                // cai para a mais próxima caso o padrão não exista na lista
                qualities.map { it.first }.minByOrNull {
                    kotlin.math.abs((it.toIntOrNull() ?: 0) - (def.toIntOrNull() ?: 0))
                } ?: qualities.first().first
            }
            FormatPrefs.FORMAT_M4A -> if (FormatPrefs.m4aPick(context) == FormatPrefs.PICK_SMALL) {
                qualities.last().first
            } else {
                qualities.first().first
            }
            else -> if (FormatPrefs.mp4Pick(context) == FormatPrefs.PICK_SMALL) {
                qualities.last().first
            } else {
                qualities.first().first
            }
        }
    }

    private fun selectedQuality(): String? =
        binding.sheetQualityGroup.tag as? String

    // ---------- confirmação ----------

    private fun confirm() {
        val quality = selectedQuality() ?: return
        FormatPrefs.remember(context, currentFormat, quality)
        val request: DownloadRequest = when (currentFormat) {
            FormatPrefs.FORMAT_MP3 -> {
                val src = mp3Source ?: return
                DownloadRequest.Mp3(info.name, src, quality.toIntOrNull() ?: 320)
            }
            FormatPrefs.FORMAT_M4A -> {
                val stream = m4aStreams.firstOrNull {
                    "${it.averageBitrate}" == quality
                } ?: m4aStreams.firstOrNull() ?: return
                DownloadRequest.M4a(info.name, stream)
            }
            else -> {
                val stream = videoOptions.firstOrNull { "${it.height}" == quality }
                    ?: videoOptions.firstOrNull() ?: return
                DownloadRequest.Mp4(info.name, stream)
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
