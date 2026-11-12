package com.example.navistaraccessclient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Chạy trên máy tính (không cần giả lập): chuột phải → Run 'AccessEventGateTest' */
class AccessEventGateTest {

    private var now = 0L
    private lateinit var gate: AccessEventGate

    private fun ok(id: String = "USER_A") =
        FaceAuthResult(AuthStatus.SUCCESS, userId = id, userName = id, confidence = 0.9f)
    private fun fail() = FaceAuthResult(AuthStatus.FAILED, confidence = 0.3f)

    @Before fun setUp() {
        now = 0L
        gate = AccessEventGate(
            successConfirmations = 2, deniedConfirmations = 3,
            sameUserCooldownMs = 10_000, strangerCooldownMs = 15_000,
            clock = { now }
        )
    }

    @Test fun success_needsTwoConsecutiveFrames() {
        assertNull(gate.submit(ok()))
        assertNotNull(gate.submit(ok()))
    }

    @Test fun sameUser_isNotRepublishedWithinCooldown() {
        gate.submit(ok()); assertNotNull(gate.submit(ok()))
        now = 5_000
        repeat(10) { assertNull(gate.submit(ok())) } // đứng lì trước cửa → không spam
        now = 10_001
        assertNotNull(gate.submit(ok()))
    }

    @Test fun stranger_needsThreeFrames_thenCooldown() {
        assertNull(gate.submit(fail()))
        assertNull(gate.submit(fail()))
        assertNotNull(gate.submit(fail()))
        now = 14_000
        assertNull(gate.submit(fail()))
        now = 15_001
        assertNotNull(gate.submit(fail()))
    }

    @Test fun oneBadFrame_breaksStreak() {
        gate.submit(fail()); gate.submit(fail())
        gate.submit(ok())                 // 1 frame nhận ra người quen → reset chuỗi người lạ
        assertNull(gate.submit(fail()))
        assertNull(gate.submit(fail()))
        assertNotNull(gate.submit(fail()))
    }

    @Test fun differentUsers_areIndependent() {
        gate.submit(ok("A")); assertNotNull(gate.submit(ok("A")))
        gate.submit(ok("B")); val r = gate.submit(ok("B"))
        assertEquals("B", r?.userId)
    }

    @Test fun resetStreak_whenFaceLeavesOval() {
        gate.submit(ok())
        gate.resetStreak()
        assertNull(gate.submit(ok()))     // phải đếm lại từ đầu
        assertNotNull(gate.submit(ok()))
    }

    @Test fun nonFinalStatuses_areIgnored() {
        assertNull(gate.submit(FaceAuthResult(AuthStatus.DETECTED)))
        assertNull(gate.submit(FaceAuthResult(AuthStatus.MULTIPLE_FACES)))
        assertNull(gate.submit(FaceAuthResult(AuthStatus.IDLE)))
    }
}
