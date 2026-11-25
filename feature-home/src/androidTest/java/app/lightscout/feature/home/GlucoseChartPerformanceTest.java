package app.lightscout.feature.home;

import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.Window;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.usecases.Segment;

/** TST-10 FrameMetrics check on RD-2 using synthetic seven-day chart data only. */
@RunWith(AndroidJUnit4.class)
public class GlucoseChartPerformanceTest {

    /** QA-07, D-154: p95 frame budget 20 ms (RD-1 is the only device; measured 17.3-17.6 ms against the old 16.7 ms). */
    private static final long FRAME_BUDGET_NS = 20_000_000L;
    private static final long MAX_FRAME_NS = 50_000_000L;
    private static final int PSS_BUDGET_KIB = 150 * 1024;

    @Test
    public void panAndZoomStayWithinQa07Budgets() {
        TestHomeApplication app = (TestHomeApplication) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        app.reset();
        List<Long> frames = Collections.synchronizedList(new ArrayList<>());
        GlucoseChartView[] chartReference = new GlucoseChartView[1];
        Instant now = Instant.now();
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(new Intent(app, TestHostActivity.class))) {
            scenario.onActivity(activity -> {
                GlucoseChartView chart = new GlucoseChartView(activity);
                chartReference[0] = chart;
                chart.setNow(now, ZoneId.systemDefault());
                chart.setThresholds(55, 70, 250);
                chart.setSummary(activity.getString(R.string.s01_chart_a11y, "142", "steady"));
                chart.setSegment(sevenDaySegment(now));
                chart.setViewport(new ChartViewport(now, Duration.ofHours(24)));
                activity.setContentView(chart);
                activity.getWindow().addOnFrameMetricsAvailableListener(
                        (Window window, FrameMetrics metrics, int dropped) ->
                                frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)),
                        new Handler(Looper.getMainLooper()));
                chart.invalidate();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(250);
            frames.clear(); // exclude initial population draw; only gestures are measured below.

            // Drive repeated chart pans through the same touch dispatch path as a user drag; one gesture per
            // main-thread task so each produces its own frame.
            for (int i = 0; i < 12; i++) {
                final int index = i;
                scenario.onActivity(activity -> {
                    GlucoseChartView chart = chartReference[0];
                    float y = chart.getHeight() / 2f;
                    long down = SystemClock.uptimeMillis();
                    // Alternate right (older) and left (newer) drags so every pan moves the 7-day data.
                    float from = index % 2 == 0 ? .25f : .75f;
                    float to = 1f - from;
                    send(chart, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                            chart.getWidth() * from, y, 0));
                    send(chart, MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_MOVE,
                            chart.getWidth() * to, y, 0));
                    send(chart, MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP,
                            chart.getWidth() * to, y, 0));
                    chart.invalidate();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                SystemClock.sleep(24);
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(250);
            assertTrue("TST-10 must capture a representative gesture frame set", frames.size() >= 12);
            ArrayList<Long> ordered = new ArrayList<>(frames);
            Collections.sort(ordered);
            long p95 = ordered.get((int) Math.ceil(ordered.size() * .95) - 1);
            assertTrue("QA-07 p95 frame duration was " + p95 + " ns", p95 <= FRAME_BUDGET_NS);
            assertTrue("QA-07 frame exceeded 50 ms", ordered.get(ordered.size() - 1) <= MAX_FRAME_NS);
            Debug.MemoryInfo memory = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memory);
            assertTrue("QA-07 PSS was " + memory.getTotalPss() + " KiB",
                    memory.getTotalPss() <= PSS_BUDGET_KIB);
        }
    }

    private static Segment sevenDaySegment(Instant now) {
        Instant from = now.minus(Duration.ofDays(7));
        ArrayList<Reading> readings = new ArrayList<>(2016);
        ArrayList<Entry> markers = new ArrayList<>(336);
        for (int i = 0; i < 2016; i++) {
            Instant time = from.plus(Duration.ofMinutes(5L * i));
            int value = 90 + i % 110;
            readings.add(new Reading("synthetic-" + i, time, value, Trend.FLAT, "test"));
            if (i % 6 == 0) {
                markers.add(new Entry("marker-" + i, EntryOrigin.TREATMENTS, EntryType.CARBS, time,
                        null, 15, null, null, null, null, null, null, "Carbs", false, null));
            }
        }
        return new Segment(from, now.plusMillis(1), readings, markers, List.of());
    }

    private static void send(GlucoseChartView chart, MotionEvent event) {
        try {
            chart.onTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
