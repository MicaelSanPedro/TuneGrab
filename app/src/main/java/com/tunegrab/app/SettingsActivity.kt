package com.tunegrab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.tunegrab.app.databinding.ActivitySettingsBinding
import com.tunegrab.app.download.SaveLocation
import com.tunegrab.app.ui.BottomNav

/**
 * Configurações: qualidade padrão de cada formato (configuradas separadamente)
 * e a pasta onde os arquivos são salvos.
 *  - MP3: bitrate padrão da conversão (320/256/192/128 kbps)
 *  - M4A: melhor disponível ou menor arquivo
 *  - MP4: melhor disponível ou menor arquivo
 *  - Pasta: Downloads/TuneGrab (padrão) ou pasta escolhida pelo usuário (SAF)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // sem permissão persistente o app volta ao padrão sozinho depois
                }
                SaveLocation.setCustomTree(this, uri)
            }
            refreshFolderUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChooseFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnResetFolder.setOnClickListener {
            SaveLocation.setCustomTree(this, null)
            refreshFolderUi()
        }

        BottomNav.setup(binding.navBar.bottomNav, this, R.id.navSettings)

        loadCurrent()
        bindMp3()
        bindM4a()
        bindMp4()
        refreshFolderUi()
    }

    private fun loadCurrent() {
        when (FormatPrefs.mp3DefaultBitrate(this)) {
            256 -> binding.rbMp3256.isChecked = true
            192 -> binding.rbMp3192.isChecked = true
            128 -> binding.rbMp3128.isChecked = true
            else -> binding.rbMp3320.isChecked = true
        }
        binding.rbM4aBest.isChecked = FormatPrefs.m4aPick(this) == FormatPrefs.PICK_BEST
        binding.rbM4aSmall.isChecked = FormatPrefs.m4aPick(this) == FormatPrefs.PICK_SMALL
        binding.rbMp4Best.isChecked = FormatPrefs.mp4Pick(this) == FormatPrefs.PICK_BEST
        binding.rbMp4Small.isChecked = FormatPrefs.mp4Pick(this) == FormatPrefs.PICK_SMALL
    }

    private fun bindMp3() {
        binding.rgMp3.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val kbps = when (checkedId) {
                R.id.rbMp3256 -> 256
                R.id.rbMp3192 -> 192
                R.id.rbMp3128 -> 128
                else -> 320
            }
            FormatPrefs.setMp3DefaultBitrate(this, kbps)
        }
    }

    private fun bindM4a() {
        binding.rgM4a.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbM4aSmall) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setM4aPick(this, pick)
        }
    }

    private fun bindMp4() {
        binding.rgMp4.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            val pick = if (checkedId == R.id.rbMp4Small) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
            FormatPrefs.setMp4Pick(this, pick)
        }
    }

    private fun refreshFolderUi() {
        val tree = SaveLocation.customTree(this)
        if (tree == null) {
            binding.tvFolderCurrent.text = getString(R.string.settings_folder_default)
            binding.btnResetFolder.visibility = View.GONE
        } else {
            val name = try {
                DocumentFile.fromTreeUri(this, tree)?.name
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
