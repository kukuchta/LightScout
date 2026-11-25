package app.lightscout.feature.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * TST-06 S-05 (CP-14 §9): Continue disabled with linked helper; tick → enabled, helper hidden; „Continue” calls
 * {@code acceptDisclaimer()} once and opens S-06 (onboarding); back exits without calling it; checkbox survives a
 * dark-mode change; 200 % font scale (sticky Continue + helper visible, text scrolls); TalkBack basics (heading,
 * list). Compiled in SS-11; run on RD-2 (API 33) over USB (CP-00 §10 CR-04).
 */
@RunWith(AndroidJUnit4.class)
public class DisclaimerScreenDeviceTest {

    private TestOnboardingApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        DeviceShell.run("settings put system font_scale 1.0");
    }

    private ActivityScenario<TestHostActivity> launch() {
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class));
    }

    @Test
    public void continueDisabledWithLinkedHelper() {
        try (ActivityScenario<TestHostActivity> ignored = launch()) {
            onView(withId(R.id.s05_continue)).check(matches(not(isEnabled())));
            onView(withId(R.id.s05_continue)).check(matches(
                    withContentDescription("Continue, disabled. Tick the box to continue")));
            onView(withId(R.id.s05_helper)).check(matches(isDisplayed()));
            onView(withId(R.id.s05_helper)).check(matches(withText("Tick the box to continue")));
        }
    }

    @Test
    public void tickEnablesContinueAndHidesHelper() {
        try (ActivityScenario<TestHostActivity> ignored = launch()) {
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo(), click());
            onView(withId(R.id.s05_continue)).check(matches(isEnabled()));
            onView(withId(R.id.s05_helper)).check(matches(withEffectiveVisibility(Visibility.GONE)));
        }
    }

    @Test
    public void continueAcceptsOnceAndOpensConnection() {
        try (ActivityScenario<TestHostActivity> scenario = launch()) {
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo(), click());
            onView(withId(R.id.s05_continue)).perform(click());
            onView(withText("Connect to Nightscout")).check(matches(isDisplayed()));
            assertEquals(1, app.onboarding.accepts.get());
            scenario.onActivity(a -> assertEquals(R.id.connectionFragment,
                    a.navController().getCurrentDestination().getId()));
        }
    }

    @Test
    public void backExitsWithoutAccepting() {
        try (ActivityScenario<TestHostActivity> scenario = launch()) {
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo(), click());
            pressBackUnconditionally();
            waitForDestroyed(scenario);
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
            assertEquals(0, app.onboarding.accepts.get());
        }
    }

    @Test
    public void checkboxSurvivesDarkModeChange() {
        try (ActivityScenario<TestHostActivity> ignored = launch()) {
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo(), click());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withId(R.id.s05_acknowledge)).check(matches(isChecked()));
            onView(withId(R.id.s05_continue)).check(matches(isEnabled()));
            assertEquals(0, app.onboarding.accepts.get());
        }
    }

    @Test
    public void fontScale200KeepsStickyContinueAndHelperVisible() {
        DeviceShell.run("settings put system font_scale 2.0");
        try (ActivityScenario<TestHostActivity> ignored = launch()) {
            onView(withId(R.id.s05_continue)).check(matches(isDisplayed()));
            onView(withId(R.id.s05_helper)).check(matches(isDisplayed()));
            onView(withId(R.id.s05_bullet_5)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test
    public void titleIsHeadingAndBulletsAreAList() {
        try (ActivityScenario<TestHostActivity> scenario = launch()) {
            AtomicReference<Boolean> heading = new AtomicReference<>(false);
            AtomicReference<Integer> rows = new AtomicReference<>(0);
            scenario.onActivity(a -> {
                com.google.android.material.appbar.MaterialToolbar toolbar = a.findViewById(R.id.s05_toolbar);
                for (int i = 0; i < toolbar.getChildCount(); i++) {
                    View c = toolbar.getChildAt(i);
                    if (c instanceof android.widget.TextView && ViewCompat.isAccessibilityHeading(c)) {
                        heading.set(true);
                    }
                }
                View list = a.findViewById(R.id.s05_bullets);
                AccessibilityNodeInfo info = list.createAccessibilityNodeInfo();
                rows.set(info.getCollectionInfo() == null ? 0 : info.getCollectionInfo().getRowCount());
            });
            assertTrue(heading.get());
            assertEquals(5, (int) rows.get());
        }
    }

    private static void waitForDestroyed(ActivityScenario<TestHostActivity> scenario) {
        long end = SystemClock.uptimeMillis() + 5_000;
        while (scenario.getState() != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(50);
        }
    }
}
