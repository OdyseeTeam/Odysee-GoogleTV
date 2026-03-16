package com.odysee.androidtv.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OdyseeApiClient(
    private val config: OdyseeApiConfig = OdyseeApiConfig(),
    client: OkHttpClient? = null,
) {
    private val httpClient: OkHttpClient = client
        ?: OkHttpClient.Builder()
            .connectTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    suspend fun callRoot(
        resource: String,
        action: String,
        params: Map<String, String> = emptyMap(),
        method: String = "POST",
    ): Any? = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val bases = listOf(config.rootApiBase, config.rootApiFallbackBase)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        for (base in bases) {
            val request = buildRootRequest(
                base = base,
                resource = resource,
                action = action,
                params = params,
                method = method,
            )
            try {
                return@withContext executeAndParseRoot(request)
            } catch (error: Exception) {
                lastError = error
            }
        }

        throw (lastError ?: ApiException("Root API request failed"))
    }

    private fun buildRootRequest(
        base: String,
        resource: String,
        action: String,
        params: Map<String, String>,
        method: String,
    ): Request {
        val url = "${base.trimEnd('/')}/$resource/$action"
        val requestBuilder = Request.Builder().url(url)

        if (method.equals("POST", ignoreCase = true)) {
            val form = FormBody.Builder()
            params.forEach { (k, v) ->
                form.add(k, v)
            }
            return requestBuilder.post(form.build()).build()
        }

        val httpUrlBuilder = requestBuilder.build().url.newBuilder()
        params.forEach { (k, v) ->
            httpUrlBuilder.addQueryParameter(k, v)
        }
        return requestBuilder.url(httpUrlBuilder.build()).get().build()
    }

    suspend fun callSdk(
        method: String,
        params: JSONObject = JSONObject(),
        authToken: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = "${config.queryProxyUrl}?m=$method"
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .put("id", 1)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "text/plain;charset=UTF-8")

        if (!authToken.isNullOrBlank()) {
            requestBuilder.header("X-Lbry-Auth-Token", authToken)
        }

        val json = executeAndParseJson(requestBuilder.build())
        if (json.has("error") && !json.isNull("error")) {
            throw ApiException(
                message = extractErrorMessage(
                    payload = json.opt("error"),
                    fallback = "SDK request failed: $method",
                ),
                statusCode = null,
            )
        }
        json.optJSONObject("result") ?: throw ApiException("SDK response missing result for method $method")
    }

    suspend fun getJson(url: String): Any? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        val bodyString = executeForBody(req)
        parseAnyJson(bodyString)
    }

    private fun executeAndParseRoot(request: Request): Any? {
        val bodyString = executeForBody(request)
        val parsed = parseAnyJson(bodyString)

        if (parsed !is JSONObject) {
            throw ApiException("Unexpected root API response")
        }

        if (parsed.optBoolean("success", true).not()) {
            throw ApiException(
                message = extractErrorMessage(
                    payload = parsed.opt("error"),
                    fallback = "Request failed",
                ),
            )
        }

        if (parsed.has("error") && !parsed.isNull("error")) {
            throw ApiException(
                extractErrorMessage(
                    payload = parsed.opt("error"),
                    fallback = "Request failed",
                )
            )
        }

        return when {
            parsed.has("data") -> parsed.opt("data")
            parsed.has("result") -> parsed.opt("result")
            else -> parsed
        }
    }

    private fun executeAndParseJson(request: Request): JSONObject {
        val bodyString = executeForBody(request)
        val parsed = parseAnyJson(bodyString)
        if (parsed is JSONObject) {
            return parsed
        }
        throw ApiException("Expected JSON object response")
    }

    private fun executeForBody(request: Request): String {
        try {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val parsedBody = runCatching { parseAnyJson(body) }.getOrNull()
                    val message = extractErrorMessage(
                        payload = parsedBody ?: body,
                        fallback = body.take(240).ifBlank { "HTTP ${resp.code}" },
                    )
                    throw ApiException(
                        message = message,
                        statusCode = resp.code,
                    )
                }
                return body
            }
        } catch (io: IOException) {
            throw ApiException("Network request failed", cause = io)
        }
    }

    private fun parseAnyJson(raw: String): Any? {
        val text = raw.trim()
        if (text.isEmpty()) {
            return null
        }
        return when {
            text.startsWith("{") -> JSONObject(text)
            text.startsWith("[") -> JSONArray(text)
            else -> text
        }
    }

    private fun extractErrorMessage(payload: Any?, fallback: String): String {
        return when (payload) {
            null, JSONObject.NULL -> fallback
            is String -> payload.trim().removeSurrounding("\"").ifBlank { fallback }
            is JSONArray -> {
                (0 until payload.length())
                    .asSequence()
                    .map { index -> extractErrorMessage(payload.opt(index), "") }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .ifBlank { fallback }
            }
            is JSONObject -> {
                sequenceOf(
                    payload.opt("message"),
                    payload.opt("error_message"),
                    payload.opt("errorMessage"),
                    payload.opt("detail"),
                    payload.opt("description"),
                    payload.opt("title"),
                    payload.opt("error"),
                )
                    .map { value -> extractErrorMessage(value, "") }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .ifBlank { fallback }
            }
            else -> payload.toString().trim().ifBlank { fallback }
        }
    }
}
