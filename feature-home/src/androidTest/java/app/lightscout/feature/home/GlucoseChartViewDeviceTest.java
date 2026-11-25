package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;
import androidx.appcompat.view.ContextThemeWrapper;

import androidx.test.espresso.Espresso;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static org.junit.Assert.assertNotEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.lightscout.domain.model.BasalSegment;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.GlucoseRendering;
import app.lightscout.usecases.Segment;

/** CP-11 TST-06 device checks. Synthetic-only data; no network or Nightscout access. */
@RunWith(AndroidJUnit4.class)
public class GlucoseChartViewDeviceTest {

    private ActivityScenario<TestHostActivity> scenario;
    private NestedScrollView scroll;
    private LinearLayout chartColumn;
    private GlucoseChartView chart;
    private Instant now;

    @Before
    public void createChart() {
        TestHomeApplication app = (TestHomeApplication) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        app.reset();
        scenario = ActivityScenario.launch(new Intent(app, TestHostActivity.class));
        now = Instant.parse("2026-10-26T12:00:00Z");
        scenario.onActivity(activity -> {
            chart = new GlucoseChartView(activity);
            chart.setSummary(activity.getString(R.string.s01_chart_a11y, "142", "rising"));
            // Same host shape as S-01: the chart sits in a vertically scrolling NestedScrollView.
            scroll = new NestedScrollView(activity);
            chartColumn = new LinearLayout(activity);
            chartColumn.setOrientation(LinearLayout.VERTICAL);
            chartColumn.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            chartColumn.addView(new View(activity), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    3 * activity.getResources().getDisplayMetrics().heightPixels));
            scroll.addView(chartColumn, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            activity.setContentView(scroll);
            chart.setNow(now, ZoneId.of("Europe/Warsaw"));
            chart.setThresholds(55, 70, 250);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @After
    public void closeActivity() {
        if (scenario != null) {
            scenario.close();
        }
    }

    @Test
    public void chartIsOneTalkBackNodeAndResetsToDefaultWindow() {
        scenario.onActivity(activity -> {
            assertEquals("Glucose chart, current 142, rising", chart.getContentDescription());
            assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                    chart.getImportantForAccessibility());
            assertEquals(null, chart.getAccessibilityNodeProvider());

            chart.setViewport(new ChartViewport(now.minus(Duration.ofDays(2)), Duration.ofHours(12)));
            chart.resetToNow();
            assertEquals(now, chart.getViewport().end());
            assertEquals(Duration.ofHours(6), chart.getViewport().span());
        });
    }

    @Test
    public void offlineWindowCanBeRequestedAgain() {
        List<Instant> requestedStarts = Collections.synchronizedList(new ArrayList<>());
        scenario.onActivity(activity -> chart.setListener(new GlucoseChartView.Listener() {
            @Override
            public void onViewportChanged(ChartViewport viewport, boolean atNow) {
            }

            @Override
            public void onWindowNeeded(Instant from, Instant to) {
                requestedStarts.add(from);
            }

            @Override
            public void onEditRequested(String entryId) {
            }
        }));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, requestedStarts.size());

        scenario.onActivity(activity -> {
            Instant from = chart.getViewport().end().minus(chart.getViewport().span());
            Instant to = chart.getViewport().end();
            chart.setSegmentState(from, to, SegmentState.OFFLINE);
            chart.setViewport(chart.getViewport());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(2, requestedStarts.size());
        assertEquals(requestedStarts.get(0), requestedStarts.get(1));
    }

    @Test
    public void dragPansIntoHistoryAndDoubleTapDoesNotZoom() {
        scenario.onActivity(activity -> {
            Instant originalEnd = chart.getViewport().end();
            float y = chart.getHeight() / 2f;
            long down = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, chart.getWidth() * .3f, y, 0));
            send(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_MOVE, chart.getWidth() * .7f, y, 0));
            send(MotionEvent.obtain(down, down + 100, MotionEvent.ACTION_UP, chart.getWidth() * .7f, y, 0));
            assertTrue(chart.getViewport().end().isBefore(originalEnd));

            Duration beforeDoubleTap = chart.getViewport().span();
            long second = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(second, second, MotionEvent.ACTION_DOWN, chart.getWidth() / 2f, y, 0));
            send(MotionEvent.obtain(second, second + 20, MotionEvent.ACTION_UP, chart.getWidth() / 2f, y, 0));
            send(MotionEvent.obtain(second + 60, second + 60, MotionEvent.ACTION_DOWN,
                    chart.getWidth() / 2f, y, 0));
            send(MotionEvent.obtain(second + 60, second + 80, MotionEvent.ACTION_UP,
                    chart.getWidth() / 2f, y, 0));
            assertEquals(beforeDoubleTap, chart.getViewport().span());
        });
    }

    @Test
    public void pinchClampsToOneAndTwentyFourHours() {
        scenario.onActivity(activity -> {
            float y = chart.getHeight() / 2f;
            float center = chart.getWidth() / 2f;
            // The platform detector ignores spans below its minimum scaling span, so keep both ends above it.
            float narrow = ViewConfiguration.get(activity).getScaledMinimumScalingSpan() * 1.2f;
            float wide = chart.getWidth() * .9f;
            assertTrue("device too narrow for a two-finger pinch", wide > narrow * 1.5f);
            for (int i = 0; i < 8; i++) {
                sendPinch(center - wide / 2, center + wide / 2, center - narrow / 2, center + narrow / 2, y);
            }
            assertEquals(Duration.ofHours(24), chart.getViewport().span());

            for (int i = 0; i < 12; i++) {
                sendPinch(center - narrow / 2, center + narrow / 2, center - wide / 2, center + wide / 2, y);
            }
            assertEquals(Duration.ofHours(1), chart.getViewport().span());
        });
    }

    @Test
    public void groupedTooltipShowsCompactRowsAndOwnOnlyEditAndBackClosesIt() {
        List<String> edits = Collections.synchronizedList(new ArrayList<>());
        String[] rows = new String[2];
        scenario.onActivity(activity -> {
            chart.setListener(new GlucoseChartView.Listener() {
                @Override
                public void onViewportChanged(ChartViewport viewport, boolean atNow) {
                }

                @Override
                public void onWindowNeeded(Instant from, Instant to) {
                }

                @Override
                public void onEditRequested(String entryId) {
                    edits.add(entryId);
                }
            });
            Instant first = now.minus(Duration.ofHours(1));
            rows[0] = "Carbs 15 g, " + Formatters.clockTime(activity, first, ZoneId.of("Europe/Warsaw"));
            Instant second = first.plusSeconds(60);
            rows[1] = "Carbs 20 g, " + Formatters.clockTime(activity, second, ZoneId.of("Europe/Warsaw"));
            Entry own = entry("own-id", first, 15, true);
            Entry split = entry("uploader#carbs", second, 20, false);
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    java.util.List.of(), java.util.List.of(own, split), java.util.List.of()));
            float density = activity.getResources().getDisplayMetrics().density;
            float x = 12 * density + (chart.getWidth() - 54 * density) * (5f / 6f);
            float y = chart.getHeight() - (34 + 18) * density;
            long time = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0));
            send(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        androidx.test.espresso.Espresso.onView(withText(rows[0])).check(matches(isDisplayed()));
        androidx.test.espresso.Espresso.onView(withText(rows[1])).check(matches(isDisplayed()));
        androidx.test.espresso.Espresso.onView(withText("LightScout")).check(doesNotExist());
        androidx.test.espresso.Espresso.onView(withText("Nightscout")).check(doesNotExist());
        androidx.test.espresso.Espresso.onView(withText("Edit")).check(matches(isDisplayed()));

        Espresso.pressBack();
        androidx.test.espresso.Espresso.onView(withText("Edit")).check(doesNotExist());

        scenario.onActivity(activity -> {
            float density = activity.getResources().getDisplayMetrics().density;
            float x = 12 * density + (chart.getWidth() - 54 * density) * (5f / 6f);
            float y = chart.getHeight() - (34 + 18) * density;
            long time = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0));
            send(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        androidx.test.espresso.Espresso.onView(withText("Edit")).perform(
                androidx.test.espresso.action.ViewActions.click());
        assertEquals(List.of("own-id"), edits);
    }

    @Test
    public void siteSensorAndPumpTooltipsUseLocalTimeAndPreserveCompleteAlertText() {
        String[] rows = new String[3];
        String alertText = "Pump alert " + "A".repeat(150);
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofHours(1));
            String time = Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            rows[0] = "Site change, " + time;
            rows[1] = "Sensor change, " + time;
            rows[2] = "Pump alert " + alertText.substring("Pump alert ".length()) + ", " + time;
            Entry site = new Entry("site", EntryOrigin.TREATMENTS, EntryType.SITE_CHANGE, at, null, null, null,
                    null, null, null, null, "LightScout", "Site Change", true, null);
            Entry sensor = new Entry("sensor", EntryOrigin.TREATMENTS, EntryType.SENSOR_CHANGE, at, null, null, null,
                    null, null, null, null, "LightScout", "Sensor Change", true, null);
            Entry alert = new Entry("alert", EntryOrigin.TREATMENTS, EntryType.PUMP_ALERT, at, null, null, null,
                    null, null, alertText.substring("Pump alert ".length()), "Pump", null, "Pump alert", false, null);
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(site, sensor, alert), List.of()));
            float density = activity.getResources().getDisplayMetrics().density;
            tap(markerX(density), (32 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Espresso.onView(withText(rows[0])).check(matches(isDisplayed()));
        Espresso.onView(withText(rows[1])).check(matches(isDisplayed()));
        Espresso.onView(withText(rows[2])).check(matches(isDisplayed()));
        Espresso.onView(withText("Pump")).check(doesNotExist());
        Espresso.onView(withText("LightScout")).check(doesNotExist());
        Espresso.onView(withText(rows[0])).check((view, error) -> {
            if (error != null) {
                throw error;
            }
            ViewGroup content = (ViewGroup) view.getParent();
            int editCount = 0;
            for (int i = 0; i < content.getChildCount(); i++) {
                if (content.getChildAt(i) instanceof TextView text && "Edit".contentEquals(text.getText())) {
                    editCount++;
                }
            }
            assertEquals("only the own site and sensor entries are editable", 2, editCount);
        });
    }

    @Test
    public void bolusTooltipKeepsThreeDecimalPrecisionAndLegacyFallback() {
        String[] rows = new String[2];
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofHours(1));
            String time = Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            rows[0] = "Bolus 1.234 U, " + time;
            rows[1] = "Bolus 0.05 U, " + time;
            Entry imported = new Entry("imported-bolus", EntryOrigin.TREATMENTS, EntryType.BOLUS, at, null, null,
                    null, 1234L, null, null, null, "Pump", null, "Bolus", false, null);
            Entry legacy = new Entry("legacy-bolus", EntryOrigin.TREATMENTS, EntryType.BOLUS, at, null, null,
                    5, null, null, null, null, "Pump", null, "Bolus", false, null);
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(imported, legacy), List.of()));
            float density = activity.getResources().getDisplayMetrics().density;
            tap(markerX(density), (32 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Espresso.onView(withText(rows[0])).check(matches(isDisplayed()));
        Espresso.onView(withText(rows[1])).check(matches(isDisplayed()));
    }

    @Test
    public void dragFollowsFingerIntoHistoryWithoutParentStealingIt() {
        scenario.onActivity(activity -> {
            float width = chart.getWidth();
            float y = chart.getHeight() / 2f;
            float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            float startX = width * .3f;
            float endX = width * .7f;
            long t = SystemClock.uptimeMillis();
            dispatch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, startX, y, 0));
            // Diagonal finger: the vertical drift exceeds the slop, but the horizontal move dominates.
            for (int i = 1; i <= 8; i++) {
                dispatch(MotionEvent.obtain(t, t + i * 40L, MotionEvent.ACTION_MOVE,
                        startX + (endX - startX) * i / 8f, y + 2 * slop * i / 8f, 0));
            }
            dispatch(MotionEvent.obtain(t, t + 400, MotionEvent.ACTION_MOVE, endX, y + 2 * slop, 0));
            dispatch(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, endX, y + 2 * slop, 0));

            assertEquals(0, scroll.getScrollY());
            long expectedMs = Math.round(Duration.ofHours(6).toMillis() * (endX - startX) / (double) plotWidth());
            long movedMs = Duration.between(chart.getViewport().end(), now).toMillis();
            assertTrue("drag right must show older data by the finger distance, moved " + movedMs,
                    Math.abs(movedMs - expectedMs) <= expectedMs * 0.02);
        });
    }

    @Test
    public void verticalSwipeOnChartScrollsTheParent() {
        scenario.onActivity(activity -> {
            ChartViewport before = chart.getViewport();
            float x = chart.getWidth() / 2f;
            float y = chart.getHeight() * .8f;
            float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            long t = SystemClock.uptimeMillis();
            dispatch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
            for (int i = 1; i <= 8; i++) {
                dispatch(MotionEvent.obtain(t, t + i * 40L, MotionEvent.ACTION_MOVE, x, y - slop * i, 0));
            }
            dispatch(MotionEvent.obtain(t, t + 500, MotionEvent.ACTION_UP, x, y - slop * 8, 0));

            assertTrue(scroll.getScrollY() > 0);
            assertEquals(before, chart.getViewport());
        });
    }

    @Test
    public void pinchSurvivesVerticalDriftAndHandsOffToTheRemainingFingerWithoutFling() {
        ChartViewport[] atUp = new ChartViewport[1];
        scenario.onActivity(activity -> {
            chart.setViewport(new ChartViewport(now.minus(Duration.ofDays(1)), Duration.ofHours(6)));
            float width = chart.getWidth();
            float y = chart.getHeight() / 2f;
            float slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            long t = SystemClock.uptimeMillis();
            dispatch(pointers(t, t, MotionEvent.ACTION_DOWN, new float[] {0, width * .2f, y}));
            dispatch(pointers(t, t + 10, MotionEvent.ACTION_POINTER_DOWN
                            | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new float[] {0, width * .2f, y}, new float[] {1, width * .8f, y}));
            Duration afterThirdMove = null;
            float firstX = 0;
            float secondX = 0;
            float driftY = y;
            for (int i = 1; i <= 6; i++) {
                firstX = width * (.2f - .02f * i);
                secondX = width * (.8f + .02f * i);
                driftY = y + slop * i;
                dispatch(pointers(t, t + 10 + i * 16L, MotionEvent.ACTION_MOVE,
                        new float[] {0, firstX, driftY}, new float[] {1, secondX, driftY}));
                if (i == 3) {
                    afterThirdMove = chart.getViewport().span();
                }
            }
            assertEquals(0, scroll.getScrollY());
            assertTrue(afterThirdMove.compareTo(Duration.ofHours(6)) < 0);
            assertTrue("pinch must keep zooming to the end", chart.getViewport().span().compareTo(afterThirdMove) < 0);

            // Lift the first (active) finger; the second one continues as a pan from its own position.
            dispatch(pointers(t, t + 120, MotionEvent.ACTION_POINTER_UP,
                    new float[] {0, firstX, driftY}, new float[] {1, secondX, driftY}));
            ChartViewport beforePan = chart.getViewport();
            float panTo = secondX - width * .1f;
            dispatch(pointers(t, t + 136, MotionEvent.ACTION_MOVE, new float[] {1, panTo, driftY}));
            long expectedMs = Math.round(beforePan.span().toMillis() * (secondX - panTo) / (double) plotWidth());
            long newerMs = Duration.between(beforePan.end(), chart.getViewport().end()).toMillis();
            assertTrue("remaining finger must pan without a jump, moved " + newerMs,
                    Math.abs(newerMs - expectedMs) <= expectedMs * 0.05);
            dispatch(pointers(t, t + 140, MotionEvent.ACTION_UP, new float[] {1, panTo, driftY}));
            atUp[0] = chart.getViewport();
        });
        SystemClock.sleep(300);
        scenario.onActivity(activity -> assertEquals("no fling after a pinch", atUp[0], chart.getViewport()));
    }

    @Test
    public void quickDragFlingsIntoHistoryAndStopsOnTouchAndReset() {
        Instant[] marks = new Instant[2];
        scenario.onActivity(activity -> {
            chart.setViewport(new ChartViewport(now.minus(Duration.ofDays(1)), Duration.ofHours(6)));
            fastDrag(.2f, .7f);
            marks[0] = chart.getViewport().end();
        });
        SystemClock.sleep(150);
        scenario.onActivity(activity -> {
            assertTrue("fling must keep moving toward older data", chart.getViewport().end().isBefore(marks[0]));
            assertTrue(!chart.getViewport().end().minus(chart.getViewport().span())
                    .isBefore(now.minus(Duration.ofDays(7))));
            long t = SystemClock.uptimeMillis();
            dispatch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, chart.getWidth() / 2f,
                    chart.getHeight() / 2f, 0));
            marks[1] = chart.getViewport().end();
        });
        SystemClock.sleep(200);
        scenario.onActivity(activity -> {
            assertEquals("a new touch stops the fling", marks[1], chart.getViewport().end());
            long t = SystemClock.uptimeMillis();
            dispatch(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, chart.getWidth() / 2f,
                    chart.getHeight() / 2f, 0));
            fastDrag(.2f, .7f);
            chart.resetToNow();
        });
        SystemClock.sleep(200);
        scenario.onActivity(activity -> assertEquals(ChartViewportRules.defaultViewport(now), chart.getViewport()));
    }

    @Test
    public void flingStopsExactlyAtNowAndAtTheSevenDayLimit() {
        scenario.onActivity(activity -> {
            chart.setViewport(new ChartViewport(now.minus(Duration.ofHours(3)), Duration.ofHours(6)));
            fastDrag(.6f, .45f);
        });
        SystemClock.sleep(2000);
        scenario.onActivity(activity -> {
            assertEquals(now, chart.getViewport().end());
            Instant oldestEnd = now.minus(Duration.ofDays(7)).plus(Duration.ofHours(6));
            chart.setViewport(new ChartViewport(oldestEnd.plus(Duration.ofHours(3)), Duration.ofHours(6)));
            fastDrag(.45f, .6f);
        });
        SystemClock.sleep(2000);
        scenario.onActivity(activity -> assertEquals(now.minus(Duration.ofDays(7)),
                chart.getViewport().end().minus(chart.getViewport().span())));
    }

    @Test
    public void sameTimeCarbsAndBolusHaveSeparateMarkersAndTooltips() {
        String[] bolusText = new String[1];
        String[] carbsText = new String[1];
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofHours(1));
            bolusText[0] = "Bolus 1.5 U, " + Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            carbsText[0] = "Carbs 15 g, " + Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            Entry carbs = entry("carbs-id", at, 15, true);
            Entry bolus = new Entry("bolus-id", EntryOrigin.TREATMENTS, EntryType.BOLUS, at,
                    null, null, 150, null, null, null, null, "LightScout", "Bolus", true, null);
            BasalSegment basal = new BasalSegment(now.minus(Duration.ofHours(6)), Duration.ofHours(6), 800, true);
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(carbs, bolus), List.of(basal)));
            float density = activity.getResources().getDisplayMetrics().density;
            // Carbs retain the bottom lane; basal data no longer reserves drawing space.
            tap(markerX(density), chart.getHeight() - (34 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Espresso.onView(withText(carbsText[0])).check(matches(isDisplayed()));
        Espresso.onView(withText(bolusText[0])).check(doesNotExist());
        Espresso.pressBack();

        scenario.onActivity(activity -> {
            float density = activity.getResources().getDisplayMetrics().density;
            // Bolus: top lane.
            tap(markerX(density), (32 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Espresso.onView(withText(bolusText[0])).check(matches(isDisplayed()));
        Espresso.onView(withText(carbsText[0])).check(doesNotExist());
        Espresso.pressBack();
    }

    @Test
    public void flingDefersHistoryRequestUntilTheViewportSettles() {
        List<String> requestedWindows = Collections.synchronizedList(new ArrayList<>());
        Instant[] afterDrag = new Instant[1];
        scenario.onActivity(activity -> {
            chart.setSegment(new Segment(now.minus(Duration.ofHours(8)), now,
                    List.of(), List.of(), List.of()));
            chart.setListener(new GlucoseChartView.Listener() {
                @Override
                public void onViewportChanged(ChartViewport viewport, boolean atNow) {
                }

                @Override
                public void onWindowNeeded(Instant from, Instant to) {
                    requestedWindows.add(from + "/" + to);
                }

                @Override
                public void onEditRequested(String entryId) {
                }
            });
            fastDrag(.2f, .28f);
            afterDrag[0] = chart.getViewport().end();
        });

        SystemClock.sleep(150);
        scenario.onActivity(activity -> {
            assertTrue("fling continues after release", chart.getViewport().end().isBefore(afterDrag[0]));
            assertTrue("no loading window is started during inertial motion", requestedWindows.isEmpty());
        });

        SystemClock.sleep(2500);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            ChartViewport settled = chart.getViewport();
            assertEquals(List.of(settled.end().minus(settled.span()) + "/" + settled.end()), requestedWindows);
        });
    }

    @Test
    public void groupedBolusesUseCompactTimeOnlyRows() {
        String[] time = new String[1];
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofHours(1));
            time[0] = Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            Entry first = new Entry("bolus-a", EntryOrigin.TREATMENTS, EntryType.BOLUS, at,
                    null, null, 50, null, null, null, null, "LightScout", "Bolus", true, null);
            Entry second = new Entry("bolus-b", EntryOrigin.TREATMENTS, EntryType.BOLUS, at.plusSeconds(30),
                    null, null, 130, null, null, null, null, "LightScout", "Bolus", true, null);
            Entry precise = new Entry("bolus-c", EntryOrigin.TREATMENTS, EntryType.BOLUS, at.plusSeconds(45),
                    null, null, 237, 2375L, null, null, null, null, "LightScout", "Bolus", true, null);
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(first, second, precise), List.of()));
            tap(markerX(activity.getResources().getDisplayMetrics().density), (32 + 18) *
                    activity.getResources().getDisplayMetrics().density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        Espresso.onView(withText("Bolus 0.5 U, " + time[0])).check(matches(isDisplayed()));
        Espresso.onView(withText("Bolus 1.3 U, " + time[0])).check(matches(isDisplayed()));
        Espresso.onView(withText("Bolus 2.375 U, " + time[0])).check(matches(isDisplayed()));
        Espresso.onView(withText("LightScout")).check(doesNotExist());
        Espresso.onView(withText("Nightscout")).check(doesNotExist());
    }

    @Test
    public void cachedPreparedMarkerSurvivesViewportSegmentRefreshAndLoadingState() {
        String[] row = new String[1];
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofDays(5));
            row[0] = "Carbs 15 g, " + Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            Instant from = now.minus(Duration.ofDays(7));
            chart.setSegmentState(at.minus(Duration.ofHours(3)), at.plus(Duration.ofHours(3)),
                    SegmentState.LOADING);
            chart.setCachedSegment(new Segment(from, now, List.of(),
                    List.of(entry("prepared", at, 15, true)), List.of()));
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(), List.of()));
            chart.setViewport(new ChartViewport(at.plus(Duration.ofHours(3)), Duration.ofHours(6)));

            float density = activity.getResources().getDisplayMetrics().density;
            float x = 12 * density + (chart.getWidth() - 54 * density) * .5f;
            tap(x, chart.getHeight() - (34 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        Espresso.onView(withText(row[0])).check(matches(isDisplayed()));
        Espresso.onView(withText("Edit")).check(matches(isDisplayed()));
    }

    @Test
    public void refreshedCacheSnapshotUpdatesExistingMarkersAndRemovesDeletedMarkers() {
        String[] rows = new String[3];
        scenario.onActivity(activity -> {
            Instant at = now.minus(Duration.ofDays(3));
            String time = Formatters.clockTime(activity, at, ZoneId.of("Europe/Warsaw"));
            rows[0] = "Carbs 20 g, " + time;
            rows[1] = "Carbs 15 g, " + time;
            rows[2] = "Carbs 5 g, " + time;
            Instant from = now.minus(Duration.ofDays(7));
            chart.setCachedSegment(new Segment(from, now, List.of(),
                    List.of(entry("updated", at, 15, true), entry("deleted", at, 5, true)), List.of()));
            chart.setCachedSegment(new Segment(from, now, List.of(),
                    List.of(entry("updated", at, 20, true)), List.of()));

            float density = activity.getResources().getDisplayMetrics().density;
            chart.setViewport(new ChartViewport(at.plus(Duration.ofHours(3)), Duration.ofHours(6)));
            float x = 12 * density + (chart.getWidth() - 54 * density) * .5f;
            tap(x, chart.getHeight() - (34 + 18) * density);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        Espresso.onView(withText(rows[0])).check(matches(isDisplayed()));
        Espresso.onView(withText(rows[1])).check(doesNotExist());
        Espresso.onView(withText(rows[2])).check(doesNotExist());
    }

    @Test
    public void flingReschedulesLatestMissingWindowAfterViewBecomesVisible() {
        List<String> requestedWindows = Collections.synchronizedList(new ArrayList<>());
        Instant[] afterDrag = new Instant[1];
        scenario.onActivity(activity -> {
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(), List.of()));
            listenForWindowRequests(requestedWindows);
            fastDrag(.2f, .28f);
            afterDrag[0] = chart.getViewport().end();
        });
        SystemClock.sleep(120);
        scenario.onActivity(activity -> {
            assertTrue("fling advances viewport before hide", chart.getViewport().end().isBefore(afterDrag[0]));
            chart.setVisibility(View.INVISIBLE);
            assertTrue("hidden chart does not request history", requestedWindows.isEmpty());
        });
        SystemClock.sleep(120);
        scenario.onActivity(activity -> {
            assertTrue("hidden chart remains idle", requestedWindows.isEmpty());
            chart.setVisibility(View.VISIBLE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> assertSingleRequestForCurrentViewport(requestedWindows));
    }

    @Test
    public void flingReschedulesLatestMissingWindowAfterDetachAndAttach() {
        List<String> requestedWindows = Collections.synchronizedList(new ArrayList<>());
        Instant[] afterDrag = new Instant[1];
        Instant[] detachedViewport = new Instant[1];
        scenario.onActivity(activity -> {
            chart.setSegment(new Segment(now.minus(Duration.ofHours(6)), now,
                    List.of(), List.of(), List.of()));
            listenForWindowRequests(requestedWindows);
            fastDrag(.2f, .28f);
            afterDrag[0] = chart.getViewport().end();
        });
        SystemClock.sleep(120);
        scenario.onActivity(activity -> {
            assertTrue("fling advances viewport before detach", chart.getViewport().end().isBefore(afterDrag[0]));
            detachedViewport[0] = chart.getViewport().end();
            ViewGroup.LayoutParams params = chart.getLayoutParams();
            chartColumn.removeView(chart);
            assertTrue("detaching does not request history", requestedWindows.isEmpty());
            chartColumn.addView(chart, 0, params);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(activity -> {
            assertEquals("reattached view resumes at the same viewport", detachedViewport[0],
                    chart.getViewport().end());
            assertSingleRequestForCurrentViewport(requestedWindows);
        });
    }

    @Test
    public void recreatedChartUsesPreparedCacheBeforeLoadingShadeButKeepsRealStatesVisible() {
        int[] colors = new int[2];
        scenario.onActivity(activity -> {
            ViewGroup.LayoutParams params = chart.getLayoutParams();
            chartColumn.removeView(chart);
            chart = new GlucoseChartView(activity);
            chartColumn.addView(chart, 0, params);

            ChartViewport retained = new ChartViewport(now.minus(Duration.ofDays(1)),
                    Duration.ofHours(12));
            Instant from = retained.end().minus(retained.span());
            Instant cacheFrom = from.minus(Duration.ofDays(1));
            Instant cachedTo = retained.end().minus(Duration.ofHours(1));
            Reading cachedReading = new Reading("prepared-reading",
                    from.plus(retained.span().dividedBy(2)), 140, Trend.FLAT, "test");
            chart.setNow(now, ZoneId.of("Europe/Warsaw"));
            chart.setViewport(retained);
            chart.setCachedSegment(new Segment(cacheFrom, cachedTo, List.of(cachedReading), List.of(), List.of()));
            chart.setListener(new GlucoseChartView.Listener() {
                @Override
                public void onViewportChanged(ChartViewport viewport, boolean atNow) {
                }

                @Override
                public void onWindowNeeded(Instant requestedFrom, Instant requestedTo) {
                    chart.setSegmentState(requestedFrom, requestedTo, SegmentState.LOADING);
                }

                @Override
                public void onEditRequested(String entryId) {
                }
            });
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        scenario.onActivity(activity -> {
            float density = activity.getResources().getDisplayMetrics().density;
            int sampleX = Math.round(chart.plotBounds().left + 12 * density);   // inside the plot, right of the captions
            // In the target-range band (no tint beyond the guides), between two dashed reference lines (50, 100, 150).
            int sampleY = Math.round(GlucoseRendering.scaleFor(List.of())
                    .y(125, chart.readingTopPx(), chart.readingBottomPx()));
            colors[0] = com.google.android.material.color.MaterialColors.getColor(chart,
                    com.google.android.material.R.attr.colorSurface);
            colors[1] = com.google.android.material.color.MaterialColors.getColor(chart,
                    com.google.android.material.R.attr.colorSurfaceVariant);
            Bitmap initialFrame = drawChart();
            assertEquals("prepared history should not flash a full-window loading shade",
                    colors[0], initialFrame.getPixel(sampleX, sampleY));

            android.graphics.RectF plotBounds = chart.plotBounds();
            int plotLeft = Math.round(plotBounds.left);
            int plotWidth = Math.round(plotBounds.width());
            int readingX = Math.round(plotLeft + plotWidth * .5f);
            float plotTop = chart.readingTopPx();
            float plotBottom = chart.readingBottomPx();
            int chartCenterY = Math.round(plotBounds.centerY());
            int readingY = Math.round(GlucoseRendering.scaleFor(List.of(
                    new Reading("prepared-reading", now.minus(Duration.ofDays(1))
                            .minus(Duration.ofHours(6)), 140, Trend.FLAT, "test")))
                    .y(140, plotTop, plotBottom));
            assertNotEquals("prepared reading remains visible above the loading indicator",
                    colors[0], initialFrame.getPixel(readingX, readingY));
            assertTrue("the genuine loading spinner remains visible",
                    containsColor(initialFrame, colors[1], readingX, chartCenterY, Math.round(14 * density)));
            int uncoveredX = Math.round(plotLeft + plotWidth * .97f);
            assertEquals("unprepared history retains its loading shade",
                    colors[1], initialFrame.getPixel(uncoveredX, sampleY));
            initialFrame.recycle();

            Instant from = chart.getViewport().end().minus(chart.getViewport().span());
            chart.setSegmentState(from, chart.getViewport().end(), SegmentState.OFFLINE);
            Bitmap offlineFrame = drawChart();
            assertEquals("an actual offline result still shades the cached range",
                    colors[1], offlineFrame.getPixel(sampleX, sampleY));
            offlineFrame.recycle();

            chart.setListener(null);
            chart.clearHistoryData();
            chart.setSegmentState(from, chart.getViewport().end(), SegmentState.LOADING);
            Bitmap uncachedLoadingFrame = drawChart();
            assertEquals("a genuine cache miss still shows the loading shade",
                    colors[1], uncachedLoadingFrame.getPixel(sampleX, sampleY));
            uncachedLoadingFrame.recycle();
        });
    }

    private Bitmap drawChart() {
        Bitmap bitmap = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test
    public void basalDataDoesNotDrawOrMoveReadingsMarkersAndAxes() {
        scenario.onActivity(activity -> {
            Instant from = now.minus(Duration.ofHours(6));
            List<Reading> points = List.of(new Reading("reading", now.minus(Duration.ofHours(1)),
                    140, Trend.FLAT, "test"));
            List<Entry> markers = List.of(entry("carbs", now.minus(Duration.ofHours(1)), 15, false));
            chart.setSegment(new Segment(from, now, points, markers, List.of()));
            Bitmap withoutBasal = drawChart();
            List<BasalSegment> basal = List.of(
                    new BasalSegment(from, Duration.ofHours(3), 800, true),
                    new BasalSegment(from.plus(Duration.ofHours(3)), Duration.ofHours(3), 1200, false));
            chart.setSegment(new Segment(from, now, points, markers, basal));
            Bitmap complete = drawChart();
            assertTrue("delivered and profile segments must not change any chart pixels",
                    withoutBasal.sameAs(complete));
            chart.setCachedSegment(new Segment(from, now, points, markers, basal));
            Bitmap cached = drawChart();
            assertTrue("cache preparation must not reintroduce basal rendering", withoutBasal.sameAs(cached));
            withoutBasal.recycle();
            complete.recycle();
            cached.recycle();
        });
    }

    @Test
    public void homePaletteUsesGlassBandsInLightAndDarkAndLeavesLegacyContextUntouched() {
        scenario.onActivity(activity -> {
            for (int mode : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
                Context legacy = new ContextThemeWrapper(activity.createConfigurationContext(configuration),
                        app.lightscout.ui.core.R.style.Theme_LightScout);
                var before = GlucoseRendering.palette(legacy);
                GlucoseChartView themedChart = new GlucoseChartView(legacy);
                var home = GlucoseRendering.palette(themedChart.getContext());
                // Light and dark have their own glass tokens (DCR-29): the chart uses the set of its mode.
                assertEquals(legacy.getColor(app.lightscout.ui.core.R.color.glass_band_in_range), home.inRange());
                assertEquals(legacy.getColor(app.lightscout.ui.core.R.color.glass_band_low), home.low());
                assertEquals(legacy.getColor(app.lightscout.ui.core.R.color.glass_band_very_low), home.urgentLow());
                assertEquals(legacy.getColor(app.lightscout.ui.core.R.color.glass_band_high), home.high());
                assertEquals(legacy.getColor(app.lightscout.ui.core.R.color.glass_band_very_high), home.veryHigh());
                assertEquals("widget/alarm callers retain their own palette", before, GlucoseRendering.palette(legacy));
            }
        });
    }

    private static boolean containsColor(Bitmap bitmap, int color, int centerX, int centerY, int radius) {
        for (int y = Math.max(0, centerY - radius); y < Math.min(bitmap.getHeight(), centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius); x < Math.min(bitmap.getWidth(), centerX + radius); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    return true;
                }
            }
        }
        return false;
    }

    private void listenForWindowRequests(List<String> requestedWindows) {
        chart.setListener(new GlucoseChartView.Listener() {
            @Override
            public void onViewportChanged(ChartViewport viewport, boolean atNow) {
            }

            @Override
            public void onWindowNeeded(Instant from, Instant to) {
                requestedWindows.add(from + "/" + to);
            }

            @Override
            public void onEditRequested(String entryId) {
            }
        });
    }

    private void assertSingleRequestForCurrentViewport(List<String> requestedWindows) {
        ChartViewport current = chart.getViewport();
        assertEquals(List.of(current.end().minus(current.span()) + "/" + current.end()), requestedWindows);
    }

    private float markerX(float density) {
        return 12 * density + (chart.getWidth() - 54 * density) * (5f / 6f);
    }

    private void tap(float x, float y) {
        long time = SystemClock.uptimeMillis();
        dispatch(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0));
        dispatch(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0));
    }

    /** A quick horizontal drag through the scrolling parent, released while moving (fling velocity). */
    private void fastDrag(float fromFraction, float toFraction) {
        float width = chart.getWidth();
        float y = chart.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        dispatch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, width * fromFraction, y, 0));
        for (int i = 1; i <= 5; i++) {
            dispatch(MotionEvent.obtain(t, t + i * 8L, MotionEvent.ACTION_MOVE,
                    width * (fromFraction + (toFraction - fromFraction) * i / 5f), y, 0));
        }
        dispatch(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, width * toFraction, y, 0));
    }

    private float plotWidth() {
        return chart.plotBounds().width();
    }

    /** Dispatches through the NestedScrollView so parent interception applies as on S-01. */
    private void dispatch(MotionEvent event) {
        try {
            scroll.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void send(MotionEvent event) {
        try {
            chart.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private void sendPinch(float firstStart, float secondStart, float firstEnd, float secondEnd, float y) {
        long down = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, firstStart, y, 0));
        send(multi(down, down + 10, MotionEvent.ACTION_POINTER_DOWN
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), firstStart, secondStart, y));
        // Several moves: the detector's first move only begins the scale (factor 1), later moves apply it.
        for (int i = 1; i <= 6; i++) {
            send(multi(down, down + 10 + i * 16L, MotionEvent.ACTION_MOVE,
                    firstStart + (firstEnd - firstStart) * i / 6f, secondStart + (secondEnd - secondStart) * i / 6f, y));
        }
        send(multi(down, down + 120, MotionEvent.ACTION_POINTER_UP
                | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), firstEnd, secondEnd, y));
        send(MotionEvent.obtain(down, down + 130, MotionEvent.ACTION_UP, firstEnd, y, 0));
    }

    private static MotionEvent multi(long down, long eventTime, int action, float firstX, float secondX, float y) {
        return pointers(down, eventTime, action, new float[] {0, firstX, y}, new float[] {1, secondX, y});
    }

    /** Builds a touchscreen event; each pointer is {@code {id, x, y}}. */
    private static MotionEvent pointers(long down, long eventTime, int action, float[]... idXy) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[idXy.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[idXy.length];
        for (int i = 0; i < idXy.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = (int) idXy[i][0];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = idXy[i][1];
            coords[i].y = idXy[i][2];
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        return MotionEvent.obtain(down, eventTime, action, idXy.length, properties, coords,
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    private static Entry entry(String id, Instant time, int grams, boolean own) {
        return new Entry(id, EntryOrigin.TREATMENTS, EntryType.CARBS, time,
                null, grams, null, null, null, null, null, own ? "LightScout" : null,
                "Carbs", own, null);
    }
}
