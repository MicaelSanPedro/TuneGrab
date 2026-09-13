package com.tunegrab.app.ui

import android.content.ContentUris
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
 * v0.22.3 — micaelsan: "eu não consigo deletar pastas ou renomear elas".
 * RENOMEAR e APAGAR de pasta, nos mesmos 3 backends do mover:
 *  · rename — File renameTo do diretório (24–28 e 30+ FUSE) + UPDATE do
 *    RELATIVE_PATH em lote no MediaStore (corrige o índice quando o rename
 *    de disco funciona e vira o PLANO B quando não vira); SAF renameTo.
 *  · delete — varredura MediaStore (arquivos indexados da pasta, API 29+,
 *    só o que o PRÓPRIO app contribuiu) + limpeza física java.io.File do
 *    que sobrou de fora do índice + MediaScanner; SAF: varridura recursiva
 *    do DocumentFile (arquivos primeiro, pastas de baixo pra cima).
 * Sempre com contagem honesta (apagados x que ficaram) — a UI avisa.
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

    /** v0.22.3: resultado da exclusão de pasta — `deleted` conta arquivos
     *  removidos (+1 se a própria pasta virou nada no fim); `failed` conta
     *  os que NEGARAM a remoção (faixa de outro app, storage teimoso). */
    data class DeleteResult(val deleted: Int, val failed: Int)

    /** v0.22.3: resultado do rename — cada caso tem frase própria na UI. */
    sealed class RenameResult {
        object Renamed : RenameResult()
        object Exists : RenameResult()
        object Invalid : RenameResult()
        object Failed : RenameResult()
    }

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

    // ---------- v0.22.3: RENOMEAR E APAGAR PASTA ----------

    /** RELATIVE_PATH do MediaStore que corresponde à chave: "Download/
     *  TuneGrab/Foo/" — SEMPRE com barra no fim (é o formato do índice). */
    private fun relOf(key: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_NAME" +
            if (key.isBlank()) "/" else "/$key/"

    /**
     * RENOMEAR PASTA (v0.22.3) — micaelsan: "eu não consigo deletar pastas
     * ou renomear elas". Ordem do rename no destino padrão:
     *  1. colisão primeiro (File E MediaStore) — nunca em cima do vizinho;
     *  2. File renameTo do DIRETÓRIO (24–28 legado; 30+ FUSE nos próprios
     *     arquivos; 29 não alcança caminho — cai pro passo 3);
     *  3. UPDATE do RELATIVE_PATH em LOTE no MediaStore (API 29+): corrige
     *     o índice quando o rename de disco funcionou e move por conta
     *     própria quando não funcionou (a pasta nova nasce implícita, a
     *     velha fica vazia e é removida no fim).
     * SAF: renameTo do DocumentFile (renameDocument do provider).
     */
    fun renameFolder(ctx: Context, key: String, rawNewName: String): RenameResult {
        if (key.isBlank()) return RenameResult.Failed // a raiz não tem nome
        val newName = sanitize(rawNewName) ?: return RenameResult.Invalid
        val currentName = key.substringAfterLast('/')
        if (newName == currentName) return RenameResult.Renamed
        return try {
            if (isCustomTree(ctx)) {
                val parentKey = key.substringBeforeLast('/', "")
                val parentDoc = navigateDoc(ctx, parentKey) ?: return RenameResult.Failed
                if (parentDoc.findFile(newName)?.exists() == true) return RenameResult.Exists
                val dir = navigateDoc(ctx, key) ?: return RenameResult.Failed
                if (dir.renameTo(newName)) RenameResult.Renamed else RenameResult.Failed
            } else {
                val root = defaultRoot()
                val parentKey = key.substringBeforeLast('/', "")
                val newKey = joinKey(parentKey, newName)
                // 1) colisão: o MediaStore indexa (29+) e o disco confirma
                if (Build.VERSION.SDK_INT >= 29 &&
                    mediaStoreCountUnder(ctx, relOf(newKey)) > 0
                ) return RenameResult.Exists
                val parentDir = navigateFile(root, parentKey)
                if (parentDir != null && File(parentDir, newName).exists()) {
                    return RenameResult.Exists
                }
                // 2) rename do diretório por caminho
                var ok = false
                val dir = navigateFile(root, key)
                if (dir?.isDirectory == true) {
                    val target = File(dir.parentFile, newName)
                    if (dir.renameTo(target)) {
                        ok = true
                        MediaScannerConnection.scanFile(
                            ctx.applicationContext,
                            arrayOf(dir.absolutePath, target.absolutePath), null, null
                        )
                    }
                }
                // 3) índice: quando o disco moveu, CORRIGE; quando não moveu,
                // MOVE (e a velha vazia some no fim)
                if (Build.VERSION.SDK_INT >= 29) {
                    if (mediaStoreRenamePath(ctx, relOf(key), relOf(newKey)) > 0) ok = true
                    if (ok) try { dir?.delete() } catch (_: Throwable) {}
                }
                if (ok) RenameResult.Renamed else RenameResult.Failed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "renomear pasta falhou (key=$key)", t)
            RenameResult.Failed
        }
    }

    /**
     * APAGAR PASTA (v0.22.3) com TUDO que mora dentro (subpastas vão junto
     * — a confirmação da UI diz isso). Contagem honesta: `deleted` =
     * arquivos removidos (+1 se a própria pasta sumiu no fim); `failed` =
     * os que negaram (faixa de outro app fica, o resto da pasta também).
     * Destino padrão: MediaStore varre os indexados (29+, só o próprio app
     * consegue apagar sem confirmação por arquivo) e o File limpa o que
     * ficou de fora do índice + a pasta física. SAF: varridura recursiva.
     */
    fun deleteFolder(ctx: Context, key: String): DeleteResult {
        if (key.isBlank()) return DeleteResult(0, 1) // a raiz nunca
        val acc = IntArray(2) // [apagados, falhados]
        try {
            if (isCustomTree(ctx)) {
                val dir = navigateDoc(ctx, key) ?: return DeleteResult(0, 0)
                wipeDoc(dir, acc)
                if (dir.delete()) acc[0]++ // a pasta em si
            } else {
                if (Build.VERSION.SDK_INT >= 29) mediaStoreSweepDelete(ctx, key, acc)
                val dir = navigateFile(defaultRoot(), key)
                if (dir?.isDirectory == true) {
                    wipeFile(dir, acc)
                    if (dir.delete()) acc[0]++ // a pasta em si
                    MediaScannerConnection.scanFile(
                        ctx.applicationContext, arrayOf(dir.absolutePath), null, null
                    )
                }
                // API 29 sem pasta alcançável: o MediaProvider poda o
                // diretório sozinho quando o último arquivo vai embora
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apagar pasta falhou (key=$key)", t)
        }
        return DeleteResult(acc[0], acc[1])
    }

    /** Varredura SAF: arquivos primeiro, pastas de baixo pra cima. */
    private fun wipeDoc(dir: DocumentFile, acc: IntArray) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                wipeDoc(child, acc)
                child.delete() // some se os filhos saíram todos; senão fica
            } else {
                if (child.delete()) acc[0]++ else acc[1]++
            }
        }
    }

    /** Varredura java.io.File: idem (o que o índice não tinha, o disco paga). */
    private fun wipeFile(dir: File, acc: IntArray) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                wipeFile(child, acc)
                child.delete()
            } else {
                if (child.delete()) acc[0]++ else acc[1]++
            }
        }
    }

    /**
     * APAGA no MediaStore (API 29+) cada arquivo indexado sob a pasta — o
     * app é dono dos que ele mesmo baixou (delete direto, sem confirmação
     * por arquivo); faixa de OUTRO app lança RecoverableSecurityException e
     * entra na contagem de `failed` (o aviso da UI explica o que ficou).
     */
    private fun mediaStoreSweepDelete(ctx: Context, key: String, acc: IntArray) {
        try {
            val uris = ArrayList<Uri>()
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                arrayOf(escapeLike(relOf(key)) + "%"), null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) {
                    uris.add(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        )
                    )
                }
            }
            for (u in uris) {
                try {
                    if (ctx.contentResolver.delete(u, null, null) > 0) acc[0]++ else acc[1]++
                } catch (t: Throwable) {
                    acc[1]++ // RecoverableSecurityException = não é dela
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "varredura MediaStore (apagar pasta) falhou", t)
        }
    }

    /** Quantos arquivos indexados moram sob `relPrefix` (com barra no fim) —
     *  o teste de colisão do rename usa (LIKE escapado, como no resto). */
    private fun mediaStoreCountUnder(ctx: Context, relPrefix: String): Int = try {
        var n = 0
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
            arrayOf(escapeLike(relPrefix) + "%"), null
        )?.use { c -> n = c.count }
        n
    } catch (t: Throwable) {
        Log.w(TAG, "contagem MediaStore (colisão de rename) falhou", t)
        0
    }

    /**
     * RENAME em LOTE no índice: cada arquivo com RELATIVE_PATH sob `oldRel`
     * ganha UPDATE pro caminho novo (prefixo trocado, resto intacto). É a
     * MESMA operação do moveViaMediaStore da v0.22.0, só que em lote: no
     * arquivo do próprio app o UPDATE passa sem pergunta; o de outro app
     * falha e não conta. Devolve quantos passaram.
     */
    private fun mediaStoreRenamePath(ctx: Context, oldRel: String, newRel: String): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var ok = 0
        try {
            val updates = ArrayList<Pair<Uri, String>>()
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'",
                arrayOf(escapeLike(oldRel) + "%"), null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val old = c.getString(pathCol) ?: continue
                    updates.add(
                        ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol)
                        ) to (newRel + old.removePrefix(oldRel))
                    )
                }
            }
            for ((uri, newPath) in updates) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath)
                    }
                    if (ctx.contentResolver.update(uri, values, null, null) > 0) ok++
                } catch (_: Throwable) {
                    // faixa de outro app: o índice dela fica, o disco manda
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "renomear via MediaStore falhou", t)
        }
        return ok
    }
}
