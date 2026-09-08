package com.tunegrab.app.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Pasta de download escolhida pelo usuário (Configurações → Pasta de download).
 *
 * null = padrão (Downloads/TuneGrab, comportamento de sempre).
 * Se o usuário escolher uma pasta via SAF, guardamos a tree URI com permissão
 * persistente e o DownloadService publica os arquivos lá. Se a pasta sumir ou
 * a permissão se perder, o app cai de volta no padrão automaticamente.
 */
object SaveLocation {

    private const val NAME = "tunegrab_settings"
    private const val KEY_TREE = "save_tree_uri"

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
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, uri?.toString())
            .apply()
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
}
