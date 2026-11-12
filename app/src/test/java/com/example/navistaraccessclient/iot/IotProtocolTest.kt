package com.example.navistaraccessclient.iot

import com.example.navistaraccessclient.iot.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Kiểm tra code khớp với "Device MQTT Integration Guide" — dùng ĐÚNG các JSON mẫu trong tài liệu.
 * Chạy trên máy tính: chuột phải thư mục test → Run Tests (cần testImplementation org.json).
 */
class IotProtocolTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    // ---------- 2.1 Token ----------
    @Test fun password_isBase64OfSha256OfMacPlusSalt() {
        // Giá trị tham chiếu tính bằng Python: base64(sha256("AA:BB:CC:DD:EE:FF" + "test_salt"))
        assertEquals("zT5wKSCh5tOdEWCADtz5AoZtY3Q80hSOqSCzr3MeFCg=", TokenClient.password(mac, "test_salt"))
    }

    @Test fun generatedMac_isValidLocallyAdministered() {
        repeat(20) {
            val m = DeviceConfig.generateMac()
            assertTrue(m, DeviceConfig.isValidMac(m))
            val first = m.substring(0, 2).toInt(16)
            assertEquals("bit locally-administered", 2, first and 0x02)
            assertEquals("unicast", 0, first and 0x01)
        }
    }

    // ---------- 3. Topics ----------
    @Test fun serverTopic_parsedOnlyForOwnMac() {
        assertEquals(Topics.ServerType.COMMAND, Topics.parseServerTopic("iot/server/command/$mac", mac))
        assertEquals(Topics.ServerType.PUSH_CONFIG, Topics.parseServerTopic("iot/server/push_config/$mac", mac))
        assertNull(Topics.parseServerTopic("iot/server/command/11:22:33:44:55:66", mac))
        assertNull(Topics.parseServerTopic("iot/devices/access", mac))
        assertNull(Topics.parseServerTopic("iot/server/unknown_type/$mac", mac))
        assertEquals("iot/server/+/$mac", Topics.serverFilter(mac))
    }

    // ---------- 4.1 healthcheck ----------
    @Test fun healthcheck_hasPascalCaseKeys() {
        val o = JSONObject(HealthCheckMsg(
            mac, "192.168.1.50", "2026-10-22T03:15:00Z", false, false, DoorStatus.CLOSE,
            20261022001L, BioAuthType(direction = Direction.IN)
        ).toJson())
        for (k in listOf("MacAddress", "IpAddress", "DeviceTime", "IsDeviceDisable", "IsEmergencyOpen", "DoorStatus", "Version", "BioAuthType"))
            assertTrue("thiếu $k", o.has(k))
        assertEquals(20261022001L, o.getLong("Version"))
        assertEquals("IN", o.getJSONObject("BioAuthType").getString("Direction"))
    }

    @Test fun healthcheck_directionNullBeforeConfig() {
        val o = JSONObject(HealthCheckMsg(mac, null, "t", false, false, null, 1, BioAuthType()).toJson())
        assertTrue(o.getJSONObject("BioAuthType").isNull("Direction"))
    }

    // ---------- 4.2 access ----------
    @Test fun access_strangerHasExplicitNulls() {
        val o = JSONObject(AccessMsg(
            macAddress = mac, bioId = null, idNumber = null, accessTime = "2026-10-22T03:15:00Z",
            direction = Direction.IN, isAuthenSuccess = false, failedCode = FailedCode.FACE_NOT_MATCH,
            abnormal = true, doorStatus = DoorStatus.CLOSE, rgbScore = 0.4123
        ).toJson())
        assertTrue(o.has("BioId")); assertTrue(o.isNull("BioId"))
        assertTrue(o.isNull("IdNumber"))
        assertEquals("FACE_NOT_MATCH", o.getString("FailedCode"))
        assertEquals("face", o.getString("AuthenType"))
        assertEquals(0.412, o.getDouble("RgbScore"), 1e-9)
        assertFalse(o.getBoolean("IsResend"))
    }

    @Test fun access_roundTrip_keepsAccessTime() {
        val a = AccessMsg(mac, 1234, "001099012345", "2026-10-22T03:15:00Z", Direction.IN,
            isAuthenSuccess = true, doorStatus = DoorStatus.OPEN, rgbScore = 0.93)
        val b = AccessMsg.fromJson(a.copy(isResend = true).toJson())
        assertEquals("2026-10-22T03:15:00Z", b.accessTime)
        assertTrue(b.isResend)
        assertEquals(1234L, b.bioId)
        assertEquals(DoorStatus.OPEN, b.doorStatus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun doorstatus_rejectsEmergencyOpen() {
        DoorStatusMsg(mac, DoorStatus.EMERGENCYOPEN, "t")
    }

    @Test fun settingChanged_settingsAreStrings() {
        val o = JSONObject(SettingChangedMsg(mac, "t", JSONObject().put("SECURITY_LEVEL", 1), JSONObject().put("SECURITY_LEVEL", 2)).toJson())
        assertEquals("{\"SECURITY_LEVEL\":1}", o.getString("OldSettings"))
    }

    @Test fun isoUtc_format() {
        assertEquals("2026-10-22T03:15:00Z", IotTime.isoUtc(Instant.parse("2026-10-22T03:15:00.789Z")))
    }

    // ---------- 5.1 push_config ----------
    @Test fun pushConfig_shortForm_onlyOverridesPresentFields() {
        val cfg = PushConfig.parse("""{"BioAuthType":{"IsFace":true,"Direction":"OUT"}}""")
        assertNull(cfg.lockerTimeHoldMs)
        assertNull(cfg.identifyThreshold)
        val old = BioAuthType(isFace = true, oneIsOpen = false, direction = Direction.IN)
        val merged = old.mergedWith(cfg.bioAuthType!!)
        assertEquals(Direction.OUT, merged.direction)
        assertFalse("OneIsOpen không có trong bản tin → giữ giá trị cũ", merged.oneIsOpen)
    }

    @Test fun pushConfig_fullForm() {
        val cfg = PushConfig.parse("""{
          "BioAuthType": {"IsFace": true, "IsFinger": false, "IsIris": false, "IsIdCard": false,
                          "Direction": "IN", "OneIsOpen": true, "OneIsOpenDoorState": false},
          "SECURITY_LEVEL": 1, "IDENTIFY_THRESHOLD": 0.8, "LOCKER_TIME_HOLD": 3000,
          "ONE_IS_OPEN": true, "OFFLINE_MODE": false, "NTP_SERVER": "", "SOME_FUTURE_KEY": 42
        }""")
        assertEquals(0.8, cfg.identifyThreshold!!, 1e-9)
        assertEquals(3000L, cfg.lockerTimeHoldMs)
        assertEquals(42, cfg.raw.getInt("SOME_FUTURE_KEY")) // khoá lạ không làm hỏng việc đọc
    }

    // ---------- 5.2 command ----------
    @Test fun command_parsesNonIsoCmdTime() {
        val c = ServerCommand.parse("""{"MacAddress":"$mac","CmdId":5678,"CmdType":"REMOTE_OPEN",
            "CmdTime":"2026-10-22 03:15:00","CmdTimeout":30}""")
        assertEquals(5678L, c.cmdId)
        assertEquals(CommandType.REMOTE_OPEN, c.cmdType)
        assertEquals(Instant.parse("2026-10-22T03:15:00Z"), c.cmdTime)
        assertFalse(c.isExpired(Instant.parse("2026-10-22T03:15:29Z")))
        assertTrue(c.isExpired(Instant.parse("2026-10-22T03:15:31Z")))
    }

    @Test fun command_unknownTypeStillParses() {
        val c = ServerCommand.parse("""{"CmdId":1,"CmdType":"REBOOT"}""")
        assertNull(c.cmdType)
        assertEquals("REBOOT", c.rawCmdType)
        assertFalse(c.isExpired())
    }

    // ---------- 5.3 push_biometric ----------
    @Test fun pushBiometric_sampleFromGuide() {
        val b = PushBiometric.parse("""{
          "CmdType": "NEW_BIO", "BioId": 1005, "DoorId": 12, "IdNumber": "001099012345",
          "FullName": "Nguyen Van A", "FromDate": "2026-10-22", "ToDate": "2026-10-31",
          "FromTime": "08:00:00", "ToTime": "17:30:00", "ActiveDays": "",
          "LstTimes": [ { "Day": "2026-10-22", "Time": [ { "Start": "08:00:00", "End": "11:00:00" } ] } ],
          "FaceTemps": ["abc"], "IrFaceTemps": ["def"], "FingerTemps": [], "IrisTemps": [],
          "IsNoTemplate": false, "FaceImg": "", "FingerImg": "", "IrisImg": "",
          "SyncEnded": false, "IsCheckIn": false
        }""")
        assertEquals(1005L, b.bioId)
        assertEquals(12L, b.doorId)
        assertEquals(listOf("abc"), b.faceTemps)
        assertNull("FaceImg rỗng → null", b.faceImg)
        assertEquals(1, b.schedule.lstTimes.size)
    }

    @Test fun pushBiometric_deleteOnlyNeedsIds() {
        val b = PushBiometric.parse("""{"CmdType":"DELETE_BIO","BioId":7,"DoorId":12,"SyncEnded":true}""")
        assertEquals("DELETE_BIO", b.cmdType)
        assertTrue(b.syncEnded)
        assertTrue(b.faceTemps.isEmpty())
    }
}
