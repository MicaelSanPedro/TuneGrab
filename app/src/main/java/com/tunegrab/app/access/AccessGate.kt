package com.tunegrab.app.access

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trava de acesso por convite (v0.20.0, pedido do autor: "sistema de senha
 * pra acessar o app que só pode ser usado uma vez" — e as senhas nascem do
 * NOME COMPLETO de quem vai usar, via base64 + HMAC no gerador; o app só
 * confere contra os HASHES).
 *
 * Como funciona, sem enrolação:
 *  - A lista de senhas válidas mora no PRÓPRIO repo (access/passwords.json),
 *    mas guardando SÓ o hash PBKDF2-SHA256 de cada senha — a senha em si
 *    nunca é commitada. A lista é pública porque hash de senha aleatória
 *    (8 chars de um alfabeto de 31 ≈ 31^8 chutes) não se quebra de graça.
 *  - O app busca essa lista anonimamente (igual ao UpdateChecker — zero
 *    token) com cache-buster pra furar o cache CDN do raw.githubusercontent.
 *  - Acertou? grava a flag de destravado no aparelho → destrava PRA SEMPRE
 *    (semântica cravada com o autor). Limpar os dados do app volta a pedir.
 *  - Queimar uma senha = o autor apaga a entrada do JSON no repo. Quem já
 *    liberou continua dentro (a flag é local); mundo novo não entra mais.
 *  - A PRIMEIRA liberação exige internet (sem net, sem convite aceito).
 *
 * v0.21.0 — SENHA PRESA NO APARELHO (pedido do autor: "uma senha e ela só
 * funciona naquele dispositivo, se tentar em outro, não pega"). Entrada com
 * campo "device" no JSON SÓ destrava no aparelho daquele código: o app deriva
 * um CÓDIGO DE APARELHO do ANDROID_ID (Settings.Secure — sem permissão, por
 * assinatura do app, sobrevive a desinstalar/limpar dados; só reset de
 * fábrica troca), mostra na LockActivity e, na checagem, repete o HMAC da
 * entrada com o código LOCAL e compara tempo-constante. Não bateu = "essa
 * senha pertence a outro aparelho". Entrada SEM "device" vale em qualquer
 * aparelho (formato antigo, grandfathered). O código NÃO é segredo — o par
 * (senha, aparelho) é o que vale; o algoritmo de derivação é CONGELADO
 * (DEVICE_CODE_KEY "TuneGrabDeviceCode-v1"): mudou, todo código muda.
 *
 * PBKDF2 é implementado AQUI NA MÃO (RFC 8018, um bloco de 32 bytes) porque
 * o SecretKeyFactory "PBKDF2WithHmacSHA256" só existe a partir da API 26 —
 * e o app atende até a API 24. HmacSHA256 via javax.crypto é de berço.
 * Comparação sempre com MessageDigest.isEqual (tempo constante).
 */
object AccessGate {

    /** Resultado da tentativa de liberação — cada caso tem frase própria na tela. */
    sealed interface Verdict {
        data class Accepted(val id: String) : Verdict
        object Wrong : Verdict      // senha não bateu (errada ou queimada)
        object WrongDevice : Verdict // senha EXISTE, mas é presa noutro aparelho
        object Empty : Verdict      // digitou nada
        object Offline : Verdict    // rede falhou — 1ª liberação pede internet
        object NoActive : Verdict   // lista existe mas está vazia
        object Failed : Verdict     // outro problema (JSON quebrado etc.)
    }

    private data class Entry(
        val id: String,
        val salt: ByteArray,
        val hash: ByteArray,
        val iters: Int,
        val binding: Pair<ByteArray, ByteArray>? // (deviceSalt, deviceHash) — null = solta
    )

    private const val LIST_URL =
        "https://raw.githubusercontent.com/MicaelSanPedro/TuneGrab/main/access/passwords.json"

    /** O MESMO alfabeto do gerador (sem 0/O/1/I/L — 31 chars). */
    internal const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Chave da derivação do código de aparelho — CONGELADA (está no APK,
     *  nunca foi segredo). v1: se um dia precisar mudar o algoritmo, vira -v2
     *  e a lista inteira é re-emitida. O espelho em Python vive no passgen. */
    private val DEVICE_CODE_KEY = "TuneGrabDeviceCode-v1".toByteArray(Charsets.UTF_8)

    private const val PREFS = "access_gate"
    private const val KEY_UNLOCKED = "unlocked"
    private const val KEY_ENTRY = "entry_id"
    private const val KEY_AT = "unlocked_at"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** O aparelho já foi liberado por alguma senha? */
    fun isUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLOCKED, false)

    /** Registra a liberação pra sempre (flag local do aparelho). */
    fun markUnlocked(context: Context, entryId: String) {
        prefs(context).edit()
            .putBoolean(KEY_UNLOCKED, true)
            .putString(KEY_ENTRY, entryId)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * CÓDIGO DESTE APARELHO (v0.21.0) — "XXXX-XXXX" derivado do ANDROID_ID,
     * mostrado na LockActivity pra pessoa mandar pro autor junto com o nome.
     * Estável: por assinatura do app desde a API 26 (sobrevive a desinstalar,
     * limpar dados, atualizar; só reset de fábrica/troca de celular muda —
     * e aí o autor re-emite a MESMA senha pro código novo). Fallback raríssimo
     * (aparelho sem ID provisionado): fingerprint do build.
     */
    fun deviceCode(context: Context): String {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() } ?: Build.FINGERPRINT
        return deviceCodeFrom(raw)
    }

    /** Espelho EXATO do device_code_from() do passgen (Python) — NÃO MUDAR
     *  sem mudar lá junto: ID bruto -> trim/lowercase -> HMAC(KEY, id) ->
     *  5 bytes -> 8 chars do alfabeto (passeio de 5 bits, módulo 31). */
    internal fun deviceCodeFrom(raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(DEVICE_CODE_KEY, "HmacSHA256"))
        val digest = mac.doFinal(raw.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (digest[i].toLong() and 0xFFL)
        val sb = StringBuilder(9)
        for (i in 0 until 8) {
            sb.append(ALPHABET[(((bits shr (5 * (7 - i))) and 0x1FL).toInt()) % ALPHABET.length])
            if (i == 3) sb.append('-')
        }
        return sb.toString()
    }

    /** Canônico do código pro binding: MAIÚSCULAS, só [A-Z0-9] — o traço é
     *  máscara visual; quem digita/copiar com caixa errada não engana. */
    internal fun canonicalDeviceCode(raw: String): String =
        raw.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

    /** HMAC-SHA256 puro (chave = salt do binding, msg = canônico do código).
     *  Espelho do device_binding() do passgen. */
    internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /**
     * Confere a senha digitada contra a lista remota. Rede e PBKDF2 rodam
     * no IO; a activity só lida com o veredito. Erros NUNCA explodem —
     * viram veredito (mesma filosofia do UpdateChecker).
     *
     * v0.21.0: precisa do contexto pra derivar o código DESTE aparelho —
     * entrada com binding que não casa com o código local = WrongDevice
     * (senha existe, mas é de outro aparelho).
     */
    suspend fun verify(context: Context, typed: String): Verdict = withContext(Dispatchers.IO) {
        // Canônico: MAIÚSCULAS e só letras/números — "tune-7k3m-9qpd",
        // "TUNE 7K3M 9QPD" e "TUNE7K3M9QPD" viram a MESMA chave. É esse
        // canônico que o gerador hasheou (as linhas de display são só
        // máscara pra leitura humana).
        val canonical = canonicalize(typed)
        if (canonical.isBlank()) return@withContext Verdict.Empty

        val body = try {
            fetchList()
        } catch (t: Throwable) {
            return@withContext Verdict.Offline
        }
        val entries = parseEntries(body) ?: return@withContext Verdict.Failed
        if (entries.isEmpty()) return@withContext Verdict.NoActive

        val passwordBytes = canonical.toByteArray(Charsets.UTF_8)
        // Código local canônico (8 chars, sem traço) derivado UMA vez —
        // o HMAC de binding é barato, mas nada de recalcular por entrada.
        val localCode = canonicalDeviceCode(deviceCode(context))
        val localCodeBytes = localCode.toByteArray(Charsets.UTF_8)
        for (entry in entries) {
            val candidate = pbkdf2Sha256(passwordBytes, entry.salt, entry.iters, 32)
            if (MessageDigest.isEqual(candidate, entry.hash)) {
                val (devSalt, devHash) = entry.binding
                    ?: return@withContext Verdict.Accepted(entry.id) // solta = qualquer aparelho
                // Entrada PRESA: o canônico do código local tem que gerar
                // o MESMO HMAC. Falhou = senha certa, aparelho errado.
                val mine = hmacSha256(devSalt, localCodeBytes)
                return@withContext if (MessageDigest.isEqual(mine, devHash)) {
                    Verdict.Accepted(entry.id)
                } else {
                    Verdict.WrongDevice
                }
            }
        }
        Verdict.Wrong
    }

    /** Busca anônima da lista; o "?t=" fura o cache CDN do raw (queima novo). */
    private fun fetchList(): String {
        val url = "$LIST_URL?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("resposta vazia")
        }
    }

    /** null = JSON quebrado (Failed); lista vazia/sem hashes = NoActive.
     *  Binding "device" é OPCIONAL: presente e MALFORMADO = fail-closed
     *  (salt vazio x hash vazio nunca batem com nada — a entrada morre). */
    private fun parseEntries(body: String): List<Entry>? {
        return try {
            val root = JSONObject(body)
            val arr = root.optJSONArray("hashes") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val salt = try {
                        Base64.decode(o.optString("salt"), Base64.DEFAULT)
                    } catch (t: Throwable) { ByteArray(0) }
                    val hash = try {
                        Base64.decode(o.optString("hash"), Base64.DEFAULT)
                    } catch (t: Throwable) { ByteArray(0) }
                    val iters = o.optInt("iters", 0)
                    val dev = o.optJSONObject("device")
                    val binding: Pair<ByteArray, ByteArray>? = when {
                        dev == null -> null // entrada SOLTA (formato v0.20.0 ou sem binding)
                        else -> {
                            val ds = try {
                                Base64.decode(dev.optString("salt"), Base64.DEFAULT)
                            } catch (t: Throwable) { ByteArray(0) }
                            val dh = try {
                                Base64.decode(dev.optString("hash"), Base64.DEFAULT)
                            } catch (t: Throwable) { ByteArray(0) }
                            if (ds.size == 16 && dh.size == 32) ds to dh
                            else ByteArray(0) to ByteArray(0) // binding quebrado = fail-closed
                        }
                    }
                    if (salt.isNotEmpty() && hash.size == 32 && iters > 0) {
                        add(Entry(o.optString("id", ""), salt, hash, iters, binding))
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 manual (RFC 8018) — aqui usamos um único bloco de
     * 32 bytes, mas o laço é genérico. Feito na mão porque o provider de
     * PBKDF2 com SHA-256 só existe na API 26 e nosso piso é a 24.
     */
    internal fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        var offset = 0
        for (block in 1..blocks) {
            // U1 = PRF(P, salt || INT_32_BE(block))
            mac.update(salt)
            var u = mac.doFinal(byteArrayOf(0, 0, 0, block.toByte()))
            val t = u.copyOf()
            for (iter in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
        }
        return out
    }

    /** Canônico da senha: MAIÚSCULAS (Locale.ROOT — sem surpresa turca) e
     *  só [A-Z0-9]; traço, espaço e pontuação somem. */
    internal fun canonicalize(raw: String): String =
        raw.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }
}
