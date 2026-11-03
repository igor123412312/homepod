package com.airplay.streamer

import com.airplay.streamer.QuickConnectPolicy.ConnectionAction

fun runPolicyChecks(): Int {
    var checks = 0
    fun verify(expected: Boolean, label: String) { check(expected) { label }; checks++ }
    val ids = setOf("homepod", "other", "office")
    verify(QuickConnectPolicy.choose("homepod", ids, ids) == "homepod", "prefer saved")
    verify(QuickConnectPolicy.choose("homepod", ids, setOf("other")) == null, "offline favorite never changes")
    verify(QuickConnectPolicy.choose(null, setOf("office"), ids) == "office", "one saved")
    verify(QuickConnectPolicy.choose(null, setOf("office", "homepod"), ids) == null, "ambiguous saved")
    verify(QuickConnectPolicy.choose(null, emptySet(), ids) == null, "never choose stranger")
    verify(QuickConnectPolicy.choose("", emptySet(), emptySet()) == null, "empty list")

    // Exhaustively exercise 16 combinations of click preconditions.
    for (mask in 0 until 16) {
        val pending = mask and 1 != 0
        val permission = mask and 2 != 0
        val youtube = mask and 4 != 0
        val active = mask and 8 != 0
        val expected = when {
            !permission -> ConnectionAction.ASK_PERMISSIONS
            pending -> ConnectionAction.BUSY
            !youtube -> ConnectionAction.REQUIRE_YOUTUBE
            active -> ConnectionAction.OPEN_YOUTUBE
            else -> ConnectionAction.CONNECT
        }
        verify(QuickConnectPolicy.connectAction(pending, permission, youtube, active) == expected, "connect case $mask")
    }
    for (capture in listOf(true, false)) for (playing in listOf(true, false)) {
        verify(QuickConnectPolicy.readyToLaunch(capture, playing) == (capture && playing), "readyToLaunch $capture/$playing")
    }
    for (online in listOf(true, false)) for (pending in listOf(true, false)) {
        verify(QuickConnectPolicy.shouldEnableConnect(online, pending) == (online && !pending), "connect enabled $online/$pending")
    }
    for (capture in listOf(true, false)) for (pending in listOf(true, false)) {
        verify(QuickConnectPolicy.shouldEnableStop(capture, pending) == (capture || pending), "stop enabled $capture/$pending")
    }
    verify(QuickConnectPolicy.canAdjustRemoteVolume(true), "volume selected")
    verify(!QuickConnectPolicy.canAdjustRemoteVolume(false), "volume no selected")
    for (pct in listOf(-30, 0, 1, 10, 50, 99, 100, 200)) {
        val fraction = QuickConnectPolicy.volumeFraction(pct)
        verify(fraction in 0f..1f && kotlin.math.abs(fraction * 100 - pct.coerceIn(0, 100)) < .001f, "volume $pct")
    }
    return checks
}

fun main() { println("PASS: ${runPolicyChecks()} JVM interaction policy checks") }
