package com.tunegrab.app.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.tunegrab.app.R
import com.tunegrab.app.download.SaveLocation
import java.io.File

/** Um arquivo salvo pelo TuneGrab, de qualquer uma das 3 fontes de listagem. */
data class LibraryEntry(
    val name: String,
    val size: Long,
    val modifiedMs: Long,
    val mime: String,
    val isVideo: Boolean,
    val mediaUri: Uri? = null,   // MediaStore (API 29+)
    val docUri: Uri? = null,     // pasta escolhida (SAF)
    val file: File? = null       // pasta padrão (API 24–28)
) {
    val isVideoKind: Boolean get() = isVideo || mime.startsWith("video")
}

/**
 * Listagem e ações sobre os arquivos baixados — compartilhada pela aba
 * Músicas (lista tudo) e pela Central de Downloads (acha o arquivo recém
 * concluído para o botão compartilhar). Somente leitura do ponto de vista
 * do download: nada aqui mexe na mecânica.
 */
object LibraryFiles {

    private const val TAG = "TuneGrab"

    /** Lista tudo e devolve também o rótulo da pasta atual. */
    fun listAll(ctx: Context): Pair<List<LibraryEntry>, String> {
        val tree = SaveLocation.customTree(ctx)
        return when {
            tree != null -> {
                val entries = listTree(ctx, tree)
                val label = ctx.getString(
                    R.string.lib_folder_custom,
                    SaveLocation.label(ctx) ?: ctx.getString(R.string.lib_folder_unknown)
                )
                entries to label
            }
            Build.VERSION.SDK_INT >= 29 -> listMediaStore(ctx) to ctx.getString(R.string.lib_folder_default)
            else -> listLegacy() to ctx.getString(R.string.lib_folder_default)
        }
    }

    /** Acha um arquivo pelo nome exato (o mesmo nome que o DownloadService publicou). */
    fun findByFileName(ctx: Context, fileName: String): LibraryEntry? {
        val wanted = fileName.trim().lowercase()
        val (entries, _) = listAll(ctx)
        return entries.firstOrNull { it.name.lowercase() == wanted }
    }

    private fun listTree(ctx: Context, tree: Uri): List<LibraryEntry> {
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        return dir.listFiles()
            .filter { !it.name.isNullOrBlank() && !it.name!!.startsWith(".") }
            .map {
                val name = it.name!!
                val mime = it.type ?: mimeOf(name)
                LibraryEntry(
                    name = name,
                    size = it.length(),
                    modifiedMs = it.lastModified(),
                    mime = mime,
                    isVideo = mime.startsWith("video"),
                    docUri = it.uri
                )
            }
            .sortedByDescending { it.modifiedMs }
    }

    private fun listMediaStore(ctx: Context): List<LibraryEntry> {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val out = mutableListOf<LibraryEntry>()
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("Download/TuneGrab"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val mime = c.getString(mimeCol) ?: mimeOf(name)
                    out += LibraryEntry(
                        name = name,
                        size = c.getLong(sizeCol),
                        modifiedMs = c.getLong(dateCol) * 1000L,
                        mime = mime,
                        isVideo = mime.startsWith("video"),
                        mediaUri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore falhou", t)
        }
        return out
    }

    private fun listLegacy(): List<LibraryEntry> {
        @Suppress("DEPRECATION")
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "TuneGrab"
        )
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.map {
                val mime = mimeOf(it.name)
                LibraryEntry(
                    name = it.name,
                    size = it.length(),
                    modifiedMs = it.lastModified(),
                    mime = mime,
                    isVideo = mime.startsWith("video"),
                    file = it
                )
            }
            ?.sortedByDescending { it.modifiedMs }
            ?: emptyList()
    }

    // ---------- ações ----------

    /** URI segura para abrir/compartilhar (content://, nunca file:// fora do app). */
    fun shareableUri(ctx: Context, e: LibraryEntry): Uri = when {
        e.mediaUri != null -> e.mediaUri
        e.docUri != null -> e.docUri
        e.file != null -> FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", e.file)
        else -> Uri.EMPTY
    }

    /** Compartilha o arquivo via chooser do sistema. */
    fun share(ctx: Context, e: LibraryEntry) {
        val uri = shareableUri(ctx, e)
        if (uri == Uri.EMPTY) return
        try {
            ctx.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(e.mime.ifBlank { "*/*" })
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    ctx.getString(R.string.cd_share)
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "compartilhar falhou: ${e.name}", t)
        }
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    fun formatSize(ctx: Context, bytes: Long): String =
        Formatter.formatShortFileSize(ctx, bytes)
}
