package com.tunegrab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentLibraryBinding
import com.tunegrab.app.databinding.ItemLibraryFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile

/**
 * Músicas baixadas: lista os arquivos que o TuneGrab salvou
 * (na pasta padrão Downloads/TuneGrab ou na pasta escolhida nas configurações),
 * com reproduzir no app / abrir / compartilhar / apagar.
 * Somente leitura — nada aqui mexe no download.
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val adapter = LibraryAdapter()

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
        adapter.onPlay = { e -> play(e) }
        adapter.onOpen = { e -> open(e) }
        adapter.onShare = { e -> LibraryFiles.share(requireContext(), e) }
        adapter.onDelete = { e -> delete(e) }
    }

    override fun onResume() {
        super.onResume()
        load()
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
            adapter.submit(entries)
            b.tvEmpty.isVisible = entries.isEmpty()
            b.tvFolder.text = folderLabel
        }
    }

    // ---------- ações ----------

    /** Reproduz dentro do app (player próprio: áudio com controles, vídeo em tela). */
    private fun play(e: LibraryEntry) {
        val uri = LibraryFiles.shareableUri(requireContext(), e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .setDataAndType(uri, e.mime.ifBlank { "*/*" })
                    .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                    .putExtra(PlayerActivity.EXTRA_IS_VIDEO, e.isVideoKind)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
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

    private fun delete(e: LibraryEntry) {
        val ctx = requireContext()
        lifecycleScope.launch {
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
                    Log.w(TAG, "apagar falhou: ${e.name}", t)
                    false
                }
            }
            if (!isAdded) return@launch
            if (ok) {
                Toast.makeText(ctx, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                load()
            } else {
                cant(R.string.lib_err_delete)
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

class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.EntryHolder>() {

    class EntryHolder(val binding: ItemLibraryFileBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<LibraryEntry> = emptyList()
    var onPlay: ((LibraryEntry) -> Unit)? = null
    var onOpen: ((LibraryEntry) -> Unit)? = null
    var onShare: ((LibraryEntry) -> Unit)? = null
    var onDelete: ((LibraryEntry) -> Unit)? = null

    fun submit(list: List<LibraryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder =
        EntryHolder(ItemLibraryFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        val entry = items[position]
        val b = holder.binding
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
