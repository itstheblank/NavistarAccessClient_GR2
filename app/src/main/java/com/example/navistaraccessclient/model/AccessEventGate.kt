package com.example.navistaraccessclient.model

/**
 * "Cổng sự kiện" — biến luồng kết quả nhận diện liên tục (2 lần/giây) thành
 * các SỰ KIỆN RA VÀO rời rạc, để không spam Broker / không bấm Relay liên tục.
 *
 * Vấn đề nếu không có lớp này: người đứng trước cửa 5 giây → verify() chạy ~10 lần
 * → 10 bản tin AccessLog + 10 lệnh mở cửa.
 *
 * Luật:
 *  1. Xác nhận nhiều frame liên tiếp: SUCCESS cần [successConfirmations] frame cùng 1 người,
 *     FAILED cần [deniedConfirmations] frame (tránh báo "người lạ" chỉ vì 1 frame nghiêng mặt).
 *  2. Cooldown: cùng 1 người chỉ được log lại sau [sameUserCooldownMs];
 *     người lạ chỉ log lại sau [strangerCooldownMs].
 *  3. Mặt rời khỏi Oval → gọi [resetStreak] để đếm lại từ đầu.
 *
 * Lớp này thuần Kotlin, không phụ thuộc Android → unit test được trên máy tính.
 * Chỉ gọi từ 1 luồng (Main) — không cần đồng bộ.
 */
class AccessEventGate(
    private val successConfirmations: Int = 2,
    private val deniedConfirmations: Int = 3,
    private val sameUserCooldownMs: Long = 10_000L,
    private val strangerCooldownMs: Long = 15_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private companion object {
        const val STRANGER_KEY = "__STRANGER__"
    }

    private var streakKey: String? = null
    private var streakCount = 0
    private val lastEmitted = HashMap<String, Long>()

    /**
     * Đưa 1 kết quả verify() vào cổng.
     * @return kết quả cần phát thành sự kiện (publish log / mở cửa), hoặc null nếu bỏ qua.
     */
    fun submit(result: FaceAuthResult): FaceAuthResult? {
        val key = when (result.status) {
            AuthStatus.SUCCESS -> result.userId ?: return null
            AuthStatus.FAILED -> STRANGER_KEY
            else -> { resetStreak(); return null }
        }

        if (key == streakKey) streakCount++ else { streakKey = key; streakCount = 1 }

        val needed = if (key == STRANGER_KEY) deniedConfirmations else successConfirmations
        if (streakCount < needed) return null

        val now = clock()
        val cooldown = if (key == STRANGER_KEY) strangerCooldownMs else sameUserCooldownMs
        val last = lastEmitted[key]
        if (last != null && now - last < cooldown) return null

        lastEmitted[key] = now
        // Dọn các mục quá cũ để map không phình mãi khi chạy 24/7
        if (lastEmitted.size > 200) {
            val maxCooldown = maxOf(sameUserCooldownMs, strangerCooldownMs)
            lastEmitted.entries.removeAll { now - it.value > maxCooldown }
        }
        return result
    }

    fun resetStreak() {
        streakKey = null
        streakCount = 0
    }
}
