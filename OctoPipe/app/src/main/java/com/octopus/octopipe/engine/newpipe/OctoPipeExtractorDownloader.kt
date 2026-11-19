package com.octopus.octopipe.engine.newpipe

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP bridge required by NewPipe Extractor. It deliberately lives behind the
 * OctoPipe engine boundary so the UI never depends on OkHttp or Extractor types.
 */
class OctoPipeExtractorDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        val bodyBytes = request.dataToSend()
        val method = request.httpMethod().uppercase()
        val body = if (method == "GET" || method == "HEAD") null else
            bodyBytes?.toRequestBody(
                request.headers().entries.firstOrNull { it.key.equals("Content-Type", true) }
                    ?.value?.firstOrNull()?.toMediaTypeOrNull()
            ) ?: ByteArray(0).toRequestBody(null)

        builder.method(method, body)
        client.newCall(builder.build()).execute().use { response ->
            val headers = linkedMapOf<String, List<String>>()
            for (name in response.headers.names()) headers[name] = response.headers.values(name)
            val text = if (method == "HEAD") "" else response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                headers,
                text,
                response.request.url.toString()
            )
        }
    }
}
