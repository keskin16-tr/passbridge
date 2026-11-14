package com.passbridge.studio.network

import com.passbridge.studio.model.ConvertResult
import com.passbridge.studio.model.PassCard
import com.passbridge.studio.model.ServerConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WalletApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, apiKey: String, method: String = "GET", body: okhttp3.RequestBody? = null): Request.Builder {
        val builder = Request.Builder().url(url)
        if (apiKey.isNotBlank()) builder.header("X-API-Key", apiKey)
        if (method == "POST") builder.post(body ?: ByteArray(0).toRequestBody(null))
        return builder
    }

    private fun execute(req: Request): String {
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                error(msg.ifBlank { "Sunucu HTTP ${response.code}: $body" })
            }
            return body
        }
    }

    fun getConfig(baseUrl: String, apiKey: String): ServerConfig {
        val json = JSONObject(execute(request("$baseUrl/api/config", apiKey).build()))
        return ServerConfig(
            credentialsFound = json.optBoolean("credentialsFound"),
            clientEmail = json.optString("clientEmail"),
            issuerId = json.optString("issuerId"),
            publicBaseUrl = json.optString("publicBaseUrl"),
            ready = json.optBoolean("ready"),
            apiKeyRequired = json.optBoolean("apiKeyRequired")
        )
    }

    fun saveIssuer(baseUrl: String, apiKey: String, issuerId: String) {
        val body = JSONObject().put("issuerId", issuerId).put("classSuffix", "passbridge_android")
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        execute(request("$baseUrl/api/config", apiKey, "POST", body).build())
    }

    fun uploadServiceAccount(baseUrl: String, apiKey: String, file: File) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/json".toMediaType()))
            .build()
        execute(request("$baseUrl/api/credentials", apiKey, "POST", body).build())
    }

    fun convert(baseUrl: String, apiKey: String, card: PassCard): ConvertResult {
        val json = card.toOverrides().toString()
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("overrides", json)
        val source = card.sourcePath?.let(::File)
        if (source != null && source.isFile) {
            multipart.addFormDataPart("file", card.originalFileName.ifBlank { "wallet.pkpass" },
                source.asRequestBody("application/vnd.apple.pkpass".toMediaType()))
        } else {
            error("Orijinal PKPASS geçici dosyası bulunamadı. Dosyayı yeniden seçin.")
        }
        val body = multipart.build()
        val result = JSONObject(execute(request("$baseUrl/api/convert", apiKey, "POST", body).build()))
        val warnings = mutableListOf<String>()
        result.optJSONArray("warnings")?.let { arr ->
            for (i in 0 until arr.length()) warnings += arr.optString(i)
        }
        return ConvertResult(
            saveUrl = result.optString("saveUrl").ifBlank { result.optString("url") },
            walletType = result.optString("walletType", "Google Wallet"),
            warnings = warnings
        )
    }
}
