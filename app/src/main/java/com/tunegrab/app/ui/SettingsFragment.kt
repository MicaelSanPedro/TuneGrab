package com.tunegrab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.tunegrab.app.CookieLoginActivity
import com.tunegrab.app.FormatPrefs
import com.tunegrab.app.R
import com.tunegrab.app.YtCookies
import com.tunegrab.app.databinding.FragmentSettingsBinding
import com.tunegrab.app.download.SaveLocation

/**
 * Configurações: qualidade padrão de cada formato (configuradas separadamente)
 * e a pasta onde os arquivos são salvos.
 *  - MP3: bitrate padrão da conversão (320/256/192/128 kbps) — o "320 kbps
 *    (padrão)" do seletor
 *  - M4A: melhor disponível ou menor arquivo
 *  - Vídeo: padrão FIXO em 1080p no seletor (v0.10.1) — sem configuração aqui
 *  - Pasta: Downloads/TuneGrab (padrão) ou pasta escolhida pelo usuário (SAF)
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // sem permissão persistente o app volta ao padrão sozinho depois
                }
                SaveLocation.setCustomTree(requireContext(), uri)
            }
            refreshFolderUi()
        }

    /** Importar cookies.txt exportado no PC (caminho B do login) — pega
     *  qualquer mime porque cookies.txt costuma nascer sem tipo conhecido. */
    private val pickCookieFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            val raw = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Throwable) {
                null
            }
            if (raw.isNullOrBlank()) {
                Toast.makeText(ctx, R.string.cookie_import_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (YtCookies.importNetscape(ctx, raw)) {
                Toast.makeText(ctx, R.string.cookie_import_ok, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(ctx, R.string.cookie_import_invalid, Toast.LENGTH_LONG).show()
            }
            refreshCookieUi()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnChooseFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnResetFolder.setOnClickListener {
            SaveLocation.setCustomTree(requireContext(), null)
            refreshFolderUi()
        }
        binding.btnCookieLogin.setOnClickListener {
            startActivity(Intent(requireContext(), CookieLoginActivity::class.java))
        }
        binding.btnCookieImport.setOnClickListener { pickCookieFile.launch("*/*") }
        binding.btnCookieRemove.setOnClickListener {
            YtCookies.clear(requireContext())
            Toast.makeText(requireContext(), R.string.cookie_removed, Toast.LENGTH_SHORT).show()
            refreshCookieUi()
        }
        loadCurrent()
        bindMp3()
        bindM4a()
        refreshFolderUi()
        refreshCookieUi()
        bindVisualizer()
    }

    /** Barrinhas de DJ (v0.18.8): o toggle liga o visualizador REAL do
     *  player — SEM permissão nenhuma agora (o espectro é lido por dentro
     *  do próprio player, não da saída de áudio do sistema). */
    private fun bindVisualizer() {
        binding.swVisualizer.isChecked = FormatPrefs.visualizerOn(requireContext())
        binding.swVisualizer.setOnCheckedChangeListener { _, checked ->
            FormatPrefs.setVisualizerOn(requireContext(), checked)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Volta da tela de login: recarrega o estado dos cookies. */
    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshCookieUi()
    }

    /** Status da seção de cookies: ativos (desde quando, via qual caminho)
     *  ou anônimo — e o botão Remover só aparece com cookies salvos. */
    private fun refreshCookieUi() {
        val ctx = context ?: return
        if (YtCookies.has(ctx)) {
            val at = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
            ).format(java.util.Date(YtCookies.savedAt(ctx)))
            val via = getString(
                if (YtCookies.source(ctx) == "login") R.string.cookie_via_login
                else R.string.cookie_via_import
            )
            binding.tvCookieStatus.text = getString(R.string.cookie_status_active, via, at)
            binding.btnCookieRemove.visibility = View.VISIBLE
        } else {
            binding.tvCookieStatus.setText(R.string.cookie_status_none)
            binding.btnCookieRemove.visibility = View.GONE
        }
    }

    private fun loadCurrent() {
        val ctx = requireContext()
        when (FormatPrefs.mp3DefaultBitrate(ctx)) {
            256 -> binding.rbMp3256.isChecked = true
            192 -> binding.rbMp3192.isChecked = true
            128 -> binding.rbMp3128.isChecked = true
            else -> binding.rbMp3320.isChecked = true
        }
        binding.rbM4aBest.isChecked = FormatPrefs.m4aPick(ctx) == FormatPrefs.PICK_BEST
        binding.rbM4aSmall.isChecked = FormatPrefs.m4aPick(ctx) == FormatPrefs.PICK_SMALL
    }

    private fun bindMp3() {
        binding.rgMp3.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val kbps = when (checkedId) {
                R.id.rbMp3256 -> 256
                R.id.rbMp3192 -> 192
                R.id.rbMp3128 -> 128
                else -> 320
            }
            FormatPrefs.setMp3DefaultBitrate(requireContext(), kbps)
        }
    }

    private fun bindM4a() {
        binding.rgM4a.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbM4aSmall) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setM4aPick(requireContext(), pick)
        }
    }

    private fun refreshFolderUi() {
        val ctx = context ?: return
        val tree = SaveLocation.customTree(ctx)
        if (tree == null) {
            binding.tvFolderCurrent.text = getString(R.string.settings_folder_default)
            binding.btnResetFolder.visibility = View.GONE
        } else {
            val name = try {
                DocumentFile.fromTreeUri(ctx, tree)?.name
            } catch (t: Throwable) {
                null
            }
            binding.tvFolderCurrent.text = getString(
                R.string.settings_folder_custom,
                name ?: getString(R.string.lib_folder_unknown)
            )
            binding.btnResetFolder.visibility = View.VISIBLE
        }
    }
}
