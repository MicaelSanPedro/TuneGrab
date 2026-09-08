package com.tunegrab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.tunegrab.app.FormatPrefs
import com.tunegrab.app.R
import com.tunegrab.app.databinding.FragmentSettingsBinding
import com.tunegrab.app.download.SaveLocation

/**
 * Configurações: qualidade padrão de cada formato (configuradas separadamente)
 * e a pasta onde os arquivos são salvos.
 *  - MP3: bitrate padrão da conversão (320/256/192/128 kbps)
 *  - M4A: melhor disponível ou menor arquivo
 *  - MP4: melhor disponível ou menor arquivo
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
        loadCurrent()
        bindMp3()
        bindM4a()
        bindMp4()
        refreshFolderUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
        binding.rbMp4Best.isChecked = FormatPrefs.mp4Pick(ctx) == FormatPrefs.PICK_BEST
        binding.rbMp4Small.isChecked = FormatPrefs.mp4Pick(ctx) == FormatPrefs.PICK_SMALL
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

    private fun bindMp4() {
        binding.rgMp4.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbMp4Small) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setMp4Pick(requireContext(), pick)
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
