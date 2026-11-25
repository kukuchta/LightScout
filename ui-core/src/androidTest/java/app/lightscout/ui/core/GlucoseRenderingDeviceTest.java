package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.IconCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

/**
 * §9 statusNumberIcon and renderMiniChart on a real Canvas.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08. API &lt; 31
 * ("static scheme") cannot be observed on RD-2; it is covered by the JVM {@code ThemeResourceTest} (no dynamic
 * colour, no values-v31) because the same static resources apply on every API level.
 */
@RunWith(AndroidJUnit4.class)
public class GlucoseRenderingDeviceTest {

    private static final Instant TO = Instant.parse("2026-10-15T12:00:00Z");
    private static final Instant FROM = TO.minus(Duration.ofHours(3));

    private static Bitmap bitmapOf(Context c, IconCompat icon) {
        Drawable d = icon.loadDrawable(c);
        assertNotNull(d);
        return ((BitmapDrawable) d).getBitmap();
    }

    private static void assertWhiteOnTransparent(Bitmap b) {
        int opaque = 0;
        for (int x = 0; x < b.getWidth(); x++) {
            for (int y = 0; y < b.getHeight(); y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) == 0) {
                    continue;
                }
                opaque++;
                if (Color.alpha(p) >= 64) {
                    assertTrue("white glyph", Color.red(p) >= 0xF0 && Color.green(p) >= 0xF0 && Color.blue(p) >= 0xF0);
                }
            }
        }
        assertTrue("glyph drawn", opaque > 0);
    }

    @Test
    public void numberIconRendersThreeDigitsAndDash() {
        Context c = DeviceContexts.themed(false);
        for (Integer sgv : new Integer[] {39, 142, 401, null}) {
            IconCompat icon = GlucoseRendering.statusNumberIcon(c, sgv);
            assertEquals(IconCompat.TYPE_BITMAP, icon.getType());
            Bitmap b = bitmapOf(c, icon);
            assertWhiteOnTransparent(b);
            assertEquals(0, Color.alpha(b.getPixel(0, 0)));
        }
    }

    @Test
    public void glyphFitsTheIcon() {
        Context c = DeviceContexts.themed(false);
        Bitmap b = NumberIcon.render(c, "401");
        int minX = b.getWidth();
        int maxX = -1;
        for (int x = 0; x < b.getWidth(); x++) {
            for (int y = 0; y < b.getHeight(); y++) {
                if (Color.alpha(b.getPixel(x, y)) != 0) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        assertTrue("left edge kept", minX > 0 || maxX < b.getWidth() - 1);
        assertTrue("uses most of the width", maxX - minX > b.getWidth() / 2);
    }

    @Test
    public void injectedRenderFailureGivesResourceIconWithoutException() {
        Context c = DeviceContexts.themed(false);
        IconCompat icon = GlucoseRendering.statusNumberIcon(c, 142, (ctx, text) -> {
            throw new IllegalStateException("injected");
        });
        assertEquals(IconCompat.TYPE_RESOURCE, icon.getType());
        assertEquals(R.drawable.ic_stat_lightscout, icon.getResId());
        IconCompat oom = GlucoseRendering.statusNumberIcon(c, null, (ctx, text) -> {
            throw new OutOfMemoryError("injected");
        });
        assertEquals(IconCompat.TYPE_RESOURCE, oom.getType());
    }

    private static List<Reading> threeHours() {
        List<Reading> list = new ArrayList<>();
        for (int i = 0; i <= 36; i++) {
            Instant t = FROM.plus(Duration.ofMinutes(5L * i));
            list.add(new Reading("r" + i, t, 100 + (i % 12) * 10, Trend.FLAT, "test"));
        }
        return list;
    }

    private static int count(Bitmap b, int color) {
        int n = 0;
        for (int x = 0; x < b.getWidth(); x++) {
            for (int y = 0; y < b.getHeight(); y++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) > 200 && Math.abs(Color.red(px) - Color.red(color)) <= 24
                        && Math.abs(Color.green(px) - Color.green(color)) <= 24
                        && Math.abs(Color.blue(px) - Color.blue(color)) <= 24) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    public void miniChartDrawsLineThresholdsAndLabels() {
        Context c = DeviceContexts.themed(false);
        GlucosePalette p = GlucoseRendering.palette(c);
        Bitmap b = GlucoseRendering.renderMiniChart(c, threeHours(), FROM, TO, 70, 250, 600, 200);
        assertEquals(600, b.getWidth());
        assertEquals(200, b.getHeight());
        assertTrue("line", count(b, p.inRange()) > 100);
        assertTrue("threshold lines", count(b, p.threshold()) > 50);
        int labelColor = c.getColor(R.color.md_theme_onSurfaceVariant);
        assertTrue("start/end labels", count(b, labelColor) > 0);
    }

    @Test
    public void miniChartCanOmitTimeLabelsWithoutRemovingLineOrThresholds() {
        Context c = DeviceContexts.themed(false);
        GlucosePalette p = GlucoseRendering.palette(c);
        Bitmap labeled = GlucoseRendering.renderMiniChart(c, threeHours(), FROM, TO, 70, 250, 600, 200);
        Bitmap unlabeled = GlucoseRendering.renderMiniChart(c, threeHours(), FROM, TO, 70, 250, 600, 200, false);
        int labelColor = c.getColor(R.color.md_theme_onSurfaceVariant);
        assertTrue(count(labeled, labelColor) > 0);
        assertEquals(0, count(unlabeled, labelColor));
        assertTrue(count(unlabeled, p.inRange()) > 100);
        assertTrue(count(unlabeled, p.threshold()) > 50);
    }

    @Test
    public void miniChartWithoutReadingsIsValidWithoutLine() {
        Context c = DeviceContexts.themed(false);
        GlucosePalette p = GlucoseRendering.palette(c);
        Bitmap b = GlucoseRendering.renderMiniChart(c, new ArrayList<>(), FROM, TO, 70, 250, 600, 200);
        assertEquals(0, count(b, p.inRange()));
        assertTrue(count(b, p.threshold()) > 0);
        Bitmap degenerate = GlucoseRendering.renderMiniChart(c, null, FROM, TO, 70, 250, 0, -5);
        assertEquals(1, degenerate.getWidth());
    }

    private static double luminance(int argb) {
        double[] ch = {Color.red(argb) / 255.0, Color.green(argb) / 255.0, Color.blue(argb) / 255.0};
        for (int i = 0; i < 3; i++) {
            ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }

    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Q-55: the stroke roles of container-filled marks reach 3:1 against the surface, light and dark. */
    @Test
    public void strokeRolesReachThreeToOneAgainstSurface() {
        for (boolean night : new boolean[] {false, true}) {
            Context c = DeviceContexts.themed(night);
            GlucosePalette p = GlucoseRendering.palette(c);
            int surface = c.getColor(R.color.md_theme_surface);
            assertTrue("urgent-low stroke", contrast(p.urgentLowStroke(), surface) >= 3.0);
            assertTrue("very-high stroke", contrast(p.veryHighStroke(), surface) >= 3.0);
            assertTrue("basal edge", contrast(p.basalEdge(), surface) >= 3.0);
        }
    }

    @Test
    public void lightAndDarkUiModeGiveTheirSchemeColours() {
        Context light = DeviceContexts.themed(false);
        Context dark = DeviceContexts.themed(true);
        GlucosePalette pl = GlucoseRendering.palette(light);
        GlucosePalette pd = GlucoseRendering.palette(dark);
        assertEquals(0xFF3F5F90, pl.inRange());
        assertEquals(0xFFA8C8FF, pd.inRange());
        assertEquals(0xFF7E5700, pl.high());
        assertEquals(0xFFFFBA38, pd.high());
        assertEquals(0xFF74777F, pl.threshold());
        assertEquals(0xFF8E9099, pd.threshold());
        assertNotEquals(pl, pd);
        Bitmap bd = GlucoseRendering.renderMiniChart(dark, threeHours(), FROM, TO, 70, 250, 600, 200);
        assertTrue(count(bd, pd.inRange()) > 100);
        assertFalse(count(bd, pl.inRange()) > 0);
    }
}
