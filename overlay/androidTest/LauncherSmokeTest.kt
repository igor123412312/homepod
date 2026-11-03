package com.airplay.streamer

import android.Manifest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.rule.GrantPermissionRule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator smoke tests: no physical HomePod or actual YouTube Music is assumed. */
@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {
    private val grant = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )
    private val activity = ActivityScenarioRule(HomePodMusicLauncherActivity::class.java)
    @get:Rule val chain: TestRule = RuleChain.outerRule(grant).around(activity)

    @Test fun homeScreenHasAllActions() {
        onView(withText("Підключити й відкрити YouTube Music")).check(matches(isDisplayed()))
        onView(withText("Зупинити трансляцію")).check(matches(isDisplayed()))
        onView(withText("Оновити пошук колонок")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Відкрити YouTube Music")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Додаткове керування та PIN")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test fun idleStopIsDisabled() {
        onView(withText("Зупинити трансляцію")).check(matches(not(isEnabled())))
    }

    @Test fun scanButtonRespondsWithoutCrashing() {
        onView(withText("Оновити пошук колонок")).perform(scrollTo(), click())
        onView(withText("Оновити пошук колонок")).check(matches(isDisplayed()))
    }
}
