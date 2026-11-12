package com.example.navistaraccessclient.iot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

class TokenException(val code: String, message: String) : Exception(message)

/**
 * Lấy JWT để đăng nhập EMQX (mục 2.1).
 *
 *   POST {apiBaseUrl}/api/devicecomm/getmqtttoken
 *   { "MacAddress": "...", "Password": Base64(SHA-256(UTF8(MacAddress + salt))) }
 *
 * - Token hết hạn (mặc định 600 s) → gọi LẠI trước MỖI lần connect/reconnect, KHÔNG cache.
 * - HTTP luôn trả 200 → phải đọc trường "code": OK | WRONG_ACCOUNT | EXCEPTION.
 * - Dùng HttpURLConnection có sẵn → không thêm thư viện (giữ APK/RAM nhỏ).
 * - Hàm CHẶN luồng → chỉ gọi trên Dispatchers.IO.
 */
class TokenClient(private val timeoutMs: Int = 10_000) {

    data class Token(val token: String, val username: String)

    fun fetch(apiBaseUrl: String, mac: String, salt: String): Token {
        val url = URL(apiBaseUrl.trimEnd('/') + "/api/devicecomm/getmqtttoken")
        val body = JSONObject()
            .put("MacAddress", mac)
            .put("Password", password(mac, salt))
            .toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) throw TokenException("HTTP_$status", "ApiGateway trả HTTP $status")

            val o = JSONObject(text)
            val code = o.optString("code")
            if (code != "OK") {
                val msg = when (code) {
                    "WRONG_ACCOUNT" -> "Sai MAC hoặc salt (WRONG_ACCOUNT)"
                    "EXCEPTION" -> "Server lỗi khi cấp token (EXCEPTION)"
                    else -> "Không lấy được token: ${o.optString("message", code)}"
                }
                throw TokenException(code.ifBlank { "UNKNOWN" }, msg)
            }
            val data = o.getJSONObject("data")
            return Token(data.getString("token"), data.optString("username", mac))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Base64( SHA-256( UTF8(MacAddress + salt) ) ) — Base64 chuẩn, có padding "=" */
        fun password(mac: String, salt: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest((mac + salt).toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}
