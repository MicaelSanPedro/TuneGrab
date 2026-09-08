package com.tunegrab.app.ui

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
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentDownloadsBinding
import com.tunegrab.app.databinding.ItemDownloadBinding
import com.tunegrab.app.download.DownloadBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central de Downloads: o que está baixando agora + o que terminou ou falhou.
 *
 * É só um ESPELHO do estado que o DownloadService já publica nas notificações
 * (via DownloadBus) — nada aqui interfere na mecânica de download.
 * Itens concluídos ganham botão de compartilhar (procura o arquivo nas
 * fontes de listagem — pasta escolhida, MediaStore ou pasta legada).
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!
    private val adapter = DownloadsAdapter()

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

    private fun render(items: List<DownloadBus.Item>) {
        adapter.submit(items)
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.btnClear.isVisible = items.any { it.state != DownloadBus.State.RUNNING }
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
}

class DownloadsAdapter : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<DownloadBus.Item> = emptyList()
    var onShare: ((DownloadBus.Item) -> Unit)? = null

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
        when (item.state) {
            DownloadBus.State.RUNNING -> {
                b.icon.setImageResource(R.drawable.ic_download)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                b.tvPhase.text = item.phase.ifBlank { ctx.getString(R.string.dl_preparing) }
                b.tvPercent.isVisible = true
                b.tvPercent.text = ctx.getString(R.string.dl_running_pct, item.percent)
                b.progress.isVisible = true
                b.progress.isIndeterminate = item.indeterminate
                b.progress.progress = item.percent
                b.btnShare.isVisible = false
            }
            DownloadBus.State.DONE -> {
                b.icon.setImageResource(R.drawable.ic_check)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.state_done))
                b.tvPhase.text = ctx.getString(R.string.dl_state_done)
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
                // download concluído → compartilhar direto daqui
                b.btnShare.isVisible = true
                b.btnShare.setOnClickListener { onShare?.invoke(item) }
            }
            DownloadBus.State.FAILED -> {
                b.icon.setImageResource(R.drawable.ic_error)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.state_failed))
                b.tvPhase.text = if (item.detail.isNullOrBlank()) {
                    ctx.getString(R.string.dl_state_failed)
                } else {
                    ctx.getString(R.string.dl_state_failed_reason, item.detail)
                }
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
                b.btnShare.isVisible = false
            }
        }
    }
}
