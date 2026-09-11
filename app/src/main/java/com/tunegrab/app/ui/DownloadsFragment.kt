package com.tunegrab.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunegrab.app.MainActivity
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentDownloadsBinding
import com.tunegrab.app.databinding.ItemDownloadBinding
import com.tunegrab.app.download.DownloadBus
import com.tunegrab.app.download.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central de Downloads: o que está baixando agora + o que terminou, falhou,
 * pausou ou foi cancelado.
 *
 * É um ESPELHO do estado que o DownloadService já publica nas notificações
 * (via DownloadBus) — a única coisa que interfere é os BOTÕES (pausar,
 * continuar, cancelar), que mandam intents de controle para o service.
 * Itens concluídos ganham botão de compartilhar (procura o arquivo nas
 * fontes de listagem — pasta escolhida, MediaStore ou pasta legada).
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val adapter = DownloadsAdapter()

    // Filtro do separador Músicas/Vídeos (Tudo é o padrão)
    private var filterKind = KIND_ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        // v0.19.15: "Limpar finalizados" só tira os downloads da LISTA (pra
        // ela não ficar cheia) — os arquivos continuam salvos no aparelho;
        // exclusão de verdade é a lixeira do card ou da Biblioteca
        binding.btnClear.setOnClickListener { askClearFinished() }
        // CTA do estado vazio: pula direto pro Início baixar a primeira música
        binding.btnEmptyGo.setOnClickListener {
            (activity as? MainActivity)?.openTab(R.id.navHome)
        }
        // LIGAÇÃO dos botões dos itens concluídos (o clique era morto sem isto)
        adapter.onShare = { item -> shareFinished(item) }
        adapter.onPlay = { item -> playFinished(item) }
        adapter.onDelete = { item -> deleteFinished(item) }
        adapter.onPause = { sendControl(DownloadService.controlIntent(requireContext(), DownloadService.ACTION_PAUSE)) }
        adapter.onResumeClick = { sendControl(DownloadService.resumeIntent(requireContext())) }
        adapter.onCancel = { item ->
            sendControl(
                DownloadService.controlIntent(requireContext(), DownloadService.ACTION_CANCEL, item.fileName)
            )
        }
        binding.chipAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filterKind = KIND_ALL
                render(DownloadBus.items.value)
            }
        }
        binding.chipAudio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filterKind = KIND_AUDIO
                render(DownloadBus.items.value)
            }
        }
        binding.chipVideo.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                filterKind = KIND_VIDEO
                render(DownloadBus.items.value)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadBus.items.collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun sendControl(intent: Intent) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
    }

    // ---------- limpar a lista (v0.19.15) ----------

    /**
     * "Limpar finalizados": o botão SÓ tira os downloads da LISTA da Central
     * pra ela não ficar cheia — NÃO apaga arquivo nenhum. (v0.19.15: antes
     * este botão excluía as mídias de verdade, herança da v0.19.5; hoje esse
     * papel é da lixeira no card e da Biblioteca, que apagam com verificação
     * dupla.) Diálogo na frente pra deixar claro que os arquivos ficam salvos.
     */
    private fun askClearFinished() {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dl_clear_title)
            .setMessage(R.string.dl_clear_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.dl_clear_yes) { _, _ -> clearFinishedNow() }
            .show()
    }

    /** Limpeza pura do espelho em memória (DownloadBus): concluído, falha e
     *  cancelado saem da lista; o que está rodando ou pausado continua.
     *  Nada toca no disco. */
    private fun clearFinishedNow() {
        val ctx = context ?: return
        DownloadBus.clearFinished()
        Toast.makeText(ctx, R.string.dl_clear_done_list, Toast.LENGTH_SHORT).show()
    }

    /** Lixeira do card concluído: VERIFICAÇÃO DUPLA igual à Biblioteca —
     *  diálogo do app antes; o toque errado não pode custar uma música. */
    private fun deleteFinished(item: DownloadBus.Item) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_delete_title)
            .setMessage(ctx.getString(R.string.lib_delete_msg, item.fileName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_delete_yes) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        val entry = LibraryFiles.findByFileName(ctx, item.fileName)
                        entry != null && LibraryFiles.delete(ctx, entry)
                    }
                    if (!isAdded) return@launch
                    if (ok) {
                        DownloadBus.remove(item.fileName)
                        Toast.makeText(ctx, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, R.string.lib_err_delete, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun render(items: List<DownloadBus.Item>) {
        val shown = items.filter { matchesFilter(it) }
        adapter.submit(shown)
        val b = _binding ?: return
        b.emptyState.isVisible = shown.isEmpty()
        // RAIZ DO BUG DO PRINT: lista e vazio dividem a altura em peso 1:99 —
        // com a lista VISIBLE (mesmo sem itens) o estado vazio ficava com 1%
        // da tela: só uma barrinha violeta cortada embaixo dos chips. Escondendo
        // a lista, o estado vazio toma a tela inteira.
        b.list.isVisible = shown.isNotEmpty()
        if (shown.isEmpty()) {
            if (items.isEmpty()) {
                // lista inteira vazia: convite pra baixar a primeira música
                b.tvEmptyTitle.text = getString(R.string.dl_empty_title)
                b.tvEmptySub.text = getString(R.string.dl_empty_sub)
                b.btnEmptyGo.isVisible = true
            } else {
                // tem download, mas o filtro (Músicas/Vídeos) esconde tudo
                b.tvEmptyTitle.text = getString(R.string.dl_empty_filter_title)
                b.tvEmptySub.text = getString(R.string.dl_empty_filter_sub)
                b.btnEmptyGo.isVisible = false
            }
        }
        binding.btnClear.isVisible = items.any {
            it.state != DownloadBus.State.RUNNING && it.state != DownloadBus.State.PAUSED
        }
    }

    /** Tipo do conteúdo pelo EXTENSÃO do arquivo publicado (o nome já nasce
     *  com sufixo — inclusive na fila). Vídeo = MP4/MKV (v0.10.1). */
    private fun kindOf(item: DownloadBus.Item): Int =
        when (item.fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv" -> KIND_VIDEO
            "mp3", "m4a", "opus", "ogg", "webm" -> KIND_AUDIO
            else -> KIND_UNKNOWN
        }

    /** Desconhecido aparece em TODOS os filtros — nada some sem explicação. */
    private fun matchesFilter(item: DownloadBus.Item): Boolean = when (filterKind) {
        KIND_AUDIO -> kindOf(item) != KIND_VIDEO
        KIND_VIDEO -> kindOf(item) != KIND_AUDIO
        else -> true
    }

    /** Compartilhar um download concluído: acha o arquivo pelo nome publicado. */
    internal fun shareFinished(item: DownloadBus.Item) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                LibraryFiles.findByFileName(ctx, item.fileName)
            }
            if (entry != null) {
                LibraryFiles.share(ctx, entry)
            } else {
                Toast.makeText(ctx, R.string.dl_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Reproduzir um download concluído direto daqui (player embutido). */
    internal fun playFinished(item: DownloadBus.Item) {
        val ctx = context ?: return
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) {
                LibraryFiles.findByFileName(ctx, item.fileName)
            }
            if (entry == null) {
                Toast.makeText(ctx, R.string.dl_not_found, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = LibraryFiles.shareableUri(ctx, entry)
            if (uri == Uri.EMPTY) {
                Toast.makeText(ctx, R.string.lib_err_open, Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                startActivity(
                    Intent(ctx, PlayerActivity::class.java)
                        .setDataAndType(uri, entry.mime.ifBlank { "*/*" })
                        .putExtra(PlayerActivity.EXTRA_TITLE, entry.name)
                        .putExtra(PlayerActivity.EXTRA_IS_VIDEO, entry.isVideoKind)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (t: Throwable) {
                Toast.makeText(ctx, R.string.player_err, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val KIND_ALL = 0
        private const val KIND_AUDIO = 1
        private const val KIND_VIDEO = 2
        private const val KIND_UNKNOWN = 3
    }
}

class DownloadsAdapter : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<DownloadBus.Item> = emptyList()
    var onShare: ((DownloadBus.Item) -> Unit)? = null
    var onPlay: ((DownloadBus.Item) -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onResumeClick: (() -> Unit)? = null
    var onCancel: ((DownloadBus.Item) -> Unit)? = null
    var onDelete: ((DownloadBus.Item) -> Unit)? = null

    fun submit(list: List<DownloadBus.Item>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val b = holder.binding
        val ctx = b.root.context
        b.tvName.text = item.title
        // reset do estado RECICLADO: botão de copiar do card "Falhou", a
        // lixeira do card concluído e o limite de linhas da fase não podem
        // vazar para os outros estados
        b.btnCopy.isVisible = false
        b.btnCopy.setOnClickListener(null)
        b.btnDelete.isVisible = false
        b.btnDelete.setOnClickListener(null)
        b.tvPhase.maxLines = Int.MAX_VALUE
        when (item.state) {
            DownloadBus.State.RUNNING -> {
                b.icon.setImageResource(R.drawable.ic_download)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                val isQueued = item.fileName != DownloadBus.activeFileName
                b.tvPhase.text = when {
                    item.phase.isNotBlank() -> item.phase
                    isQueued -> ctx.getString(R.string.dl_queued)
                    else -> ctx.getString(R.string.dl_preparing)
                }
                b.tvPercent.isVisible = true
                b.tvPercent.text = if (item.speed != null) {
                    ctx.getString(R.string.dl_running_pct_speed, item.percent, item.speed)
                } else {
                    ctx.getString(R.string.dl_running_pct, item.percent)
                }
                b.progress.isVisible = true
                b.progress.isIndeterminate = item.indeterminate
                b.progress.progress = item.percent
                // fase visível: baixando = violeta; processando/salvando = verde
                val finishingPhase = item.phase == ctx.getString(R.string.notif_phase_convert) ||
                    item.phase == ctx.getString(R.string.notif_phase_save)
                b.progress.progressTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        ctx,
                        if (finishingPhase) R.color.state_done else R.color.primary
                    )
                )
                // só o download ATIVO pausa; o que está na fila só cancela
                b.btnPause.isVisible = !isQueued
                b.btnPause.setOnClickListener { onPause?.invoke() }
                b.btnPlay.isVisible = false
                b.btnShare.isVisible = false
                b.btnCancel.isVisible = true
                b.btnCancel.setOnClickListener { onCancel?.invoke(item) }
            }
            DownloadBus.State.PAUSED -> {
                b.icon.setImageResource(R.drawable.ic_pause)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                b.tvPhase.text = ctx.getString(R.string.dl_state_paused)
                b.tvPercent.isVisible = true
                b.tvPercent.text = ctx.getString(R.string.dl_running_pct, item.percent)
                b.progress.isVisible = false
                // continuar (mesma faixa, de onde parou) ou desistir
                b.btnPause.isVisible = false
                b.btnPlay.isVisible = true
                b.btnPlay.setOnClickListener { onResumeClick?.invoke() }
                b.btnShare.isVisible = false
                b.btnCancel.isVisible = true
                b.btnCancel.setOnClickListener { onCancel?.invoke(item) }
            }
            DownloadBus.State.DONE -> {
                b.icon.setImageResource(R.drawable.ic_check)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.state_done))
                b.tvPhase.text = ctx.getString(R.string.dl_state_done)
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
                // download concluído → reproduzir, compartilhar e APAGAR a
                // mídia de verdade (v0.19.5) direto daqui
                b.btnPause.isVisible = false
                b.btnPlay.isVisible = true
                b.btnPlay.setOnClickListener { onPlay?.invoke(item) }
                b.btnShare.isVisible = true
                b.btnShare.setOnClickListener { onShare?.invoke(item) }
                b.btnDelete.isVisible = true
                b.btnDelete.setOnClickListener { onDelete?.invoke(item) }
                b.btnCancel.isVisible = false
            }
            DownloadBus.State.FAILED -> {
                b.icon.setImageResource(R.drawable.ic_error)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.state_failed))
                b.tvPhase.text = if (item.detail.isNullOrBlank()) {
                    ctx.getString(R.string.dl_state_failed)
                } else {
                    ctx.getString(R.string.dl_state_failed_reason, item.detail)
                }
                b.tvPhase.maxLines = 4
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
                b.btnPause.isVisible = false
                b.btnPlay.isVisible = false
                b.btnShare.isVisible = false
                b.btnCancel.isVisible = false
                // copiar o erro CRU (texto do yt-dlp/NewPipe) — é o que permite
                // diagnosticar de verdade quando o download falha
                b.btnCopy.isVisible = !item.detail.isNullOrBlank()
                b.btnCopy.setOnClickListener {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TuneGrab", item.detail ?: ""))
                    Toast.makeText(ctx, R.string.dl_error_copied, Toast.LENGTH_SHORT).show()
                }
            }
            DownloadBus.State.CANCELLED -> {
                b.icon.setImageResource(R.drawable.ic_close)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                b.tvPhase.text = ctx.getString(R.string.dl_state_cancelled)
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
                b.btnPause.isVisible = false
                b.btnPlay.isVisible = false
                b.btnShare.isVisible = false
                b.btnCancel.isVisible = false
            }
        }
    }
}
