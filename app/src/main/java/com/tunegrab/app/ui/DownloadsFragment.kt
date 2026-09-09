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
import com.tunegrab.app.update.UpdateChecker
import com.tunegrab.app.update.UpdateInstaller
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

    // Estado do card de atualização (Fase 2): baixando? % atual?
    private var updateInfo: UpdateChecker.UpdateInfo? = null
    private var updateDownloading = false
    private var updatePercent = 0

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
        binding.btnClear.setOnClickListener { DownloadBus.clearFinished() }
        // CTA do estado vazio: pula direto pro Início baixar a primeira música
        binding.btnEmptyGo.setOnClickListener {
            (activity as? MainActivity)?.openTab(R.id.navHome)
        }
        // LIGAÇÃO dos botões dos itens concluídos (o clique era morto sem isto)
        adapter.onShare = { item -> shareFinished(item) }
        adapter.onPlay = { item -> playFinished(item) }
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

        // Fase 1 do update automático: consulta o GitHub (throttle 6h, falha
        // silenciosa) e mostra o card SE existir versão mais nova. Fora do
        // repeatOnLifecycle de propósito: 1 consulta por abertura, não 1 por
        // frame de STARTED.
        viewLifecycleOwner.lifecycleScope.launch {
            val info = UpdateChecker.check(requireContext())
            if (info != null && _binding != null) showUpdateCard(info)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Card "nova versão disponível": changelog + baixar/instalar + link. */
    private fun showUpdateCard(info: UpdateChecker.UpdateInfo) {
        val b = _binding ?: return
        updateInfo = info
        b.cardUpdate.isVisible = true
        b.tvUpdateTitle.text = getString(R.string.upd_available, info.version)
        b.tvUpdateNotes.text = info.notes.ifBlank { getString(R.string.upd_no_notes) }
        b.btnUpdateOpen.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
            } catch (t: Throwable) {
                // sem navegador no aparelho — card continua lá, nada quebra
            }
        }
        b.btnCloseUpdate.setOnClickListener {
            b.cardUpdate.isVisible = false
            UpdateChecker.dismiss(requireContext(), info.version)
        }
        b.btnUpdateAction.setOnClickListener { onUpdateAction(info) }
        refreshUpdateAction()
    }

    /** Estado do botão principal: Baixar → Baixando…% → Instalar agora. */
    private fun refreshUpdateAction() {
        val b = _binding ?: return
        val info = updateInfo ?: return
        val ctx = context ?: return
        when {
            updateDownloading -> {
                b.btnUpdateAction.isEnabled = false
                b.btnUpdateAction.text = getString(R.string.upd_downloading, updatePercent)
                b.progressUpdate.isVisible = true
                b.progressUpdate.progress = updatePercent
            }
            UpdateInstaller.isReady(ctx, info) -> {
                b.btnUpdateAction.isEnabled = true
                b.btnUpdateAction.text = getString(R.string.upd_install)
                b.progressUpdate.isVisible = false
            }
            else -> {
                b.btnUpdateAction.isEnabled = true
                b.btnUpdateAction.text = getString(R.string.upd_download)
                b.progressUpdate.isVisible = false
            }
        }
    }

    private fun onUpdateAction(info: UpdateChecker.UpdateInfo) {
        if (updateDownloading) return
        val ctx = context ?: return
        if (UpdateInstaller.isReady(ctx, info)) {
            installUpdate(info)
            return
        }
        // rede móvel = dados cobrados: confirma antes de baixar ~100MB
        if (UpdateInstaller.isOnMetered(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.upd_metered_title)
                .setMessage(
                    ctx.getString(
                        R.string.upd_metered_msg,
                        UpdateInstaller.formatSize(ctx, info.apkSize)
                    )
                )
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.upd_metered_yes) { _, _ -> startUpdateDownload(info) }
                .show()
        } else {
            startUpdateDownload(info)
        }
    }

    private fun startUpdateDownload(info: UpdateChecker.UpdateInfo) {
        val ctx = context ?: return
        updateDownloading = true
        updatePercent = 0
        refreshUpdateAction()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                UpdateInstaller.download(ctx.applicationContext, info) { pct ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        updatePercent = pct
                        val b = _binding ?: return@launch
                        b.progressUpdate.progress = pct
                        b.btnUpdateAction.text = getString(R.string.upd_downloading, pct)
                    }
                }
                updateDownloading = false
                refreshUpdateAction()
                Toast.makeText(ctx, R.string.upd_ready_toast, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                updateDownloading = false
                refreshUpdateAction()
                Toast.makeText(ctx, R.string.upd_err_download, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installUpdate(info: UpdateChecker.UpdateInfo) {
        val ctx = context ?: return
        // Android 8+: sem "instalar apps desconhecidos"? manda pro ajuste certo
        if (UpdateInstaller.needsInstallPermission(ctx)) {
            Toast.makeText(ctx, R.string.upd_need_permission, Toast.LENGTH_LONG).show()
            try {
                ctx.startActivity(UpdateInstaller.unknownSourcesScreen(ctx))
            } catch (t: Throwable) {
            }
            return
        }
        try {
            ctx.startActivity(UpdateInstaller.installIntent(ctx, info))
        } catch (t: Throwable) {
            Toast.makeText(ctx, R.string.upd_err_install, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendControl(intent: Intent) {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
    }

    private fun render(items: List<DownloadBus.Item>) {
        val shown = items.filter { matchesFilter(it) }
        adapter.submit(shown)
        val b = _binding ?: return
        b.emptyState.isVisible = shown.isEmpty()
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
        // reset do estado RECICLADO: botão de copiar do card "Falhou" e o
        // limite de linhas da fase não podem vazar para os outros estados
        b.btnCopy.isVisible = false
        b.btnCopy.setOnClickListener(null)
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
                // download concluído → reproduzir e compartilhar direto daqui
                b.btnPause.isVisible = false
                b.btnPlay.isVisible = true
                b.btnPlay.setOnClickListener { onPlay?.invoke(item) }
                b.btnShare.isVisible = true
                b.btnShare.setOnClickListener { onShare?.invoke(item) }
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
