package com.tunegrab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.tunegrab.app.FavoritesStore
import com.tunegrab.app.MainActivity
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentFavoritesBinding
import com.tunegrab.app.databinding.ItemFavoriteBinding

/**
 * Aba Favoritos (v0.22.11, pedido do autor): as músicas guardadas com o
 * coração no player, numa aba só delas na barra de baixo.
 *
 * v0.22.12: favorito tem duas ORIGENS agora, e o card respeita a de cada:
 *  - LINK do YouTube (coração no player de stream): toque = devolve pro
 *    fluxo do Início (link no campo + play — fetch com retry, rotação
 *    anti-bot, seletor de formato) e o botão BAIXA DE NOVO continua lá.
 *  - ARQUIVO LOCAL (coração na Biblioteca / player de arquivo): o uri
 *    guardado É o conteúdo — toque abre o player direto com o arquivo
 *    (mime pelo ContentResolver, vídeo quando o favorito diz que é vídeo).
 *    Não existe "baixar de novo" de um arquivo que JÁ está no aparelho —
 *    o botão nem nasce.
 * ZERO lógica de stream duplicada: remota segue pelo mesmo caminho de
 * sempre; local segue pelo mesmo Intent que a Biblioteca usa.
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
            val local = isLocal(fav)
            holder.b.tvTitle.text = fav.title.ifBlank { fav.url }
            holder.b.tvMeta.text = metaLine(fav)
            // arquivo local não volta pro YouTube — o card toca direto e o
            // "baixar de novo" não existe pra ele
            holder.b.btnDownload.isVisible = !local
            holder.b.root.setOnClickListener {
                val act = activity as? MainActivity ?: return@setOnClickListener
                if (local) playLocal(fav) else act.openPlayFor(fav.url)
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

        /** Favorito LOCAL (v0.22.12): o url guardado é um content:// ou
         *  file:// — não é link de site, é o próprio arquivo. */
        private fun isLocal(fav: FavoritesStore.Fav): Boolean {
            val s = fav.url
            return s.startsWith("content://") || s.startsWith("file://")
        }

        /** Toca o arquivo do favorito local: MESMO intent da Biblioteca
         *  (uri + mime + grant de leitura + modo áudio/vídeo do favorito). */
        private fun playLocal(fav: FavoritesStore.Fav) {
            val ctx = context ?: return
            val uri = Uri.parse(fav.url)
            try {
                val mime = ctx.contentResolver.getType(uri) ?: "*/*"
                ctx.startActivity(
                    Intent(ctx, PlayerActivity::class.java)
                        .setDataAndType(uri, mime)
                        .putExtra(PlayerActivity.EXTRA_TITLE, fav.title.ifBlank { fav.url })
                        .putExtra(PlayerActivity.EXTRA_IS_VIDEO, fav.isVideo)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (t: Throwable) {
                Toast.makeText(ctx, R.string.player_err, Toast.LENGTH_SHORT).show()
            }
        }

        private fun metaLine(fav: FavoritesStore.Fav): String {
            val parts = ArrayList<String>(2)
            // favorito local não tem canal nem duração anotados — a linha
            // diz DE ONDE ele vem (o arquivo tá no aparelho)
            if (isLocal(fav)) {
                parts.add(getString(R.string.fav_on_device))
            } else {
                fav.author?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                formatDuration(fav.durationSec)?.let { parts.add(it) }
            }
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
