package com.example.navistaraccessclient.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.navistaraccessclient.model.RegisteredUser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lưu danh sách người đã đăng ký (tên + vector 192 chiều) xuống bộ nhớ trong của app.
 * → Khắc phục hạn chế GĐ 2: "tắt app là mất người dùng".
 *
 * - File JSON ~3–4 KB / người → 100 người ≈ 400 KB, đọc 1 lần khi khởi động.
 * - Dùng AtomicFile: nếu mất điện giữa lúc ghi, file cũ vẫn còn nguyên (quan trọng với thiết bị gắn tường).
 * - KHÔNG lưu ảnh khuôn mặt, chỉ lưu vector → nhẹ và đỡ nhạy cảm dữ liệu hơn.
 * - Nên LOẠI file này khỏi Auto Backup (dữ liệu sinh trắc học) — xem res/xml/backup_rules.xml
 *   và data_extraction_rules.xml.
 *
 * Các hàm đều chạy I/O → gọi trên Dispatchers.IO / Default, không gọi trên Main.
 */
class FaceStore(context: Context, fileName: String = "registered_faces.json") {

    private val file = AtomicFile(File(context.filesDir, fileName))
    private val lock = Any()

    fun loadAll(): List<RegisteredUser> = synchronized(lock) {
        try {
            if (!file.baseFile.exists()) return emptyList()
            val text = file.readFully().toString(Charsets.UTF_8)
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val emb = o.getJSONArray("embedding")
                RegisteredUser(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    embedding = FloatArray(emb.length()) { j -> emb.getDouble(j).toFloat() }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Đọc $file lỗi → bắt đầu với danh sách rỗng", e)
            emptyList()
        }
    }

    fun saveAll(users: Collection<RegisteredUser>) = synchronized(lock) {
        val arr = JSONArray()
        users.forEach { u ->
            arr.put(JSONObject().apply {
                put("id", u.id)
                put("name", u.name)
                put("embedding", JSONArray().apply { u.embedding.forEach { put(it.toDouble()) } })
            })
        }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "Ghi $file lỗi", e)
        }
    }

    private companion object { const val TAG = "FaceStore" }
}
