package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * §9 "GIR-6 insets" with synthetic window insets dispatched to the views: app bar below the status bar, bottom
 * content and sticky buttons above the navigation bar (+ 16 dp), IME keeps the content above the keyboard, S-09
 * actions + 24 dp / 16 dp; repeated dispatches do not accumulate.
 *
 * <p>Device: RD-2 (Android 13, API 33) over USB — batched run (CP-00 §10 CR-04). Compiled in SS-08. The real
 * API 35/36 edge-to-edge layout is observed on RD-1 with the screens that use these helpers (feature slices).
 */
@RunWith(AndroidJUnit4.class)
public class InsetsDeviceTest {

    private static final int STATUS = 63;
    private static final int NAV = 48;
    private static final int IME = 800;

    private static WindowInsetsCompat insets(int ime) {
        return new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                .build();
    }

    private static int dp(Context c, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                c.getResources().getDisplayMetrics()));
    }

    private static View child(Context c) {
        FrameLayout parent = new FrameLayout(c);
        View v = new View(c);
        parent.addView(v, new FrameLayout.LayoutParams(100, 100));
        return v;
    }

    @Test
    public void appBarTakesStatusBarInset() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View appBar = child(c);
            appBar.setPadding(0, 4, 0, 0);
            EdgeToEdgeInsets.applyStatusBarInset(appBar);
            ViewCompat.dispatchApplyWindowInsets(appBar, insets(0));
            ViewCompat.dispatchApplyWindowInsets(appBar, insets(0));
            assertEquals(4 + STATUS, appBar.getPaddingTop());
        });
    }

    @Test
    public void scrollContentAndStickyButtonsStayAboveNavigationBar() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View list = child(c);
            list.setPadding(0, 0, 0, 8);
            EdgeToEdgeInsets.applyScrollBottomInset(list, null);
            ViewCompat.dispatchApplyWindowInsets(list, insets(0));
            ViewCompat.dispatchApplyWindowInsets(list, insets(0));
            assertEquals(8 + NAV, list.getPaddingBottom());

            View button = child(c);
            EdgeToEdgeInsets.applyStickyBottomInset(button);
            ViewCompat.dispatchApplyWindowInsets(button, insets(0));
            assertEquals(NAV + dp(c, 16),
                    ((FrameLayout.LayoutParams) button.getLayoutParams()).bottomMargin);
        });
    }

    @Test
    public void imeKeepsContentAboveKeyboard() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = child(c);
            EdgeToEdgeInsets.applyImeInset(root);
            ViewCompat.dispatchApplyWindowInsets(root, insets(IME));
            assertEquals(IME, root.getPaddingBottom());
            ViewCompat.dispatchApplyWindowInsets(root, insets(0));
            assertEquals(NAV, root.getPaddingBottom());
        });
    }

    @Test
    public void alarmActionsKeepTwentyFourDpAndSixteenDpSides() {
        Context c = DeviceContexts.themed(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View actions = child(c);
            EdgeToEdgeInsets.applyAlarmActionInsets(actions);
            ViewCompat.dispatchApplyWindowInsets(actions, insets(0));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) actions.getLayoutParams();
            assertEquals(NAV + dp(c, 24), lp.bottomMargin);
            assertEquals(dp(c, 16), lp.leftMargin);
            assertEquals(dp(c, 16), lp.rightMargin);
        });
    }
}
