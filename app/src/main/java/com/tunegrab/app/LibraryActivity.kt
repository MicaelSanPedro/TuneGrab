package com.tunegrab.app

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.documentfile.provider.DocumentFile
import com.tunegrab.app.databinding.ActivityLibraryBinding
import com.tunegrab.app.databinding.ItemLibraryFileBinding
import com.tunegrab.app.download.SaveLocation
import com.tunegrab.app.ui.BottomNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Um arquivo salvo pelo TuneGrab, de qualquer uma das 3 fontes de listagem. */
internal data class LibraryEntry(
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
 * Músicas baixadas: lista os arquivos que o TuneGrab salvou
 * (na pasta padrão Downloads/TuneGrab ou na pasta escolhida nas configurações),
 * com abrir / compartilhar / apagar. Somente leitura — nada aqui mexe no download.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val adapter = LibraryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnRefresh.setOnClickListener { load() }

        BottomNav.setup(binding.navBar.bottomNav, this, R.id.navLibrary)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val (entries, folderLabel) = withContext(Dispatchers.IO) { listAll() }
            adapter.submit(entries)
            binding.tvEmpty.isVisible = entries.isEmpty()
            binding.tvFolder.text = folderLabel
        }
    }

    // ---------- listagem (3 fontes: pasta SAF, MediaStore 29+, File 24–28) ----------

    private fun listAll(): Pair<List<LibraryEntry>, String> {
        val tree = SaveLocation.customTree(this)
        return when {
            tree != null -> {
                val entries = listTree(tree)
                val label = getString(
                    R.string.lib_folder_custom,
                    SaveLocation.label(this) ?: getString(R.string.lib_folder_unknown)
                )
                entries to label
            }
            Build.VERSION.SDK_INT >= 29 -> listMediaStore() to getString(R.string.lib_folder_default)
            else -> listLegacy() to getString(R.string.lib_folder_default)
        }
    }

    private fun listTree(tree: Uri): List<LibraryEntry> {
        val dir = DocumentFile.fromTreeUri(this, tree) ?: return emptyList()
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

    private fun listMediaStore(): List<LibraryEntry> {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val out = mutableListOf<LibraryEntry>()
        try {
            contentResolver.query(
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
    private fun shareableUri(e: LibraryEntry): Uri = when {
        e.mediaUri != null -> e.mediaUri
        e.docUri != null -> e.docUri
        e.file != null -> FileProvider.getUriForFile(this, "$packageName.fileprovider", e.file)
        else -> Uri.EMPTY
    }

    internal fun open(e: LibraryEntry) {
        val uri = shareableUri(e)
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

    internal fun share(e: LibraryEntry) {
        val uri = shareableUri(e)
        if (uri == Uri.EMPTY) return cant(R.string.lib_err_open)
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType(e.mime.ifBlank { "*/*" })
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.cd_share)
                )
            )
        } catch (t: Throwable) {
            cant(R.string.lib_err_open)
        }
    }

    internal fun delete(e: LibraryEntry) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    when {
                        e.docUri != null ->
                            DocumentFile.fromSingleUri(this@LibraryActivity, e.docUri)?.delete() == true
                        e.mediaUri != null ->
                            contentResolver.delete(e.mediaUri, null, null) > 0
                        else ->
                            e.file?.delete() == true
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "apagar falhou: ${e.name}", t)
                    false
                }
            }
            if (ok) {
                Toast.makeText(this@LibraryActivity, R.string.lib_deleted, Toast.LENGTH_SHORT).show()
                load()
            } else {
                cant(R.string.lib_err_delete)
            }
        }
    }

    private fun cant(msgRes: Int) {
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}

class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.VH>() {

    class EntryHolder(val binding: ItemLibraryFileBinding) : RecyclerView.ViewHolder(binding.root)

    private var items: List<LibraryEntry> = emptyList()
    private var host: LibraryActivity? = null

    fun submit(list: List<LibraryEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        host = rv.context as? LibraryActivity
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        host = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder =
        EntryHolder(ItemLibraryFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: EntryHolder, position: Int) {
        val entry = items[position]
        val b = holder.binding
        val ctx = b.root.context
        val activity = host

        b.tvName.text = entry.name
        val size = Formatter.formatShortFileSize(ctx, entry.size)
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

        fun safe(action: (LibraryActivity, LibraryEntry) -> Unit) {
            if (activity != null) action(activity, entry)
        }
        b.root.setOnClickListener { safe { a, e -> a.open(e) } }
        b.btnShare.setOnClickListener { safe { a, e -> a.share(e) } }
        b.btnDelete.setOnClickListener { safe { a, e -> a.delete(e) } }
    }
}
