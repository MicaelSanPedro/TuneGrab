package com.tunegrab.app.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.tunegrab.app.download.SaveLocation
import java.io.File

/**
 * PASTAS DE VERDADE na Biblioteca (v0.22.0) — micaelsan: "faz um sistema de
 * criação de pastas na aba de biblioteca, faz o app realmente criar elas
 * dentro da pasta TuneGrab. Faça a possibilidade de navegação entre pastas e
 * mover músicas pra dentro ou fora delas direto pelo app".
 *
 * MODELO: pasta = DIRETÓRIO DE VERDADE dentro da pasta de destino do app
 * (Downloads/TuneGrab padrão, ou a pasta escolhida via SAF). Nada de coleção
 * virtual: o gerenciador de arquivos do aparelho enxerga as mesmas pastas.
 * A chave de navegação é o caminho relativo à raiz ("" = raiz, "Forró",
 * "Forró/2026") — o MESMO formato que vive no LibraryEntry.folder.
 *
 * 3 BACKENDS, conforme a fonte de cada arquivo:
 *  · java.io.File — API 24–28 (legado) e API 30+ (FUSE: o app alcança os
 *    PRÓPRIOS arquivos por caminho, sem permissão extra). mkdir/rename +
 *    MediaScanner pra manter o índice do sistema sincronizado.
 *  · MediaStore (API 29+) — a rede de segurança do File backend: move por
 *    UPDATE do RELATIVE_PATH (funciona em arquivo que o próprio app baixou;
 *    a pasta nova nasce implícita junto).
 *  · SAF DocumentFile — destino = pasta escolhida: createDirectory e
 *    moveDocument do provider.
 *
 * NUNCA sobrescreve: se já existe arquivo com o mesmo nome no destino, o
 * move falha com aviso — o toque errado não pode custar uma música.
 *
 * ZERO toque no DownloadService: downloads continuam nascendo na raiz da
 * pasta de destino, exatamente como sempre.
 */
object LibraryFolders {

    private const val TAG = "TuneGrab"
    private const val ROOT_NAME = "TuneGrab"

    /** Pasta navegável: chave relativa, nome de exibição e nº de itens. */
    data class LibFolder(val key: String, val name: String, val items: Int)

    /** Resultado da criação — cada caso tem frase própria na UI. */
    sealed class CreateResult {
        data class Created(val key: String) : CreateResult()
        object Exists : CreateResult()
        object Invalid : CreateResult()
        object Failed : CreateResult()
    }

    /** Resultado do lote: `same` = já estavam na pasta de destino. */
    data class MoveResult(val moved: Int, val same: Int)

    // ---------- raiz e chaves ----------

    /** Raiz padrão: Downloads/TuneGrab (mesma conta do DownloadService). */
    fun defaultRoot(): File =
        @Suppress("DEPRECATION")
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT_NAME)

    /** O destino atual é a pasta escolhida (SAF) em vez da padrão? */
    fun isCustomTree(ctx: Context): Boolean = SaveLocation.customTree(ctx) != null

    /** Nome da raiz pra UI: "TuneGrab" ou o nome da pasta escolhida. */
    fun rootLabel(ctx: Context): String = SaveLocation.label(ctx) ?: ROOT_NAME

    /**
     * RELATIVE_PATH do MediaStore ("Download/TuneGrab/Foo/") → chave de
     * navegação ("Foo"). Na raiz → "". Fora da raiz padrão (ex.:
     * "Music/TuneGrab/") → null: a faixa aparece na listagem plana, mas não
     * é navegável (e o move cai no backend por caminho, não por chave).
     */
    fun relativePathToKey(rel: String?): String? {
        if (rel.isNullOrBlank()) return null
        val clean = rel.trim('/')
        val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_NAME"
        if (clean == prefix) return ""
        if (!clean.startsWith("$prefix/")) return null
        return clean.removePrefix("$prefix/")
    }

    /** Chave de navegação do PAI de um arquivo java.io.File sob a raiz
     *  padrão. Fora da raiz → null (ex.: Music/TuneGrab). */
    fun fileToKey(f: File): String? {
        val rootPath = defaultRoot().absolutePath.trimEnd('/')
        val parent = f.parentFile?.absolutePath?.trimEnd('/') ?: return null
        if (parent == rootPath) return ""
        if (!parent.startsWith("$rootPath/")) return null
        return parent.removePrefix("$rootPath/")
    }

    /** Chave SAF: segmentos entre a raiz da tree e o documento
     *  ("primary:Download/TuneGrab/Foo/x.mp3" sob a tree "…/TuneGrab" →
     *  "Foo"). Direto na raiz → "". Fora da tree → null. */
    fun treeKey(treeUri: Uri, docUri: Uri): String? = try {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val docId = DocumentsContract.getDocumentId(docUri)
        if (docId == rootId) ""
        else if (docId.startsWith("$rootId/"))
            docId.removePrefix("$rootId/").substringBeforeLast('/')
        else null
    } catch (t: Throwable) {
        null
    }

    private fun joinKey(parent: String, name: String): String =
        if (parent.isBlank()) name else "$parent/$name"

    /**
     * Sanitiza o nome digitado: espaços colapsados, sem "/" nem "\", sem
     * nome que comece com ponto (arquivo oculto), teto de 80 chars.
     * null = inválido (a UI tem frase própria).
     */
    fun sanitize(raw: String): String? {
        val n = raw.replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty() || n.length > 80) return null
        if (n.contains('/') || n.contains('\\')) return null
        if (n.startsWith(".")) return null
        return n
    }

    // ---------- listagem ----------

    /**
     * Subpastas DIRETAS de `key` ("" = raiz), com contagem de itens da lista
     * viva da Biblioteca (dedupe por nome+tamanho já aplicado). Fontes no
     * destino padrão: java.io.File (pastas de verdade, inclusive VAZIAS) +
     * MediaStore (API 29+: pastas que têm arquivo mesmo quando o File não
     * alcança). No destino SAF: só o DocumentFile (a pasta de verdade).
     */
    fun listChildren(ctx: Context, key: String, all: List<LibraryEntry>): List<LibFolder> {
        val names = LinkedHashSet<String>()
        try {
            if (isCustomTree(ctx)) {
                navigateDoc(ctx, key)?.listFiles()
                    ?.filter { it.isDirectory && !it.name.isNullOrBlank() && !it.name!!.startsWith(".") }
                    ?.forEach { names.add(it.name!!) }
            } else {
                navigateFile(defaultRoot(), key)
                    ?.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.forEach { names.add(it.name) }
                if (Build.VERSION.SDK_INT >= 29) {
                    mediaStoreSubkeys(ctx, key).forEach { names.add(it.substringAfterLast('/')) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listar pastas falhou (key=$key)", t)
        }
        return names.map { name ->
            val childKey = joinKey(key, name)
            LibFolder(childKey, name, all.count { it.fromTuneGrab && it.folder == childKey })
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * TODAS as pastas do destino, recursivo (a lista do diálogo de mover),
     * ordenada por profundidade e nome (indentação na UI é por profundidade).
     */
    fun listAllKeys(ctx: Context): List<String> {
        val keys = LinkedHashSet<String>()
        try {
            if (isCustomTree(ctx)) {
                val root = SaveLocation.customTree(ctx)
                    ?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return emptyList()
                walkDoc(root, "", keys)
            } else {
                walkFile(defaultRoot(), "", keys)
                if (Build.VERSION.SDK_INT >= 29) {
                    // pastas que têm arquivo mas o File backend não alcançou
                    mediaStoreAllKeys(ctx).forEach { keys.add(it) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "listar todas as pastas falhou", t)
        }
        return keys.sortedWith(
            compareBy({ it.count { c -> c == '/' } }, { it.lowercase() })
        )
    }

    private fun walkFile(dir: File, key: String, acc: MutableSet<String>) {
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { child ->
            val childKey = joinKey(key, child.name)
            acc.add(childKey)
            walkFile(child, childKey, acc)
        }
    }

    private fun walkDoc(dir: DocumentFile, key: String, acc: MutableSet<String>) {
        dir.listFiles().filter { it.isDirectory && !it.name.isNullOrBlank() }.forEach { child ->
            val childKey = joinKey(key, child.name!!)
            acc.add(childKey)
            walkDoc(child, childKey, acc)
        }
    }

    /**
     * Chaves de subpastas de `key` que TÊM arquivo indexado no MediaStore
     * (API 29+). O LIKE é escapado (pasta "Meu %20 Acústico" não vira
     * curinga) e a profundidade é respeitada: pediu "A", vem "A/B" — o filho
     * DIRETO em chave cheia, nunca neto órfão.
     */
    private fun mediaStoreSubkeys(ctx: Context, key: String): List<String> {
        val all = mediaStoreAllKeys(ctx)
        return all.filter { k -> childOf(key, k) != null }.map { k -> childOf(key, k)!! }
    }

    private fun mediaStoreAllKeys(ctx: Context): Set<String> = try {
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_NAME"
        val pattern = escapeLike("$base/") + "%"
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
        val out = LinkedHashSet<String>()
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            sel, arrayOf(pattern), null
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val k = relativePathToKey(c.getString(col)) ?: continue
                // "A/B" implica que "A" existe: a cadeia de pais entra junto
                val segs = k.split('/')
                var acc = ""
                for (s in segs) {
                    acc = joinKey(acc, s)
                    out.add(acc)
                }
            }
        }
        out
    } catch (t: Throwable) {
        Log.w(TAG, "MediaStore (chaves de pastas) falhou", t)
        emptySet()
    }

    /** O 1º resto de `key` DEPOIS de `parent` — descendente direto na chave
     *  cheia ("A/B" sob "A" → "A/B"). Não-descendente → null. */
    private fun childOf(parent: String, key: String): String? = when {
        key == parent -> null
        parent.isBlank() -> key
        key.startsWith("$parent/") -> key.removePrefix("$parent/")
        else -> null
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ---------- criação ----------

    /** Cria a pasta DE VERDADE dentro de `parentKey`. Nome repetido → Exists
     *  (nunca "pasta (1)" — a pasta é da pessoa, o nome é dela). */
    fun create(ctx: Context, parentKey: String, rawName: String): CreateResult {
        val name = sanitize(rawName) ?: return CreateResult.Invalid
        return try {
            if (isCustomTree(ctx)) {
                val parent = navigateDoc(ctx, parentKey) ?: return CreateResult.Failed
                if (parent.findFile(name)?.isDirectory == true) return CreateResult.Exists
                parent.createDirectory(name) ?: return CreateResult.Failed
            } else {
                val parent = navigateFile(defaultRoot(), parentKey) ?: return CreateResult.Failed
                val target = File(parent, name)
                if (target.exists()) return CreateResult.Exists
                if (!target.mkdirs()) return CreateResult.Failed
            }
            CreateResult.Created(joinKey(parentKey, name))
        } catch (t: Throwable) {
            Log.w(TAG, "criar pasta falhou (pai=$parentKey)", t)
            CreateResult.Failed
        }
    }

    // ---------- navegação ----------

    private fun navigateFile(root: File, key: String): File? {
        var dir: File = root
        if (key.isNotBlank()) {
            for (seg in key.split('/')) {
                dir = File(dir, seg)
                if (!dir.isDirectory) return null
            }
        }
        return if (dir.isDirectory) dir else null
    }

    private fun navigateDoc(ctx: Context, key: String): DocumentFile? {
        var dir = SaveLocation.customTree(ctx)
            ?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return null
        if (key.isNotBlank()) {
            for (seg in key.split('/')) {
                dir = dir.findFile(seg)?.takeIf { it.isDirectory } ?: return null
            }
        }
        return dir
    }

    // ---------- mover ----------

    /**
     * Move UM arquivo pra `targetKey` ("" = raiz, "fora da pasta"). Só faixa
     * do próprio TuneGrab (`fromTuneGrab`) — mídia de outro app não move
     * (não é dona). Já na pasta certa = true sem tocar (o lote conta como
     * "same" antes de chamar). Falha honesta = false (a UI avisa).
     */
    fun move(ctx: Context, e: LibraryEntry, targetKey: String): Boolean {
        if (!e.fromTuneGrab) return false
        if (e.folder == targetKey) return true
        return try {
            if (e.docUri != null && isCustomTree(ctx)) moveViaSaf(ctx, e, targetKey)
            else moveViaFile(ctx, e, targetKey)
        } catch (t: Throwable) {
            Log.w(TAG, "mover falhou: ${e.name}", t)
            false
        }
    }

    fun moveMany(ctx: Context, entries: List<LibraryEntry>, targetKey: String): MoveResult {
        var moved = 0
        var same = 0
        for (e in entries) {
            if (e.folder == targetKey) {
                same++
                continue
            }
            if (move(ctx, e, targetKey)) moved++
        }
        return MoveResult(moved, same)
    }

    /**
     * Move pelo caminho de disco (API 24–28 legado e API 30+ FUSE nos
     * próprios arquivos). Fonte: o File da entrada, o caminho derivado da
     * (pasta + nome) ou o nome na raiz (arquivos anteriores à v0.22.0).
     * Rename falhou ou fonte não achada → rede de segurança MediaStore.
     * Depois do move: MediaScanner nos 2 caminhos (índice do sistema honesto).
     */
    private fun moveViaFile(ctx: Context, e: LibraryEntry, targetKey: String): Boolean {
        val root = defaultRoot()
        if (!root.isDirectory && !root.mkdirs()) return false

        // pasta de destino: pode ter sido apagada por outro app — recriar é
        // honesto (a pessoa pediu pra mover PRA DENTRO dela)
        val targetDir = navigateFile(root, targetKey)
            ?: File(root, targetKey).takeIf { it.isDirectory || it.mkdirs() }
            ?: return false

        val source: File? = when {
            e.file != null -> e.file
            e.folder != null -> navigateFile(root, e.folder)?.let { File(it, e.name) }
            else -> File(root, e.name).takeIf { it.isFile }
        }

        if (source == null || !source.isFile) return moveViaMediaStore(ctx, e, targetKey)

        val target = File(targetDir, e.name)
        if (target.exists()) return false // NUNCA sobrescrever
        val ok = source.renameTo(target)
        if (ok) {
            MediaScannerConnection.scanFile(
                ctx.applicationContext,
                arrayOf(source.absolutePath, target.absolutePath), null, null
            )
            return true
        }
        // rename negado (OEM teimoso, arquivo que não é do app) → MediaStore
        return moveViaMediaStore(ctx, e, targetKey)
    }

    /**
     * Rede de segurança (API 30+): UPDATE do RELATIVE_PATH em arquivo que o
     * PRÓPRIO app contribuiu — é a forma documentada de mover mídia própria
     * sob o scoped storage. A pasta nova nasce implícita com o arquivo.
     */
    private fun moveViaMediaStore(ctx: Context, e: LibraryEntry, targetKey: String): Boolean {
        val uri = e.mediaUri ?: return false
        if (Build.VERSION.SDK_INT < 30) return false
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_NAME"
        val newRel = if (targetKey.isBlank()) "$base/" else "$base/$targetKey/"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, newRel)
            }
            ctx.contentResolver.update(uri, values, null, null) > 0
        } catch (t: Throwable) {
            Log.w(TAG, "move via MediaStore falhou: ${e.name}", t)
            false
        }
    }

    /**
     * Destino = pasta escolhida (SAF): moveDocument do provider, que muda o
     * arquivo de diretório SEM copiar bytes. Falha do provider = false
     * (aviso na UI) — sem cópia às cegas seguida de delete: o toque errado
     * não pode custar uma música.
     */
    private fun moveViaSaf(ctx: Context, e: LibraryEntry, targetKey: String): Boolean {
        val docUri = e.docUri ?: return false
        val targetDir = navigateDoc(ctx, targetKey) ?: return false
        return try {
            val docId = DocumentsContract.getDocumentId(docUri)
            val parentId = docId.substringBeforeLast('/')
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(docUri, parentId)
            DocumentsContract.moveDocument(
                ctx.contentResolver, docUri, parentUri, targetDir.uri
            ) != null
        } catch (t: Throwable) {
            Log.w(TAG, "move SAF falhou: ${e.name}", t)
            false
        }
    }
}
