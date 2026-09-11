package com.tunegrab.app.download

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Pasta de download escolhida pelo usuário (Configurações → Pasta de download).
 *
 * null = padrão (Downloads/TuneGrab, comportamento de sempre).
 * Se o usuário escolher uma pasta via SAF, guardamos a tree URI com permissão
 * persistente e o DownloadService publica os arquivos lá. Se a pasta sumir ou
 * a permissão se perder, o app cai de volta no padrão automaticamente.
 *
 * HISTÓRICO (v0.19.13): TROCOU a pasta? A antiga vai pro histórico — e a
 * Biblioteca lista as músicas dela PARA SEMPRE, como "do TuneGrab" (a
 * permissão persistente da antiga nunca é solta). Assim "as músicas que
 * eram dele" são sempre descobertas, mesmo depois de trocar o destino
 * quantas vezes quiser.
 */
object SaveLocation {

    private const val NAME = "tunegrab_settings"
    private const val KEY_TREE = "save_tree_uri"
    private const val KEY_HISTORY = "past_tree_uris"

    fun customTree(context: Context): Uri? {
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null) ?: return null
        return try {
            val uri = Uri.parse(raw)
            // pasta apagada/renomeada → volta ao padrão
            if (DocumentFile.fromTreeUri(context, uri)?.canWrite() == true) uri else null
        } catch (t: Throwable) {
            null
        }
    }

    fun setCustomTree(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_TREE, null)
        if (current != null && current != uri?.toString()) {
            // a pasta que estava em uso vira "que já foi do app"
            withHistory(prefs) { it.add(current) }.apply()
        }
        val edit = if (uri != null) {
            // voltou pra uma pasta do histórico? ela é a corrente de novo
            withHistory(prefs) { it.remove(uri.toString()) }
        } else {
            prefs.edit()
        }
        edit.putString(KEY_TREE, uri?.toString()).apply()
    }

    /** Pastas que JÁ FORAM destino de download (não inclui a atual). */
    fun pastTrees(context: Context): List<Uri> {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_TREE, null)
        return (prefs.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet())
            .filter { it != current }
            .mapNotNull { raw ->
                try {
                    val u = Uri.parse(raw)
                    if (u.toString().isBlank()) null else u
                } catch (t: Throwable) {
                    null
                }
            }
    }

    /** Nome da pasta escolhida (para exibir nas configurações/biblioteca). */
    fun label(context: Context): String? {
        val uri = customTree(context) ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)?.name ?: "pasta escolhida"
        } catch (t: Throwable) {
            "pasta escolhida"
        }
    }

    /** getStringSet devolve o set INTERNAL — copia antes de mexer. */
    private inline fun withHistory(
        prefs: SharedPreferences,
        transform: (MutableSet<String>) -> Unit
    ): SharedPreferences.Editor {
        val set = LinkedHashSet(prefs.getStringSet(KEY_HISTORY, emptySet()) ?: emptySet())
        transform(set)
        return prefs.edit().putStringSet(KEY_HISTORY, set)
    }
}
