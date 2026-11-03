package com.airplay.streamer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure state tests executed by Gradle on a JVM, without needing a HomePod. */
class PolicyJUnitTest {
    @Test fun exhaustiveConnectClickAndStatusPolicies() {
        assertEquals(44, runPolicyChecks())
    }
}
