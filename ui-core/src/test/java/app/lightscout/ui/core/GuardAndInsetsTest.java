package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * GIR-1 guard state machine (§9 "GIR-1 guard": the second tap is ignored until completion) and the pure GIR-6 inset
 * arithmetic (§9 "GIR-6 insets": bottom content above the navigation bar, sticky buttons + 16 dp, IME). The View
 * wiring is covered by {@code GuardDeviceTest} and {@code InsetsDeviceTest}.
 */
public class GuardAndInsetsTest {

    @Test
    public void secondTapIsIgnoredUntilCompletion() {
        SingleActionGuard guard = new SingleActionGuard();
        int runs = 0;
        for (int tap = 0; tap < 3; tap++) {
            if (guard.tryStart()) {
                runs++;
            }
        }
        assertEquals(1, runs);
        assertTrue(guard.isInProgress());
        guard.complete();
        assertFalse(guard.isInProgress());
        assertTrue(guard.tryStart());
    }

    @Test
    public void scrollContentPaddingAddsNavigationBarAndFab() {
        assertEquals(8 + 48, InsetMath.scrollBottomPadding(8, 48, 0, 42));
        assertEquals(8 + 48 + 168 + 42, InsetMath.scrollBottomPadding(8, 48, 168, 42));   // S-01: FAB + 16 dp
        assertEquals(8, InsetMath.scrollBottomPadding(8, -1, 0, 42));
    }

    @Test
    public void stickyButtonsSitSixteenDpAboveNavigationBar() {
        assertEquals(48 + 42, InsetMath.stickyBottomMargin(48, 42));
        assertEquals(42, InsetMath.stickyBottomMargin(0, 42));
    }

    @Test
    public void imeKeepsFocusedFieldVisible() {
        assertEquals(800, InsetMath.imeBottomPadding(0, 800, 48));
        assertEquals(48, InsetMath.imeBottomPadding(0, 0, 48));
        assertEquals(16 + 800, InsetMath.imeBottomPadding(16, 800, 48));
    }

    @Test
    public void alarmSideAndBottomInsets() {
        assertEquals(42, InsetMath.sideMargin(0, 42));
        assertEquals(60 + 42, InsetMath.sideMargin(60, 42));
        assertEquals(24, InsetMath.ALARM_BOTTOM_DP);
        assertEquals(16, InsetMath.ALARM_SIDE_DP);
        assertEquals(16, InsetMath.STICKY_MARGIN_DP);
    }
}
