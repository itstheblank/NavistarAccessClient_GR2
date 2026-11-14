package com.example.navistaraccessclient.iot

import android.util.Log
import com.example.navistaraccessclient.iot.protocol.Topics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.packet.MqttProperties

sealed class ConnState {
    object Disabled : ConnState()
    object FetchingToken : ConnState()
    object Connecting : ConnState()
    data class Connected(val uri: String) : ConnState()
    /** Đang chờ để thử lại. [reason] hiển thị cho người dùng. */
    data class Waiting(val reason: String, val retryInSec: Long) : ConnState()
    data class Misconfigured(val missing: List<String>) : ConnState()
}

/**
 * Kết nối MQTT 5 tới EMQX của Lab theo mục 2.2 / 2.4 của tài liệu:
 *  - ClientId = Username = MAC, Password = token JWT
 *  - Clean start = false + Session expiry 7 ngày → bản tin QoS 1/2 gửi lúc offline được giao bù
 *  - Keep alive 60 s
 *  - Sau MỖI lần kết nối: subscribe "iot/server/+/<MAC>" QoS 2
 *  - Mất kết nối: chờ ~5 s → LẤY TOKEN MỚI → kết nối lại
 *
 * KHÔNG bật isAutomaticReconnect của Paho: Paho sẽ kết nối lại bằng mật khẩu cũ,
 * mà token hết hạn sau 600 s → EMQX từ chối. Vòng lặp [runLoop] tự làm việc này.
 *
 * Các lệnh Paho được gọi dạng chặn (waitForCompletion) trên Dispatchers.IO cho đơn giản.
 */
class MqttV5Connection(
    private val scope: CoroutineScope,
    private val tokenClient: TokenClient = TokenClient(),
    /** Gọi trên luồng của Paho — bên nhận phải tự chuyển việc nặng sang luồng khác */
    private val onMessage: (topic: String, payload: String) -> Unit,
    /** Gọi trên Dispatchers.IO ngay sau khi kết nối + subscribe xong (gửi healthcheck, xả hàng đợi offline, ...) */
    private val onConnected: () -> Unit
) {
    companion object {
        private const val TAG = "MqttV5Connection"
        private const val SESSION_EXPIRY_SEC = 7L * 24 * 3600
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }

    private val _state = MutableStateFlow<ConnState>(ConnState.Disabled)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile private var lost: CompletableDeferred<String>? = null
    @Volatile private var stopping = false
    @Volatile private var loopJob: Job? = null
    private val startMutex = Mutex() // 2 lần bấm Lưu liên tiếp không tạo 2 client cùng ClientId

    val isConnected: Boolean get() = client?.isConnected == true

    /** Áp dụng cấu hình (gọi lại mỗi khi người dùng bấm Lưu). */
    fun start(config: DeviceConfig) {
        scope.launch(Dispatchers.IO) {
            startMutex.withLock {
                stopInternal()
                when {
                    !config.enabled -> { _state.value = ConnState.Disabled }
                    config.missingFields().isNotEmpty() -> { _state.value = ConnState.Misconfigured(config.missingFields()) }
                    config.useTls -> { _state.value = ConnState.Waiting("TLS chưa được hỗ trợ trong bản này", 0) }
                    else -> { loopJob = scope.launch(Dispatchers.IO) { runLoop(config) } }
                }
            }
        }
    }

    private suspend fun runLoop(cfg: DeviceConfig) {
        stopping = false
        var backoff = RECONNECT_DELAY_MS
        val c = try {
            MqttAsyncClient(cfg.brokerUri, cfg.macAddress, MemoryPersistence()).also {
                it.setCallback(callback)
                client = it
            }
        } catch (e: MqttException) {
            _state.value = ConnState.Waiting("Địa chỉ broker không hợp lệ: ${e.message}", 0)
            return
        }

        while (currentCoroutineContext().isActive && !stopping) {
            // 1) Token mới cho MỖI lần kết nối
            _state.value = ConnState.FetchingToken
            val token = try {
                tokenClient.fetch(cfg.apiBaseUrl, cfg.macAddress, cfg.salt)
            } catch (e: TokenException) {
                waitRetry("Lấy token lỗi: ${e.message}", backoff); backoff = next(backoff); continue
            } catch (e: Exception) {
                waitRetry("Không gọi được ApiGateway: ${e.javaClass.simpleName} ${e.message ?: ""}", backoff)
                backoff = next(backoff); continue
            }

            // 2) CONNECT
            _state.value = ConnState.Connecting
            val options = MqttConnectionOptions().apply {
                setCleanStart(false)
                setSessionExpiryInterval(SESSION_EXPIRY_SEC)
                setKeepAliveInterval(60)
                setConnectionTimeout(15)
                setAutomaticReconnect(false)
                setUserName(cfg.macAddress)
                setPassword(token.token.toByteArray(Charsets.UTF_8))
            }
            val signal = CompletableDeferred<String>()
            lost = signal
            try {
                c.connect(options).waitForCompletion(20_000)
            } catch (e: MqttException) {
                waitRetry("Kết nối broker lỗi: ${describe(e)}", backoff); backoff = next(backoff); continue
            }

            // 3) SUBSCRIBE (bắt buộc sau mỗi lần kết nối)
            try {
                val sub: IMqttToken = c.subscribe(Topics.serverFilter(cfg.macAddress), Topics.QOS_SUBSCRIBE)
                sub.waitForCompletion(10_000)
                val codes = sub.reasonCodes
                if (codes != null && codes.any { it >= 0x80 }) {
                    // 0x87 = Not authorized → ACL từ chối (sai MAC trong topic?)
                    Log.e(TAG, "Subscribe bị từ chối, reason codes=${codes.joinToString()}")
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Subscribe lỗi", e)
            }

            backoff = RECONNECT_DELAY_MS
            _state.value = ConnState.Connected(cfg.brokerUri)
            Log.i(TAG, "Đã kết nối ${cfg.brokerUri} với ClientId=${cfg.macAddress}")
            try { onConnected() } catch (e: Exception) { Log.e(TAG, "onConnected lỗi", e) }

            // 4) Chờ tới khi mất kết nối, rồi quay lại bước 1
            val reason = signal.await()
            if (stopping) break
            waitRetry("Mất kết nối: $reason", RECONNECT_DELAY_MS)
        }
    }

    private suspend fun waitRetry(reason: String, ms: Long) {
        Log.w(TAG, "$reason → thử lại sau ${ms / 1000}s")
        _state.value = ConnState.Waiting(reason, ms / 1000)
        delay(ms)
    }

    private fun next(ms: Long) = (ms * 2).coerceAtMost(MAX_BACKOFF_MS)

    private val callback = object : MqttCallback {
        override fun disconnected(disconnectResponse: MqttDisconnectResponse?) {
            val reason = disconnectResponse?.reasonString
                ?: disconnectResponse?.exception?.let { describe(it) }
                ?: "code ${disconnectResponse?.returnCode}"
            Log.w(TAG, "disconnected: $reason")
            lost?.complete(reason)
        }

        override fun mqttErrorOccurred(exception: MqttException?) {
            Log.e(TAG, "mqttErrorOccurred", exception)
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            if (topic == null || message == null) return
            // Không để exception lọt ra ngoài: Paho sẽ NGẮT kết nối nếu callback ném lỗi
            try {
                onMessage(topic, String(message.payload, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "Xử lý bản tin $topic lỗi", e)
            }
        }

        override fun deliveryComplete(token: IMqttToken?) {}
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {}
        override fun authPacketArrived(reasonCode: Int, properties: MqttProperties?) {}
    }

    /** Gửi bản tin. Trả false nếu đang offline (bên gọi tự quyết định có lưu lại để gửi sau không). */
    fun publish(topic: String, payload: String, qos: Int, retained: Boolean = false): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return try {
            c.publish(topic, payload.toByteArray(Charsets.UTF_8), qos, retained)
            Log.d(TAG, "→ $topic $payload")
            true
        } catch (e: MqttException) {
            Log.e(TAG, "Publish $topic lỗi: ${describe(e)}")
            false
        }
    }

    private fun stopInternal() {
        stopping = true
        loopJob?.cancel()
        loopJob = null
        lost?.complete("stopped")
        val c = client ?: return
        client = null
        try { if (c.isConnected) c.disconnect().waitForCompletion(2_000) } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
    }

    /** Gọi trong onCleared() */
    fun shutdown() {
        Thread { stopInternal() }.start()
        _state.value = ConnState.Disabled
    }

    private fun describe(e: Throwable): String {
        if (e is MqttException) {
            return when (e.reasonCode) {
                134, 135 -> "Sai username/token hoặc không có quyền (${e.reasonCode})"
                MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() -> "Không tới được broker (sai IP/port, firewall?)"
                MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "Hết thời gian chờ broker"
                else -> "Lỗi MQTT ${e.reasonCode}: ${e.cause?.message ?: e.message}"
            }
        }
        return e.message ?: e.javaClass.simpleName
    }
}
