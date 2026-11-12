package com.example.navistaraccessclient.iot

import android.content.Context
import java.security.SecureRandom

/**
 * Thông số kết nối tới hệ thống của Lab — chỉnh được trong Dialog Cài đặt.
 * Các giá trị mặc định trỏ vào bộ giả lập chạy trên laptop (tools/mock_lab_server.py):
 * 10.0.2.2 = máy tính đang chạy giả lập Android.
 */
data class DeviceConfig(
    val enabled: Boolean = false,
    /** Gốc URL của ApiGateway, VD "http://10.0.2.2:8080" (không có "/" ở cuối) */
    val apiBaseUrl: String = "http://10.0.2.2:8080",
    val brokerHost: String = "10.0.2.2",
    val brokerPort: Int = 1883,
    /** TLS (MQTTS) — CHƯA hỗ trợ trong bản này, chờ Lab xác nhận site có bật TLS không */
    val useTls: Boolean = false,
    /** MAC dùng làm ClientId + Username + "MacAddress". Android 10 không đọc được MAC thật → tự sinh. */
    val macAddress: String = "",
    /** Salt do nhóm dự án cấp — dùng để tính Password khi lấy token */
    val salt: String = "",
    /**
     * Có áp IDENTIFY_THRESHOLD từ push_config vào ngưỡng nhận diện không.
     * Mặc định TẮT vì chưa rõ thang đo của Lab (0.8) có giống Cosine của app (0.70) không.
     */
    val applyServerThreshold: Boolean = false
) {
    val brokerUri: String get() = "${if (useTls) "ssl" else "tcp"}://$brokerHost:$brokerPort"

    fun missingFields(): List<String> = buildList {
        if (apiBaseUrl.isBlank()) add("URL ApiGateway")
        if (brokerHost.isBlank()) add("Broker host")
        if (brokerPort !in 1..65535) add("Port")
        if (!isValidMac(macAddress)) add("MAC")
        if (salt.isBlank()) add("Salt")
    }

    companion object {
        /**
         * Version gửi trong healthcheck / sync_required_door.
         * Định dạng của Lab là yyyyMMddNNN (VD 20261022001) — VƯỢT giới hạn Int của versionCode Android,
         * nên giữ hằng số riêng ở đây. Tăng số này mỗi lần phát hành.
         */
        const val APP_VERSION: Long = 20261112001L

        private val MAC_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
        fun isValidMac(s: String) = MAC_REGEX.matches(s)

        /**
         * Sinh MAC "giả" ổn định: bit locally-administered = 1, unicast (byte đầu dạng x2/x6/xA/xE).
         * Viết HOA, ngăn cách ":" giống mẫu trong tài liệu ("AA:BB:CC:DD:EE:FF").
         */
        fun generateMac(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(6).also { random.nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte()
            return b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
        }
    }
}

/** Lưu cấu hình bằng SharedPreferences. MAC được sinh 1 lần duy nhất ở lần chạy đầu. */
class DeviceConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("device_config", Context.MODE_PRIVATE)

    fun load(): DeviceConfig {
        var mac = prefs.getString("mac", null)
        if (mac.isNullOrBlank()) {
            mac = DeviceConfig.generateMac()
            prefs.edit().putString("mac", mac).apply()
        }
        val d = DeviceConfig()
        return DeviceConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            apiBaseUrl = prefs.getString("api_base_url", d.apiBaseUrl) ?: d.apiBaseUrl,
            brokerHost = prefs.getString("broker_host", d.brokerHost) ?: d.brokerHost,
            brokerPort = prefs.getInt("broker_port", d.brokerPort),
            useTls = prefs.getBoolean("use_tls", d.useTls),
            macAddress = mac,
            salt = prefs.getString("salt", d.salt) ?: "",
            applyServerThreshold = prefs.getBoolean("apply_server_threshold", d.applyServerThreshold)
        )
    }

    fun save(c: DeviceConfig) {
        prefs.edit()
            .putBoolean("enabled", c.enabled)
            .putString("api_base_url", c.apiBaseUrl.trim().trimEnd('/'))
            .putString("broker_host", c.brokerHost.trim())
            .putInt("broker_port", c.brokerPort)
            .putBoolean("use_tls", c.useTls)
            .putString("mac", c.macAddress.trim().uppercase())
            .putString("salt", c.salt)
            .putBoolean("apply_server_threshold", c.applyServerThreshold)
            .apply()
    }
}
