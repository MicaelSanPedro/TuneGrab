package com.tunegrab.app

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale

/**
 * LOGIN DO YOUTUBE VIA COOKIES (v0.18.6) — a cura definitiva do bot-check.
 *
 * Por que existe: o print do micaelsan (10/09) mostrou o yt-dlp pedindo
 * exatamente isso: "Use --cookies-from-browser or --cookies for the
 * authentication". Acesso anônimo depende da reputação do IP e da rotação
 * de clients (que já vence os downloads); com os COOKIES de uma sessão
 * logada o YouTube deixa de tratar a extração como bot na origem — é a
 * recomendação oficial do próprio yt-dlp wiki para "Sign in to confirm
 * you're not a bot".
 *
 * Como o login acontece (v0.19.8, pedido do autor: "quando o usuário fazer
 * login no YouTube integrado do site, já detectar os cookies e enviar
 * junto"): o usuário entra na aba YouTube do próprio app e loga na conta
 * DELE — o [com.tunegrab.app.ui.YoutubeFragment] chama [captureFromWebView]
 * a cada página carregada e a sessão (SID/HSID/SAPISID do CookieManager do
 * sistema) é gravada no cookies.txt Netscape SEM botão, sem importação,
 * sem tela extra. A seção de cookies das Configurações saiu de cena —
 * "isso é patético, ninguém sabe mexer nisso", e agora ninguém precisa.
 *
 * Segurança: o arquivo vive em filesDir (privado do app, some com a
 * desinstalação), nunca vai para log, e é enviado SÓ para o processo do
 * yt-dlp via --cookies. Nada é enviado a lugar nenhum pelo TuneGrab.
 *
 * Este arquivo NÃO mexe no motor: [applyTo] é um no-op quando não há
 * cookies, e o resto do yt-dlp segue idêntico (rotação, retries, presets).
 */
object YtCookies {

    private const val TAG = "TuneGrabYtCookies"
    private const val PREFS = "yt_cookies"
    private const val KEY_SAVED_AT = "saved_at"
    private const val KEY_SOURCE = "source"

    /** Assinatura da última sessão capturada (v0.19.8): evita REgravar o
     *  cookies.txt a cada página do YouTube — só escreve quando os valores
     *  dos cookies de login mudam de verdade (re-login ou rotação Google). */
    private const val KEY_SIG = "login_sig"

    /** Cookies capturados valem por 1 ano na sintaxe Netscape (o yt-dlp
     *  reescreve o arquivo com renovações que o YouTube mandar de volta). */
    private const val EXPIRY_DAYS = 365L
    private const val DAY_SECONDS = 86400L

    @Volatile private var appCtx: Context? = null

    /** Ligar uma vez no [com.tunegrab.app.TuneGrabApp.onCreate] — o motor
     *  (YtDlpEngine) não recebe Context, então o caminho do arquivo fica
     *  cached aqui (mesmo padrão do PoTokenManager.init). */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    // ---------- estado ----------

    fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, "cookies.txt")

    /** True com arquivo não-vazio. Consultado pelo motor A CADA request. */
    fun has(ctx: Context): Boolean = try {
        file(ctx).let { it.isFile && it.length() > 0 }
    } catch (_: Throwable) {
        false
    }

    fun savedAt(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SAVED_AT, 0L)

    /** "login" (WebView) ou "import" (cookies.txt do PC). */
    fun source(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, "") ?: ""

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SAVED_AT).remove(KEY_SOURCE).remove(KEY_SIG).apply()
        file(ctx).delete()
        Log.i(TAG, "cookies removidos")
    }

    // ---------- entrada 1: WebView (CookieManager) ----------

    /**
     * Salva os cookies capturados do CookieManager (header "N=V; N2=V2").
     * O CookieManager não expõe domínio/expiração por cookie, então a linha
     * Netscape é sintetizada: domínio .youtube.com (subdomínios, seguro,
     * path /) com validade de [EXPIRY_DAYS] dias — é exatamente o escopo que
     * o Google usa para os cookies de sessão do YouTube (SID/HSID/SSID/
     * SAPISID ficam em .youtube.com).
     */
    fun saveFromWebView(ctx: Context, cookieHeader: String): Boolean {
        val pairs = cookieHeader.split(";")
            .mapNotNull { p ->
                val i = p.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val name = p.substring(0, i).trim()
                val value = p.substring(i + 1).trim()
                if (name.isBlank() || value.isBlank()) null else name to value
            }
        if (pairs.isEmpty()) return false
        val expiry = System.currentTimeMillis() / 1000L + EXPIRY_DAYS * DAY_SECONDS
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# Gerado pelo TuneGrab (login do YouTube no app)\n\n")
        for ((name, value) in pairs) {
            // TAB é o separador da sintaxe Netscape — obrigatório
            sb.append(".youtube.com\tTRUE\t/\tTRUE\t").append(expiry)
                .append('\t').append(name).append('\t').append(value).append('\n')
        }
        val ok = writeAtomically(ctx, sb.toString())
        if (ok) markSaved(ctx, "login")
        return ok
    }

    /** True se o header contém cookie de SESSÃO LOGADA (não só anônimos). */
    fun headerHasLogin(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val markers = listOf(
            "SID=", "HSID=", "SSID=", "SAPISID=", "APISID=",
            "__Secure-1PSID", "__Secure-3PSID"
        )
        return markers.any { cookieHeader.contains(it, ignoreCase = true) }
    }

    /**
     * CAPTURA AUTOMÁTICA (v0.19.8): chamada pela aba YouTube a cada página
     * carregada. Se o header tem sessão logada e ela é DIFERENTE da última
     * conhecida (assinatura), grava o cookies.txt e devolve true — o aviso
     * "Conta conectada" só aparece na transição, nunca a cada página.
     * Header sem login = no-op total (nada é apagado aqui; a demissão é
     * responsabilidade de [syncLoggedOut], que exige página do YouTube).
     */
    fun captureFromWebView(ctx: Context, header: String?): Boolean {
        if (!headerHasLogin(header)) return false
        val sig = loginSignature(header!!)
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SIG, "") == sig) return false // mesma sessão de sempre
        if (!saveFromWebView(ctx, header)) return false
        prefs.edit().putString(KEY_SIG, sig).apply()
        return true
    }

    /**
     * FIM DE SESSÃO (v0.19.8): se há cookies salvos mas o jar do sistema
     * NÃO tem mais marcadores de login, a sessão morreu no aparelho (o
     * usuário deslogou no youtube.com da aba) — o arquivo guardado apontaria
     * para cookies mortos. Apaga e devolve true pra avisar uma vez só.
     */
    fun syncLoggedOut(ctx: Context, cookieHeader: String?): Boolean {
        if (!has(ctx)) return false
        if (headerHasLogin(cookieHeader)) return false // ainda logado, não é fim
        clear(ctx)
        return true
    }

    /**
     * Assinatura DETERMINÍSTICA da sessão: só os valores dos cookies de
     * login (SID/HSID/SSID/SAPISID/APISID/__Secure-1PSID/__Secure-3PSID),
     * ordenados por nome e hasheados — a ordem que o CookieManager devolve
     * os OUTROS cookies (VISITOR_INFO1_LIVE, YSC, pretextos de consentimento)
     * muda o tempo todo e NÃO pode disparar regravação.
     */
    private fun loginSignature(header: String): String {
        val names = setOf(
            "SID", "HSID", "SSID", "SAPISID", "APISID",
            "__Secure-1PSID", "__Secure-3PSID"
        )
        val pairs = header.split(";").mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) return@mapNotNull null
            val name = p.substring(0, i).trim()
            if (name !in names) null else name to p.substring(i + 1).trim()
        }.sortedBy { it.first }
        val seed = pairs.joinToString(";") { "${it.first}=${it.second}" }
        return try {
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            // sem MessageDigest (improvável): o valor cru serve de assinatura
            seed
        }
    }

    // ---------- saída: para o yt-dlp ----------

    /**
     * ÚNICO ponto de contato com o motor: acrescenta --cookies ao request
     * quando há arquivo. No-op total sem cookies (o app segue anônimo como
     * sempre). Chamado de YtDlpEngine (downloads) e DlpMetadata (resgate).
     */
    fun applyTo(request: YoutubeDLRequest) {
        val ctx = appCtx ?: return
        if (!has(ctx)) return
        request.addOption("--cookies", file(ctx).absolutePath)
    }

    /** Resumo curto para o log SEM conteúdo sensível. */
    fun describe(ctx: Context): String {
        if (!has(ctx)) return "sem cookies"
        val ageDays = (System.currentTimeMillis() - savedAt(ctx)) / (DAY_SECONDS * 1000L)
        return String.format(Locale.getDefault(), "cookies ativos (%s, %d d)", source(ctx), ageDays)
    }

    // ---------- internals ----------

    private fun writeAtomically(ctx: Context, content: String): Boolean = try {
        val tmp = File(ctx.applicationContext.filesDir, "cookies.txt.tmp")
        tmp.writeText(content)
        val dst = file(ctx)
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            // rename entre arquivos do MESMO dir não deveria falhar; rede de
            // segurança: cópia direta
            dst.outputStream().use { o -> tmp.inputStream().use { i -> i.copyTo(o) } }
            tmp.delete()
        }
        Log.i(TAG, "cookies salvos (${dst.length()} bytes)")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "falha ao salvar cookies", t)
        false
    }

    private fun markSaved(ctx: Context, src: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, src)
            .apply()
    }
}
