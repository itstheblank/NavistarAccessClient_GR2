package com.example.navistaraccessclient.iot.protocol

/**
 * Bản đồ topic (mục 3 của tài liệu).
 *  - Thiết bị → server: topic CỐ ĐỊNH, MAC nằm trong payload ("MacAddress").
 *  - Server → thiết bị: MAC nằm CUỐI topic: iot/server/<type>/<MAC>.
 * ACL của EMQX chỉ cho publish iot/devices/# và subscribe iot/server/+/<MAC của mình>.
 */
object Topics {
    // ---- Thiết bị gửi lên (QoS theo bảng trong tài liệu) ----
    const val HEALTHCHECK = "iot/devices/healthcheck"                 // QoS 0
    const val ACCESS = "iot/devices/access"                           // QoS 2
    const val DOOR_STATUS = "iot/devices/doorstatus"                  // QoS 1
    const val COMMAND_RESP = "iot/devices/command_resp"               // QoS 2
    const val DEVICE_SYNC_BIO = "iot/devices/device_sync_bio"         // QoS 2
    const val DEVICE_RECEIVED_BIO = "iot/devices/device_received_bio" // QoS 2
    const val SYNC_REQUIRED_DOOR = "iot/devices/sync_required_door"   // QoS 1
    const val SETTING_CHANGED = "iot/devices/setting_changed"         // QoS 1

    const val QOS_HEALTHCHECK = 0
    const val QOS_ACCESS = 2
    const val QOS_DOOR_STATUS = 1
    const val QOS_COMMAND_RESP = 2
    const val QOS_SETTING_CHANGED = 1
    const val QOS_SUBSCRIBE = 2

    /** 1 filter duy nhất để nhận mọi bản tin của server cho thiết bị này */
    fun serverFilter(mac: String) = "iot/server/+/$mac"

    /** Các loại bản tin server gửi xuống (phần <type> trong topic) */
    enum class ServerType(val wire: String) {
        PUSH_CONFIG("push_config"),
        COMMAND("command"),
        PUSH_BIOMETRIC("push_biometric"),
        PUSH_REQUIRED_DOOR("push_required_door"),
        NOTIFY_ACCESS_DEPEND("notify_access_depend"),
        UPDATE_APK("update_apk");

        companion object {
            fun fromWire(s: String): ServerType? = entries.firstOrNull { it.wire == s }
        }
    }

    /**
     * Tách loại bản tin từ topic "iot/server/<type>/<MAC>".
     * Trả null nếu topic không đúng mẫu hoặc MAC không phải của mình.
     */
    fun parseServerTopic(topic: String, myMac: String): ServerType? {
        val parts = topic.split('/')
        if (parts.size != 4 || parts[0] != "iot" || parts[1] != "server") return null
        if (!parts[3].equals(myMac, ignoreCase = true)) return null
        return ServerType.fromWire(parts[2])
    }
}
