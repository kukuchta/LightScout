package app.lightscout.feature.stats;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.NumberFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Synthetic-data coverage for errors, period selection and repeatable statistics rendering (TST-06). */
@RunWith(AndroidJUnit4.class)
public class StatisticsScreenDeviceTest {

    private StatisticsTestApplication application;

    @Before
    public void registerIoIdlingResource() {
        application = (StatisticsTestApplication) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertNotNull(application);
        application.dependencies.history.failNext = true;
        application.dependencies.history.readingCount = 80;
        application.dependencies.history.glucoseMgdl = 75;
        IdlingRegistry.getInstance().register(application.dependencies.idlingResource);
    }

    @After
    public void unregisterIoIdlingResource() {
        IdlingRegistry.getInstance().unregister(application.dependencies.idlingResource);
    }

    @Test
    public void errorRetryLoadsMetricsAndPeriodSelectorRemainsAvailable() {
        try (ActivityScenario<StatisticsTestHostActivity> scenario = ActivityScenario.launch(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), StatisticsTestHostActivity.class))) {
            onView(withId(app.lightscout.feature.stats.R.id.s03_period_yesterday))
                    .check(matches(isChecked()));
            onView(withText(app.lightscout.feature.stats.R.string.s03_error))
                    .check(matches(isDisplayed()));

            onView(withId(app.lightscout.feature.stats.R.id.s03_retry)).perform(click());
            onView(withText(app.lightscout.feature.stats.R.string.s03_metric_mean))
                    .check(matches(isDisplayed()));
            onView(withText(app.lightscout.feature.stats.R.string.s03_metric_cv))
                    .check(matches(isDisplayed()));
            onView(withText(app.lightscout.feature.stats.R.string.s03_metric_gmi))
                    .check(matches(isDisplayed()));
            onView(withText(app.lightscout.feature.stats.R.string.s03_band_very_low))
                    .check(matches(isDisplayed()));
            onView(withText(app.lightscout.feature.stats.R.string.s03_band_very_high))
                    .check(matches(isDisplayed()));

            onView(withId(app.lightscout.feature.stats.R.id.s03_period_7_days)).perform(click());
            onView(withId(app.lightscout.feature.stats.R.id.s03_period_7_days))
                    .check(matches(isChecked()));
            scenario.onActivity(activity -> {
                int[] ids = {R.id.s03_period_yesterday, R.id.s03_period_7_days,
                        R.id.s03_period_14_days, R.id.s03_period_30_days};
                int top = activity.findViewById(ids[0]).getTop();
                for (int id : ids) {
                    TextView button = activity.findViewById(id);
                    assertTrue(button.isSingleLine());
                    assertTrue(button.getContentDescription().length() > 0);
                    assertEquals(top, button.getTop());
                }
                View retry = activity.findViewById(R.id.s03_retry);
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) retry.getLayoutParams();
                assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width);
                assertTrue(params.topMargin >= Math.round(24
                        * activity.getResources().getDisplayMetrics().density));
            });
        }
    }

    @Test
    public void repeatedPeriodChangesReplaceMetricsAndLegendWithLatestAsyncResults() {
        application.dependencies.history.failNext = false;
        setReadings(288, 120);
        try (ActivityScenario<StatisticsTestHostActivity> scenario = ActivityScenario.launch(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), StatisticsTestHostActivity.class))) {
            assertRenderedStatistics(scenario, 120, 2, 6.2);

            setReadings(80, 200);
            onView(withId(R.id.s03_period_7_days)).perform(click());
            assertRenderedStatistics(scenario, 200, 3, 8.1);

            setReadings(4032, 50);
            onView(withId(R.id.s03_period_14_days)).perform(click());
            assertRenderedStatistics(scenario, 50, 0, 4.5);

            setReadings(288, 140);
            onView(withId(R.id.s03_period_yesterday)).perform(click());
            assertRenderedStatistics(scenario, 140, 2, 6.7);

            setReadings(80, 200);
            onView(withId(R.id.s03_period_7_days)).perform(click());
            assertRenderedStatistics(scenario, 200, 3, 8.1);
            scenario.recreate();
            assertRenderedStatistics(scenario, 200, 3, 8.1);
        }
    }

    @Test
    public void contentAndEmptyTransitionsReplaceRowsAndClearHiddenLegend() {
        application.dependencies.history.failNext = false;
        setReadings(288, 120);
        try (ActivityScenario<StatisticsTestHostActivity> scenario = ActivityScenario.launch(new Intent(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), StatisticsTestHostActivity.class))) {
            assertRenderedStatistics(scenario, 120, 2, 6.2);
            // The populated result card hugs its content: the frame reserves no height (SS-35).
            scenario.onActivity(activity -> assertEquals(0,
                    activity.findViewById(R.id.s03_result_frame).getMinimumHeight()));

            setReadings(0, 120);
            onView(withId(R.id.s03_period_7_days)).perform(click());
            assertRenderedStatistics(scenario, 0, -1, 0);
            // The empty state keeps the frame from collapsing: 352 sp, scaled with the font setting.
            scenario.onActivity(activity -> {
                int reserved = Math.round(android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_SP, 352, activity.getResources().getDisplayMetrics()));
                View frame = activity.findViewById(R.id.s03_result_frame);
                assertTrue(frame.getMinimumHeight() >= reserved);
                assertTrue(frame.getHeight() >= reserved);
            });

            setReadings(80, 200);
            onView(withId(R.id.s03_period_14_days)).perform(click());
            assertRenderedStatistics(scenario, 200, 3, 8.1);

            setReadings(0, 200);
            onView(withId(R.id.s03_period_yesterday)).perform(click());
            assertRenderedStatistics(scenario, 0, -1, 0);
            scenario.recreate();
            assertRenderedStatistics(scenario, 0, -1, 0);

            setReadings(2016, 150);
            onView(withId(R.id.s03_period_7_days)).perform(click());
            assertRenderedStatistics(scenario, 150, 2, 6.9);
        }
    }

    private void setReadings(int count, int glucoseMgdl) {
        application.dependencies.history.readingCount = count;
        application.dependencies.history.glucoseMgdl = glucoseMgdl;
    }

    private void assertRenderedStatistics(ActivityScenario<StatisticsTestHostActivity> scenario,
                                          int glucoseMgdl, int band, double expectedGmi) {
        onView(withId(R.id.s03_result_card)).check(matches(isDisplayed()));
        scenario.onActivity(activity -> {
            boolean empty = band < 0;
            LinearLayout metrics = activity.findViewById(R.id.s03_metrics);
            int[] labels = {R.string.s03_metric_mean, R.string.s03_metric_cv, R.string.s03_metric_gmi};
            NumberFormat decimal = NumberFormat.getNumberInstance(Locale.getDefault());
            decimal.setMinimumFractionDigits(1);
            decimal.setMaximumFractionDigits(1);
            String[] values = {
                    activity.getString(R.string.s03_metric_mean_value, glucoseMgdl),
                    activity.getString(R.string.s03_percent, 0),
                    activity.getString(R.string.s03_metric_gmi_value, decimal.format(expectedGmi))
            };
            // Three metric rows with a divider between them only (none under the last one): 3 + 2 children.
            assertEquals(empty ? 0 : 5, metrics.getChildCount());
            int rowCount = 0;
            for (int i = 0; i < metrics.getChildCount(); i++) {
                if (metrics.getChildAt(i) instanceof LinearLayout) {
                    rowCount++;
                }
            }
            assertEquals(empty ? 0 : 3, rowCount);
            for (int i = 0; i < rowCount; i++) {
                LinearLayout row = (LinearLayout) metrics.getChildAt(i * 2);
                LinearLayout primary = (LinearLayout) row.getChildAt(0);
                String label = activity.getString(labels[i]);
                assertEquals(label, ((TextView) primary.getChildAt(0)).getText().toString());
                assertEquals(values[i], ((TextView) primary.getChildAt(1)).getText().toString());
                assertEquals(1, row.getChildCount());
                assertTrue(row.getContentDescription().toString().startsWith(label + ", " + values[i]));
            }
            LinearLayout legend = activity.findViewById(R.id.s03_legend);
            assertEquals(empty ? 0 : 5, legend.getChildCount());
            assertEquals(empty ? View.GONE : View.VISIBLE, legend.getVisibility());
            assertEquals(empty ? View.GONE : View.VISIBLE,
                    activity.findViewById(R.id.s03_distribution_bar).getVisibility());
            assertEquals(empty ? View.VISIBLE : View.GONE,
                    activity.findViewById(R.id.s03_empty).getVisibility());
            assertEquals(empty ? View.GONE : View.VISIBLE, activity.findViewById(R.id.s03_content).getVisibility());
            int[] bands = {R.string.s03_band_very_high, R.string.s03_band_high,
                    R.string.s03_band_in_range, R.string.s03_band_low, R.string.s03_band_very_low};
            for (int i = 0; i < legend.getChildCount(); i++) {
                LinearLayout row = (LinearLayout) legend.getChildAt(i);
                String label = activity.getString(bands[i]);
                String value = activity.getString(R.string.s03_percent, band == 4 - i ? 100 : 0);
                assertEquals(label, ((TextView) row.getChildAt(1)).getText().toString());
                assertEquals(value, ((TextView) row.getChildAt(2)).getText().toString());
                assertEquals(label + ", " + value, row.getContentDescription().toString());
            }
        });
    }
}
