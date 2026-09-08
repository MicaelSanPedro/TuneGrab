package com.tunegrab.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tunegrab.app.databinding.ActivityDownloadsBinding
import com.tunegrab.app.databinding.ItemDownloadBinding
import com.tunegrab.app.download.DownloadBus
import com.tunegrab.app.ui.BottomNav
import kotlinx.coroutines.launch

/**
 * Central de Downloads: o que está baixando agora + o que terminou ou falhou.
 *
 * É só um ESPELHO do estado que o DownloadService já publica nas notificações
 * (via DownloadBus) — nada aqui interfere na mecânica de download.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = DownloadsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnClear.setOnClickListener { DownloadBus.clearFinished() }

        BottomNav.setup(binding.navBar.bottomNav, this, R.id.navDownloads)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadBus.items.collect { render(it) }
            }
        }
    }

    private fun render(items: List<DownloadBus.Item>) {
        adapter.submit(items)
        binding.tvEmpty.isVisible = items.isEmpty()
        binding.btnClear.isVisible = items.any { it.state != DownloadBus.State.RUNNING }
    }
}

class DownloadsAdapter : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<DownloadBus.Item> = emptyList()

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
            }
            DownloadBus.State.DONE -> {
                b.icon.setImageResource(R.drawable.ic_check)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.state_done))
                b.tvPhase.text = ctx.getString(R.string.dl_state_done)
                b.tvPercent.isVisible = false
                b.progress.isVisible = false
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
            }
        }
    }
}
