package com.tunegrab.app

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tunegrab.app.databinding.SheetPlaylistBinding
import com.tunegrab.app.yt.YtExtractor

/**
 * Seletor da PLAYLIST: mostra título/canal/quantidade e escolhe formato +
 * qualidade UMA vez para a playlist inteira. Ao confirmar, a HomeFragment
 * (via [onConfirm]) prepara os vídeos um por um e enfileira cada um pelo
 * MESMO caminho de um download único — pausar/cancelar continua funcionando
 * item a item na Central.
 *
 * Depois do confirm() o sheet vira painel de progresso ("Preparando 3 de 25…")
 * e ganha o botão Parar. Este sheet não baixa nada por si só — só escolha e
 * espelho do progresso.
 */
class PlaylistSheet(
    private val context: Context,
    private val playlist: YtExtractor.PlaylistMeta,
    private val onConfirm: (format: String, quality: String) -> Unit
) {

    /** true assim que o usuário pede pra sair (antes ou durante a fila). */
    @Volatile
    var cancelled = false
        private set

    private var running = false
    private var currentFormat: String = FormatPrefs.lastFormat(context)

    private val dialog = BottomSheetDialog(context)
    private val binding = SheetPlaylistBinding.inflate(LayoutInflater.from(context))

    fun show() {
        binding.sheetTitle.text = playlist.title.ifBlank {
            context.getString(R.string.pl_title_fallback)
        }
        val count = context.getString(R.string.pl_count, playlist.items.size)
        val capped = if (playlist.totalFound > playlist.items.size) {
            context.getString(R.string.pl_capped, playlist.items.size)
        } else null
        binding.sheetAuthor.text = listOfNotNull(playlist.uploader, count, capped)
            .joinToString(" · ")
        playlist.thumbnail?.let { binding.sheetThumb.load(it) { crossfade(true) } }

        buildFormatChips()
        selectFormat(currentFormat)

        binding.sheetCancel.setOnClickListener {
            cancelled = true
            dialog.dismiss()
        }
        binding.sheetGo.setOnClickListener { confirm() }

        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(binding.root)
        dialog.show()
    }

    // ---------- confirmação ----------

    private fun confirm() {
        if (running) return
        val quality = binding.sheetQualityGroup.tag as? String ?: return
        running = true
        FormatPrefs.remember(context, currentFormat)

        // sheet vira painel de progresso; o botão principal some e o de
        // cancelar vira "Parar" (a fila continua até o próximo vídeo)
        binding.sheetGo.isVisible = false
        binding.sheetCancel.text = context.getString(R.string.pl_stop)
        binding.sheetProgress.isVisible = true
        binding.sheetProgress.progress = 0
        binding.sheetStatus.isVisible = true
        binding.sheetStatus.text = context.getString(R.string.pl_preparing_zero)

        onConfirm(currentFormat, quality)
    }

    /** "Preparando X de Y — nome do vídeo" (chamado pela fila na Home). */
    fun setProgress(done: Int, total: Int, current: String) {
        if (!dialog.isShowing) return
        binding.sheetProgress.max = total
        binding.sheetProgress.progress = done
        binding.sheetStatus.text =
            context.getString(R.string.pl_preparing, done + 1, total, current)
    }

    /** Fila inteira preparada: resumo com falhas, se houver. Nada entrou
     *  (ok = 0) tem mensagem própria — orienta a esperar e re-tentar. */
    fun finished(okCount: Int, failCount: Int) {
        finishPanel(
            when {
                okCount == 0 -> context.getString(R.string.pl_done_none)
                failCount == 0 -> context.getString(R.string.pl_done_all, okCount)
                else -> context.getString(R.string.pl_done_partial, okCount, failCount)
            }
        )
    }

    /** Parou no meio (verificação do YouTube): o que entrou segue na fila. */
    fun stopped(atVideo: Int, total: Int) {
        finishPanel(context.getString(R.string.pl_stopped, atVideo, total))
    }

    private fun finishPanel(statusText: String) {
        if (!dialog.isShowing) return
        binding.sheetProgress.isVisible = false
        binding.sheetStatus.text = statusText
        binding.sheetCancel.text = context.getString(R.string.pl_close)
        binding.sheetCancel.setOnClickListener { dialog.dismiss() }
    }

    // ---------- chips de formato ----------

    private fun buildFormatChips() {
        binding.sheetFormatGroup.removeAllViews()
        // Todos os tipos habilitados: cada vídeo é extraído na hora e o motor
        // adapta (MP3 cai pro melhor áudio disponível; vídeo usa o plano A do
        // yt-dlp, que não depende de faixa progressiva).
        addFormatChip(FormatPrefs.FORMAT_MP3, "Áudio • MP3")
        addFormatChip(FormatPrefs.FORMAT_M4A, "Áudio • M4A")
        addFormatChip(FormatPrefs.FORMAT_OPUS, "Áudio • Opus")
        addFormatChip(FormatPrefs.FORMAT_MP4, "Vídeo")
    }

    private fun addFormatChip(format: String, label: String) {
        val chip = newChip(binding.sheetFormatGroup, label)
        chip.tag = format
        chip.isChecked = format == currentFormat
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked && !running) selectFormat(format)
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

    /**
     * Qualidades fixas por tipo (as faixas reais variam POR VÍDEO — escolhas
     * "best/small" e alturas são resolvidas vídeo a vídeo na fila):
     *  - MP3: escada de bitrate (320/256/192/128) — igual ao seletor único;
     *  - M4A: melhor disponível ou menor arquivo (mesma lógica das Config.);
     *  - Opus: melhor disponível;
     *  - Vídeo: escada 360p → 4K (o motor baixa no máximo que o vídeo tem).
     */
    private fun buildQualityChips(format: String) {
        binding.sheetQualityGroup.removeAllViews()
        binding.sheetFormatHint.text = when (format) {
            FormatPrefs.FORMAT_MP3 -> context.getString(R.string.hint_mp3)
            FormatPrefs.FORMAT_M4A -> context.getString(R.string.hint_m4a)
            FormatPrefs.FORMAT_OPUS -> context.getString(R.string.hint_opus)
            else -> context.getString(R.string.hint_mp4)
        }

        data class Q(val value: String, val label: String)

        val qualities: List<Q> = when (format) {
            FormatPrefs.FORMAT_MP3 -> listOf("320", "256", "192", "128").map {
                Q(it, context.getString(R.string.q_kbps, it))
            }
            FormatPrefs.FORMAT_M4A -> listOf(
                Q(FormatPrefs.PICK_BEST, context.getString(R.string.pl_q_best)),
                Q(FormatPrefs.PICK_SMALL, context.getString(R.string.pl_q_small))
            )
            FormatPrefs.FORMAT_OPUS -> listOf(
                Q(FormatPrefs.PICK_BEST, context.getString(R.string.pl_q_best))
            )
            else -> listOf(2160, 1440, 1080, 720, 480, 360).map {
                Q("$it", mp4Label(it))
            }
        }

        val preferred = when (format) {
            FormatPrefs.FORMAT_MP3 -> {
                val def = FormatPrefs.mp3DefaultBitrate(context).toString()
                qualities.minByOrNull {
                    kotlin.math.abs((it.value.toIntOrNull() ?: 0) - (def.toIntOrNull() ?: 0))
                }?.value ?: qualities.first().value
            }
            FormatPrefs.FORMAT_M4A ->
                if (FormatPrefs.m4aPick(context) == FormatPrefs.PICK_SMALL) {
                    qualities.last().value
                } else {
                    qualities.first().value
                }
            // vídeo: a qualidade padrão das Configurações (v0.19.3 — era
            // 1080p fixo; o YouTube pode não ter o degrau exato, então cai
            // pro mais próximo disponível)
            else -> {
                val def = FormatPrefs.videoDefaultHeight(context)
                qualities.minByOrNull {
                    kotlin.math.abs((it.value.toIntOrNull() ?: 0) - def)
                }?.value ?: qualities.first().value
            }
        }

        qualities.forEach { q ->
            val chip = newChip(binding.sheetQualityGroup, q.label)
            chip.tag = q.value
            if (q.value == preferred) {
                chip.text = context.getString(R.string.q_default, q.label)
            }
            chip.isChecked = q.value == preferred
            chip.isEnabled = true
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) binding.sheetQualityGroup.tag = q.value
            }
            binding.sheetQualityGroup.addView(chip)
        }
        binding.sheetQualityGroup.tag = preferred
    }

    private fun mp4Label(h: Int): String = when (h) {
        2160 -> "4K (2160p)"
        else -> "${h}p"
    }

    private fun newChip(group: ChipGroup, label: String): Chip {
        val chip = LayoutInflater.from(context)
            .inflate(R.layout.chip_audio, group, false) as Chip
        chip.id = android.view.View.generateViewId()
        chip.text = label
        return chip
    }
}
