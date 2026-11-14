package com.example.navistaraccessclient.iot

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.navistaraccessclient.iot.protocol.AccessMsg
import org.json.JSONArray
import java.io.File

/**
 * Hàng đợi bản tin "access" phát sinh lúc mất kết nối (ghi chú "Offline history" mục 4.2).
 * Khi có mạng lại: gửi theo thứ tự cũ → mới, đánh dấu IsResend = true và GIỮ NGUYÊN AccessTime gốc
 * (server dùng AccessTime để bỏ bản ghi trùng).
 *
 * Bản đơn giản: 1 file JSON (AtomicFile), tối đa [capacity] bản ghi, đầy thì bỏ bản cũ nhất.
 * Bản Room Database để GĐ 4 như kế hoạch.
 * Không lưu FaceImg vào hàng đợi để file nhỏ.
 * Chỉ gọi từ 1 luồng (worker của DeviceAgent).
 */
class OfflineAccessQueue(context: Context, private val capacity: Int = 500) {
    private val file = AtomicFile(File(context.filesDir, "offline_access.json"))

    private fun readAll(): MutableList<String> = try {
        if (!file.baseFile.exists()) mutableListOf()
        else JSONArray(file.readFully().toString(Charsets.UTF_8)).let { a -> MutableList(a.length()) { a.getString(it) } }
    } catch (e: Exception) {
        Log.e(TAG, "Đọc hàng đợi lỗi → bỏ", e); mutableListOf()
    }

    private fun writeAll(items: List<String>) {
        val out = file.startWrite()
        try {
            out.write(JSONArray(items).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out); Log.e(TAG, "Ghi hàng đợi lỗi", e)
        }
    }

    fun add(msg: AccessMsg) {
        val items = readAll()
        items.add(msg.copy(isResend = true, faceImg = null).toJson())
        while (items.size > capacity) items.removeAt(0)
        writeAll(items)
    }

    fun size(): Int = readAll().size

    /**
     * Gửi lần lượt. [send] trả false (mất mạng giữa chừng) → dừng, giữ lại phần chưa gửi.
     * @return số bản đã gửi
     */
    fun drain(send: (AccessMsg) -> Boolean): Int {
        val items = readAll()
        if (items.isEmpty()) return 0
        var sent = 0
        for (json in items) {
            val msg = try { AccessMsg.fromJson(json) } catch (e: Exception) { sent++; continue } // bản hỏng → bỏ
            if (!send(msg.copy(isResend = true))) break
            sent++
        }
        writeAll(items.drop(sent))
        return sent
    }

    private companion object { const val TAG = "OfflineAccessQueue" }
}
