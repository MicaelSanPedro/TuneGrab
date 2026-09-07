package com.tunegrab.app.audio

/**
 * Ponte JNI para o LAME (libmp3lame) compilado via NDK.
 * Cada sessão usa um handle > 0 retornado por [nativeInit].
 */
object Mp3Encoder {

    init {
        System.loadLibrary("tunegrab_lame")
    }

    /** Cria o encoder. Retorna handle (>0) ou 0 em caso de falha. */
    external fun nativeInit(
        sampleRate: Int,
        channels: Int,
        kbps: Int,
        title: String?,
        artist: String?
    ): Long

    /**
     * Codifica [frames] frames de PCM 16-bit intercalado
     * (pcm.size deve cobrir frames * canais do encoder).
     * Retorna o MP3 gerado (ou null se nada foi produzido nesta chamada).
     */
    external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int): ByteArray?

    /** Finaliza o stream e devolve os últimos bytes do MP3. */
    external fun nativeFlush(handle: Long): ByteArray?

    external fun nativeClose(handle: Long): Int
}
