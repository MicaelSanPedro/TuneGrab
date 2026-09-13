package com.tunegrab.app.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.res.ColorStateList
import androidx.activity.result.IntentSenderRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentLibraryBinding
import com.tunegrab.app.databinding.ItemLibraryFileBinding
import com.tunegrab.app.databinding.ItemLibraryFolderBinding
import com.tunegrab.app.databinding.ItemLibraryHeaderBinding
import com.tunegrab.app.databinding.ItemLibraryNoteBinding
import com.tunegrab.app.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.documentfile.provider.DocumentFile

/**
 * Biblioteca: lista os arquivos que o TuneGrab salvou (na pasta padrão
 * Downloads/TuneGrab, na pasta escolhida ou em QUALQUER pasta do aparelho —
 * o banner pede a permissão de áudio para achar músicas antigas mesmo depois
 * de atualizar/reinstalar o app). MÚSICAS (áudio) e VÍDEOS ficam separados
 * em chips próprios — nada misturado. Ações: reproduzir no app / abrir /
 * compartilhar / apagar. v0.19.7: SEGUIR NA FAIXA — segurar num cartão
 * entra no modo de seleção múltipla (tap alterna o check, barra contextual
 * com contador + Todos + lixeira) pra apagar em lote. v0.22.0: PASTAS DE
 * VERDADE — botão de nova pasta (diretório real dentro da TuneGrab), linhas
 * de pasta navegáveis e mover músicas pra dentro/fora (individual e lote).
 */
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val adapter = LibraryAdapter()

    private var all: List<LibraryEntry> = emptyList()

    /** Filtro do separador: false = Músicas (padrão), true = Vídeos. */
    private var showVideos = false

    /** FILA (v0.19.0): as músicas VISÍVEIS na aba (na ordem da tela, próprias
     *  primeiro) — repassada ao player, que ganha anterior/próxima e pula
     *  sozinho pra próxima no fim de cada faixa. */
    private var audioQueue: List<LibraryEntry> = emptyList()

    /** PASTAS (v0.22.0): a pasta aberta agora — chave relativa à raiz
     *  ("" = raiz da pasta TuneGrab / da pasta escolhida). */
    private var currentFolder: String = ""

    /** Subpastas DIRETAS da pasta aberta (recarregadas no load/navegação). */
    private var folders: List<LibraryFolders.LibFolder> = emptyList()

    /** O texto de destino da raiz ("Salvando em: …") pra montar a trilha. */
    private var rootFolderLabel: String = ""

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            updateBanner()
            load()
        }

    /** Apagar faixa de OUTRO app (permissão de leitura não basta): confirmar. */
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(context, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                load()
            } else {
                cant(R.string.lib_err_delete)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.btnRefresh.setOnClickListener { load() }
        binding.btnPermission.setOnClickListener {
            try {
                permLauncher.launch(LibraryFiles.mediaReadPermission())
            } catch (t: Throwable) {
                Log.w(TAG, "pedido de permissão falhou", t)
            }
        }
        adapter.onPlay = { e -> play(e) }
        adapter.onOpen = { e -> open(e) }
        adapter.onShare = { e -> LibraryFiles.share(requireContext(), e) }
        adapter.onDelete = { e -> delete(e) }
        // v0.22.0: PASTAS — navegar, criar pasta e mover (individual e lote)
        adapter.onEnterFolder = { f -> enterFolder(f.folder.key) }
        adapter.onGoUp = { goUp() }
        adapter.onMove = { e -> moveDialog(listOf(e)) }
        // v0.22.3: segurar na pasta (ou o ⋮ dela) abre renomear/apagar
        adapter.onFolderOptions = { f -> folderOptions(f) }
        binding.btnNewFolder.setOnClickListener { askNewFolder() }
        binding.btnMoveSel.setOnClickListener { moveDialog(adapter.selectedEntries()) }
        // v0.19.7: barra contextual da seleção múltipla (contador/Todos/lixeira)
        adapter.onSelectCount = { n -> onSelCount(n) }
        binding.btnSelClose.setOnClickListener { adapter.exitSelection() }
        binding.btnSelAll.setOnClickListener { adapter.selectAllVisible() }
        binding.btnDeleteSel.setOnClickListener { confirmDeleteSelected() }
        binding.chipFilterAudio.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // trocou de aba: a seleção morre — nada de apagar o que não se vê
                adapter.exitSelection()
                showVideos = false
                render()
            }
        }
        binding.chipFilterVideo.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                adapter.exitSelection()
                showVideos = true
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
        load()
    }

    private fun updateBanner() {
        val ctx = context ?: return
        binding.btnPermission.isVisible = !LibraryFiles.hasMediaReadPermission(ctx)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val ctx = requireContext()
        lifecycleScope.launch {
            val (entries, folderLabel) = withContext(Dispatchers.IO) { LibraryFiles.listAll(ctx) }
            val b = _binding ?: return@launch
            all = entries
            rootFolderLabel = folderLabel
            b.tvFolder.text = crumbsLabel()
            refreshFolders(ctx)
        }
    }

    /** Trilha de navegação (v0.22.0): na raiz, o destino de sempre
     *  ("Salvando em: …"); dentro de pasta, "TuneGrab › Forró › 2026". */
    private fun crumbsLabel(): String {
        if (currentFolder.isBlank()) return rootFolderLabel
        val root = LibraryFolders.rootLabel(requireContext())
        val path = currentFolder.split('/').joinToString(" › ")
        return "$root › $path"
    }

    /** Lista as subpastas da pasta aberta (IO rápido) e re-renderiza. */
    private fun refreshFolders(ctx: android.content.Context) {
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                LibraryFolders.listChildren(ctx, currentFolder, all)
            }
            val b = _binding ?: return@launch
            folders = f
            render()
        }
    }

    /** Entrou numa pasta: seleção morre (nada de mover/apagar o que não se
     *  vê), pasta vira a atual, lista volta pro topo. */
    private fun enterFolder(key: String) {
        val ctx = requireContext()
        adapter.exitSelection()
        currentFolder = key
        binding.list.scrollToPosition(0)
        binding.tvFolder.text = crumbsLabel()
        refreshFolders(ctx)
    }

    /** Voltou uma pasta (a linha “…” da lista). */
    private fun goUp() {
        val ctx = requireContext()
        adapter.exitSelection()
        currentFolder = currentFolder.substringBeforeLast('/', "")
        binding.list.scrollToPosition(0)
        binding.tvFolder.text = crumbsLabel()
        refreshFolders(ctx)
    }

    /**
     * Aplica o filtro do chip (Músicas/Vídeos) e monta as SEÇÕES. NA RAIZ:
     * pastas primeiro (seção própria, v0.22.0), depois o baixado PELO
     * TuneGrab que mora NA RAIZ e as mídias achadas no aparelho. DENTRO DE
     * PASTA: linha de voltar, subpastas e só o que mora nela (faixa do
     * próprio app; mídia de outro app não tem pasta).
     *
     * v0.22.2 FIX (o “ctrl v” do micaelsan): a raiz era PLANA de propósito
     * (“NADA sumiu”), listando TODAS as próprias — inclusive as que moram
     * dentro de pastas. Mover pra pasta entrava nela mas “continha” na
     * raiz: mover parecia COPIAR. Agora raiz = quem mora na raiz; quem tem
     * pasta aparece SÓ dentro dela (mover é ctrl x). As SEM pasta navegável
     * (folder nulo — mídia própria fora da raiz, ex.: Music/TuneGrab)
     * seguem visíveis na raiz pra nada sumir da tela. */
    private fun render() {
        val b = _binding ?: return
        val shown = all.filter { it.isVideoKind == showVideos }
        // v0.22.3 FIX (o "porque as pastas de MP3 aparecem em vídeos?"):
        // a seção PASTAS listava `folders` cru, IGNORANDO o chip — a pasta
        // de música aparecia até no filtro de VÍDEOS. Agora pasta entra só
        // se tiver algo do tipo do chip DENTRO (ela mesma ou nas subpastas);
        // a contagem da linha passa a contar esse tipo (o que você vê ao
        // entrar bate com o número). Vazia de verdade: aparece nos 2 chips
        // (pasta vazia precisa continuar achável pra ser preenchida).
        val shownFolders = folders.mapNotNull { f ->
            val prefix = "${f.key}/"
            val sub = all.filter {
                it.fromTuneGrab && (it.folder == f.key || it.folder.startsWith(prefix))
            }
            val kind = sub.count { it.isVideoKind == showVideos }
            if (kind > 0 || sub.isEmpty()) f.copy(items = kind) else null
        }
        val rows = buildList {
            if (currentFolder.isBlank()) {
                if (shownFolders.isNotEmpty()) {
                    add(
                        LibRow.Section(
                            getString(R.string.lib_folders_section),
                            shownFolders.size,
                            own = true,
                            folders = true
                        )
                    )
                    addAll(shownFolders.map { LibRow.Folder(it) })
                }
                // v0.22.2 FIX (o “ctrl v”): raiz mostra só quem mora NA RAIZ —
                // antes listava TODAS as próprias (vista plana da v0.22.0),
                // então a música movida pra pasta “continha” aqui: mover
                // parecia copiar. Quem tem pasta mora SÓ na pasta; as sem
                // casa navegável (folder nulo) seguem na raiz, nada some.
                val own = shown.filter { it.fromTuneGrab && it.folder.isNullOrEmpty() }
                val others = shown.filter { !it.fromTuneGrab }
                if (own.isNotEmpty()) {
                    add(
                        LibRow.Section(
                            getString(R.string.lib_section_own),
                            own.size,
                            own = true
                        )
                    )
                    addAll(own.map { LibRow.File(it) })
                }
                if (others.isNotEmpty()) {
                    add(
                        LibRow.Section(
                            getString(R.string.lib_section_others),
                            others.size,
                            own = false
                        )
                    )
                    addAll(others.map { LibRow.File(it) })
                }
            } else {
                val parentKey = currentFolder.substringBeforeLast('/', "")
                val parentLabel = if (parentKey.isBlank()) {
                    LibraryFolders.rootLabel(requireContext())
                } else {
                    parentKey.substringAfterLast('/')
                }
                add(LibRow.Up(parentLabel))
                if (shownFolders.isNotEmpty()) {
                    add(
                        LibRow.Section(
                            getString(R.string.lib_folders_section),
                            shownFolders.size,
                            own = true,
                            folders = true
                        )
                    )
                    addAll(shownFolders.map { LibRow.Folder(it) })
                }
                val inside = shown.filter { it.fromTuneGrab && it.folder == currentFolder }
                if (inside.isNotEmpty()) {
                    add(
                        LibRow.Section(
                            getString(R.string.lib_section_own),
                            inside.size,
                            own = true
                        )
                    )
                    addAll(inside.map { LibRow.File(it) })
                }
                // v0.22.1: pasta sem NADA (nem subpasta, nem faixa) — a frase
                // de sempre vira linha da lista, embaixo do voltar
                if (folders.isEmpty() && inside.isEmpty()) {
                    add(LibRow.Note(getString(R.string.lib_empty_folder_view)))
                }
            }
        }
        adapter.submit(rows)
        // vazio: dentro de pasta a frase ensina o caminho (mover pra cá /
        // subpasta); na raiz vale a mensagem estática de sempre (XML)
        if (currentFolder.isNotBlank()) {
            b.tvEmpty.text = getString(R.string.lib_empty_folder_view)
        }
        // v0.22.1 FIX (o “não consigo voltar”): a linha de VOLTAR tem que
        // viver mesmo em pasta vazia — antes, rows=[Up] não tinha File nem
        // Folder, a lista inteira ia pra GONE e a linha de voltar sumia
        // junto: autor PRESO dentro da pasta. Agora Up conta como conteúdo
        // (a nota de vazia só existe acompanhando o Up, nunca sozinha).
        val hasRows = rows.any { it is LibRow.File || it is LibRow.Folder || it is LibRow.Up }
        b.tvEmpty.isVisible = !hasRows
        b.list.isVisible = hasRows
        // fila de músicas na ordem em que aparecem na tela (próprias
        // primeiro; dentro de pasta: só o que mora nela)
        audioQueue = if (showVideos) emptyList() else {
            // MESMA REGRA da vista (v0.22.2): na raiz, só quem mora na raiz
            // (sem pasta mapeável entra junto); dentro de pasta, só dela.
            val own = if (currentFolder.isBlank()) {
                shown.filter { it.fromTuneGrab && it.folder.isNullOrEmpty() }
            } else {
                shown.filter { it.fromTuneGrab && it.folder == currentFolder }
            }
            if (currentFolder.isBlank()) own + shown.filter { !it.fromTuneGrab } else own
        }
        // MESMA DOENÇA do print da Central: vazio peso 1 × lista peso 99 —
        // com a lista “visível e vazia” a mensagem ficava com 1% da tela.
        // contagem em cada chip: dá pra ver o que tem no outro filtro sem sair daqui
        b.chipFilterAudio.text = getString(
            R.string.lib_chip_count,
            getString(R.string.lib_filter_audio),
            all.count { !it.isVideoKind }
        )
        b.chipFilterVideo.text = getString(
            R.string.lib_chip_count,
            getString(R.string.lib_filter_video),
            all.count { it.isVideoKind }
        )
    }

    // ---------- ações ----------

    /** Reproduz dentro do app (player próprio: áudio com controles, vídeo em tela). */
    private fun play(e: LibraryEntry) {
        val uri = LibraryFiles.shareableUri(requireContext(), e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            val i = Intent(requireContext(), PlayerActivity::class.java)
                .setDataAndType(uri, e.mime.ifBlank { "*/*" })
                .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                .putExtra(PlayerActivity.EXTRA_IS_VIDEO, e.isVideoKind)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // FILA (v0.19.0): nas MÚSICAS, manda a lista visível da aba + o
            // índice da faixa tocada — no player viram os botões de
            // anterior/próxima e o avanço automático no fim da faixa
            if (!e.isVideoKind && audioQueue.size > 1) {
                val uris = ArrayList<String>(audioQueue.size)
                val titles = ArrayList<String>(audioQueue.size)
                var index = -1
                for (entry in audioQueue) {
                    val u = LibraryFiles.shareableUri(requireContext(), entry)
                    if (u == Uri.EMPTY) continue
                    if (index < 0 && entry.name == e.name && entry.size == e.size) {
                        index = uris.size
                    }
                    uris.add(u.toString())
                    titles.add(entry.name)
                }
                if (uris.size > 1 && index >= 0) {
                    i.putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_URIS, uris)
                    i.putStringArrayListExtra(PlaybackService.EXTRA_QUEUE_TITLES, titles)
                    i.putExtra(PlaybackService.EXTRA_QUEUE_INDEX, index)
                }
            }
            startActivity(i)
        } catch (t: Throwable) {
            cant(R.string.player_err)
        }
    }

    /** Abre num app externo (player do sistema, etc.). */
    private fun open(e: LibraryEntry) {
        val uri = LibraryFiles.shareableUri(requireContext(), e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, e.mime.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (t: Throwable) {
            cant(R.string.lib_err_open)
        }
    }

    /** VERIFICAÇÃO DUPLA: o toque no botão de lixo NUNCA apaga direto —
     *  primeiro um diálogo do app; para faixa de OUTRO app o sistema ainda
     *  mostra a confirmação própria dele (RecoverableSecurityException).
     *  O toque errado não pode custar uma música. */
    private fun delete(e: LibraryEntry) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_delete_title)
            .setMessage(ctx.getString(R.string.lib_delete_msg, e.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_delete_yes) { _, _ -> deleteNow(e) }
            .show()
    }

    private fun deleteNow(e: LibraryEntry) {
        val ctx = requireContext()
        lifecycleScope.launch {
            var recoverable: android.content.IntentSender? = null
            val ok = withContext(Dispatchers.IO) {
                try {
                    when {
                        e.docUri != null ->
                            DocumentFile.fromSingleUri(ctx, e.docUri)?.delete() == true
                        e.mediaUri != null ->
                            ctx.contentResolver.delete(e.mediaUri, null, null) > 0
                        else ->
                            e.file?.delete() == true
                    }
                } catch (t: Throwable) {
                    // faixa de OUTRO app (permissão de leitura não autoriza
                    // apagar): o sistema oferece confirmação própria
                    if (Build.VERSION.SDK_INT >= 29 && t is RecoverableSecurityException) {
                        recoverable = t.userAction.actionIntent.intentSender
                    } else {
                        Log.w(TAG, "apagar falhou: ${e.name}", t)
                    }
                    false
                }
            }
            if (!isAdded) return@launch
            val sender = recoverable
            when {
                sender != null ->
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                ok -> {
                    Toast.makeText(ctx, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                    load()
                }
                else -> cant(R.string.lib_err_delete)
            }
        }
    }

    // ---------- PASTAS (v0.22.0): criar e mover ----------

    /** Botão de pasta com + (topo): diálogo com o nome da nova pasta —
     *  nasce DE VERDADE dentro da pasta aberta agora. */
    private fun askNewFolder() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = ctx.getString(R.string.lib_new_folder_hint)
            setSingleLine(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_new_folder_title)
            .setMessage(R.string.lib_new_folder_msg)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_new_folder_ok) { _, _ ->
                createFolder(input.text.toString())
            }
            .show()
    }

    private fun createFolder(rawName: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LibraryFolders.create(ctx, currentFolder, rawName)
            }
            if (_binding == null) return@launch // a aba saiu da tela no meio
            when (res) {
                is LibraryFolders.CreateResult.Created -> {
                    Toast.makeText(ctx, R.string.lib_folder_created, Toast.LENGTH_SHORT).show()
                    refreshFolders(ctx)
                }
                LibraryFolders.CreateResult.Exists -> cant(R.string.lib_err_folder_exists)
                LibraryFolders.CreateResult.Invalid -> cant(R.string.lib_err_folder_name)
                LibraryFolders.CreateResult.Failed -> cant(R.string.lib_err_folder_create)
            }
        }
    }

    /**
     * MOVER: diálogo com a lista de pastas (raiz + todas, indentadas por
     * profundidade). A pasta onde o arquivo já está aparece, mas mover pra
     * onde já está é contado como "same" — nada de surpresa.
     */
    private fun moveDialog(entries: List<LibraryEntry>) {
        val owned = entries.filter { it.fromTuneGrab }
        if (owned.isEmpty()) return cant(R.string.lib_move_nofolders)
        val ctx0 = context ?: return
        lifecycleScope.launch {
            val keys = withContext(Dispatchers.IO) { LibraryFolders.listAllKeys(ctx0) }
            if (!isAdded) return@launch
            val ctx = context ?: return@launch
            val options = mutableListOf("" to ctx.getString(R.string.lib_move_root))
            keys.forEach { k ->
                val depth = k.count { c -> c == '/' }
                options.add(k to "    ".repeat(depth) + k.substringAfterLast('/'))
            }
            var chosen = -1
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.lib_move_title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), -1) { _, which ->
                    chosen = which
                }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.lib_move_ok) { _, _ ->
                    if (chosen in options.indices) {
                        moveSelected(owned, options[chosen].first)
                    }
                }
                .show()
        }
    }

    private fun moveSelected(entries: List<LibraryEntry>, targetKey: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LibraryFolders.moveMany(ctx, entries, targetKey)
            }
            if (_binding == null) return@launch // a aba saiu da tela no meio
            adapter.exitSelection()
            when {
                res.moved == 0 && res.same == entries.size ->
                    cant(R.string.lib_move_same)
                res.moved == 0 ->
                    cant(if (entries.size == 1) R.string.lib_err_move_one else R.string.lib_err_move)
                res.moved + res.same == entries.size -> {
                    val msg = if (res.moved == 1) {
                        ctx.getString(R.string.lib_moved_one)
                    } else {
                        ctx.getString(R.string.lib_moved_many, res.moved)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
                else ->
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.lib_move_partial, res.moved, entries.size),
                        Toast.LENGTH_LONG
                    ).show()
            }
            load()
        }
    }

    // ---------- PASTAS (v0.22.3): renomear e apagar ----------

    /** Segurar na pasta (ou o ⋮): as duas ações que faltavam. */
    private fun folderOptions(row: LibRow.Folder) {
        val f = row.folder
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(f.name)
            .setItems(
                arrayOf(
                    ctx.getString(R.string.lib_folder_opt_rename),
                    ctx.getString(R.string.lib_folder_opt_delete)
                )
            ) { _, which ->
                when (which) {
                    0 -> askRenameFolder(f)
                    else -> confirmFolderDelete(f)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Renomear: caixa de texto vinda com o nome atual, selecionado. */
    private fun askRenameFolder(f: LibraryFolders.LibFolder) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = ctx.getString(R.string.lib_new_folder_hint)
            setText(f.name)
            setSingleLine(true)
            setSelection(f.name.length)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_folder_rename_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_folder_rename_ok) { _, _ ->
                renameFolder(f, input.text.toString())
            }
            .show()
    }

    private fun renameFolder(f: LibraryFolders.LibFolder, rawName: String) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LibraryFolders.renameFolder(ctx, f.key, rawName)
            }
            if (_binding == null) return@launch // a aba saiu da tela no meio
            when (res) {
                is LibraryFolders.RenameResult.Renamed -> {
                    Toast.makeText(ctx, R.string.lib_folder_renamed, Toast.LENGTH_SHORT).show()
                    // aberta na pasta renomeada (ou dentro dela)? acompanha
                    if (currentFolder == f.key || currentFolder.startsWith("${f.key}/")) {
                        val newName = LibraryFolders.sanitize(rawName) ?: f.name
                        val parent = f.key.substringBeforeLast('/', "")
                        val newKey = if (parent.isBlank()) newName else "$parent/$newName"
                        currentFolder = newKey + currentFolder.removePrefix(f.key)
                        binding.tvFolder.text = crumbsLabel()
                    }
                    load()
                }
                LibraryFolders.RenameResult.Exists -> cant(R.string.lib_err_folder_exists)
                LibraryFolders.RenameResult.Invalid -> cant(R.string.lib_err_folder_name)
                LibraryFolders.RenameResult.Failed -> cant(R.string.lib_err_folder_rename)
            }
        }
    }

    /** Apagar: VERIFICAÇÃO DUPLA (mesma religião da lixeira de faixa) —
     *  avisa QUANTO vai junto (subpastas e tudo) antes do toque final. */
    private fun confirmFolderDelete(f: LibraryFolders.LibFolder) {
        val ctx = context ?: return
        val inside = all.count {
            it.fromTuneGrab && (it.folder == f.key || it.folder.startsWith("${f.key}/"))
        }
        val msg = if (inside == 0) {
            ctx.getString(R.string.lib_folder_delete_empty, f.name)
        } else {
            ctx.resources.getQuantityString(
                R.plurals.lib_folder_delete_items, inside, f.name, inside
            )
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.lib_folder_delete_title)
            .setMessage(msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_delete_yes) { _, _ -> deleteFolderNow(f) }
            .show()
    }

    private fun deleteFolderNow(f: LibraryFolders.LibFolder) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LibraryFolders.deleteFolder(ctx, f.key)
            }
            if (_binding == null) return@launch // a aba saiu da tela no meio
            when {
                // sem negação = a pasta foi (vazia incluída — o vazio não
                // tem arquivo pra contar, e é isso que a frase diz)
                res.failed == 0 ->
                    Toast.makeText(ctx, R.string.lib_folder_deleted, Toast.LENGTH_SHORT).show()
                res.deleted > 0 ->
                    Toast.makeText(
                        ctx,
                        ctx.resources.getQuantityString(
                            R.plurals.lib_folder_delete_left, res.failed, res.failed
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                else -> cant(R.string.lib_err_folder_delete)
            }
            // a aba estava DENTRO da pasta apagada? sobe pro pai dela
            if (currentFolder == f.key || currentFolder.startsWith("${f.key}/")) {
                currentFolder = f.key.substringBeforeLast('/', "")
                binding.tvFolder.text = crumbsLabel()
            }
            load()
        }
    }

    // ---------- seleção múltipla (v0.19.7) ----------

    /** A barra contextual acompanha a contagem: entrando no modo ela
     *  substitui o cabeçalho; voltando a 0 o "Biblioteca" de sempre volta. */
    private fun onSelCount(n: Int) {
        val b = _binding ?: return
        b.headerRow.isVisible = !adapter.selectionMode
        b.selBar.isVisible = adapter.selectionMode
        b.tvSelCount.text = resources.getQuantityString(R.plurals.lib_sel_count, n, n)
        // "Todos" some quando a aba visível inteira já está marcada
        b.btnSelAll.isVisible = adapter.visibleFileCount > n
    }

    /** Lixeira da barra: VERIFICAÇÃO DUPLA igual à individual — o toque
     *  errado não pode custar N músicas de uma vez. */
    private fun confirmDeleteSelected() {
        val entries = adapter.selectedEntries()
        if (entries.isEmpty()) return
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle(
                resources.getQuantityString(
                    R.plurals.lib_sel_delete_title, entries.size, entries.size
                )
            )
            .setMessage(R.string.lib_sel_delete_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lib_delete_yes) { _, _ -> deleteMany(entries) }
            .show()
    }

    /** Deleção em lote com o MESMO LibraryFiles.delete da v0.19.5 (o app é
     *  dono dos próprios downloads — sem permissão extra). Faixa de OUTRO
     *  app falha no lote (a confirmação do sistema é por arquivo): o toast
     *  diz quantos foram e explica o resto. */
    private fun deleteMany(entries: List<LibraryEntry>) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                entries.count { LibraryFiles.delete(ctx, it) }
            }
            if (_binding == null) return@launch // a aba saiu da tela no meio
            adapter.exitSelection()
            when {
                deleted == entries.size ->
                    Toast.makeText(
                        ctx,
                        ctx.resources.getQuantityString(
                            R.plurals.lib_sel_deleted, deleted, deleted
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                deleted > 0 ->
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.lib_sel_partial, deleted, entries.size),
                        Toast.LENGTH_LONG
                    ).show()
                else -> cant(R.string.lib_err_delete)
            }
            load()
        }
    }

    private fun cant(msgRes: Int) {
        context?.let { Toast.makeText(it, msgRes, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}

/** Linha da lista da Biblioteca: cabeçalho de seção, arquivo, PASTA
 *  navegável (v0.22.0), a linha de voltar ou a nota de pasta vazia
 *  (v0.22.1). */
sealed class LibRow {
    data class Section(
        val title: String,
        val count: Int,
        val own: Boolean,
        val folders: Boolean = false
    ) : LibRow()

    data class File(val entry: LibraryEntry) : LibRow()

    /** PASTAS (v0.22.0): linha de pasta que abre ao toque. */
    data class Folder(val folder: LibraryFolders.LibFolder) : LibRow()

    /** PASTAS (v0.22.0): linha “…” — sobe uma pasta (a raiz usa o nome dela). */
    data class Up(val parentLabel: String) : LibRow()

    /** v0.22.1: texto sem toque dentro da lista (pasta vazia) — a frase
     *  que era do vazio em tela cheia, agora convivendo com a linha de
     *  voltar sem brigar pelo peso da tela. */
    data class Note(val text: String) : LibRow()
}

class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class HeaderHolder(val binding: ItemLibraryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class EntryHolder(val binding: ItemLibraryFileBinding) : RecyclerView.ViewHolder(binding.root)
    class FolderHolder(val binding: ItemLibraryFolderBinding) : RecyclerView.ViewHolder(binding.root)
    class NoteHolder(val binding: ItemLibraryNoteBinding) : RecyclerView.ViewHolder(binding.root)

    private var rows: List<LibRow> = emptyList()
    var onPlay: ((LibraryEntry) -> Unit)? = null
    var onOpen: ((LibraryEntry) -> Unit)? = null
    var onShare: ((LibraryEntry) -> Unit)? = null
    var onDelete: ((LibraryEntry) -> Unit)? = null

    // v0.22.0: pastas — entrar, subir e mover faixa pra pasta
    var onEnterFolder: ((LibRow.Folder) -> Unit)? = null
    var onGoUp: (() -> Unit)? = null
    var onMove: ((LibraryEntry) -> Unit)? = null
    // v0.22.3: segurar na pasta (ou o ⋮) — renomear/apagar
    var onFolderOptions: ((LibRow.Folder) -> Unit)? = null

    // ---------- seleção múltipla (v0.19.7) ----------

    /** Estado do modo: segurar numa faixa entra, tap alterna. A chave é
     *  (nome, tamanho) — o MESMO critério de dedupe do listAll, então a
     *  seleção sobrevive a re-render da lista. */
    var selectionMode = false
        private set

    private val selected = LinkedHashSet<String>()

    /** Contagem mudou (entra/alterna/sai) — o fragment atualiza a barra. */
    var onSelectCount: ((Int) -> Unit)? = null

    /** Quantas faixas a aba visível tem (o "Todos" some quando completa). */
    val visibleFileCount: Int
        get() = rows.count { it is LibRow.File }

    private fun key(e: LibraryEntry) = "${e.name}\u0000${e.size}"

    /** Segurar na faixa: entra no modo com ela já marcada (o fragment
     *  cuida do feedback tátil antes de chamar). */
    fun enterSelection(first: LibraryEntry) {
        if (!selectionMode) {
            selectionMode = true
            selected.clear()
        }
        selected.add(key(first))
        notifyDataSetChanged()
        onSelectCount?.invoke(selected.size)
    }

    /** Toque na faixa DENTRO do modo: alterna o check. Voltando a 0 sai. */
    fun toggle(entry: LibraryEntry) {
        val k = key(entry)
        if (!selected.remove(k)) selected.add(k)
        if (selected.isEmpty()) {
            exitSelection()
        } else {
            notifyDataSetChanged()
            onSelectCount?.invoke(selected.size)
        }
    }

    fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selected.clear()
        notifyDataSetChanged()
        onSelectCount?.invoke(0)
    }

    /** "Todos": marca a aba visível inteira (o filtro atual). */
    fun selectAllVisible() {
        selectionMode = true
        rows.filterIsInstance<LibRow.File>().forEach { selected.add(key(it.entry)) }
        notifyDataSetChanged()
        onSelectCount?.invoke(selected.size)
    }

    /** As faixas marcadas, na ordem da tela (a lixeira usa). */
    fun selectedEntries(): List<LibraryEntry> =
        rows.filterIsInstance<LibRow.File>().map { it.entry }.filter { key(it) in selected }

    fun submit(list: List<LibRow>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is LibRow.Section -> TYPE_HEADER
        is LibRow.File -> TYPE_FILE
        is LibRow.Note -> TYPE_NOTE
        // pasta e voltar compartilham o layout de pasta (ícone/nome/chevron)
        else -> TYPE_FOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                ItemLibraryHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            TYPE_FILE -> EntryHolder(
                ItemLibraryFileBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            TYPE_NOTE -> NoteHolder(
                ItemLibraryNoteBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
            else -> FolderHolder(
                ItemLibraryFolderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is LibRow.Section -> {
                val b = (holder as HeaderHolder).binding
                b.tvSectionTitle.text = "${row.title} · ${row.count}"
                if (row.folders) {
                    // v0.22.0: seção de PASTAS — ícone de pasta na cor do app
                    b.headerIcon.setImageResource(R.drawable.ic_folder)
                    b.headerIcon.setColorFilter(
                        ContextCompat.getColor(b.root.context, R.color.primary)
                    )
                } else if (row.own) {
                    b.headerIcon.setImageResource(R.drawable.ic_download)
                    b.headerIcon.setColorFilter(
                        ContextCompat.getColor(b.root.context, R.color.primary)
                    )
                } else {
                    b.headerIcon.setImageResource(R.drawable.ic_library_music)
                    b.headerIcon.setColorFilter(
                        ContextCompat.getColor(b.root.context, R.color.on_surface_variant)
                    )
                }
            }
            is LibRow.Folder -> {
                // v0.22.0: linha de pasta — nome, contagem de itens e chevron;
                // o toque entra na pasta (navegação de verdade)
                val b = (holder as FolderHolder).binding
                val ctx = b.root.context
                b.tvName.text = row.folder.name
                b.tvMeta.text = when (row.folder.items) {
                    0 -> ctx.getString(R.string.lib_folder_empty)
                    1 -> ctx.getString(R.string.lib_folder_items_one)
                    else -> ctx.getString(R.string.lib_folder_items, row.folder.items)
                }
                b.icon.setImageResource(R.drawable.ic_folder)
                b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                b.chevron.isVisible = true
                b.root.setOnClickListener { onEnterFolder?.invoke(row) }
                // v0.22.3: as ações que faltavam — o ⋮ VISÍVEL e o segurar
                // (mesmo gesto das faixas) abrem renomear/apagar
                b.btnMore.isVisible = true
                b.btnMore.setOnClickListener { onFolderOptions?.invoke(row) }
                b.root.setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onFolderOptions?.invoke(row)
                    true
                }
            }
            is LibRow.Up -> {
                // v0.22.0: linha de voltar — seta pra esquerda + o nome da
                // pasta de cima (a raiz usa o nome dela, "TuneGrab")
                val b = (holder as FolderHolder).binding
                val ctx = b.root.context
                b.tvName.text = row.parentLabel
                b.tvMeta.text = ctx.getString(R.string.lib_up_meta)
                b.icon.setImageResource(R.drawable.ic_back)
                b.icon.setColorFilter(
                    ContextCompat.getColor(ctx, R.color.on_surface_variant)
                )
                b.chevron.isVisible = false
                b.root.setOnClickListener { onGoUp?.invoke() }
                // v0.22.3: voltar NÃO é pasta — sem ⋮ e sem segurar (recycle
                // devolve holder sujo se não limpar aqui)
                b.btnMore.isVisible = false
                b.root.setOnLongClickListener(null)
                b.root.isLongClickable = false
            }
            is LibRow.Note -> {
                // v0.22.1: nota de pasta vazia — texto puro, sem toque
                (holder as NoteHolder).binding.tvNote.text = row.text
            }
            is LibRow.File -> {
                val entry = row.entry
                val b = (holder as EntryHolder).binding
                val ctx = b.root.context

                b.tvName.text = entry.name
                val size = LibraryFiles.formatSize(ctx, entry.size)
                val whenTxt = DateUtils.getRelativeTimeSpanString(
                    entry.modifiedMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )
                b.tvMeta.text = ctx.getString(R.string.lib_meta_line, size, whenTxt.toString())
                if (entry.isVideoKind) {
                    b.icon.setImageResource(R.drawable.ic_movie)
                    b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.secondary))
                } else {
                    b.icon.setImageResource(R.drawable.ic_music_note)
                    b.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                }

                // v0.19.7: no modo seleção os 4 botões saem e o círculo de
                // check entra; o cartão marcado ganha fundo roxo + borda violeta
                val isSel = selectionMode && key(entry) in selected
                b.selBox.isVisible = selectionMode
                b.chk.setImageResource(if (isSel) R.drawable.bg_sel_on else R.drawable.bg_sel_off)
                b.btnPlay.isVisible = !selectionMode
                b.btnShare.isVisible = !selectionMode
                // v0.22.0: MOVER só em faixa do TuneGrab — o app é dono dos
                // próprios downloads; mídia de outro app não é dele pra mover
                b.btnMove.isVisible = !selectionMode && entry.fromTuneGrab
                b.btnDelete.isVisible = !selectionMode
                b.root.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, if (isSel) R.color.sel_card else R.color.surface)
                )
                // setStrokeColor EXPLÍCITO: o `strokeColor =` do Kotlin casa
                // com o setter Int (pelo tipo do getter) e o ColorStateList
                // não entra — v0.19.4 labelSlide, mesma família de overload
                b.root.setStrokeColor(
                    ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, if (isSel) R.color.primary else R.color.stroke)
                    )
                )
                b.root.setOnClickListener {
                    if (selectionMode) toggle(entry) else onOpen?.invoke(entry)
                }
                b.root.setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (selectionMode) toggle(entry) else enterSelection(entry)
                    true
                }
                b.btnPlay.setOnClickListener { onPlay?.invoke(entry) }
                b.btnShare.setOnClickListener { onShare?.invoke(entry) }
                b.btnMove.setOnClickListener { onMove?.invoke(entry) }
                b.btnDelete.setOnClickListener { onDelete?.invoke(entry) }
            }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FILE = 1
        private const val TYPE_FOLDER = 2
        private const val TYPE_NOTE = 3
    }
}
