package com.tunegrab.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tunegrab.app.DownloadRequest
import com.tunegrab.app.FormatPickerSheet
import com.tunegrab.app.FormatPrefs
import com.tunegrab.app.MainActivity
import com.tunegrab.app.PlaylistSheet
import com.tunegrab.app.PlayerActivity
import com.tunegrab.app.R
import com.tunegrab.app.TuneGrabApp
import com.tunegrab.app.databinding.FragmentHomeBinding
import com.tunegrab.app.download.DownloadService
import com.tunegrab.app.yt.YtExtractor
import com.tunegrab.app.yt.potoken.PoTokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Aba Início: cole o link, escolha formato/qualidade e baixe.
 * (Mesmo fluxo da antiga MainActivity, agora como aba do single-activity.)
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private var busy = false

    /** Ação guardada enquanto o usuário responde o pedido de permissão. */
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val action = pendingAction
            pendingAction = null
            // prossegue mesmo se negar: o download funciona, só a notificação é afetada
            action?.invoke()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnDownload.setOnClickListener { onDownloadClicked() }
        binding.btnPlay.setOnClickListener { onPlayClicked() }
        binding.btnSettings.setOnClickListener { (activity as? MainActivity)?.openTab(R.id.navSettings) }
        binding.tilUrl.setEndIconOnClickListener { pasteFromClipboard() }
        binding.inputUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                onDownloadClicked(); true
            } else {
                false
            }
        }
        setStatus(getString(R.string.status_idle))
        // link compartilhado que chegou antes desta aba existir
        (activity as? MainActivity)?.consumePendingSharedUrl()?.let { handleSharedUrl(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Link vindo de "Compartilhar → TuneGrab" ou de abrir uma URL do YouTube. */
    fun handleSharedUrl(shared: String) {
        binding.inputUrl.setText(shared)
        // link compartilhado do YouTube → abre o seletor de formato direto
        onDownloadClicked()
    }

    /**
     * Fluxo principal: busca as informações do vídeo e abre o seletor
     * de formato (MP3 / M4A / MP4) e qualidade.
     */
    private fun onDownloadClicked() {
        if (busy) return
        val url = extractUrl()
        if (url.isNullOrBlank()) {
            setStatus(getString(R.string.err_invalid_url))
            return
        }
        // PLAYLIST pura (youtube.com/playlist?list=…) tem fluxo próprio:
        // escolhe formato UMA vez, os vídeos entram na fila um a um —
        // pelo MESMO caminho de download único (motor intocado)
        if (YtExtractor.isPlaylistUrl(url)) {
            startPlaylist(url)
            return
        }
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        setStatus(getString(R.string.status_fetching))

        lifecycleScope.launch {
            try {
                val verified = fetchAndVerify(url)
                if (verified.audio.isEmpty() && verified.video.isEmpty()) {
                    setStatus(getString(R.string.err_streams_blocked))
                    return@launch
                }
                setStatus(getString(R.string.status_pick_format))
                FormatPickerSheet(
                    requireContext(),
                    verified.info,
                    verified.audio,
                    verified.video
                ) { request ->
                    ensurePermissionsThen { start(request) }
                }.show()
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao buscar vídeo", t)
                setStatus(getString(R.string.err_generic, friendlyError(t)))
            } finally {
                _binding?.progress?.visibility = View.GONE
                setBusy(false)
            }
        }
    }

    /**
     * REPRODUZIR sem baixar: cola o link, o app extrai as faixas (a MESMA
     * extração de sempre, em modo leitura — nada toca no motor de download)
     * e abre o player com a melhor faixa MP4 progressiva (vídeo+áudio juntos)
     * que passou no teste de URL. Sem faixa de vídeo? Toca o áudio.
     */
    private fun onPlayClicked() {
        if (busy) return
        val url = extractUrl()
        if (url.isNullOrBlank()) {
            setStatus(getString(R.string.err_invalid_url))
            return
        }
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        setStatus(getString(R.string.status_fetching))

        lifecycleScope.launch {
            try {
                val verified = fetchAndVerify(url)
                val title = verified.info.name
                val video = verified.video.firstOrNull()
                val audio = verified.audio.firstOrNull()
                when {
                    // faixa progressiva MP4 (vídeo+áudio embutidos): o MediaPlayer
                    // do player lê direto da rede, sem salvar NADA
                    video != null -> {
                        setStatus(getString(R.string.status_opening_player))
                        startActivity(PlayerActivity.remoteVideo(requireContext(), video.url!!, title))
                        setStatus(getString(R.string.status_idle))
                    }
                    // só sobrou faixa de áudio (raro): toca a música no player
                    audio != null -> {
                        setStatus(getString(R.string.status_opening_player))
                        startActivity(PlayerActivity.remoteAudio(requireContext(), audio.url!!, title))
                        setStatus(getString(R.string.status_idle))
                    }
                    else -> setStatus(getString(R.string.err_no_stream_play))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao buscar vídeo para reproduzir", t)
                setStatus(getString(R.string.err_generic, friendlyError(t)))
            } finally {
                _binding?.progress?.visibility = View.GONE
                setBusy(false)
            }
        }
    }

    private class VerifiedStreams(
        val info: StreamInfo,
        val audio: List<AudioStream>,
        val video: List<VideoStream>
    )

    /**
     * Busca o vídeo e testa cada URL de stream antes de oferecer — o YouTube
     * às vezes entrega URLs que rejeitam o download (HTTP 403). Se TODAS
     * falharem, renova a sessão de PoToken (a atual pode ter sido queimada)
     * e busca de novo uma vez, tudo automático — mas sem renovar se o pipeline
     * estiver em pausa pós-falha (rate limit), que seria gasolina no fogo.
     *
     * Suspend: cuida do próprio dispatch (rede em IO, setStatus na main).
     */
    private suspend fun fetchAndVerify(url: String): VerifiedStreams {
        var info = withContext(Dispatchers.IO) { fetchWithRetry(url) }
        setStatus(getString(R.string.status_checking))
        var audio = withContext(Dispatchers.IO) { YtExtractor.workingAudio(info) }
        var video = withContext(Dispatchers.IO) { YtExtractor.workingVideo(info) }
        if (audio.isEmpty() && video.isEmpty() && !PoTokenManager.inCooldown()) {
            Log.w(TAG, "Todas as URLs falharam no teste; renovando sessão de PoToken e buscando de novo")
            withContext(Dispatchers.IO) { PoTokenManager.invalidate() }
            info = withContext(Dispatchers.IO) { fetchWithRetry(url) }
            setStatus(getString(R.string.status_checking))
            audio = withContext(Dispatchers.IO) { YtExtractor.workingAudio(info) }
            video = withContext(Dispatchers.IO) { YtExtractor.workingVideo(info) }
        }
        return VerifiedStreams(info, audio, video)
    }

    /**
     * Busca o vídeo com retry automático: o bot-check do YouTube
     * ("Sign in to confirm you're not a bot") é intermitente, e tentar
     * novamente costuma passar. A sessão de PoToken só é renovada no 1º
     * bot-check — renovar em toda tentativa martela o GenerateIT (que tem
     * limite por IP) e mata o token justo quando ele é a única rota.
     */
    private suspend fun fetchWithRetry(url: String, maxAttempts: Int = 3): StreamInfo {
        var last: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return YtExtractor.fetch(url)
            } catch (t: Throwable) {
                last = t
                if (t !is SignInConfirmNotBotException || attempt == maxAttempts - 1) throw t
                if (attempt == 0) {
                    Log.w(TAG, "Bot-check do YouTube na 1ª tentativa; renovando sessão de PoToken")
                    withContext(Dispatchers.IO) { PoTokenManager.invalidate() }
                    delay(5000)
                } else {
                    Log.w(TAG, "Bot-check na ${attempt + 1}ª tentativa; mantendo a sessão e aguardando mais")
                    delay(10_000)
                }
            }
        }
        throw last ?: IllegalStateException("fetch falhou sem exceção")
    }

    private fun start(request: DownloadRequest) {
        val prepared = intentFor(request) ?: return
        launchService(prepared.first)
        setStatus(prepared.second)
    }

    /**
     * Monta o intent do DownloadService para um pedido — MESMOS parâmetros
     * de sempre (o motor de download não mudou NADA). Usa contexto do APP,
     * não da aba: a fila da playlist roda fora do ciclo de vida do fragment
     * e continua enfileirando mesmo com a aba Início fechada. Devolve também
     * o texto de status do fluxo único (a playlist ignora).
     */
    private fun intentFor(request: DownloadRequest): Pair<Intent, String>? {
        val appCtx = context?.applicationContext ?: return null
        return when (request) {
            is DownloadRequest.Mp3 -> {
                val url = request.source.url
                if (url.isNullOrBlank()) {
                    Toast.makeText(appCtx, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
                    return null
                }
                val fileName = sanitize(request.title) + ".mp3"
                val intent = DownloadService.mp3Intent(appCtx, request.title, url, fileName, request.bitrateKbps)
                if (request.videoUrl != null) {
                    intent.putExtra(DownloadService.EXTRA_VIDEO_URL, request.videoUrl)
                    intent.putExtra(DownloadService.EXTRA_FORMAT, "mp3")
                }
                intent to appCtx.getString(R.string.status_converting, "${request.bitrateKbps}")
            }
            is DownloadRequest.M4a -> directIntent(
                appCtx,
                request.title,
                request.stream,
                request.videoUrl,
                suffix = request.stream.format?.suffix ?: "m4a",
                mime = request.stream.format?.mimeType ?: "audio/mp4",
                engineFormat = "m4a",
                engineBitrate = request.stream.averageBitrate
            )
            is DownloadRequest.Webm -> directIntent(
                appCtx,
                request.title,
                request.stream,
                request.videoUrl,
                suffix = request.stream.format?.suffix ?: "webm",
                mime = request.stream.format?.mimeType ?: "audio/webm",
                engineFormat = "opus",
                engineBitrate = request.stream.averageBitrate
            )
            is DownloadRequest.Mp4 -> directIntent(
                appCtx,
                request.title,
                request.stream,
                request.videoUrl,
                // contêiner acompanha o pedido: >1080p sai em MKV (VP9/AV1
                // dentro de MP4 o Android não lê); ≤1080p sai em MP4/H.264
                suffix = if (request.height > 1080) "mkv" else "mp4",
                mime = if (request.height > 1080) {
                    "video/x-matroska"
                } else {
                    request.stream?.format?.mimeType ?: "video/mp4"
                },
                engineFormat = "mp4",
                // altura ESCOLHIDA (pode ser 1080p via merge do yt-dlp);
                // a faixa combinada vai só como plano B (limitado a 720p)
                maxHeight = request.height
            )
        }
    }

    /** Faixa direta (M4a/Webm/Mp4): mesma montagem de sempre — o stream pode
     *  ser nulo quando só o plano A (yt-dlp com a URL do vídeo) dá conta. */
    private fun directIntent(
        appCtx: Context,
        title: String,
        stream: Any?,
        videoUrl: String?,
        suffix: String,
        mime: String,
        engineFormat: String,
        engineBitrate: Int = 0,
        maxHeight: Int = 0
    ): Pair<Intent, String>? {
        val url = when (stream) {
            is AudioStream -> stream.url
            is VideoStream -> stream.url
            else -> null
        }
        // stream/url nulos são ok quando há videoUrl: o plano A (yt-dlp) baixa
        // só com a URL do vídeo — MP4 nunca fica bloqueado por extração falha
        if (url.isNullOrBlank() && videoUrl.isNullOrBlank()) {
            Toast.makeText(appCtx, R.string.err_no_audio, Toast.LENGTH_SHORT).show()
            return null
        }
        val fileName = sanitize(title) + "." + suffix
        val intent = DownloadService.intent(appCtx, title, url ?: "", fileName, mime)
        if (videoUrl != null) {
            // plano A: yt-dlp embutido; a URL direta fica de plano B no intent
            intent.putExtra(DownloadService.EXTRA_VIDEO_URL, videoUrl)
            intent.putExtra(DownloadService.EXTRA_FORMAT, engineFormat)
            if (maxHeight > 0) intent.putExtra(DownloadService.EXTRA_MAX_HEIGHT, maxHeight)
            if (engineBitrate > 0) intent.putExtra(DownloadService.EXTRA_BITRATE, engineBitrate)
        }
        return intent to appCtx.getString(R.string.status_downloading)
    }

    private fun launchService(intent: Intent) {
        // contexto do APP: funciona até com a aba fechada (fila da playlist)
        val ctx = context?.applicationContext ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, intent)
        } else {
            ctx.startService(intent)
        }
    }

    // ---------- playlist (v0.13.0) ----------

    /** Fluxo da PLAYLIST: busca metadados e abre o seletor (formato/qualidade
     *  UMA vez pra tudo). Nada é baixado até o usuário confirmar. */
    private fun startPlaylist(url: String) {
        if (busy) return
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        setStatus(getString(R.string.pl_fetching))

        lifecycleScope.launch {
            try {
                val pl = withContext(Dispatchers.IO) { YtExtractor.fetchPlaylist(url) }
                if (_binding == null) return@launch
                if (pl.items.isEmpty()) {
                    setStatus(getString(R.string.pl_empty))
                    return@launch
                }
                setStatus(getString(R.string.status_pick_format))
                ensurePermissionsThen {
                    val ctx = context ?: return@ensurePermissionsThen
                    var sheet: PlaylistSheet? = null
                    sheet = PlaylistSheet(ctx, pl) { format, quality ->
                        sheet?.let { runPlaylist(pl, format, quality, it) }
                    }
                    sheet.show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao buscar playlist", t)
                setStatus(getString(R.string.err_generic, friendlyError(t)))
            } finally {
                _binding?.progress?.visibility = View.GONE
                setBusy(false)
            }
        }
    }

    /**
     * Prepara os vídeos UM A UM e enfileira cada um pelo MESMO intentFor do
     * download único. Roda no escopo do APP: trocar de aba no meio não aborta
     * (a aba é replace(); o app não morre). Respiro de 1,2s entre extrações
     * pra não martelar o YouTube; bot-check insistente = parada limpa, e o
     * que já entrou na fila continua baixando normalmente.
     */
    private fun runPlaylist(
        pl: YtExtractor.PlaylistMeta,
        format: String,
        quality: String,
        sheet: PlaylistSheet
    ) {
        val total = pl.items.size
        var ok = 0
        var fail = 0
        TuneGrabApp.appScope.launch {
            for ((idx, item) in pl.items.withIndex()) {
                if (sheet.cancelled) return@launch
                sheet.setProgress(idx, total, item.name)
                try {
                    val info = fetchWithRetry(item.url)
                    val request = playlistRequest(info, format, quality)
                    val prepared = request?.let { intentFor(it) }
                    if (prepared == null) throw IllegalStateException("sem faixa utilizável")
                    launchService(prepared.first)
                    ok++
                } catch (t: Throwable) {
                    Log.w(TAG, "playlist: '${item.name}' falhou", t)
                    fail++
                    // bot-check depois dos re-tentativas: para bonito — a fila
                    // mantém o que já entrou e o usuário tenta de novo depois
                    if (t is SignInConfirmNotBotException) {
                        sheet.stopped(idx + 1, total)
                        return@launch
                    }
                }
                delay(1200)
            }
            sheet.finished(ok, fail)
        }
    }

    /** Pedido de download de um vídeo da playlist — MESMA semântica do
     *  seletor único: MP3 converte do melhor áudio; M4A/Opus resolvem a
     *  qualidade vídeo a vídeo (best = maior bitrate, small = menor); MP4
     *  usa o plano A (URL) + melhor combinada ≤ altura como plano B. */
    private fun playlistRequest(
        info: StreamInfo,
        format: String,
        quality: String
    ): DownloadRequest? {
        val videoUrl = info.originalUrl ?: info.url
        val audio = YtExtractor.audioOptions(info)
        val video = YtExtractor.videoOptions(info)
        return when (format) {
            FormatPrefs.FORMAT_MP3 -> {
                val src = audio.firstOrNull { it.format?.name == "M4A" }
                    ?: audio.firstOrNull()
                    ?: return null
                DownloadRequest.Mp3(info.name, src, quality.toIntOrNull() ?: 320, videoUrl)
            }
            FormatPrefs.FORMAT_M4A -> {
                val m4a = audio.filter { it.format?.name == "M4A" }
                    .sortedByDescending { it.averageBitrate }
                val stream = (if (quality == FormatPrefs.PICK_SMALL) {
                    m4a.lastOrNull()
                } else {
                    m4a.firstOrNull()
                }) ?: return null
                DownloadRequest.M4a(info.name, stream, videoUrl)
            }
            FormatPrefs.FORMAT_OPUS -> {
                val opus = audio.filter {
                    it.format == org.schabi.newpipe.extractor.MediaFormat.WEBMA ||
                        it.format == org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS
                }.sortedByDescending { it.averageBitrate }
                val stream = opus.firstOrNull() ?: return null
                DownloadRequest.Webm(info.name, stream, videoUrl)
            }
            else -> {
                val chosen = quality.toIntOrNull() ?: return null
                // Plano B (URL direta) só sabe baixar faixa COM áudio: usa a
                // combinada mais próxima abaixo da altura escolhida
                val stream = video.filter { it.height <= chosen }
                    .maxByOrNull { it.height }
                    ?: video.minByOrNull { it.height }
                DownloadRequest.Mp4(info.name, stream, chosen, videoUrl)
            }
        }
    }

    private fun ensurePermissionsThen(action: () -> Unit) {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) {
            pendingAction = action
            permissionLauncher.launch(needed.toTypedArray())
            return
        }
        action()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        binding.btnDownload.isEnabled = !b
        binding.btnPlay.isEnabled = !b
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(80)
            .ifBlank { "audio" }

    private fun friendlyError(t: Throwable): String = when (t) {
        is ReCaptchaException -> getString(R.string.err_recaptcha)
        is ContentNotAvailableException -> getString(R.string.err_unavailable)
        // antes de ParsingException: é subclasse dela, e a mensagem genérica
        // "Link inválido" enganaria o usuário — o problema é o YouTube, não a URL
        is SignInConfirmNotBotException -> when {
            // em pausa pós-rate-limit: renovar agora piora; orientar a esperar
            PoTokenManager.inCooldown() -> getString(R.string.err_bot_check_cooldown)
            // com o motivo real da falha do token o erro deixa de ser cego
            PoTokenManager.lastFailureMessage() != null ->
                getString(R.string.err_bot_check_reason, PoTokenManager.lastFailureMessage())
            else -> getString(R.string.err_bot_check)
        }
        is ParsingException, is IllegalArgumentException -> getString(R.string.err_invalid_url)
        else -> "${t.javaClass.simpleName}: ${t.message ?: getString(R.string.err_generic_short)}"
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun extractUrl(): String? =
        Regex("https?://\\S+").find(binding.inputUrl.text?.toString().orEmpty())
            ?.value
            ?.trim('"', '\'', ')', '>', '<')

    private fun pasteFromClipboard() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(context, R.string.paste_empty, Toast.LENGTH_SHORT).show()
            return
        }
        binding.inputUrl.setText(text.trim())
    }

    companion object {
        private const val TAG = "TuneGrab"
    }
}
