package com.tunegrab.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tunegrab.app.BuildConfig
import com.tunegrab.app.FormatPrefs
import com.tunegrab.app.MainActivity
import com.tunegrab.app.OnboardingActivity
import com.tunegrab.app.R
import com.tunegrab.app.YtCookies
import com.tunegrab.app.databinding.FragmentSettingsBinding
import com.tunegrab.app.download.SaveLocation
import com.tunegrab.app.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Configurações (v0.19.8 — "organizadas por categoria, parecido com a foto",
 * pedido do autor): lista estilo YouTube — cabeçalho de categoria em
 * negrito e linhas de ícone + título + subtítulo.
 *
 *  - DOWNLOADS: pasta (SAF), MP3, M4A e vídeo — as qualidades viraram
 *    linhas que abrem diálogo de escolha única (como "Idiomas" no YouTube).
 *  - PLAYER: barrinhas de áudio (switch inline na própria linha).
 *  - YOUTUBE: status da conta — o LOGIN agora é automático na aba YouTube
 *    (a sessão é capturada sozinha pelo [YoutubeFragment]); daqui o usuário
 *    só vê se está conectado, abre a aba pra logar ou desconecta. A antiga
 *    seção de cookies (login manual + importar cookies.txt + remover)
 *    SAIU — "isso é patético, ninguém sabe mexer nisso", e agora ninguém
 *    precisa mexer em nada.
 *  - AJUDA: reabrir o tutorial de primeira abertura.
 *  - ATUALIZAÇÃO: verificação manual — achou versão nova? Leva pra Central
 *    de Downloads, onde vive o card de instalação.
 *  - SOBRE: versão, motores e repositório.
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
        bindFolder()
        bindQualities()
        bindVisualizer()
        bindAccount()
        bindTutorial()
        bindUpdate()
        bindAbout()
        refreshFolderUi()
        refreshQualities()
        refreshAccountUi()
    }

    /** Volta da aba YouTube: o status da conta pode ter mudado (login lá). */
    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshAccountUi()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- Downloads ----------

    private fun bindFolder() {
        binding.rowFolder.setOnClickListener { pickFolder.launch(null) }
        binding.rowFolderReset.setOnClickListener {
            SaveLocation.setCustomTree(requireContext(), null)
            refreshFolderUi()
        }
    }

    private fun refreshFolderUi() {
        val ctx = context ?: return
        val tree = SaveLocation.customTree(ctx)
        if (tree == null) {
            binding.tvFolderCurrent.text = getString(R.string.settings_folder_default)
            binding.rowFolderReset.visibility = View.GONE
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
            binding.rowFolderReset.visibility = View.VISIBLE
        }
    }

    /** MP3 / M4A / vídeo: linhas que abrem DIALOG de escolha única — o
     *  padrão das configurações do YouTube pra opções curtas (e o subtítulo
     *  da linha mostra o valor corrente, igual "Idiomas > Português"). */
    private fun bindQualities() {
        binding.rowMp3.setOnClickListener { askMp3() }
        binding.rowM4a.setOnClickListener { askM4a() }
        binding.rowVideo.setOnClickListener { askVideo() }
    }

    private fun askMp3() {
        val ctx = requireContext()
        val options = intArrayOf(320, 256, 192, 128)
        val labels = arrayOf(
            ctx.getString(R.string.q_mp3_320),
            ctx.getString(R.string.q_mp3_256),
            ctx.getString(R.string.q_mp3_192),
            ctx.getString(R.string.q_mp3_128)
        )
        val checked = when (FormatPrefs.mp3DefaultBitrate(ctx)) {
            256 -> 1
            192 -> 2
            128 -> 3
            else -> 0
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_row_mp3)
            .setMessage(R.string.settings_mp3_hint)
            .setSingleChoiceItems(labels, checked) { d, which ->
                FormatPrefs.setMp3DefaultBitrate(ctx, options[which])
                d.dismiss()
                refreshQualities()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askM4a() {
        val ctx = requireContext()
        val checked = if (FormatPrefs.m4aPick(ctx) == FormatPrefs.PICK_SMALL) 1 else 0
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_row_m4a)
            .setMessage(R.string.set_dialog_m4a_msg)
            .setSingleChoiceItems(
                arrayOf(ctx.getString(R.string.q_best), ctx.getString(R.string.q_smallest)),
                checked
            ) { d, which ->
                FormatPrefs.setM4aPick(
                    ctx,
                    if (which == 1) FormatPrefs.PICK_SMALL else FormatPrefs.PICK_BEST
                )
                d.dismiss()
                refreshQualities()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askVideo() {
        val ctx = requireContext()
        val options = intArrayOf(2160, 1080, 720, 480)
        val labels = arrayOf(
            ctx.getString(R.string.q_video_2160),
            ctx.getString(R.string.q_video_1080),
            ctx.getString(R.string.q_video_720),
            ctx.getString(R.string.q_video_480)
        )
        val checked = when (FormatPrefs.videoDefaultHeight(ctx)) {
            2160 -> 0
            720 -> 2
            480 -> 3
            else -> 1
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.set_row_video)
            .setMessage(R.string.settings_mp4_hint)
            .setSingleChoiceItems(labels, checked) { d, which ->
                FormatPrefs.setVideoDefaultHeight(ctx, options[which])
                d.dismiss()
                refreshQualities()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshQualities() {
        val ctx = context ?: return
        binding.tvMp3Sub.text =
            getString(R.string.set_sub_mp3_fmt, FormatPrefs.mp3DefaultBitrate(ctx))
        binding.tvM4aSub.setText(
            if (FormatPrefs.m4aPick(ctx) == FormatPrefs.PICK_SMALL) R.string.q_smallest
            else R.string.q_best
        )
        binding.tvVideoSub.text =
            getString(R.string.set_sub_video_fmt, FormatPrefs.videoDefaultHeight(ctx))
    }

    // ---------- Player ----------

    /** Barrinhas de DJ (v0.18.8): o toggle liga o visualizador REAL do
     *  player — SEM permissão nenhuma (o espectro é lido por dentro do
     *  próprio player). Toque na linha inteira também alterna. */
    private fun bindVisualizer() {
        binding.swVisualizer.isChecked = FormatPrefs.visualizerOn(requireContext())
        binding.swVisualizer.setOnCheckedChangeListener { _, checked ->
            FormatPrefs.setVisualizerOn(requireContext(), checked)
        }
        binding.rowVisualizer.setOnClickListener {
            binding.swVisualizer.toggle()
        }
    }

    // ---------- YouTube (conta, v0.19.8) ----------

    /** A linha NÃO loga por aqui: mostra o status e manda pra aba YouTube —
     *  é LÁ que o login acontece, e a captura da sessão é automática. Com
     *  conta conectada, o toque abre o diálogo de gerenciamento. */
    private fun bindAccount() {
        binding.rowAccount.setOnClickListener {
            val ctx = requireContext()
            if (YtCookies.has(ctx)) {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.acc_dialog_title)
                    .setMessage(R.string.acc_dialog_msg)
                    .setPositiveButton(R.string.acc_open_tab) { _, _ -> openYouTubeTab() }
                    .setNegativeButton(R.string.acc_disconnect) { _, _ ->
                        YtCookies.clear(ctx)
                        Toast.makeText(ctx, R.string.acc_toast_off, Toast.LENGTH_SHORT).show()
                        refreshAccountUi()
                    }
                    .setNeutralButton(R.string.cancel, null)
                    .show()
            } else {
                Toast.makeText(ctx, R.string.acc_hint, Toast.LENGTH_LONG).show()
                openYouTubeTab()
            }
        }
    }

    private fun openYouTubeTab() {
        (activity as? MainActivity)?.openTab(R.id.navYouTube)
    }

    private fun refreshAccountUi() {
        val ctx = context ?: return
        binding.tvAccountSub.setText(
            if (YtCookies.has(ctx)) R.string.set_sub_account_on else R.string.set_sub_account_off
        )
    }

    // ---------- Ajuda ----------

    /** Como usar (v0.19.6): reabre o MESMO tutorial da primeira abertura
     *  (EXTRA_FROM_SETTINGS: ao terminar ele só fecha, sem abrir a Main
     *  de novo — ela já está atrás). */
    private fun bindTutorial() {
        binding.rowTutorial.setOnClickListener {
            startActivity(
                Intent(requireContext(), OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_FROM_SETTINGS, true)
            )
        }
    }

    // ---------- Atualização ----------

    /** Atualização manual (v0.19.3): chama o MESMO UpdateChecker da Central
     *  (mesmo cache/throttle — verificar aqui não martela a API do GitHub).
     *  Achou versão nova? Leva o usuário pra Central de Downloads, onde o
     *  card de baixar/instalar já está esperando. */
    private fun bindUpdate() {
        binding.rowUpdate.setOnClickListener {
            val ctx = requireContext()
            binding.rowUpdate.isEnabled = false
            binding.tvUpdateStatus.setText(R.string.update_checking)
            viewLifecycleOwner.lifecycleScope.launch {
                val info = UpdateChecker.check(ctx)
                if (_binding == null) return@launch // a aba saiu da tela no meio
                binding.rowUpdate.isEnabled = true
                if (info != null) {
                    binding.tvUpdateStatus.text =
                        getString(R.string.update_available, info.version)
                    (activity as? MainActivity)?.openTab(R.id.navDownloads)
                } else {
                    binding.tvUpdateStatus.text =
                        getString(R.string.update_uptodate, BuildConfig.VERSION_NAME)
                }
            }
        }
    }

    // ---------- Sobre ----------

    /** Sobre: versão + assinatura + repositório (abre no navegador do
     *  sistema — a aba YouTube é PRESA ao YouTube de propósito). */
    private fun bindAbout() {
        binding.tvAboutVersion.text =
            getString(R.string.about_version_fmt, BuildConfig.VERSION_NAME) +
                "  ·  " + getString(R.string.about_made_by)
        binding.rowGithub.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/MicaelSanPedro/TuneGrab")
                    )
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
