package app.lightscout.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.text.Layout;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;
import app.lightscout.ui.core.Formatters;
import app.lightscout.ui.core.GlucoseRendering;

/** Synthetic launcher-size tests: no widget registration, server or monitoring state is touched. */
@RunWith(AndroidJUnit4.class)
public class WidgetLayoutDeviceTest {
    private static final Instant TO = Instant.parse("2026-10-28T15:00:00Z");
    private static final Instant FROM = TO.minusSeconds(10800);

    @Test
    public void contentRemainsReadableAndChartUsableAtLauncherSizesAndFontScales() {
        for (float scale : new float[]{1f, 2f}) {
            for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Context context = themed(scale, night);
                int minimumHeight = scale == 1f ? 160 : 300;
                for (int[] size : new int[][]{{250, minimumHeight}, {320, minimumHeight},
                        {420, minimumHeight}, {600, 360}}) {
                    for (WidgetPresentation presentation : contentStates()) {
                        assertRendered(context, presentation, size[0], size[1]);
                    }
                }
            }
        }
    }

    @Test
    public void compactNormalFontKeepsAllStatesReadableWithUsableChart() {
        List<WidgetPresentation> states = new ArrayList<>(contentStates());
        for (WidgetPresentation.State state : new WidgetPresentation.State[]{
                WidgetPresentation.State.FIRST_USE, WidgetPresentation.State.NO_READINGS}) {
            states.add(new WidgetPresentation(state, null, WidgetPresentation.RangeLabel.NONE, null,
                    false, 70, 250));
        }
        for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Context context = themed(1f, night);
            for (WidgetPresentation state : states) {
                assertFitsCompactHeight(context, state, 160);
            }
        }
    }

    private static void assertFitsCompactHeight(Context context, WidgetPresentation presentation, int height) {
        View root = render(context, presentation, 250, height);
        View chart = root.findViewById(R.id.widget_chart);
        assertTrue(chart.getHeight() >= dp(context, 64));
        for (int id : new int[]{R.id.widget_reading,
                R.id.widget_age, R.id.widget_empty}) {
            TextView text = root.findViewById(id);
            if (text.getVisibility() == View.VISIBLE && text.length() > 0) {
                if (id != R.id.widget_age) {
                    assertTextVisible(text);
                }
                Rect box = bounds(root, text);
                assertTrue(box.left >= root.getPaddingLeft());
                assertTrue(box.right <= root.getWidth() - root.getPaddingRight());
                assertTrue(box.bottom <= bounds(root, chart).top);
            }
        }
        if (presentation.state() == WidgetPresentation.State.CONTENT) {
            assertAgeFits(context, root, presentation);
        }
    }

    @Test
    public void ageKeepsItsTextWidthAfterApplyAndReapplyAtSupportedMinimum() {
        for (float scale : new float[]{1f, 2f}) {
            Context context = themed(scale, Configuration.UI_MODE_NIGHT_NO);
            int height = scale == 1f ? 160 : 300;
            View reused = null;
            for (WidgetPresentation presentation : contentStates()) {
                RemoteViews update = views(context, presentation, 250, height);
                View fresh = update.apply(context, new FrameLayout(context));
                measure(fresh, context, 250, height);
                assertAgeFits(context, fresh, presentation);
                if (reused == null) {
                    reused = fresh;
                }
                update.reapply(context, reused);
                measure(reused, context, 250, height);
                reused.draw(new Canvas(Bitmap.createBitmap(reused.getWidth(), reused.getHeight(),
                        Bitmap.Config.ARGB_8888)));
                assertAgeFits(context, reused, presentation);
            }
        }
    }

    @Test
    public void firstLineShowsDeltaUnitAndTrendAndOnlyTheReadingTakesTheRangeColour() {
        Context context = themed(1f, Configuration.UI_MODE_NIGHT_YES);
        int[] expected = {context.getColor(R.color.widget_on_surface),
                context.getColor(app.lightscout.ui.core.R.color.notification_guide_high),
                context.getColor(app.lightscout.ui.core.R.color.glass_guide_low),
                context.getColor(app.lightscout.ui.core.R.color.glass_guide_low)};
        for (int i = 0; i < expected.length; i++) {
            View root = render(context, contentStates().get(i), 420, 200);
            assertEquals(expected[i], ((TextView) root.findViewById(R.id.widget_reading)).getCurrentTextColor());
        }
        Reading latest = new Reading("synthetic", TO.minusSeconds(180), 300, Trend.FORTY_FIVE_DOWN, null);
        WidgetPresentation high = new WidgetPresentation(WidgetPresentation.State.CONTENT, latest,
                WidgetPresentation.RangeLabel.HIGH, Duration.ofMinutes(3), false, 70, 250, -5);
        View root = render(context, high, 420, 200);
        assertEquals("-5", ((TextView) root.findViewById(R.id.widget_delta)).getText().toString());
        assertEquals("mg/dL", ((TextView) root.findViewById(R.id.widget_delta_unit)).getText().toString());
        assertEquals(context.getColor(R.color.widget_on_surface),
                ((TextView) root.findViewById(R.id.widget_delta)).getCurrentTextColor());
        assertEquals(View.VISIBLE, root.findViewById(R.id.widget_trend).getVisibility());
        assertTrue(bounds(root, root.findViewById(R.id.widget_age)).left
                >= bounds(root, root.findViewById(R.id.widget_delta_unit)).right);
        assertTrue(bounds(root, root.findViewById(R.id.widget_trend)).left
                >= bounds(root, root.findViewById(R.id.widget_age)).right);
        WidgetPresentation zero = new WidgetPresentation(WidgetPresentation.State.CONTENT, latest,
                WidgetPresentation.RangeLabel.NONE, Duration.ofMinutes(3), false, 70, 250, 0);
        assertEquals("+0", ((TextView) render(context, zero, 420, 200).findViewById(R.id.widget_delta))
                .getText().toString());
    }

    @Test
    public void emptyStatesAndRemoteViewsReapplyDoNotRetainOldBadges() {
        Context context = themed(2f, Configuration.UI_MODE_NIGHT_NO);
        RemoteViews content = views(context, contentStates().get(2), 250, 300);
        View root = content.apply(context, new FrameLayout(context));
        measure(root, context, 250, 300);
        for (WidgetPresentation.State state : new WidgetPresentation.State[]{
                WidgetPresentation.State.FIRST_USE, WidgetPresentation.State.NO_READINGS}) {
            WidgetPresentation empty = new WidgetPresentation(state, null,
                    WidgetPresentation.RangeLabel.NONE, null, false, 70, 250);
            RemoteViews updated = views(context, empty, 250, 300);
            View fresh = updated.apply(context, new FrameLayout(context));
            measure(fresh, context, 250, 300);
            assertTextVisible(fresh.findViewById(R.id.widget_empty));
            updated.reapply(context, root);
            measure(root, context, 250, 300);
            root.draw(new Canvas(Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888)));
            assertEquals(View.GONE, root.findViewById(R.id.widget_stale_icon).getVisibility());
            assertTextVisible(root.findViewById(R.id.widget_empty));
            assertChart(context, root);
        }
    }

    private static void assertRendered(Context context, WidgetPresentation presentation, int width, int height) {
        View root = render(context, presentation, width, height);
        TextView reading = root.findViewById(R.id.widget_reading);
        TextView age = root.findViewById(R.id.widget_age);
        assertTextVisible(reading);
        assertTrue(reading.getTextSize() >= sp(context, 22) - 0.5f);
        assertTrue(age.length() > 0);
        assertFalse(Rect.intersects(bounds(root, reading), bounds(root, age)));
        assertTrue(root.getContentDescription().toString().contains(Integer.toString(presentation.latest().sgv())));
        assertEquals(presentation.stale() ? View.VISIBLE : View.GONE,
                root.findViewById(R.id.widget_stale_icon).getVisibility());
        View chart = root.findViewById(R.id.widget_chart);
        for (int id : new int[]{R.id.widget_reading, R.id.widget_age}) {
            View text = root.findViewById(id);
            if (text.getVisibility() == View.VISIBLE) {
                Rect box = bounds(root, text);
                assertTrue(box.left >= root.getPaddingLeft());
                assertTrue(box.right <= root.getWidth() - root.getPaddingRight());
                assertTrue(box.bottom <= bounds(root, chart).top);
            }
        }
        assertChart(context, root);
    }

    private static void assertChart(Context context, View root) {
        ImageView chart = root.findViewById(R.id.widget_chart);
        assertTrue("chart must retain at least 64dp", chart.getHeight() >= dp(context, 64));
        Bitmap bitmap = ((BitmapDrawable) chart.getDrawable()).getBitmap();
        assertEquals(chart.getWidth(), bitmap.getWidth());
        assertEquals(chart.getHeight(), bitmap.getHeight());
        int color = GlucoseRendering.palette(context).inRange();
        int labelColor = context.getColor(app.lightscout.ui.core.R.color.md_theme_onSurfaceVariant);
        int labelPixels = 0;
        int top = bitmap.getHeight();
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
                if (bitmap.getPixel(x, y) == labelColor) {
                    labelPixels++;
                }
            }
        }
        assertTrue("synthetic glucose excursion must not become a flat strip", bottom - top >= dp(context, 28));
        assertEquals("widget chart must not draw hour labels", 0, labelPixels);
    }

    private static void assertTextVisible(TextView text) {
        Layout layout = text.getLayout();
        assertNotNull(layout);
        String context = "view=" + text.getResources().getResourceEntryName(text.getId())
                + ", text=\"" + text.getText() + "\", size=" + text.getWidth() + "x" + text.getHeight()
                + ", fontScale=" + text.getResources().getConfiguration().fontScale;
        assertTrue(context + ": text exceeds available height",
                layout.getHeight() <= text.getHeight() - text.getCompoundPaddingTop()
                - text.getCompoundPaddingBottom());
        int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals(0, layout.getEllipsisCount(line));
            String lineContext = context + ", line=" + line + ", availableWidth=" + availableWidth
                    + ", lineWidth=" + layout.getLineWidth(line) + ", visibleWidth=" + layout.getLineMax(line)
                    + ", start=" + layout.getLineStart(line) + ", visibleEnd=" + layout.getLineVisibleEnd(line)
                    + ", end=" + layout.getLineEnd(line);
            // getLineWidth includes the trailing space at a wrap; getLineMax measures visible content.
            assertTrue(lineContext + ": visible text exceeds its available width",
                    layout.getLineMax(line) <= availableWidth + 1);
            if (layout.getLineWidth(line) > availableWidth + 1) {
                int visibleEnd = layout.getLineVisibleEnd(line);
                int end = layout.getLineEnd(line);
                assertTrue(lineContext + ": overflow must consist only of trailing whitespace", visibleEnd < end);
                for (int index = visibleEnd; index < end; index++) {
                    assertTrue(lineContext + ": non-whitespace overflow",
                            Character.isWhitespace(text.getText().charAt(index)));
                }
            }
        }
        assertEquals(text.length(), layout.getLineEnd(layout.getLineCount() - 1));
    }

    private static void assertAgeFits(Context context, View root, WidgetPresentation presentation) {
        TextView age = root.findViewById(R.id.widget_age);
        assertEquals(Formatters.readingAge(context, presentation.age()), age.getText().toString());
        if (context.getResources().getConfiguration().fontScale <= 1f) {
            assertTrue("age must have positive text width", age.getWidth() > 0);
        }
        assertEquals(1, age.getLayout().getLineCount());
        Rect box = bounds(root, age);
        assertTrue(box.left >= root.getPaddingLeft());
        assertTrue(box.right <= root.getWidth() - root.getPaddingRight());
        assertTrue(box.bottom <= bounds(root, root.findViewById(R.id.widget_chart)).top);
        assertFalse(Rect.intersects(box, bounds(root, root.findViewById(R.id.widget_reading))));
    }

    private static Rect bounds(View root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    private static View render(Context context, WidgetPresentation presentation, int width, int height) {
        View root = views(context, presentation, width, height).apply(context, new FrameLayout(context));
        measure(root, context, width, height);
        root.draw(new Canvas(Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888)));
        return root;
    }

    private static RemoteViews views(Context context, WidgetPresentation presentation, int width, int height) {
        return GlucoseWidgetUpdater.buildViews(context, presentation, readings(), FROM, TO, width, height, null);
    }

    private static void measure(View root, Context context, int width, int height) {
        int w = dp(context, width);
        int h = dp(context, height);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static float sp(Context context, int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics());
    }

    private static Context themed(float scale, int night) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = scale;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return new ContextThemeWrapper(base.createConfigurationContext(configuration),
                app.lightscout.ui.core.R.style.Theme_LightScout);
    }

    private static List<WidgetPresentation> contentStates() {
        List<WidgetPresentation> states = new ArrayList<>();
        int[] values = {142, 300, 45, 70};
        WidgetPresentation.RangeLabel[] labels = {WidgetPresentation.RangeLabel.NONE,
                WidgetPresentation.RangeLabel.HIGH, WidgetPresentation.RangeLabel.URGENT_LOW,
                WidgetPresentation.RangeLabel.LOW};
        for (int i = 0; i < values.length; i++) {
            states.add(new WidgetPresentation(WidgetPresentation.State.CONTENT,
                    new Reading("synthetic", TO.minusSeconds(180), values[i], Trend.DOUBLE_UP, null),
                    labels[i], Duration.ofMinutes(3), false, 70, 250));
        }
        states.add(new WidgetPresentation(WidgetPresentation.State.CONTENT,
                new Reading("stale", TO.minusSeconds(604800), 999, Trend.DOUBLE_DOWN, null),
                WidgetPresentation.RangeLabel.HIGH, Duration.ofMinutes(10080), true, 70, 250));
        states.add(new WidgetPresentation(WidgetPresentation.State.CONTENT,
                new Reading("stale-urgent", TO.minusSeconds(604800), 45, Trend.UNKNOWN, null),
                WidgetPresentation.RangeLabel.URGENT_LOW, Duration.ofMinutes(10080), true, 70, 250));
        return states;
    }

    private static List<Reading> readings() {
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i <= 36; i++) {
            readings.add(new Reading("synthetic-" + i, FROM.plusSeconds(i * 300L),
                    i % 2 == 0 ? 90 : 170, Trend.FLAT, null));
        }
        return readings;
    }
}
