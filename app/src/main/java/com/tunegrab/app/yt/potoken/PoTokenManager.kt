package com.tunegrab.app.yt.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult

/**
 * Gera PoTokens ("proof of origin") do YouTube via BotGuard rodando em um
 * WebView fora da tela — a mesma abordagem usada pelo NewPipe (PoTokenWebView)
 * e pelo bgutil-ytdlp-pot-provider.
 *
 * Por que isso existe: o YouTube passou a exigir verificação anti-bot
 * ("Sign in to confirm you're not a bot") nas requisições anônimas de player
 * de vários IPs, bloqueando o download. Um player request com PoToken válido
 * passa nessa verificação.
 *
 * Pipeline (todo HTTP feito em Kotlin/OkHttp; só a VM roda no WebView):
 *  1. GET da homepage do YouTube -> `ytcfg.set({...})` + `window.ytAtN({...})`
 *     com o desafio atual (programa + interpreterUrl);
 *  2. GET do interpreter JavaScript do BotGuard;
 *  3. WebView carrega [po_token.html] (origin youtube.com, sem rede), injeta
 *     yt.config_ + interpreter + programa e roda o snapshot;
 *  4. POST jnn/v1/GenerateIT -> integrity token (TTL ~12h);
 *  5. poTokens: streaming (ligado ao visitorData, gerado uma vez por sessão)
 *     e player (ligado ao videoId, gerado por vídeo).
 *
 * A API é bloqueante e deve ser chamada de threads de IO (o extractor chama
 * o [PoTokenProvider] dentro de StreamInfo.getInfo). Em caso de falha a
 * sessão é recriada uma vez; se falhar de novo, retorna null e o extractor
 * usa o caminho antigo (reel/visionOS/TVHTML5).
 */
object PoTokenManager {

    private const val TAG = "TuneGrabPoToken"

    // Chave pública usada pelo BotGuard (mesma do NewPipe/bgutils)
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var done = false
        @Volatile var error: Throwable? = null
        @Volatile var result: String? = null

        fun resolve(r: String?, e: Throwable?) {
            synchronized(this) {
                if (done) return
                result = r
                error = e
                done = true
            }
            latch.countDown()
        }
    }

    private val lock = Any()
    private val waitersLock = Any()
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var webViewSupported: Boolean? = null
    private var htmlAsset: String? = null

    // Estado da sessão (protegido por [lock]); WebView só é tocado na main thread
    private var webview: WebView? = null
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var integrityTokenB64: String? = null
    private var expiresAtMillis = 0L

    // Cooldown anti-martelada: o GenerateIT do YouTube tem limite por IP. Renovar
    // a sessão sem parar (retry em cascata) derruba 429 e mata o PoToken justo
    // quando ele é a única rota que passa. Em cooldown, falhamos rápido e de
    // graça, sem tocar no YouTube.
    @Volatile private var cooldownUntilMillis = 0L
    @Volatile private var cooldownSeconds = 60L

    // Diagnóstico: último motivo de falha do pipeline (visível na UI quando a
    // extração falha com bot-check — acabou o erro cego).
    @Volatile private var lastFailure: String? = null

    private var snapshotWaiter: Waiter? = null
    private val mintWaiters = HashMap<String, Waiter>()
    private val playerPotCache = HashMap<String, String>()
    @Volatile private var pageReady: CountDownLatch? = null

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** Força uma sessão nova no próximo pedido (ex.: bot-check persistente). */
    fun invalidate() {
        synchronized(lock) { closeSessionLocked() }
    }

    /** True enquanto estivermos em cooldown pós-falha (não adianta renovar agora). */
    fun inCooldown(): Boolean = System.currentTimeMillis() < cooldownUntilMillis

    /** Último motivo de falha do pipeline de PoToken (null se tudo certo). */
    fun lastFailureMessage(): String? = lastFailure

    /**
     * Devolve o [PoTokenResult] para o vídeo (visitorData + player pot +
     * streaming pot), bloqueando a thread chamante. Retorna null se a geração
     * falhar (WebView indisponível, rede bloqueada, BotGuard alterado…).
     */
    fun getPoTokenResult(videoId: String): PoTokenResult? {
        val looper = Looper.myLooper()
        if (looper != null && looper == Looper.getMainLooper()) {
            Log.w(TAG, "getPoTokenResult chamado na main thread; ignorando para não travar a UI")
            return null
        }
        val ctx = appContext ?: return null
        if (webViewSupported == false) return null
        if (inCooldown()) {
            Log.w(TAG, "PoToken em cooldown pós-falha (${(cooldownUntilMillis - System.currentTimeMillis()) / 1000}s); falhando rápido")
            lastFailure = "em pausa após falha (${(cooldownUntilMillis - System.currentTimeMillis()) / 1000}s)"
            return null
        }
        return try {
            val result = obtain(ctx, videoId, forceRecreate = false)
            onPipelineSuccess()
            result
        } catch (t: Throwable) {
            Log.e(TAG, "PoToken falhou; recriando sessão e tentando de novo", t)
            val rateLimited = t.message?.contains("429") == true
            markFailure(t, armCooldown = rateLimited)
            if (rateLimited || inCooldown()) {
                // 429 do GenerateIT: insistir agora seria martelar o limite do
                // YouTube e piorar o bloqueio. Falhamos rápido de graça.
                return null
            }
            try {
                val result = obtain(ctx, videoId, forceRecreate = true)
                onPipelineSuccess()
                result
            } catch (t2: Throwable) {
                Log.e(TAG, "PoToken falhou após recriar sessão", t2)
                markFailure(t2, armCooldown = true)
                null
            }
        }
    }

    private fun markFailure(t: Throwable, armCooldown: Boolean) {
        lastFailure = t.message ?: t.javaClass.simpleName
        if (armCooldown) {
            // 429 (rate limit) dobra o tempo de pausa a cada ocorrência
            synchronized(lock) {
                if (t.message?.contains("429") == true) {
                    cooldownSeconds = (cooldownSeconds * 2).coerceAtMost(900)
                    Log.w(TAG, "Rate limit do GenerateIT; cooldown de ${cooldownSeconds}s")
                } else {
                    cooldownSeconds = 60
                }
                cooldownUntilMillis = System.currentTimeMillis() + cooldownSeconds * 1000
            }
        }
    }

    private fun onPipelineSuccess() {
        lastFailure = null
        synchronized(lock) {
            cooldownUntilMillis = 0L
            cooldownSeconds = 60L
        }
    }

    private fun obtain(ctx: Context, videoId: String, forceRecreate: Boolean): PoTokenResult {
        synchronized(lock) {
            val expired = System.currentTimeMillis() > expiresAtMillis
            if (forceRecreate || webview == null || expired) {
                recreateSessionLocked(ctx)
            }
            val vd = visitorData ?: throw PoTokenException("sessão sem visitorData")
            val sp = streamingPot ?: throw PoTokenException("sessão sem streaming pot")
            val playerPot = playerPotCache[videoId] ?: mintBlocking(videoId, timeoutMs = 20_000)
                .also { if (playerPotCache.size > 20) playerPotCache.clear() }
                .also { playerPotCache[videoId] = it }
            Log.d(TAG, "poToken para $videoId (${playerPot.length} chars)")
            return PoTokenResult(vd, playerPot, sp)
        }
    }

    //region Sessão

    private fun recreateSessionLocked(ctx: Context) {
        closeSessionLocked()

        // 1. homepage: ytcfg + desafio ytAtN
        val html = httpGetText("https://www.youtube.com/", referer = null)
        val ytcfg = extractYtcfg(html)
            ?: throw PoTokenException("ytcfg não encontrado na homepage")
        val challenge = extractChallenge(html)
            ?: throw PoTokenException("desafio BotGuard não encontrado na homepage")
        val vd = optString(ytcfg, "VISITOR_DATA")
            ?: ytcfg.optJSONObject("INNERTUBE_CONTEXT")?.optJSONObject("client")
                ?.let { optString(it, "visitorData") }
            ?: throw PoTokenException("visitorData ausente no ytcfg")
        visitorData = vd
        Log.d(TAG, "sessão nova: visitorData=${vd.take(16)}… clientVersion=${optString(ytcfg, "INNERTUBE_CLIENT_VERSION")}")

        // 2. interpreter JavaScript do BotGuard
        val interpreterUrl = challenge.interpreterUrl
        val interpreterJs = httpGetText(
            if (interpreterUrl.startsWith("https:")) interpreterUrl else "https:$interpreterUrl",
            referer = "https://www.youtube.com/"
        )

        // 3. snapshot do BotGuard dentro do WebView
        ensureWebViewLocked(ctx)
        val botguardResponse = snapshotBlocking(
            ytcfgJson = ytcfg.toString(),
            interpreterJs = interpreterJs,
            program = challenge.program,
            globalName = challenge.globalName,
            timeoutMs = 30_000
        )

        // 4. integrity token
        val (token, ttlSecs) = generateIt(botguardResponse)
        integrityTokenB64 = token

        // 5. streaming pot primeiro (visitante), depois os player pots por vídeo
        streamingPot = mintBlocking(vd, timeoutMs = 20_000)
        expiresAtMillis = System.currentTimeMillis() +
            TimeUnit.SECONDS.toMillis((ttlSecs - 600).coerceAtLeast(60))
        Log.d(TAG, "sessão pronta (ttl=${ttlSecs}s): streamingPot=${streamingPot?.length} chars")
    }

    private fun closeSessionLocked() {
        val wv = webview
        webview = null
        visitorData = null
        streamingPot = null
        integrityTokenB64 = null
        expiresAtMillis = 0L
        pageReady = null
        playerPotCache.clear()
        snapshotWaiter = null
        synchronized(waitersLock) { mintWaiters.clear() }
        if (wv != null) {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    wv.loadUrl("about:blank")
                    wv.onPause()
                    wv.removeAllViews()
                    wv.destroy()
                } catch (ignored: Throwable) {
                }
                latch.countDown()
            }
            latch.await(5, TimeUnit.SECONDS)
        }
    }

    private fun ensureWebViewLocked(ctx: Context) {
        if (webview != null) return
        if (webViewSupported == null) {
            webViewSupported = try {
                Class.forName("android.webkit.WebView")
                Class.forName("android.webkit.CookieManager")
                true
            } catch (t: Throwable) {
                false
            }
            if (webViewSupported == false) {
                throw PoTokenException("aparelho sem WebView funcional")
            }
        }
        val html = htmlAsset ?: ctx.assets.open("po_token.html").bufferedReader()
            .use { it.readText() }.also { htmlAsset = it }

        val latch = CountDownLatch(1)
        val pageLoaded = CountDownLatch(1)
        var setupError: Throwable? = null
        val posted = mainHandler.post {
            try {
                val wv = WebView(ctx)
                wv.settings.javaScriptEnabled = true
                wv.settings.userAgentString = DESKTOP_UA
                wv.settings.blockNetworkLoads = true // a página não precisa de rede
                wv.addJavascriptInterface(Bridge(), "TgPoInterface")
                wv.webChromeClient = object : WebChromeClient() {}
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded.countDown()
                    }
                }
                wv.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "utf-8", null)
                webview = wv
                pageReady = pageLoaded
            } catch (t: Throwable) {
                setupError = t
            }
            latch.countDown()
        }
        if (!posted || !latch.await(10, TimeUnit.SECONDS)) {
            throw PoTokenException("main thread ocupada ao criar WebView")
        }
        setupError?.let { throw PoTokenException("falha ao criar WebView: ${it.message}", it) }
    }

    private fun snapshotBlocking(
        ytcfgJson: String,
        interpreterJs: String,
        program: String,
        globalName: String,
        timeoutMs: Long
    ): String {
        val waiter = Waiter()
        synchronized(waitersLock) { snapshotWaiter = waiter }
        val args = JSONObject()
            .put("ytcfg", JSONObject(ytcfgJson))
            .put("interpreterJs", interpreterJs)
            .put("program", program)
            .put("globalName", globalName)
        val js = "window.TgPoSnapshot(${JSONObject.quote(args.toString())})"
        // espera a página terminar de carregar, senão TgPoSnapshot nem existe ainda
        val ready = pageReady
        if (ready != null && !ready.await(15, TimeUnit.SECONDS)) {
            waiter.resolve(null, PoTokenException("página do WebView não carregou"))
        }
        dispatchToWebView(js, waiter)
        if (!waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            waiter.resolve(null, PoTokenException("timeout no snapshot do BotGuard"))
        }
        synchronized(waitersLock) { if (snapshotWaiter === waiter) snapshotWaiter = null }
        waiter.error?.let { throw it }
        return waiter.result ?: throw PoTokenException("snapshot vazio")
    }

    private fun mintBlocking(binding: String, timeoutMs: Long): String {
        val token = integrityTokenB64 ?: throw PoTokenException("sessão sem integrity token")
        val id = UUID.randomUUID().toString()
        val waiter = Waiter()
        synchronized(waitersLock) { mintWaiters[id] = waiter }
        val js = "window.TgPoMint(${JSONObject.quote(id)}, ${JSONObject.quote(token)}, " +
            JSONObject.quote(binding) + ")"
        dispatchToWebView(js, waiter)
        if (!waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            waiter.resolve(null, PoTokenException("timeout ao mintar poToken"))
        }
        synchronized(waitersLock) { mintWaiters.remove(id) }
        waiter.error?.let { throw it }
        return waiter.result ?: throw PoTokenException("poToken vazio")
    }

    /** Envia o JS ao WebView na main thread; erros de despacho resolvem o waiter. */
    private fun dispatchToWebView(js: String, waiter: Waiter) {
        val posted = mainHandler.post {
            val wv = webview
            try {
                if (wv == null) {
                    waiter.resolve(null, PoTokenException("WebView destruída"))
                } else {
                    wv.evaluateJavascript(js, null)
                }
            } catch (t: Throwable) {
                waiter.resolve(null, t)
            }
        }
        if (!posted) waiter.resolve(null, PoTokenException("main thread indisponível"))
    }

    //endregion

    //region HTTP (Kotlin side)

    private fun httpGetText(url: String, referer: String?): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "en-US,en;q=0.7")
        if (referer != null) builder.header("Referer", referer)
        httpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200) throw PoTokenException("HTTP ${resp.code} em $url")
            return resp.body?.string() ?: throw PoTokenException("resposta vazia de $url")
        }
    }

    private fun generateIt(botguardResponse: String): Pair<String, Long> {
        val body = JSONArray().put(REQUEST_KEY).put(botguardResponse).toString()
        val req = Request.Builder().url("https://www.youtube.com/api/jnn/v1/GenerateIT")
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", GOOGLE_API_KEY)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw PoTokenException("GenerateIT HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: "[]")
            val token = arr.optString(0, "")
            if (token.isEmpty()) throw PoTokenException("integrity token vazio")
            return token to arr.optLong(1, 43_200L)
        }
    }

    //endregion

    //region Parsing (homepage)

    private fun extractYtcfg(html: String): JSONObject? {
        val match = Regex("""ytcfg\.set\((\{.+?\})\);""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return try {
            JSONObject(match.groupValues[1])
        } catch (t: Throwable) {
            Log.e(TAG, "ytcfg inválido", t)
            null
        }
    }

    private class Challenge(val program: String, val globalName: String, val interpreterUrl: String)

    private fun extractChallenge(html: String): Challenge? {
        val match = Regex("""window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)""").find(html)
            ?: return null
        val attData = try {
            parseLooseJson(match.groupValues[1])
        } catch (t: Throwable) {
            Log.e(TAG, "ytAtN inválido", t)
            return null
        }
        val bg = attData.optJSONObject("R")?.optJSONObject("bgChallenge")
            ?: return null
        val program = bg.optString("program", "")
        val globalName = bg.optString("globalName", "")
        val interpreterUrl = bg.optJSONObject("interpreterUrl")
            ?.optString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", "")
            ?: ""
        if (program.isEmpty() || globalName.isEmpty() || interpreterUrl.isEmpty()) return null
        return Challenge(program, globalName, interpreterUrl)
    }

    /**
     * Porta do parseLooseJSON do bgutils-js (MIT): o payload do ytAtN usa aspas
     * simples, escapes \xHH, vírgulas sobrando e valores JSON serializados.
     */
    private fun parseLooseJson(raw: String): JSONObject {
        var s = Regex("""\\x([0-9A-Fa-f]{2})""").replace(raw) { m ->
            val code = m.groupValues[1].toInt(16)
            code.toChar().toString()
        }
        s = Regex(""",\s*([}\]])""").replace(s) { m -> m.groupValues[1] }
        s = Regex("""'((?:[^'\\]|\\[\s\S])*)'""").replace(s) { m ->
            val inner = m.groupValues[1].replace("\\'", "'")
            JSONObject.quote(inner)
        }
        s = Regex("""([{,]\s*)([a-zA-Z0-9_$]+)\s*:""").replace(s) { m ->
            m.groupValues[1] + "\"" + m.groupValues[2] + "\":"
        }
        val obj = JSONObject(s)
        for (key in obj.keys().asSequence().toList()) {
            val value = obj.opt(key)
            if (value is String) {
                val trimmed = value.trimStart()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        obj.put(key, JSONObject(value))
                    } catch (e: Throwable) {
                        try {
                            obj.put(key, JSONArray(value))
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            }
        }
        return obj
    }

    private fun optString(obj: JSONObject, key: String): String? {
        val v = obj.optString(key, "")
        return v.ifEmpty { null }
    }

    //endregion

    /** Ponte JavaScript -> Kotlin (chamado pela WebView em thread própria). */
    private class Bridge {
        @JavascriptInterface
        fun onSnapshot(response: String) {
            synchronized(waitersLock) { snapshotWaiter }?.resolve(response, null)
        }

        @JavascriptInterface
        fun onSnapshotError(err: String) {
            Log.e(TAG, "erro JS no snapshot: $err")
            synchronized(waitersLock) { snapshotWaiter }?.resolve(null, PoTokenException(err))
        }

        @JavascriptInterface
        fun onMinted(id: String, poToken: String) {
            synchronized(waitersLock) { mintWaiters[id] }?.resolve(poToken, null)
        }

        @JavascriptInterface
        fun onMintError(id: String, err: String) {
            Log.e(TAG, "erro JS ao mintar: $err")
            synchronized(waitersLock) { mintWaiters[id] }?.resolve(null, PoTokenException(err))
        }
    }
}

class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)
