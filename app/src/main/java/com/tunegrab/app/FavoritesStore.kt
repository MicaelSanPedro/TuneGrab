package com.tunegrab.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favoritos (v0.22.11, pedido do autor): lista de músicas que o usuário
 * guardou com o coração no player. Cada favorito guarda o LINK do YouTube
 * (a página watch), título, canal e duração — o suficiente pra reabrir a
 * música na aba Favoritos pelo mesmo fluxo do Início (colou → toca/baixa),
 * sem duplicar NENHUMA linha do motor de download.
 *
 * Persistência no mesmo espírito do FormatPrefs (que já vive no app):
 * SharedPreferences + JSON, zero banco, zero schema migration — favorito
 * é uma lista pequena e viva. Teto de 200 itens: lista de favorito não é
 * acervo; chegou lá, o mais antigo sai (e o app nunca incha à toa).
 */
object FavoritesStore {

    /** Um favorito. [url] é a identidade: link da página watch do YouTube
     *  (faixa remota) OU o uri do arquivo local (v0.22.12 — coração na
     *  Biblioteca e no player de arquivo). [isVideo] só manda no replay
     *  do favorito local (áudio x tela de vídeo); remota não usa. */
    data class Fav(
        val url: String,
        val title: String,
        val author: String?,
        val durationSec: Long,
        val isVideo: Boolean = false,
        val addedAt: Long
    )

    private const val PREFS = "favorites"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lista atual, mais recente primeiro (a última música amada aparece em cima). */
    fun all(ctx: Context): List<Fav> {
        val raw = prefs(ctx).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                Fav(
                    url = url,
                    title = o.optString("title"),
                    author = o.optString("author").takeIf { it.isNotBlank() },
                    durationSec = o.optLong("durationSec"),
                    isVideo = o.optBoolean("isVideo", false),
                    addedAt = o.optLong("addedAt")
                )
            }.sortedByDescending { it.addedAt }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun contains(ctx: Context, url: String): Boolean =
        all(ctx).any { it.url == url }

    /** Guarda (ou re-guarda jogando pra cima) e devolve a lista nova. */
    fun add(ctx: Context, fav: Fav): List<Fav> {
        val rest = all(ctx).filterNot { it.url == fav.url }
        val list = (listOf(fav) + rest).take(MAX_ITEMS)
        save(ctx, list)
        return list
    }

    /** Tira pelo link e devolve a lista nova. */
    fun remove(ctx: Context, url: String): List<Fav> {
        val list = all(ctx).filterNot { it.url == url }
        save(ctx, list)
        return list
    }

    private fun save(ctx: Context, list: List<Fav>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("url", f.url)
                    .put("title", f.title)
                    .put("author", f.author ?: "")
                    .put("durationSec", f.durationSec)
                    .put("isVideo", f.isVideo)
                    .put("addedAt", f.addedAt)
            )
        }
        prefs(ctx).edit().putString(KEY_ITEMS, arr.toString()).apply()
    }
}
