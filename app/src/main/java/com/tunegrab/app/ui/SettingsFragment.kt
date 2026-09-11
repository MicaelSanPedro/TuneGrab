package com.tunegrab.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
 * Configurações (v0.19.10 — "só aparece o que cada config faz", pedido do
 * autor): as opções ficam VISÍVEIS na página — linha com título + sub +
 * bolinha de seleção, toque escolhe e salva NA HORA (igual às configs do
 * YouTube de verdade). Nada de diálogo: escolher é tocar.
 *
 *  - DOWNLOADS: pasta de salvamento (SAF) + voltar ao padrão + aviso de
 *    dados móveis (switch inline, aplicado de verdade no Início).
 *  - ÁUDIO: qualidade do MP3 (320/256/192/128) e do M4A (melhor/menor)
 *    como linhas de radio inline.
 *  - VÍDEO: escada completa 4K→360p como linhas de radio inline.
 *  - PLAYER: barrinhas de áudio (switch inline na própria linha).
 *  - YOUTUBE: status da conta — o LOGIN é automático na aba YouTube
 *    (capturado pelo [YoutubeFragment]); daqui só se vê, abre a aba ou
 *    desconecta.
 *  - AJUDA: reabrir o tutorial de primeira abertura.
 *  - ATUALIZAÇÃO: verificação manual — achou versão nova? Leva pro
 *    INÍCIO, onde o card de instalação vive (v0.19.9).
 *  - SOBRE: versão e motores — SEM link externo ("não deixe rastros de
 *    que esse app foi feito no github").
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

    /** Back do sistema dentro de uma página = volta pra lista de pastas. */
    private val backToRoot = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (_binding != null) showRoot()
        }
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
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToRoot)
        bindCategories()
        bindFolder()
        bindMetered()
        bindQualities()
        bindVisualizer()
        bindAutoplay()
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

    // ---------- Navegação por pastas (estilo YouTube) ----------

    private companion object {
        const val PAGE_DOWNLOADS = 0
        const val PAGE_AUDIO = 1
        const val PAGE_VIDEO = 2
        const val PAGE_PLAYER = 3
        const val PAGE_YOUTUBE = 4
        const val PAGE_HELP = 5
        const val PAGE_UPDATE = 6
        const val PAGE_ABOUT = 7
    }

    private fun bindCategories() {
        binding.catDownloads.setOnClickListener { openCategory(PAGE_DOWNLOADS) }
        binding.catAudio.setOnClickListener { openCategory(PAGE_AUDIO) }
        binding.catVideo.setOnClickListener { openCategory(PAGE_VIDEO) }
        binding.catPlayer.setOnClickListener { openCategory(PAGE_PLAYER) }
        binding.catYoutube.setOnClickListener { openCategory(PAGE_YOUTUBE) }
        binding.catHelp.setOnClickListener { openCategory(PAGE_HELP) }
        binding.catUpdate.setOnClickListener { openCategory(PAGE_UPDATE) }
        binding.catAbout.setOnClickListener { openCategory(PAGE_ABOUT) }
        binding.btnBack.setOnClickListener { showRoot() }
    }

    /** Toca numa pasta: cabeçalho vira "voltar + título", o conteúdo da
     *  lista sai e a página do assunto ENTRA deslizando (como no YouTube). */
    private fun openCategory(page: Int) {
        val b = _binding ?: return
        b.headerRoot.visibility = View.GONE
        b.pageRoot.visibility = View.GONE
        b.headerDetail.visibility = View.VISIBLE
        b.tvPageTitle.setText(
            when (page) {
                PAGE_DOWNLOADS -> R.string.set_cat_downloads
                PAGE_AUDIO -> R.string.set_cat_audio
                PAGE_VIDEO -> R.string.set_cat_video
                PAGE_PLAYER -> R.string.set_cat_player
                PAGE_YOUTUBE -> R.string.set_cat_youtube
                PAGE_HELP -> R.string.set_cat_help
                PAGE_UPDATE -> R.string.set_cat_update
                else -> R.string.set_cat_about
            }
        )
        // só a página do assunto fica visível; as outras dormem
        b.pageDownloads.root.visibility = if (page == PAGE_DOWNLOADS) View.VISIBLE else View.GONE
        b.pageAudio.root.visibility = if (page == PAGE_AUDIO) View.VISIBLE else View.GONE
        b.pageVideo.root.visibility = if (page == PAGE_VIDEO) View.VISIBLE else View.GONE
        b.pagePlayer.root.visibility = if (page == PAGE_PLAYER) View.VISIBLE else View.GONE
        b.pageYoutube.root.visibility = if (page == PAGE_YOUTUBE) View.VISIBLE else View.GONE
        b.pageHelp.root.visibility = if (page == PAGE_HELP) View.VISIBLE else View.GONE
        b.pageUpdate.root.visibility = if (page == PAGE_UPDATE) View.VISIBLE else View.GONE
        b.pageAbout.root.visibility = if (page == PAGE_ABOUT) View.VISIBLE else View.GONE
        b.pageDetail.alpha = 0f
        b.pageDetail.translationX = 64f
        b.pageDetail.visibility = View.VISIBLE
        b.pageDetail.animate().alpha(1f).translationX(0f)
            .setDuration(220L).setInterpolator(DecelerateInterpolator(1.6f)).start()
        b.pageDetail.scrollTo(0, 0)
        backToRoot.isEnabled = true
    }

    /** Volta pra lista de pastas (botão do cabeçalho ou back do sistema). */
    private fun showRoot() {
        val b = _binding ?: return
        b.pageDetail.visibility = View.GONE
        b.headerDetail.visibility = View.GONE
        b.headerRoot.visibility = View.VISIBLE
        b.pageRoot.alpha = 0f
        b.pageRoot.visibility = View.VISIBLE
        b.pageRoot.animate().alpha(1f).setDuration(180L).start()
        backToRoot.isEnabled = false
    }

    // ---------- Downloads ----------

    private fun bindFolder() {
        binding.pageDownloads.rowFolder.setOnClickListener { pickFolder.launch(null) }
        binding.pageDownloads.rowFolderReset.setOnClickListener {
            SaveLocation.setCustomTree(requireContext(), null)
            refreshFolderUi()
        }
    }

    /** Atualiza o subtítulo da linha E da pasta na lista principal. */
    private fun refreshFolderUi() {
        val ctx = context ?: return
        val tree = SaveLocation.customTree(ctx)
        val text: String
        val resetVisible: Boolean
        if (tree == null) {
            text = getString(R.string.settings_folder_default)
            resetVisible = false
        } else {
            val name = try {
                DocumentFile.fromTreeUri(ctx, tree)?.name
            } catch (t: Throwable) {
                null
            }
            text = getString(
                R.string.settings_folder_custom,
                name ?: getString(R.string.lib_folder_unknown)
            )
            resetVisible = true
        }
        binding.pageDownloads.tvFolderCurrent.text = text
        binding.pageDownloads.rowFolderReset.visibility =
            if (resetVisible) View.VISIBLE else View.GONE
        binding.tvCatDownloadsSub.text = text
    }

    /** Aviso de dados móveis (v0.19.10): switch INLINE na própria página —
     *  o Início lê essa preferência antes de começar qualquer download.
     *  Toque na linha inteira também alterna. */
    private fun bindMetered() {
        binding.pageDownloads.swMetered.isChecked =
            FormatPrefs.meteredWarningOn(requireContext())
        binding.pageDownloads.swMetered.setOnCheckedChangeListener { _, checked ->
            FormatPrefs.setMeteredWarningOn(requireContext(), checked)
        }
        binding.pageDownloads.rowMetered.setOnClickListener {
            binding.pageDownloads.swMetered.toggle()
        }
    }

    /** Qualidades como linhas de RADIO INLINE (v0.19.10): a escolhida fica
     *  com a bolinha roxa marcada — toque escolhe e salva na hora, sem
     *  diálogo. As bolinhas são só visuais (clickable=false no estilo): a
     *  linha inteira é quem responde. */
    private fun bindQualities() {
        val a = binding.pageAudio
        a.rowMp3320.setOnClickListener { pickMp3(320) }
        a.rowMp3256.setOnClickListener { pickMp3(256) }
        a.rowMp3192.setOnClickListener { pickMp3(192) }
        a.rowMp3128.setOnClickListener { pickMp3(128) }
        a.rowM4aBest.setOnClickListener {
            FormatPrefs.setM4aPick(requireContext(), FormatPrefs.PICK_BEST)
            refreshQualities()
        }
        a.rowM4aSmall.setOnClickListener {
            FormatPrefs.setM4aPick(requireContext(), FormatPrefs.PICK_SMALL)
            refreshQualities()
        }
        val v = binding.pageVideo
        v.rowV2160.setOnClickListener { pickVideo(2160) }
        v.rowV1440.setOnClickListener { pickVideo(1440) }
        v.rowV1080.setOnClickListener { pickVideo(1080) }
        v.rowV720.setOnClickListener { pickVideo(720) }
        v.rowV480.setOnClickListener { pickVideo(480) }
        v.rowV360.setOnClickListener { pickVideo(360) }
    }

    private fun pickMp3(kbps: Int) {
        FormatPrefs.setMp3DefaultBitrate(requireContext(), kbps)
        refreshQualities()
    }

    private fun pickVideo(height: Int) {
        FormatPrefs.setVideoDefaultHeight(requireContext(), height)
        refreshQualities()
    }

    /** Marca a bolinha da escolha atual em Áudio e Vídeo. */
    private fun refreshQualities() {
        val ctx = context ?: return
        val a = binding.pageAudio
        val mp3 = FormatPrefs.mp3DefaultBitrate(ctx)
        a.rMp3320.isChecked = mp3 == 320
        a.rMp3256.isChecked = mp3 == 256
        a.rMp3192.isChecked = mp3 == 192
        a.rMp3128.isChecked = mp3 == 128
        val best = FormatPrefs.m4aPick(ctx) == FormatPrefs.PICK_BEST
        a.rM4aBest.isChecked = best
        a.rM4aSmall.isChecked = !best
        val v = binding.pageVideo
        val h = FormatPrefs.videoDefaultHeight(ctx)
        v.rV2160.isChecked = h == 2160
        v.rV1440.isChecked = h == 1440
        v.rV1080.isChecked = h == 1080
        v.rV720.isChecked = h == 720
        v.rV480.isChecked = h == 480
        v.rV360.isChecked = h == 360
    }

    // ---------- Player ----------

    /** Barrinhas de DJ (v0.18.8): o toggle liga o visualizador REAL do
     *  player — SEM permissão nenhuma (o espectro é lido por dentro do
     *  próprio player). Toque na linha inteira também alterna. */
    private fun bindVisualizer() {
        binding.pagePlayer.swVisualizer.isChecked = FormatPrefs.visualizerOn(requireContext())
        binding.pagePlayer.swVisualizer.setOnCheckedChangeListener { _, checked ->
            FormatPrefs.setVisualizerOn(requireContext(), checked)
        }
        binding.pagePlayer.rowVisualizer.setOnClickListener {
            binding.pagePlayer.swVisualizer.toggle()
        }
    }

    /** Reprodução automática (v0.19.16): na LISTA PRINCIPAL (pedido do
     *  autor), define o PADRÃO do botão no player — desligada, o player
     *  abre com o ciclo desligado (dá pra ligar na hora, vale até fechar);
     *  ligada, abre já ciclando a fila. O toggle NO player nunca escreve
     *  aqui. Toque na linha inteira também alterna. */
    private fun bindAutoplay() {
        binding.swAutoplay.isChecked = FormatPrefs.autoplayDefault(requireContext())
        binding.swAutoplay.setOnCheckedChangeListener { _, checked ->
            FormatPrefs.setAutoplayDefault(requireContext(), checked)
        }
        binding.rowAutoplay.setOnClickListener {
            binding.swAutoplay.toggle()
        }
    }

    // ---------- YouTube (conta) ----------

    /** A linha NÃO loga por aqui: mostra o status e manda pra aba YouTube —
     *  é LÁ que o login acontece, e a captura da sessão é automática. Com
     *  conta conectada, o toque abre o diálogo de gerenciamento. */
    private fun bindAccount() {
        binding.pageYoutube.rowAccount.setOnClickListener {
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

    /** Status na página E na pasta da lista principal. */
    private fun refreshAccountUi() {
        val ctx = context ?: return
        val res = if (YtCookies.has(ctx)) R.string.set_sub_account_on else R.string.set_sub_account_off
        binding.pageYoutube.tvAccountSub.setText(res)
        binding.tvCatYoutubeSub.setText(res)
    }

    // ---------- Ajuda ----------

    /** Como usar (v0.19.6): reabre o MESMO tutorial da primeira abertura
     *  (EXTRA_FROM_SETTINGS: ao terminar ele só fecha, sem abrir a Main
     *  de novo — ela já está atrás). */
    private fun bindTutorial() {
        binding.pageHelp.rowTutorial.setOnClickListener {
            startActivity(
                Intent(requireContext(), OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_FROM_SETTINGS, true)
            )
        }
    }

    // ---------- Atualização ----------

    /** Atualização manual: chama o MESMO UpdateChecker do card do Início
     *  (mesmo cache/throttle). Achou versão nova? Leva pro INÍCIO do app,
     *  onde o card de baixar/instalar já está esperando (v0.19.9). */
    private fun bindUpdate() {
        binding.pageUpdate.rowUpdate.setOnClickListener {
            val ctx = requireContext()
            binding.pageUpdate.rowUpdate.isEnabled = false
            binding.pageUpdate.tvUpdateStatus.setText(R.string.update_checking)
            viewLifecycleOwner.lifecycleScope.launch {
                val info = UpdateChecker.check(ctx)
                if (_binding == null) return@launch // a aba saiu da tela no meio
                binding.pageUpdate.rowUpdate.isEnabled = true
                if (info != null) {
                    binding.pageUpdate.tvUpdateStatus.text =
                        getString(R.string.update_available, info.version)
                    (activity as? MainActivity)?.openTab(R.id.navHome)
                } else {
                    binding.pageUpdate.tvUpdateStatus.text =
                        getString(R.string.update_uptodate, BuildConfig.VERSION_NAME)
                }
            }
        }
    }

    // ---------- Sobre ----------

    /** Sobre: versão + assinatura. Sem GitHub, sem link externo. */
    private fun bindAbout() {
        binding.pageAbout.tvAboutVersion.text =
            getString(R.string.about_version_fmt, BuildConfig.VERSION_NAME) +
                "  ·  " + getString(R.string.about_made_by)
    }
}
