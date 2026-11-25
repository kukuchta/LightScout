package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * §9 "200 % font" for the shared styles: every typography role renders the S-01 / S-03 / S-09 / S-12 sample texts
 * at fontScale 2.0 without truncation (no ellipsis, measured height holds all lines). The full-screen samples are
 * checked again by the feature slices.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08.
 */
@RunWith(AndroidJUnit4.class)
public class FontScaleDeviceTest {

    private static final int[] ROLES = {
        R.style.TextAppearance_LightScout_ScreenTitle, R.style.TextAppearance_LightScout_CurrentReading,
        R.style.TextAppearance_LightScout_CurrentReading_Widget, R.style.TextAppearance_LightScout_AlarmTitle,
        R.style.TextAppearance_LightScout_CounterTitle, R.style.TextAppearance_LightScout_CounterValue,
        R.style.TextAppearance_LightScout_CounterStatus, R.style.TextAppearance_LightScout_DoughnutCentre,
        R.style.TextAppearance_LightScout_DoughnutLegend, R.style.TextAppearance_LightScout_ChartLabel,
        R.style.TextAppearance_LightScout_ListPrimary, R.style.TextAppearance_LightScout_Supporting,
        R.style.TextAppearance_LightScout_SupportingSmall,
    };

    @Test
    public void sharedStylesDoNotTruncateAtFontScaleTwo() {
        Context c = DeviceContexts.themed(false, 2.0f);
        int width = Math.round(360 * c.getResources().getDisplayMetrics().density);
        String[] samples = {
            "142",
            c.getString(R.string.s01_age_h_min, 12, 59),
            c.getString(R.string.s01_counter_value, 10, 23),
            c.getString(R.string.trend_double_down),
            c.getString(R.string.s01_age_no_readings),
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int role : ROLES) {
                for (String sample : samples) {
                    TextView tv = new TextView(c);
                    tv.setTextAppearance(role);
                    tv.setText(sample);
                    tv.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    tv.layout(0, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
                    Layout layout = tv.getLayout();
                    for (int line = 0; line < layout.getLineCount(); line++) {
                        assertEquals(0, layout.getEllipsisCount(line));
                    }
                    assertTrue(tv.getMeasuredHeight() >= layout.getHeight());
                }
            }
        });
    }
}
