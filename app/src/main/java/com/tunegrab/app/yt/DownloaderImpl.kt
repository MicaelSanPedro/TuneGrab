package com.tunegrab.app.yt

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * Implementação do [Downloader] exigido pelo NewPipeExtractor,
 * baseada em OkHttp.
 */
object DownloaderImpl : Downloader() {

    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())

        val body = request.dataToSend()?.toRequestBody("application/json".toMediaTypeOrNull())
        when (request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(null))
            "PUT" -> builder.put(body ?: ByteArray(0).toRequestBody(null))
            else -> builder.method(request.httpMethod(), body)
        }

        var hasUserAgent = false
        for ((name, values) in request.headers()) {
            if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                hasUserAgent = true
            }
            for (value in values) builder.header(name, value)
        }
        if (!hasUserAgent) builder.header("User-Agent", USER_AGENT)

        client.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.code == 429) {
                throw ReCaptchaException(
                    "Rate limit atingido pelo YouTube",
                    resp.request.url.toString()
                )
            }
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                bodyStr,
                resp.request.url.toString()
            )
        }
    }
}
