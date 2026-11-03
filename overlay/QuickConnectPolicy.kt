package com.airplay.streamer

/** Pure policies shared by the UI and their JVM regression tests. */
internal object QuickConnectPolicy {
    enum class ConnectionAction { ASK_PERMISSIONS, BUSY, REQUIRE_YOUTUBE, OPEN_YOUTUBE, CONNECT }

    fun choose(preferred: String?, last: Set<String>, online: Set<String>): String? {
        if (!preferred.isNullOrBlank()) return preferred.takeIf { it in online }
        return last.intersect(online).singleOrNull()
    }

    fun connectAction(
        pending: Boolean,
        hasPermissions: Boolean,
        youtubeInstalled: Boolean,
        alreadyPlayingOnTarget: Boolean
    ): ConnectionAction = when {
        !hasPermissions -> ConnectionAction.ASK_PERMISSIONS
        pending -> ConnectionAction.BUSY
        !youtubeInstalled -> ConnectionAction.REQUIRE_YOUTUBE
        alreadyPlayingOnTarget -> ConnectionAction.OPEN_YOUTUBE
        else -> ConnectionAction.CONNECT
    }

    fun readyToLaunch(capturing: Boolean, receiverPlaying: Boolean): Boolean =
        capturing && receiverPlaying

    fun shouldEnableConnect(targetOnline: Boolean, pending: Boolean): Boolean = targetOnline && !pending
    fun shouldEnableStop(capturing: Boolean, pending: Boolean): Boolean = capturing || pending
    fun canAdjustRemoteVolume(targetSelected: Boolean): Boolean = targetSelected
    fun volumeFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f
}
