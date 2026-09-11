package com.tunegrab.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.ContextCompat
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
    val file: File? = null,      // pasta padrão (API 24–28)
    /** true = veio de fonte DO PRÓPRIO TuneGrab (pastas TuneGrab / pasta
     *  escolhida — atual ou do histórico de pastas — ou caminho com
     *  "TuneGrab" achado pela permissão de áudio) — ganha a seção de
     *  destaque no topo da Biblioteca. false = achado no aparelho pela
     *  permissão de áudio (música de outro app). */
    val fromTuneGrab: Boolean = false
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

    // ---------- permissão de leitura de mídia ----------
    // Sem ela, o MediaStore só devolve arquivos do PRÓPRIO app. Com ela, a
    // aba Músicas enxerga as músicas antigas em QUALQUER pasta — inclusive
    // depois de atualizar ou desinstalar e instalar de novo (o app novo
    // perde as preferências, mas as músicas continuam no aparelho).

    fun mediaReadPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasMediaReadPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, mediaReadPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * Lista TUDO que o app reconhece como biblioteca, UNINDO as fontes:
     *  1. a pasta escolhida (SAF), se houver;
     *  2. qualquer pasta ".../TuneGrab/..." indexada no MediaStore — a busca é
     *     por CAMINHO (não por preferências), então sobrevive a atualização e
     *     a desinstalar/reinstalar;
     *  3. com a permissão de áudio concedida: TODAS as músicas do aparelho —
     *     cobre pasta personalizada sem "TuneGrab" no nome após reinstalar.
     * A mesma faixa pode surgir em 2 fontes: dedupe por (nome, tamanho).
     */
    fun listAll(ctx: Context): Pair<List<LibraryEntry>, String> {
        val merged = LinkedHashMap<String, LibraryEntry>()
        fun put(e: LibraryEntry) {
            merged.putIfAbsent("${e.name.lowercase()}|${e.size}", e)
        }

        // fontes DO PRÓPRIO app: tudo que entra aqui ganha fromTuneGrab=true
        // (a pasta escolhida, o MediaStore por caminho ".../TuneGrab/..." e a
        // listagem direta das pastas padrão). A ÚLTIMA fonte — todas as
        // músicas do aparelho, via permissão — é de OUTROS apps e entra com
        // flag false. Como ela roda POR ÚLTIMO, o dedupe garante: um arquivo
        // do TuneGrab nunca vira "outro".
        fun putOwn(e: LibraryEntry) {
            merged.putIfAbsent("${e.name.lowercase()}|${e.size}", e.copy(fromTuneGrab = true))
        }

        val tree = SaveLocation.customTree(ctx)
        if (tree != null) listTree(ctx, tree).forEach { putOwn(it) }

        // PASTAS QUE JÁ FORAM DESTINO (v0.19.13): trocou o destino do
        // download? As músicas das pastas antigas continuam sendo listadas
        // como "do TuneGrab" — o histórico guarda as tree URIs e a permissão
        // persistente de cada uma nunca é solta.
        SaveLocation.pastTrees(ctx).forEach { past ->
            try {
                listTree(ctx, past).forEach { putOwn(it) }
            } catch (t: Throwable) {
                Log.w(TAG, "pasta antiga indisponível na Biblioteca", t)
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            // vídeos + áudios do TuneGrab em Download/TuneGrab (e derivadas)
            queryMediaStore(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "%TuneGrab%")
                ?.forEach { putOwn(it) }
            // áudios do TuneGrab fora do Download (ex.: Music/TuneGrab)
            queryMediaStore(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "%TuneGrab%")
                ?.forEach { putOwn(it) }
            // PLANO B — listagem DIRETA das pastas padrão: cobre o caso de o
            // MediaStore NÃO devolver os arquivos que o próprio app criou
            // (OEMs que perdem o índice, arquivos presos em IS_PENDING,
            // mime dessincronizado). Via FUSE o app enxerga os próprios
            // arquivos por caminho SEM depender de permissão nem do MediaStore.
            listDefaultDirs().forEach { putOwn(it) }
            // com permissão: todas as músicas do aparelho (OUTROS apps também)
            // — mas as que moram em caminho com "TuneGrab" ganham o selo de
            // origem (a própria pasta do app, mesmo que nenhuma das fontes
            // acima as tenha achado)
            if (hasMediaReadPermission(ctx)) {
                queryAllAudio(ctx)?.forEach { if (it.fromTuneGrab) putOwn(it) else put(it) }
            }
        } else {
            listLegacy().forEach { putOwn(it) }
            if (hasMediaReadPermission(ctx)) {
                @Suppress("DEPRECATION")
                val music = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC
                    ),
                    "TuneGrab"
                )
                legacyDir(music).forEach { putOwn(it) }
            }
        }

        val label = when {
            tree != null -> ctx.getString(
                R.string.lib_folder_custom,
                SaveLocation.label(ctx) ?: ctx.getString(R.string.lib_folder_unknown)
            )
            else -> ctx.getString(R.string.lib_folder_default)
        }
        return merged.values.sortedByDescending { it.modifiedMs } to label
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

    /**
     * Busca no MediaStore por caminho (LIKE): acha os arquivos do TuneGrab
     * MESMO depois de reinstalar o app — o caminho é coluna do MediaProvider,
     * não preferência do app. Sem permissão, devolve só o que é do app; com
     * ela, devolve tudo que casa com o padrão.
     */
    private fun queryMediaStore(ctx: Context, collection: Uri, pathLike: String): List<LibraryEntry>? {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val out = mutableListOf<LibraryEntry>()
        try {
            // LIKE (não =): o MediaProvider armazena RELATIVE_PATH com barra
            // final ("Download/TuneGrab/"), então a igualdade exata sem barra
            // devolvia 0 linhas — e a aba Músicas ficava vazia.
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "(${MediaStore.MediaColumns.MIME_TYPE} LIKE 'audio/%' OR " +
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%')"
            ctx.contentResolver.query(
                collection,
                proj,
                sel,
                arrayOf(pathLike),
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
                        mediaUri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore falhou", t)
            return null
        }
        return out
    }

    /**
     * Todas as músicas do aparelho (precisa da permissão de áudio).
     * Marca fromTuneGrab=true nas que moram em caminho com "TuneGrab"
     * (RELATIVE_PATH): “as músicas que eram dele” recebem o selo de origem
     * MESMO que só a permissão de áudio as enxergue (ex.: pasta padrão
     * renomeada, índice dessincronizado, depois de reinstalar).
     */
    private fun queryAllAudio(ctx: Context): List<LibraryEntry>? {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        val out = mutableListOf<LibraryEntry>()
        try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val mime = c.getString(mimeCol) ?: mimeOf(name)
                    val path = try {
                        c.getString(pathCol) ?: ""
                    } catch (t: Throwable) {
                        ""
                    }
                    out += LibraryEntry(
                        name = name,
                        size = c.getLong(sizeCol),
                        modifiedMs = c.getLong(dateCol) * 1000L,
                        mime = mime,
                        isVideo = mime.startsWith("video"),
                        mediaUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        ),
                        fromTuneGrab = path.contains("tunegrab", ignoreCase = true)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore (todas as músicas) falhou", t)
            return null
        }
        return out
    }

    /**
     * Listagem DIRETA (java.io.File) das pastas padrão do TuneGrab —
     * Download/TuneGrab e Music/TuneGrab. Plano B da aba Músicas: o app
     * enxerga os PRÓPRIOS arquivos por caminho mesmo quando o MediaStore
     * não os devolve (sem permissão de áudio, índice dessincronizado no
     * OEM, arquivo presos em IS_PENDING). Só entra na lista o que tem
     * extensão de áudio/vídeo conhecida — lixo/temporário fica de fora.
     * O dedupe por (nome, tamanho) da listAll cuida dos duplicados.
     */
    private fun listDefaultDirs(): List<LibraryEntry> {
        @Suppress("DEPRECATION")
        val dirs = listOf(
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "TuneGrab"
            ),
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC
                ),
                "TuneGrab"
            )
        )
        return dirs.flatMap { legacyDir(it) }
            .filter { it.mime != "application/octet-stream" }
            .sortedByDescending { it.modifiedMs }
    }

    private fun listLegacy(): List<LibraryEntry> {
        @Suppress("DEPRECATION")
        val downloads = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "TuneGrab"
        )
        return legacyDir(downloads)
    }

    /** Lista um diretório via java.io.File (Android 7–9). null/sem permissão → vazio. */
    private fun legacyDir(dir: File): List<LibraryEntry> {
        if (!dir.isDirectory) return emptyList()
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

    /**
     * APAGAR DE VERDADE (v0.19.5): remove a mídia do aparelho em qualquer
     * uma das fontes (pasta escolhida via SAF, MediaStore API 29+ ou
     * java.io.File nas pastas legadas). Os downloads do TuneGrab são donos
     * dos próprios arquivos, então a exclusão não pede permissão extra;
     * falha (arquivo de outro app, storage travado) devolve false — quem
     * chama decide o aviso. Nada aqui mexe na mecânica de download.
     */
    fun delete(ctx: Context, e: LibraryEntry): Boolean = try {
        when {
            e.docUri != null -> DocumentFile.fromSingleUri(ctx, e.docUri)?.delete() == true
            e.mediaUri != null -> ctx.contentResolver.delete(e.mediaUri, null, null) > 0
            e.file != null -> e.file.delete()
            else -> false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "apagar falhou: ${e.name}", t)
        false
    }

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
            val send = Intent(Intent.ACTION_SEND)
                .setType(e.mime.ifBlank { "*/*" })
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // ClipData garante a concessão de leitura ao app destino em todos
            // os fabricantes (alguns ignoram a flag do intent interno)
            send.clipData = android.content.ClipData.newRawUri(e.name, uri)
            ctx.startActivity(
                Intent.createChooser(send, ctx.getString(R.string.cd_share))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
