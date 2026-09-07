package com.tunegrab.app.yt.potoken

import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * Implementação de [PoTokenProvider] do TuneGrab: gera os poTokens com o
 * BotGuard em WebView ([PoTokenManager]) e os entrega ao NewPipeExtractor
 * para os clients WEB (player request garantido), ANDROID e iOS.
 *
 * O contracto do extractor: retornar null significa "sem poToken para este
 * client", e o extractor usa os caminhos anônimos antigos (reel/visionOS/TV).
 * Assim, se o WebView não estiver disponível, o app degrada graciosamente.
 */
object TuneGrabPoTokenProvider : PoTokenProvider {

    private const val TAG = "TuneGrabPoToken"

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = withManager(videoId, "web")

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? =
        withManager(videoId, "android")

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = withManager(videoId, "ios")

    private fun withManager(videoId: String, client: String): PoTokenResult? = try {
        PoTokenManager.getPoTokenResult(videoId)
    } catch (t: Throwable) {
        Log.e(TAG, "poToken ($client) falhou inesperadamente para $videoId", t)
        null
    }
}
