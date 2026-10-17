package com.tunegrab.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.tunegrab.app.FavoritesStore
import com.tunegrab.app.MainActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentFavoritesBinding
import com.tunegrab.app.databinding.ItemFavoriteBinding

/**
 * Aba Favoritos (v0.22.11, pedido do autor): as músicas guardadas com o
 * coração no player, numa aba só delas na barra de baixo.
 *
 * O card NÃO baixa nem toca aqui dentro: ele devolve a música pro mesmo
 * fluxo do Início — toque no card = ouvir (link no campo + play), botão de
 * download = baixar de novo (seletor de formato de sempre). Assim os
 * favoritos aproveitam 100% do fetch com retry, da rotação anti-bot, do
 * seletor de formato e da Central — ZERO lógica duplicada pra manutenir.
 */
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val adapter = FavoritesAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        reload()
    }

    /** Recarrega SEMPRE que a aba volta a aparecer: quem apagou no player
     *  (ou salvou e voltou) encontra a lista do jeito que ela está agora. */
    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val ctx = context ?: return
        val items = FavoritesStore.all(ctx)
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.tvCount.text = when {
            items.isEmpty() -> getString(R.string.fav_empty_title)
            items.size == 1 -> getString(R.string.fav_count_one)
            else -> getString(R.string.fav_count_many, items.size)
        }
    }

    private inner class FavoritesAdapter : RecyclerView.Adapter<FavoritesAdapter.VH>() {

        private val items = ArrayList<FavoritesStore.Fav>()

        fun submit(list: List<FavoritesStore.Fav>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemFavoriteBinding.inflate(layoutInflater, parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val fav = items[position]
            holder.b.tvTitle.text = fav.title.ifBlank { fav.url }
            holder.b.tvMeta.text = metaLine(fav)
            holder.b.root.setOnClickListener {
                (activity as? MainActivity)?.openPlayFor(fav.url)
            }
            holder.b.btnDownload.setOnClickListener {
                (activity as? MainActivity)?.openDownloadFor(fav.url)
            }
            holder.b.btnRemove.setOnClickListener {
                val ctx = it.context
                FavoritesStore.remove(ctx, fav.url)
                Toast.makeText(ctx, R.string.fav_removed, Toast.LENGTH_SHORT).show()
                reload()
            }
        }

        private fun metaLine(fav: FavoritesStore.Fav): String {
            val parts = ArrayList<String>(2)
            fav.author?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            val dur = formatDuration(fav.durationSec)
            if (dur != null) parts.add(dur)
            return parts.joinToString("  ·  ")
        }

        /** 187s → "3:07"; 3905s → "1:05:05". Zero/inválido → nada na linha. */
        private fun formatDuration(sec: Long): String? {
            if (sec <= 0) return null
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }

        inner class VH(val b: ItemFavoriteBinding) : RecyclerView.ViewHolder(b.root)
    }
}
