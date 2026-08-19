package com.pockettoolbox.app.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class VirusTotalStats(
    val malicious: Int,
    val suspicious: Int,
    val harmless: Int,
    val undetected: Int,
    val timeout: Int,
    val failure: Int,
    val unsupported: Int,
)

data class VirusTotalReport(
    val stats: VirusTotalStats,
    val analysisEpochSeconds: Long?,
    val reportUrl: String,
)

data class VirusTotalAnalysis(
    val status: String,
    val stats: VirusTotalStats?,
    val analysisEpochSeconds: Long?,
    val reportUrl: String,
)

class VirusTotalRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // Never forward the custom x-apikey header through a redirect.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun lookupUrl(url: String, apiKey: String): VirusTotalReport? = withContext(Dispatchers.IO) {
        val normalized = validateUrl(url)
        val urlId = urlIdentifier(normalized)
        val request = apiRequest("$API_BASE/urls/$urlId", apiKey).get().build()
        execute(request, allowNotFound = true).use { response ->
            if (response.code == 404) return@withContext null
            val json = response.readJson()
            val attributes = json.getJSONObject("data").getJSONObject("attributes")
            VirusTotalReport(
                stats = attributes.optJSONObject("last_analysis_stats").toStats(),
                analysisEpochSeconds = attributes.optLong("last_analysis_date").takeIf { it > 0L },
                reportUrl = reportUrl(urlId),
            )
        }
    }

    suspend fun submitUrl(url: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val normalized = validateUrl(url)
        val request = apiRequest("$API_BASE/urls", apiKey)
            .post(FormBody.Builder().add("url", normalized).build())
            .build()
        execute(request).use { response ->
            response.readJson().getJSONObject("data").getString("id")
        }
    }

    suspend fun getAnalysis(url: String, analysisId: String, apiKey: String): VirusTotalAnalysis =
        withContext(Dispatchers.IO) {
            val normalized = validateUrl(url)
            require(analysisId.isNotBlank() && analysisId.length <= 500) { "分析编号无效。" }
            val request = apiRequest("$API_BASE/analyses/${Uri.encode(analysisId)}", apiKey).get().build()
            execute(request).use { response ->
                val json = response.readJson()
                val attributes = json.getJSONObject("data").getJSONObject("attributes")
                VirusTotalAnalysis(
                    status = attributes.optString("status").ifBlank { "unknown" },
                    stats = attributes.optJSONObject("stats")?.toStats(),
                    analysisEpochSeconds = attributes.optLong("date").takeIf { it > 0L },
                    reportUrl = reportUrl(urlIdentifier(normalized)),
                )
            }
        }

    private fun apiRequest(url: String, apiKey: String): Request.Builder {
        require(apiKey.isNotBlank() && apiKey.length <= MAX_API_KEY_LENGTH) { "VirusTotal API key 无效。" }
        return Request.Builder()
            .url(url)
            .header("x-apikey", apiKey)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
    }

    private fun execute(request: Request, allowNotFound: Boolean = false): Response {
        val response = client.newCall(request).execute()
        if (!response.request.url.isHttps || response.request.url.host != API_HOST) {
            response.close()
            throw IOException("VirusTotal 请求跳转到了不受信任的地址。")
        }
        if (allowNotFound && response.code == 404) return response
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException(
                when (code) {
                    401, 403 -> "VirusTotal API key 无效、权限不足或已被停用。"
                    429 -> "VirusTotal API 请求额度已用完，请稍后再试。"
                    else -> "VirusTotal 请求失败（HTTP $code）。"
                },
            )
        }
        return response
    }

    private fun Response.readJson(): JSONObject {
        val responseBody = body ?: throw IOException("VirusTotal 响应为空。")
        return JSONObject(responseBody.source().readLimitedUtf8(MAX_JSON_BYTES))
    }

    private fun JSONObject?.toStats(): VirusTotalStats {
        val json = this ?: JSONObject()
        return VirusTotalStats(
            malicious = json.optInt("malicious"),
            suspicious = json.optInt("suspicious"),
            harmless = json.optInt("harmless"),
            undetected = json.optInt("undetected"),
            timeout = json.optInt("timeout") + json.optInt("confirmed-timeout"),
            failure = json.optInt("failure"),
            unsupported = json.optInt("type-unsupported"),
        )
    }

    private fun validateUrl(value: String): String {
        require(value.length <= MAX_URL_LENGTH) { "URL 过长，无法提交检测。" }
        val uri = Uri.parse(value)
        require(
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        ) { "只支持检测 HTTP(S) URL。" }
        return value
    }

    private fun urlIdentifier(url: String): String = Base64.encodeToString(
        url.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun reportUrl(urlId: String): String = "https://www.virustotal.com/gui/url/$urlId/detection"

    private fun BufferedSource.readLimitedUtf8(limit: Long): String {
        val buffer = okio.Buffer()
        var total = 0L
        while (true) {
            val read = read(buffer, minOf(64 * 1024L, limit + 1L - total))
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("VirusTotal 响应过大。")
        }
        return buffer.readUtf8()
    }

    private companion object {
        const val API_BASE = "https://www.virustotal.com/api/v3"
        const val API_HOST = "www.virustotal.com"
        const val USER_AGENT = "PocketToolbox/0.6 (Android; VirusTotal client)"
        const val MAX_URL_LENGTH = 8_192
        const val MAX_API_KEY_LENGTH = 512
        const val MAX_JSON_BYTES = 2L * 1024 * 1024
    }
}
