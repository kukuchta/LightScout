package app.lightscout.feature.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.ui.core.ReadinessMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TST-06 S-07 (CP-14 §9): band lists and display order, resume re-check, inline error (M3-30) with live region,
 * „All set”, „Done” (complete + finish / C-S07-07 dialog / check mode without Done), item 7 opens the current
 * versioned channel, F7 guard while ringing (Q-52), GIR-2, process death, 200 % font scale, TalkBack status first.
 * Per CR-04 the run is on RD-2 (API 33); other bands are rendered through the SDK-level probe fake (the band logic
 * itself is JVM-tested in {@code ReadinessModelTest}). Compiled in SS-12; run on RD-2 over USB, then
 * {@code adb uninstall} of app and test APKs (SR-34).
 */
@RunWith(AndroidJUnit4.class)
public class ReadinessScreenDeviceTest {

    private TestOnboardingApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
    }

    @After
    public void tearDown() {
        DeviceShell.run("settings put system font_scale 1.0");
    }

    private ActivityScenario<TestHostActivity> launch(ReadinessMode mode) {
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class)
                .putExtra(TestHostActivity.EXTRA_READINESS, mode.name()));
    }

    private static List<String> headlines(TestHostActivity a) {
        ViewGroup list = a.findViewById(R.id.s07_items);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            out.add(((TextView) list.getChildAt(i).findViewById(R.id.s07_item_headline)).getText().toString());
        }
        return out;
    }

    private static View row(TestHostActivity a, String headline) {
        ViewGroup list = a.findViewById(R.id.s07_items);
        for (int i = 0; i < list.getChildCount(); i++) {
            View r = list.getChildAt(i);
            if (headline.contentEquals(((TextView) r.findViewById(R.id.s07_item_headline)).getText())) {
                return r;
            }
        }
        throw new AssertionError("no row " + headline);
    }

    private static void tapAction(String headline) {
        onView(allOf(withId(R.id.s07_item_action), withParent(hasDescendant(withText(headline)))))
                .perform(scrollTo(), click());
    }

    @Test
    public void band33ListsItemsInDisplayOrder() {
        app.probe.sdk = 33;
        app.probe.exactAlarms = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> assertEquals(List.of("Notifications", "Alarms during Do Not Disturb",
                    "Battery optimisation", "Alarms & reminders", "Alarm notifications", "Manufacturer settings"),
                    headlines(a)));
        }
    }

    @Test
    public void bandFakesShowTheirItems() {
        int[] sdks = {29, 31, 34, 36};
        String[][] expected = {
            {"Alarms during Do Not Disturb", "Battery optimisation", "Alarm notifications", "Manufacturer settings"},
            {"Alarms during Do Not Disturb", "Battery optimisation", "Alarm notifications", "Manufacturer settings"},
            {"Notifications", "Alarms during Do Not Disturb", "Full-screen alarms", "Battery optimisation",
                "Alarm notifications", "Manufacturer settings"},
            {"Notifications", "Alarms during Do Not Disturb", "Full-screen alarms", "Battery optimisation",
                "Alarm notifications", "Manufacturer settings"},
        };
        for (int i = 0; i < sdks.length; i++) {
            app.reset();
            app.probe.sdk = sdks[i];
            final List<String> want = List.of(expected[i]);
            try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
                scenario.onActivity(a -> assertEquals(want, headlines(a)));
            }
        }
    }

    @Test
    public void resumeRechecksStatuses() {
        app.probe.notifications = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> assertEquals("Needed to show alarms and the current reading.",
                    ((TextView) row(a, "Notifications").findViewById(R.id.s07_item_supporting)).getText().toString()));
            app.probe.notifications = true;     // granted in system settings
            app.probe.battery = false;          // battery exemption revoked
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> {
                assertEquals("Done", ((TextView) row(a, "Notifications").findViewById(R.id.s07_item_supporting))
                        .getText().toString());
                assertEquals(View.VISIBLE, row(a, "Battery optimisation").findViewById(R.id.s07_item_action)
                        .getVisibility());
            });
        }
    }

    @Test
    public void settingsPageThatCannotOpenShowsInlineErrorUntilTheCheckPasses() {
        app.probe.dnd = false;
        app.intents.failing = true;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            tapAction("Alarms during Do Not Disturb");
            onView(allOf(withId(R.id.s07_item_error), withEffectiveVisibility(Visibility.VISIBLE)))
                    .check(matches(withText("Couldn't open this setting. Open Settings → Apps → LightScout.")));
            scenario.onActivity(a -> {
                View error = row(a, "Alarms during Do Not Disturb").findViewById(R.id.s07_item_error);
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, error.getAccessibilityLiveRegion());
            });
            app.probe.dnd = true;
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> assertEquals(View.GONE,
                    row(a, "Alarms during Do Not Disturb").findViewById(R.id.s07_item_error).getVisibility()));
        }
    }

    @Test
    public void allRequiredDoneShowsAllSet() {
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.ONBOARDING)) {
            onView(withText("All set — alarms can ring.")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void doneWithAllRequiredCompletesOnboardingAndFinishes() {
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.ONBOARDING)) {
            onView(withId(R.id.s07_done)).perform(click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            waitFor(() -> TestHostActivity.FINISHED_ONBOARDING.get() == 1);
            assertEquals(1, app.onboarding.completes.get());
        }
    }

    @Test
    public void doneWithMissingItemsAsksAndGoBackStaysContinueFinishes() {
        app.probe.battery = false;
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.ONBOARDING)) {
            onView(withId(R.id.s07_done)).perform(click());
            onView(withText("Some settings are missing")).check(matches(isDisplayed()));
            onView(withText("Alarms may not ring. Continue anyway?")).check(matches(isDisplayed()));
            onView(withText("Go back")).perform(click());
            onView(withText("Some settings are missing")).check(doesNotExist());
            assertEquals(0, app.onboarding.completes.get());
            onView(withId(R.id.s07_done)).perform(click());
            onView(withText("Continue")).perform(click());
            waitFor(() -> TestHostActivity.FINISHED_ONBOARDING.get() == 1);
            assertEquals(1, app.onboarding.completes.get());
        }
    }

    @Test
    public void checkModeHasNoDoneButton() {
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.CHECK)) {
            onView(withId(R.id.s07_done)).check(matches(withEffectiveVisibility(Visibility.GONE)));
        }
    }

    @Test
    public void item7OpenSettingsOpensTheCurrentVersionedChannel() {
        app.probe.channelReady = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> assertEquals("Open settings", ((TextView) row(a, "Alarm notifications")
                    .findViewById(R.id.s07_item_action)).getText().toString()));
            tapAction("Alarm notifications");
            waitFor(() -> !RecordingSettingsActivity.OPENED.isEmpty());
            Intent opened = RecordingSettingsActivity.OPENED.get(0);
            assertEquals("item.ALARM_CHANNEL", opened.getAction());
            assertEquals("alarms_v1", opened.getStringExtra(Settings.EXTRA_CHANNEL_ID));
        }
    }

    @Test
    public void resumeRunsF7OnlyWhileNothingRings() {
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.ONBOARDING)) {
            waitFor(() -> app.channels.bypass.get() >= 1);
        }
        app.reset();
        app.status.ringing = AlarmType.URGENT_LOW;
        try (ActivityScenario<TestHostActivity> ignored = launch(ReadinessMode.ONBOARDING)) {
            waitFor(() -> app.channels.ensureChannels.get() >= 1);
            assertEquals(0, app.channels.bypass.get());
        }
    }

    @Test
    public void manufacturerItemOpensTheGuideWithTheDetectedManufacturer() {
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            tapAction("Manufacturer settings");
            scenario.onActivity(a -> assertEquals(R.id.manufacturerGuideFragment,
                    a.navController().getCurrentDestination().getId()));
            onView(withId(R.id.s08_selector)).check(matches(withText("Xiaomi / Redmi / POCO")));
        }
    }

    @Test
    public void recreateKeepsStatuses() {
        app.probe.dnd = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.recreate();
            scenario.onActivity(a -> assertEquals(View.VISIBLE,
                    row(a, "Alarms during Do Not Disturb").findViewById(R.id.s07_item_action).getVisibility()));
        }
    }

    @Test
    public void processDeathRestoresS07NotS05() {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> {
                Bundle out = new Bundle();
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(a, out);
                saved.set(out);
            });
        }
        TestHostActivity.restoreNextFrom(saved.get());
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(new Intent(app, TestHostActivity.class))) {
            scenario.onActivity(a -> assertEquals(R.id.readinessFragment,
                    a.navController().getCurrentDestination().getId()));
            onView(withText("Alarm readiness")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void fontScale200RationalesWrapAndButtonSitsBelowText() {
        DeviceShell.run("settings put system font_scale 2.0");
        app.probe.dnd = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> {
                View r = row(a, "Alarms during Do Not Disturb");
                TextView supporting = r.findViewById(R.id.s07_item_supporting);
                View action = r.findViewById(R.id.s07_item_action);
                assertTrue(supporting.getLineCount() >= 1);
                assertFalse("rationale truncated", supporting.getLayout() != null
                        && supporting.getLayout().getEllipsisCount(supporting.getLineCount() - 1) > 0);
                int[] text = new int[2];
                int[] button = new int[2];
                supporting.getLocationOnScreen(text);
                action.getLocationOnScreen(button);
                assertTrue("button below the supporting text", button[1] >= text[1] + supporting.getHeight());
            });
        }
    }

    @Test
    public void talkBackReadsStatusFirstAndActionSeparately() {
        app.probe.notifications = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> {
                View r = row(a, "Notifications");
                CharSequence cd = r.findViewById(R.id.s07_item_text).getContentDescription();
                assertEquals("Notifications, Needs attention, Needed to show alarms and the current reading.",
                        cd.toString());
                assertTrue(r.findViewById(R.id.s07_item_text).isFocusable());
                assertTrue(r.findViewById(R.id.s07_item_action).isFocusable());
                CharSequence done = row(a, "Battery optimisation").findViewById(R.id.s07_item_text)
                        .getContentDescription();
                assertEquals("Battery optimisation, Done, Stops Android from pausing LightScout in the background.",
                        done.toString());
            });
        }
    }

    @Test
    public void statusIconsCarryTheirStatusWords() {   // Q-81
        app.probe.notifications = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(ReadinessMode.ONBOARDING)) {
            scenario.onActivity(a -> {
                assertEquals("Needs attention", String.valueOf(row(a, "Notifications")
                        .findViewById(R.id.s07_item_icon).getContentDescription()));
                assertEquals("Done", String.valueOf(row(a, "Battery optimisation")
                        .findViewById(R.id.s07_item_icon).getContentDescription()));
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, row(a, "Notifications")
                        .findViewById(R.id.s07_item_icon).getImportantForAccessibility());
            });
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long end = android.os.SystemClock.uptimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (android.os.SystemClock.uptimeMillis() > end) {
                throw new AssertionError("condition not met within 5 s");
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(50);
        }
    }
}
