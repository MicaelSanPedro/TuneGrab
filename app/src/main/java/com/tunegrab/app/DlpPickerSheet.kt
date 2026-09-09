package com.tunegrab.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tunegrab.app.databinding.SheetFormatPickerBinding

/**
 * Seletor de formato do MODO DE RESGATE (v0.18.4).
 *
 * Quando a extração normal (NewPipe + PoToken) cai no bot-check do YouTube,
 * o [DlpMetadata] extrai os metadados pelo yt-dlp embutido e ESTE seletor
 * abre com a mesma cara de sempre (MESMO layout XML do seletor normal): o
 * usuário escolhe formato/qualidade igualzinho e o download sai pelo Plano A
 * (yt-dlp via DownloadService) — que não depende de PoToken, WebView nem de
 * rota nenhuma do NewPipe.
 *
 * Diferenças honestas em relação ao seletor normal: os chips de M4A/Opus
 * usam os bitrates REAIS que o yt-dlp enxergou; MP4 usa a escada padrão
 * (2160→360) + alturas extras detectadas — a semântica de escolha é a mesma
 * ("escolheu 4K num vídeo de 1080p? baixa em 1080p").
 *
 * Este arquivo NÃO mexe no motor: a confirmação devolve (formato, qualidade)
 * e o HomeFragment monta o intent do Plano A com os MESMOS extras de sempre.
 */
class DlpPickerSheet(
    private val context: Context,
    private val meta: DlpMetadata.VideoMeta,
    private val onConfirm: (format: String, quality: String) -> Unit
) {

    private var currentFormat: String = FormatPrefs.lastFormat(context)

    private val dialog = BottomSheetDialog(context)
    private val binding = SheetFormatPickerBinding.inflate(LayoutInflater.from(context))

    fun show() {
        binding.sheetTitle.text = meta.title
        binding.sheetAuthor.text =
            context.getString(R.string.meta_line, meta.uploader ?: "", formatDuration(meta.durationSec))
        meta.thumbnailUrl?.let { binding.sheetThumb.load(it) { crossfade(true) } }

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
        // MP3 sempre liberado: ba/best existe em qualquer vídeo que o yt-dlp
        // consiga extrair (converte com o ffmpeg embutido)
        addFormatChip(FormatPrefs.FORMAT_MP3, "Áudio • MP3", true,
            context.getString(R.string.hint_mp3_unavailable))
        addFormatChip(FormatPrefs.FORMAT_M4A, "Áudio • M4A", meta.m4aBitrates.isNotEmpty(),
            context.getString(R.string.hint_m4a_unavailable))
        addFormatChip(FormatPrefs.FORMAT_OPUS, "Áudio • Opus", meta.opusBitrates.isNotEmpty(),
            context.getString(R.string.hint_opus_unavailable))
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

        val qualities: List<Pair<String, String>> = when (format) {
            FormatPrefs.FORMAT_MP3 -> listOf("320", "256", "192", "128").map {
                it to context.getString(R.string.q_kbps, it)
            }
            FormatPrefs.FORMAT_M4A -> meta.m4aBitrates.map {
                "$it" to context.getString(R.string.q_kbps, "$it")
            }
            FormatPrefs.FORMAT_OPUS -> meta.opusBitrates.map {
                "$it" to context.getString(R.string.q_kbps, "$it")
            }
            else -> mp4Entries()
        }

        val values = qualities.map { it.first }
        val preferred = defaultQualityFor(format, values)

        qualities.forEach { (value, label) ->
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
        binding.sheetQualityGroup.tag = preferred
    }

    /**
     * Escada de vídeo idêntica à do seletor normal: degraus padrão
     * (2160→360) + alturas extras que o yt-dlp detectou. Todos habilitados —
     * o motor baixa no máximo disponível ≤ escolhido e a notificação confirma
     * a resolução real.
     */
    private fun mp4Entries(): List<Pair<String, String>> {
        val standard = listOf(2160, 1440, 1080, 720, 480, 360)
        val rungs = (standard + meta.heights.filter { it in 144..2160 && it !in standard })
            .distinct()
            .sortedDescending()
        return rungs.map { "$it" to mp4Label(it) }
    }

    private fun mp4Label(h: Int): String = when (h) {
        2160 -> "4K (2160p)"
        else -> "${h}p"
    }

    private fun defaultQualityFor(format: String, values: List<String>): String {
        if (values.isEmpty()) return ""
        return when (format) {
            FormatPrefs.FORMAT_MP3 -> {
                val def = FormatPrefs.mp3DefaultBitrate(context).toString()
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
            else -> if (values.contains("1080")) "1080" else values.first()
        }
    }

    private fun selectedQuality(): String? =
        binding.sheetQualityGroup.tag as? String

    // ---------- confirmação ----------

    private fun confirm() {
        val quality = selectedQuality() ?: return
        FormatPrefs.remember(context, currentFormat)
        dialog.dismiss()
        onConfirm(currentFormat, quality)
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
