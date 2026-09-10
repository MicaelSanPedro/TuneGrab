package com.tunegrab.app.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentLibraryBinding
import com.tunegrab.app.databinding.ItemLibraryFileBinding
import com.tunegrab.app.databinding.ItemLibraryHeaderBinding
import com.tunegrab.app.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile

/**
 * Biblioteca: lista os arquivos que o TuneGrab salvou (na pasta padrão
 * Downloads/TuneGrab, na pasta escolhida ou em QUALQUER pasta do aparelho —
 * o banner pede a permissão de áudio para achar músicas antigas mesmo depois
 * de atualizar/reinstalar o app). MÚSICAS (áudio) e VÍDEOS ficam separados
 * em chips próprios — nada misturado. Ações: reproduzir no app / abrir /
 * compartilhar / apagar.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val adapter = LibraryAdapter()

    private var all: List<LibraryEntry> = emptyList()

    /** Filtro do separador: false = Músicas (padrão), true = Vídeos. */
    private var showVideos = false

    /** FILA (v0.19.0): as músicas VISÍVEIS na aba (na ordem da tela, próprias
     *  primeiro) — repassada ao player, que ganha anterior/próxima e pula
     *  sozinho pra próxima no fim de cada faixa. */
    private var audioQueue: List<LibraryEntry> = emptyList()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateBanner()
            load()
        }

    /** Apagar faixa de OUTRO app (permissão de leitura não basta): confirmar. */
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(context, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                load()
            } else {
                cant(R.string.lib_err_delete)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.btnRefresh.setOnClickListener { load() }
        binding.btnPermission.setOnClickListener {
            try {
                permLauncher.launch(LibraryFiles.mediaReadPermission())
            } catch (t: Throwable) {
                Log.w(TAG, "pedido de permissão falhou", t)
            }
        }
        adapter.onPlay = { e -> play(e) }
        adapter.onOpen = { e -> open(e) }
        adapter.onShare = { e -> LibraryFiles.share(requireContext(), e) }
        adapter.onDelete = { e -> delete(e) }
        binding.chipFilterAudio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showVideos = false
                render()
            }
        }
        binding.chipFilterVideo.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                showVideos = true
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
        load()
    }

    private fun updateBanner() {
        val ctx = context ?: return
        binding.btnPermission.isVisible = !LibraryFiles.hasMediaReadPermission(ctx)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val (entries, folderLabel) = withContext(Dispatchers.IO) { LibraryFiles.listAll(ctx) }
            val b = _binding ?: return@launch
            all = entries
            b.tvFolder.text = folderLabel
            render()
        }
    }

    /** Aplica o filtro do chip (Músicas/Vídeos) e divide em SEÇÕES: primeiro
     *  o que foi baixado PELO TuneGrab (destaque, com preferência no topo),
     *  depois as mídias achadas no aparelho. */
    private fun render() {
        val b = _binding ?: return
        val shown = all.filter { it.isVideoKind == showVideos }
        val own = shown.filter { it.fromTuneGrab }
        val others = shown.filter { !it.fromTuneGrab }
        val rows = buildList {
            if (own.isNotEmpty()) {
                add(
                    LibRow.Section(
                        getString(R.string.lib_section_own),
                        own.size,
                        own = true
                    )
                )
                addAll(own.map { LibRow.File(it) })
            }
            if (others.isNotEmpty()) {
                add(
                    LibRow.Section(
                        getString(R.string.lib_section_others),
                        others.size,
                        own = false
                    )
                )
                addAll(others.map { LibRow.File(it) })
            }
        }
        adapter.submit(rows)
        b.tvEmpty.isVisible = shown.isEmpty()
        // fila de músicas na ordem em que aparecem na tela (próprias primeiro)
        audioQueue = if (showVideos) emptyList() else own + others
        // MESMA DOENÇA do print da Central: vazio peso 1 × lista peso 99 —
        // com a lista “visível e vazia” a mensagem ficava com 1% da tela.
        b.list.isVisible = shown.isNotEmpty()
        // contagem em cada chip: dá pra ver o que tem no outro filtro sem sair daqui
        b.chipFilterAudio.text = getString(
            R.string.lib_chip_count,
            getString(R.string.lib_filter_audio),
            all.count { !it.isVideoKind }
        )
        b.chipFilterVideo.text = getString(
            R.string.lib_chip_count,
            getString(R.string.lib_filter_video),
            all.count { it.isVideoKind }
        )
    }

    // ---------- ações ----------

    /** Reproduz dentro do app (player próprio: áudio com controles, vídeo em tela). */
    private fun play(e: LibraryEntry) {
        val uri = LibraryFiles.shareableUri(requireContext(), e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            val i = Intent(requireContext(), PlayerActivity::class.java)
                .setDataAndType(uri, e.mime.ifBlank { "*/*" })
                .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                .putExtra(PlayerActivity.EXTRA_IS_VIDEO, e.isVideoKind)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // FILA (v0.19.0): nas MÚSICAS, manda a lista visível da aba + o
            // índice da faixa tocada — no player viram os botões de
            // anterior/próxima e o avanço automático no fim da faixa
            if (!e.isVideoKind && audioQueue.size > 1) {
                val uris = ArrayList<String>(audioQueue.size)
                val titles = ArrayList<String>(audioQueue.size)
                var index = -1
                for (entry in audioQueue) {
                    val u = LibraryFiles.shareableUri(requireContext(), entry)
                    if (u == Uri.EMPTY) continue
                    if (index < 0 && entry.name == e.name && entry.size == e.size) {
                        index = uris.size
                    }
                    uris.add(u.toString())
                    titles.add(entry.name)
                }
                if (uris.size > 1 && index >= 0) {
                    i.putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_URIS, uris)
                    i.putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_TITLES, titles)
                    i.putExtra(PlaybackService.EXTRA_QUEUE_INDEX, index)
                }
            }
            startActivity(i)
        } catch (t: Throwable) {
            cant(R.string.player_err)
        }
    }

    /** Abre num app externo (player do sistema, etc.). */
    private fun open(e: LibraryEntry) {
        val uri = LibraryFiles.shareableUri(requireContext(), e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, e.mime.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (t: Throwable) {
            cant(R.string.lib_err_open)
        }
    }

    /** VERIFICAÇÃO DUPLA: o toque no botão de lixo NUNCA apaga direto —
     *  primeiro um diálogo do app; para faixa de OUTRO app o sistema ainda
     *  mostra a confirmação própria dele (RecoverableSecurityException).
     *  O toque errado não pode custar uma música. */
    private fun delete(e: LibraryEntry) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_delete_title)
            .setMessage(ctx.getString(R.string.lib_delete_msg, e.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_delete_yes) { _, _ -> deleteNow(e) }
            .show()
    }

    private fun deleteNow(e: LibraryEntry) {
        val ctx = requireContext()
        lifecycleScope.launch {
            var recoverable: android.content.IntentSender? = null
            val ok = withContext(Dispatchers.IO) {
                try {
                    when {
                        e.docUri != null ->
                            DocumentFile.fromSingleUri(ctx, e.docUri)?.delete() == true
                        e.mediaUri != null ->
                            ctx.contentResolver.delete(e.mediaUri, null, null) > 0
                        else ->
                            e.file?.delete() == true
                    }
                } catch (t: Throwable) {
                    // faixa de OUTRO app (permissão de leitura não autoriza
                    // apagar): o sistema oferece confirmação própria
                    if (Build.VERSION.SDK_INT >= 29 && t is RecoverableSecurityException) {
                        recoverable = t.userAction.actionIntent.intentSender
                    } else {
                        Log.w(TAG, "apagar falhou: ${e.name}", t)
                    }
                    false
                }
            }
            if (!isAdded) return@launch
            val sender = recoverable
            when {
                sender != null ->
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                ok -> {
                    Toast.makeText(ctx, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                    load()
                }
                else -> cant(R.string.lib_err_delete)
            }
        }
    }

    private fun cant(msgRes: Int) {
        context?.let { Toast.makeText(it, msgRes, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}

/** Linha da lista da Biblioteca: cabeçalho de seção ou arquivo. */
sealed class LibRow {
    data class Section(val title: String, val count: Int, val own: Boolean) : LibRow()
    data class File(val entry: LibraryEntry) : LibRow()
}

class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class HeaderHolder(val binding: ItemLibraryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class EntryHolder(val binding: ItemLibraryFileBinding) : RecyclerView.ViewHolder(binding.root)

    private var rows: List<LibRow> = emptyList()
    var onPlay: ((LibraryEntry) -> Unit)? = null
    var onOpen: ((LibraryEntry) -> Unit)? = null
    var onShare: ((LibraryEntry) -> Unit)? = null
    var onDelete: ((LibraryEntry) -> Unit)? = null

    fun submit(list: List<LibRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is LibRow.Section) TYPE_HEADER else TYPE_FILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemLibraryHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            EntryHolder(ItemLibraryFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibRow.Section -> {
                val b = (holder as HeaderHolder).binding
                b.tvSectionTitle.text = "${row.title} · ${row.count}"
                if (row.own) {
                    b.headerIcon.setImageResource(R.drawable.ic_download)
                    b.headerIcon.setColorFilter(
                        ContextCompat.getColor(b.root.context, R.color.primary)
                    )
                } else {
                    b.headerIcon.setImageResource(R.drawable.ic_library_music)
                    b.headerIcon.setColorFilter(
                        ContextCompat.getColor(b.root.context, R.color.on_surface_variant)
                    )
                }
            }
            is LibRow.File -> {
                val entry = row.entry
                val b = (holder as EntryHolder).binding
                val ctx = b.root.context

                b.tvName.text = entry.name
                val size = LibraryFiles.formatSize(ctx, entry.size)
                val whenTxt = DateUtils.getRelativeTimeSpanString(
                    entry.modifiedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                b.tvMeta.text = ctx.getString(R.string.lib_meta_line, size, whenTxt.toString())
                if (entry.isVideoKind) {
                    b.icon.setImageResource(R.drawable.ic_movie)
                    b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.secondary))
                } else {
                    b.icon.setImageResource(R.drawable.ic_music_note)
                    b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                }

                b.root.setOnClickListener { onOpen?.invoke(entry) }
                b.btnPlay.setOnClickListener { onPlay?.invoke(entry) }
                b.btnShare.setOnClickListener { onShare?.invoke(entry) }
                b.btnDelete.setOnClickListener { onDelete?.invoke(entry) }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FILE = 1
    }
}
