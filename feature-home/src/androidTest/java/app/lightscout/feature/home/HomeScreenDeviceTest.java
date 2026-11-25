package app.lightscout.feature.home;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.lightscout.domain.consumable.ConsumableState;
import app.lightscout.domain.model.Trend;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.status.StatusSnapshot;
import app.lightscout.ui.core.UiMessage;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.Duration;
import java.util.List;

/** R-93 native state, geometry, popup/focus and R-95 route probes. Synthetic host only; device execution due. */
@RunWith(AndroidJUnit4.class)
public class HomeScreenDeviceTest {
    private TestHomeApplication app;

    @Before public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
    }

    private ActivityScenario<TestHostActivity> launch(DeviceFakes.Snap snap) {
        app.status.snapshot = snap.build();
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class));
    }

    private void publish(StatusSnapshot snapshot) {
        app.status.publish(snapshot);
        settle();
    }

    private static void settle() {
        SystemClock.sleep(200);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static void awaitDisplayed(String text) {
        long deadline = SystemClock.uptimeMillis() + 3_000;
        while (true) {
            try {
                onView(withText(text)).check(matches(isDisplayed()));
                return;
            } catch (RuntimeException | AssertionError e) {
                if (SystemClock.uptimeMillis() > deadline) {
                    throw e;
                }
                SystemClock.sleep(100);
            }
        }
    }

    /** The view takes no part in what the user sees: INVISIBLE (reserved space) or GONE (no space, DCR-23). */
    private static void invisible(int id) {
        onView(withId(id)).check(matches(not(isDisplayed())));
    }

    private static int bannerId(String name) {
        switch (name) {
            case "Check settings":
                return R.id.s01_banner_readiness;
            case "Server error":
            case "Token reject":
                return R.id.s01_banner_connection;
            default:
                return R.id.s01_banner;
        }
    }

    private static void details(String name, String message, String action) {
        onView(withId(bannerId(name))).check(matches(withText(name))).perform(click());
        onView(withText(message)).check(matches(isDisplayed()));
        if (action != null) {
            onView(withId(android.R.id.button1)).check(matches(withText(action)));
        }
    }

    @Test public void contentHasNativeChartQuietCachedRefreshAndNoFabricatedIob() {
        try (ActivityScenario<TestHostActivity> scenario = launch(new DeviceFakes.Snap().cachedOnly())) {
            onView(withId(R.id.s01_reading)).check(matches(withText("142")));
            onView(withId(R.id.s01_age)).check(matches(withText("3 min ago")));
            onView(withId(R.id.s01_unit)).check(matches(withText("mg/dL")));
            onView(withId(R.id.s01_range_label)).check(matches(withText("In range")));
            onView(withId(R.id.s01_delta)).check(matches(withText("--- mg/dL")));
            invisible(R.id.s01_iob);
            invisible(R.id.s01_stale);
            onView(withId(R.id.s01_banner)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(R.id.s01_site_value)).check(matches(withText("---")));
            onView(withId(R.id.s01_scroll)).check((view, noViewFoundException) ->
                    assertEquals("Home starts at the top", 0, view.getScrollY()));
            boolean[] standardFit = {false};
            scenario.onActivity(activity -> {
                HomeContentScrollView scroll = activity.findViewById(R.id.s01_scroll);
                standardFit[0] = !scroll.isScrollingEnabled();
                if (standardFit[0]) {
                    for (int id : new int[] {R.id.s01_hero, R.id.s01_chart_host, R.id.s01_counters,
                            R.id.s01_doughnut_card, R.id.s01_dock}) {
                        View child = activity.findViewById(id);
                        Rect visible = new Rect();
                        assertTrue("Home card fully visible: " + id, child.getGlobalVisibleRect(visible));
                        assertEquals(child.getHeight(), visible.height());
                        assertEquals(child.getWidth(), visible.width());
                    }
                }
            });
            if (standardFit[0]) {
                onView(withId(R.id.s01_chart)).check(matches(isDisplayed()));
                onView(withId(R.id.s01_doughnut_card)).check(matches(isDisplayed()));
            }
            publish(new DeviceFakes.Snap().build());
            onView(withId(R.id.s01_reading)).check(matches(withText("142")));
        }
    }

    @Test public void measuredScrollPolicyRecalculatesWhenViewportAndInsetsChange() {
        try (ActivityScenario<TestHostActivity> scenario = launch(new DeviceFakes.Snap())) {
            scenario.onActivity(activity -> {
                HomeContentScrollView scroll = activity.findViewById(R.id.s01_scroll);
                if (HomeLayoutPolicy.allowsScrolling(
                        activity.getResources().getConfiguration().fontScale)) {
                    assertTrue("large-font accessibility scrolling remains enabled", scroll.isScrollingEnabled());
                    return;
                }
                View content = scroll.getChildAt(0);
                View chartHost = content.findViewById(R.id.s01_chart_host);
                int fixedContentHeight = content.getMeasuredHeight() - chartHost.getMeasuredHeight();
                int requiredHeight = fixedContentHeight + chartHost.getMinimumHeight();
                int width = Math.max(1, scroll.getWidth());
                int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);

                scroll.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        requiredHeight, View.MeasureSpec.EXACTLY));
                assertFalse("exact fit should not enable page scrolling", scroll.isScrollingEnabled());
                assertTrue("chart retains its usable minimum",
                        chartHost.getMeasuredHeight() >= chartHost.getMinimumHeight());

                // The fixed content compresses with the viewport by a device-dependent amount, so use an inset that
                // leaves less than the usable chart minimum: that can never fit.
                int navInset = requiredHeight - chartHost.getMinimumHeight() / 2;
                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                        scroll.getPaddingRight(), navInset);
                scroll.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        requiredHeight, View.MeasureSpec.EXACTLY));
                assertTrue("inset-reduced viewport should enable scrolling", scroll.isScrollingEnabled());

                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                        scroll.getPaddingRight(), 0);
                scroll.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                        requiredHeight, View.MeasureSpec.EXACTLY));
                assertFalse("restored viewport should disable scrolling", scroll.isScrollingEnabled());
            });
        }
    }

    @Test public void emptyStatesRetainPlaceholderAndExplanationWithoutFakeAge() {
        try (ActivityScenario<TestHostActivity> ignored = launch(new DeviceFakes.Snap().noReading().firstUse())) {
            onView(withId(R.id.s01_reading)).check(matches(withText("---")));
            onView(withId(R.id.s01_unit)).check(matches(withText("mg/dL")));
            onView(withId(R.id.s01_delta)).check(matches(withText("--- mg/dL")));
            invisible(R.id.s01_range_label);
            onView(withId(R.id.s01_age)).check(matches(withText("Waiting for the first reading from Nightscout…")));
            publish(new DeviceFakes.Snap().noReading().build());
            onView(withId(R.id.s01_age)).check(matches(withText("No readings in the last 24 hours")));
            invisible(R.id.s01_stale);
            onView(withId(R.id.s01_chart)).check(matches(
                    withContentDescription("Glucose chart, current ---, trend unknown")));
        }
    }

    @Test public void freshServerErrorIsNamedAndDoesNotMarkReadingStale() {
        try (ActivityScenario<TestHostActivity> ignored = launch(
                new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING))) {
            invisible(R.id.s01_stale);
            assertTrue(TestHostActivity.NAVIGATIONS.isEmpty());
            details("Server error", "Nightscout is not responding", "Retry");
            pressBack();
            onView(withId(R.id.s01_banner_connection)).check(matches(withText("Server error")));
        }
    }

    @Test public void backClosesDetailsFirstAndRestoresChipFocus() {
        DeviceFakes.Snap off = new DeviceFakes.Snap();
        off.alarmsEnabled = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(off)) {
            // Focus return is only observable outside touch mode, so open the window without a touch event.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB);
            scenario.onActivity(activity -> activity.findViewById(R.id.s01_banner).performClick());
            settle();
            onView(withText("Alarms are off")).check(matches(isDisplayed()));
            pressBack();
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.s01_banner).isFocused()));
            onView(withId(R.id.s01_banner)).check(matches(withText("Alarms off")));
            assertTrue(app.alarms.calls.isEmpty());
        }
    }

    @Test public void severalFaultsShowSeparateChipsAndEachOpensItsOwnWindow() {
        DeviceFakes.Snap multi = new DeviceFakes.Snap().outcome(FetchOutcome.TOKEN_REJECTED);
        multi.lowEnabled = false;
        multi.readinessMissing = 1;
        try (ActivityScenario<TestHostActivity> ignored = launch(multi)) {
            onView(withId(R.id.s01_banner)).check(matches(withText("Low off")));
            onView(withId(R.id.s01_banner_readiness)).check(matches(withText("Check settings")));
            onView(withId(R.id.s01_banner_connection)).check(matches(withText("Token reject")));
            onView(withId(R.id.s01_banner_offline)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            details("Check settings", "Alarms may not ring — some phone settings are missing", "Fix");
            pressBack();
            details("Token reject", "Nightscout rejected the access token", "Fix connection");
        }
    }

    @Test public void windowClosesWhenItsChipDisappearsAndChipReturnsWithoutWindow() {
        DeviceFakes.Snap fault = new DeviceFakes.Snap();
        fault.readinessMissing = 1;
        try (ActivityScenario<TestHostActivity> ignored = launch(fault)) {
            details("Check settings", "Alarms may not ring — some phone settings are missing", "Fix");
            publish(new DeviceFakes.Snap().build());
            onView(withId(R.id.s01_banner_readiness)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withText("Alarms may not ring — some phone settings are missing")).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            publish(fault.build());
            onView(withId(R.id.s01_banner_readiness)).check(matches(isDisplayed()));
            onView(withText("Alarms may not ring — some phone settings are missing")).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    @Test public void changedCauseClosesDetailsWithoutActivatingAnotherAction() {
        try (ActivityScenario<TestHostActivity> ignored = launch(
                new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING))) {
            details("Server error", "Nightscout is not responding", "Retry");
            DeviceFakes.Snap off = new DeviceFakes.Snap();
            off.alarmsEnabled = false;
            publish(off.build());
            onView(withId(R.id.s01_banner)).check(matches(withText("Alarms off")));
            assertEquals(0, app.status.retries.get());
            assertTrue(app.alarms.calls.isEmpty());
            onView(withText("Nightscout is not responding")).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    @Test public void retryUsesSingleFlightGuardAndFetchingDisablesPopupAction() {
        try (ActivityScenario<TestHostActivity> ignored = launch(
                new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING))) {
            details("Server error", "Nightscout is not responding", "Retry");
            onView(withId(android.R.id.button1)).perform(click());
            assertEquals(1, app.status.retries.get());
            DeviceFakes.Snap fetching = new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING);
            fetching.fetching = true;
            publish(fetching.build());
            details("Server error", "Nightscout is not responding", "Retry");
            onView(withId(android.R.id.button1)).check(matches(not(isEnabled())));
        }
    }

    @Test public void alarmReadinessAndConnectionActionsRetainPorts() {
        DeviceFakes.Snap off = new DeviceFakes.Snap();
        off.lowEnabled = false;
        off.highEnabled = false;
        try (ActivityScenario<TestHostActivity> scenario = launch(off)) {
            details("Low/High off", "Low and high alarms are off", "Turn on");
            onView(withId(android.R.id.button1)).perform(click());
            assertEquals(List.of("type:LOW:true", "type:HIGH:true"), app.alarms.calls);
            DeviceFakes.Snap missing = new DeviceFakes.Snap();
            missing.readinessMissing = 1;
            publish(missing.build());
            details("Check settings", "Alarms may not ring — some phone settings are missing", "Fix");
            onView(withId(android.R.id.button1)).perform(click());
            scenario.moveToState(Lifecycle.State.STARTED).moveToState(Lifecycle.State.RESUMED);
            publish(new DeviceFakes.Snap().outcome(FetchOutcome.TOKEN_REJECTED).build());
            details("Token reject", "Nightscout rejected the access token", "Fix connection");
            onView(withId(android.R.id.button1)).perform(click());
            assertEquals(List.of("readiness:CHECK", "connection:EDIT"), TestHostActivity.NAVIGATIONS);
        }
    }

    @Test public void staleReadingIsRetainedAndNamedIndependentlyOfPriorityWarning() {
        DeviceFakes.Snap snap = new DeviceFakes.Snap();
        snap.latest = snap.reading(52, Duration.ofMinutes(25), Trend.SINGLE_DOWN);
        snap.alarmsEnabled = false;
        try (ActivityScenario<TestHostActivity> ignored = launch(snap)) {
            onView(withId(R.id.s01_reading)).check(matches(withText("52")));
            onView(withId(R.id.s01_stale)).check(matches(withText("No readings")));
            onView(withId(R.id.s01_range_label)).check(matches(withText("Urgent low")));
            onView(withId(R.id.s01_banner)).check(matches(withText("Alarms off")));
            onView(withId(R.id.s01_reading_group)).check(matches(withContentDescription(
                    "Glucose 52 milligrams per decilitre, Urgent low, falling, 25 minutes ago, Delta — mg/dL")));
        }
    }


    @Test public void dockAndCounterRoutesRemainAvailableWithoutData() {
        try (ActivityScenario<TestHostActivity> ignored = launch(new DeviceFakes.Snap().noReading())) {
            onView(withId(R.id.s01_carbs)).perform(click());
            onView(withId(R.id.s01_bolus)).perform(click());
            onView(withId(R.id.s01_bg)).perform(click());
            onView(withId(R.id.s01_site_entry)).perform(click());
            onView(withId(R.id.s01_sensor_entry)).perform(click());
            assertEquals(List.of("newEntry:CARBS", "newEntry:BOLUS", "newEntry:BG_CHECK",
                    "newEntry:SITE_CHANGE", "newEntry:SENSOR_CHANGE"), TestHostActivity.NAVIGATIONS);
        }
    }

    @Test public void emptyDistributionIsAccessibleAndOpensStatisticsOnce() {
        try (ActivityScenario<TestHostActivity> scenario = launch(new DeviceFakes.Snap().noReading())) {
            onView(withId(R.id.s01_doughnut_card)).check(matches(
                    withContentDescription("Last 24 hours: Not enough data. Open statistics")));
            scenario.onActivity(activity -> {
                View card = activity.findViewById(R.id.s01_doughnut_card);
                card.performClick();
                card.performClick();
            });
            assertEquals(List.of("statistics"), TestHostActivity.NAVIGATIONS);
        }
    }

    @Test public void cardBoundsRemainStableAcrossLoadingErrorsStaleAndCounterStates() {
        DeviceFakes.Snap empty = new DeviceFakes.Snap().noReading().firstUse();
        empty.lastCheckCompleted = null;
        try (ActivityScenario<TestHostActivity> scenario = launch(empty)) {
            settle();
            int[] before = heights(scenario);
            publish(new DeviceFakes.Snap().build());
            // The hero grows with its chip stack (no reserved row, D-132 decision 4), the chart gives the space back.
            assertCardsStable("after first publish", before, heights(scenario));
            DeviceFakes.Snap stale = new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING)
                    .site(ConsumableState.CHANGE_SOON, Duration.ofDays(2).plusHours(5))
                    .sensor(ConsumableState.EXPIRED, Duration.ofDays(11));
            stale.latest = stale.reading(52, Duration.ofMinutes(25), Trend.SINGLE_DOWN);
            publish(stale.build());
            assertCardsStable("stale", before, heights(scenario));
            onView(withId(R.id.s01_site_status_text)).check(matches(withText("Replace soon")));
            onView(withId(R.id.s01_sensor_status_text)).check(matches(withText("Expired")));
        }
    }

    /**
     * The counters and the 24 h card keep their height. The hero (index 0) grows with its chips (D-132) and the chart
     * (index 1) is the elastic card that gives the space back: hero + chart stay the same (rounding tolerance).
     */
    private static void assertCardsStable(String when, int[] before, int[] after) {
        String state = " before=" + java.util.Arrays.toString(before) + " after=" + java.util.Arrays.toString(after);
        assertTrue(when + ": hero never shrinks," + state, after[0] >= before[0]);
        assertEquals(when + ": hero + chart stay constant," + state, before[0] + before[1], after[0] + after[1], 8);
        for (int i = 2; i < before.length; i++) {
            assertEquals(when + ": card " + i + "," + state, before[i], after[i]);
        }
    }

    private static int[] heights(ActivityScenario<TestHostActivity> scenario) {
        int[] result = new int[5];
        scenario.onActivity(activity -> {
            int[] ids = {R.id.s01_hero, R.id.s01_chart_host, R.id.s01_site_card, R.id.s01_sensor_card,
                    R.id.s01_doughnut_card};
            for (int i = 0; i < ids.length; i++) {
                result[i] = activity.findViewById(ids[i]).getHeight();
            }
        });
        return result;
    }

    @Test public void recreationAndResumeKeepStatusAndListenerAndRecheckReadiness() {
        try (ActivityScenario<TestHostActivity> scenario = launch(
                new DeviceFakes.Snap().outcome(FetchOutcome.NS_NOT_RESPONDING))) {
            scenario.recreate();
            onView(withId(R.id.s01_banner_connection)).check(matches(withText("Server error")));
            assertEquals(1, app.status.listeners.size());
            int count = app.status.refreshes.get();
            scenario.moveToState(Lifecycle.State.CREATED);
            assertEquals(1, app.status.listeners.size());
            scenario.moveToState(Lifecycle.State.RESUMED);
            settle();
            assertTrue(app.status.refreshes.get() > count);
        }
        assertEquals(0, app.status.listeners.size());
    }

    @Test public void everyResultReachesTheSnackbarNotOnlyTheFirst() {
        try (ActivityScenario<TestHostActivity> scenario = launch(new DeviceFakes.Snap())) {
            scenario.onActivity(activity -> activity.deliverResult(UiMessage.ENTRY_SAVED));
            onView(withText("Entry saved")).check(matches(isDisplayed()));
            scenario.onActivity(activity -> activity.deliverResult(UiMessage.ENTRY_DELETED));
            // The second snackbar shows once the first has been dismissed (consecutive snackbars, ~0.3 s).
            awaitDisplayed("Entry deleted");
        }
    }

    @Test public void settingsAndResultSnackbarAndRootBackRemainNative() {
        try (ActivityScenario<TestHostActivity> scenario = launch(new DeviceFakes.Snap())) {
            onView(withId(R.id.s01_action_settings)).perform(click());
            assertEquals(List.of("settings"), TestHostActivity.NAVIGATIONS);
            scenario.onActivity(activity -> activity.deliverResult(UiMessage.ENTRY_SAVED));
            onView(withText("Entry saved")).check(matches(isDisplayed()));
            pressBackUnconditionally();
            settle();
            // HyperOS delays stop/destroy after finish(); poll instead of asserting once.
            for (int i = 0; i < 30 && scenario.getState() != Lifecycle.State.DESTROYED; i++) {
                SystemClock.sleep(100);
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }
}
