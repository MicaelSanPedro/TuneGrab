package com.tunegrab.app.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fase 1 do update automático: só DETECTA e informa.
 *
 * Consulta a release "latest" do GitHub (a mesma que o CI publica), compara
 * com a versão instalada e devolve os dados do card da Central. Regras:
 *  - Throttle: rede no máximo 1x a cada 6h; entre consultas, o último
 *    resultado fica em cache e o card reaparece sem custo;
 *  - "Dispensar" esconde o card SÓ daquela versão (nova versão = avisa de novo);
 *  - Qualquer falha (rede, JSON, parse) = null: o card não aparece e a vida
 *    segue — update NUNCA pode atrapalhar o funcionamento do app;
 *  - Zero permissão nova: usa a INTERNET que o app já tem. O link abre a
 *    página da release no navegador (Fase 2 troca por instalação in-app).
 */
object UpdateChecker {

    data class UpdateInfo(
        val version: String,   // "0.10.7" (tag sem o "v")
        val notes: String,     // corpo da release (changelog)
        val url: String,       // html_url da release
        val apkUrl: String = "",  // asset .apk instalável (sem o -debug)
        val apkSize: Long = 0L    // tamanho em bytes (0 = desconhecido)
    )

    private const val API = "https://api.github.com/repos/MicaelSanPedro/TuneGrab/releases/latest"
    private const val PREFS = "update_checker"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_CACHE = "cached_update_json"
    private const val KEY_DISMISSED = "dismissed_version"

    // Abertura fria do app SEMPRE consulta (gap mínimo de 10 min contra loop
    // de force-kill); no mesmo processo, no máximo 1x por hora. Nada de
    // janela cega de 6h — a v0.10.8 saiu 40min depois da 1ª consulta e o
    // usuário ficava sem card até 6 horas (bug real reportado).
    private const val MIN_GAP_MS = 10 * 60 * 1000L
    private const val SAME_PROCESS_GAP_MS = 60 * 60 * 1000L

    @Volatile
    private var checkedThisProcess = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Chamado na abertura da Central (corrotina — rede no IO). */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Antes de QUALQUER decisão (inclusive o gap): se o cache fala de uma
        // versão que já é a instalada, é resto morto — apaga AGORA. Reabrindo
        // o app logo após instalar a atualização, o gap de 10 min devolvia o
        // card zumbi de "atualização disponível" pra quem ACABOU de atualizar
        // (o último check fora minutos antes, no download).
        clearStale(appCtx, prefs)

        val now = System.currentTimeMillis()

        // Gap mínimo: morto e reaberto em segundos não martela a API.
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (now - lastCheck < MIN_GAP_MS) return@withContext readCache(prefs)
        // Mesmo processo: já consultou há menos de 1h → usa o cache.
        if (checkedThisProcess && now - lastCheck < SAME_PROCESS_GAP_MS) {
            return@withContext readCache(prefs)
        }
        checkedThisProcess = true

        try {
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .build()
            val json = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext readCache(prefs)
                JSONObject(resp.body?.string() ?: return@withContext readCache(prefs))
            }

            val version = json.optString("tag_name", "").trim()
                .removePrefix("v").removePrefix("V").trim()
            val url = json.optString("html_url", "").trim()
            val notes = json.optString("body", "").trim()
            if (version.isBlank() || url.isBlank()) return@withContext readCache(prefs)

            // Fase 2: acha o APK instalável entre os assets da release
            // (TuneGrab-vX.apk — o -debug fica de fora).
            var apkUrl = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk") && name.startsWith("TuneGrab-")
                        && !name.contains("debug")
                    ) {
                        apkUrl = a.optString("browser_download_url", "")
                        apkSize = a.optLong("size", 0L)
                        break
                    }
                }
            }

            val current = currentVersion(appCtx) ?: return@withContext readCache(prefs)
            val info = if (isNewer(version, current)) {
                UpdateInfo(version, notes, url, apkUrl, apkSize)
            } else null

            prefs.edit()
                .putString(KEY_CACHE, info?.let { toJson(it) })
                .apply()
            info
        } catch (t: Throwable) {
            // Falhou a rede? Mostra o que tiver em cache; senão, nada.
            readCache(prefs)
        }
    }

    /** A versão dispensada volta a avisar só quando sair uma NOVA versão. */
    fun dismiss(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED, version).apply()
    }

    /**
     * Notas da versão em texto LEIGO, direto pro diálogo "O que veio de
     * novo?": tira a maquiagem do markdown da release (cabeçalhos, negrito,
     * itálico, código e links ficam só com o texto) e transforma os itens
     * de lista em bolinha — quem não é técnico lê sem esbarrar em símbolos.
     */
    fun cleanNotes(raw: String): String = raw
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+)\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    /**
     * Limpeza de resto pós-instalação: o usuário atualizou, o app novo abriu
     *  — e nada do app VELHO sobrevive pra confundir.
     *  - Cache de update cuja versão não é mais nova que a instalada = resto
     *    ("0.19.11 disponível" rodando DENTRO da 0.19.11 kkk) → fora, na hora.
     *  - APK na pasta de updates cuja versão do nome não é mais nova que a
     *    instalada = ~100MB que não servem mais pra nada → fora também.
     *    (O cleanOldApks do download novo continua como 2ª linha de defesa.)
     */
    private fun clearStale(context: Context, prefs: android.content.SharedPreferences) {
        val current = currentVersion(context) ?: return
        prefs.getString(KEY_CACHE, null)?.let { raw ->
            val cached = try {
                JSONObject(raw).optString("version", "")
            } catch (t: Throwable) {
                "" // cache ilegível é resto também
            }
            if (cached.isBlank() || !isNewer(cached, current)) {
                prefs.edit().remove(KEY_CACHE).apply()
            }
        }
        val dir = UpdateInstaller.updatesDir(context)
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val v = Regex("^tunegrab-(.+)\\.apk$").find(f.name)?.groupValues?.get(1) ?: return@forEach
            if (!isNewer(v, current)) f.delete()
        }
    }

    private fun readCache(prefs: android.content.SharedPreferences): UpdateInfo? {
        val raw = prefs.getString(KEY_CACHE, null) ?: return null
        val dismissed = prefs.getString(KEY_DISMISSED, null)
        return try {
            val o = JSONObject(raw)
            val info = UpdateInfo(
                o.getString("version"),
                o.optString("notes", ""),
                o.getString("url"),
                o.optString("apkUrl", ""),
                o.optLong("apkSize", 0L)
            )
            if (info.version == dismissed) null else info
        } catch (t: Throwable) {
            null
        }
    }

    private fun toJson(i: UpdateInfo): String = JSONObject().apply {
        put("version", i.version)
        put("notes", i.notes)
        put("url", i.url)
        put("apkUrl", i.apkUrl)
        put("apkSize", i.apkSize)
    }.toString()

    /** versionName instalado, ex.: "0.10.6". */
    private fun currentVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (t: Throwable) {
        null
    }

    /** Comparação por SEGMENTO numérico: "0.10.7" > "0.10.6", "1.0" > "0.99.9".
     *  Nunca compara string (senão "0.10.9" pareceria maior que "0.10.10"). */
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
