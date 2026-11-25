package app.lightscout.feature.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeLayoutPolicyTest {

    @Test
    public void largeFontBoundaryKeepsAccessibilityScrollingAllowance() {
        assertFalse(HomeLayoutPolicy.allowsScrolling(1.0f));
        assertFalse(HomeLayoutPolicy.allowsScrolling(1.49f));
        assertTrue(HomeLayoutPolicy.allowsScrolling(1.5f));
        assertTrue(HomeLayoutPolicy.allowsScrolling(2.0f));
        assertFalse(HomeLayoutPolicy.requiresWrapping(1.0f));
        assertTrue(HomeLayoutPolicy.requiresWrapping(1.01f));
        assertTrue(HomeLayoutPolicy.requiresWrapping(2.0f));
    }

    @Test
    public void standardFontScrollsOnlyWhenFixedContentAndUsableChartExceedViewport() {
        int fixedContentPx = 440;
        int chartMinimumPx = 160;
        assertFalse(HomeLayoutPolicy.shouldScroll(1.0f, 600, fixedContentPx, chartMinimumPx));
        assertFalse(HomeLayoutPolicy.shouldScroll(1.0f, 601, fixedContentPx, chartMinimumPx));
        assertTrue(HomeLayoutPolicy.shouldScroll(1.0f, 599, fixedContentPx, chartMinimumPx));
        assertTrue(HomeLayoutPolicy.shouldScroll(1.49f, 599, fixedContentPx, chartMinimumPx));
    }

    @Test
    public void viewportRecalculationUsesPostInsetAvailableHeight() {
        int fixedContentPx = 440;
        int chartMinimumPx = 160;
        int viewportBeforeInsetsPx = 660;
        int viewportAfterInsetsPx = viewportBeforeInsetsPx - 24 - 36;

        assertFalse(HomeLayoutPolicy.shouldScroll(
                1.0f, viewportBeforeInsetsPx, fixedContentPx, chartMinimumPx));
        assertFalse(HomeLayoutPolicy.shouldScroll(
                1.0f, viewportAfterInsetsPx, fixedContentPx, chartMinimumPx));
        assertTrue(HomeLayoutPolicy.shouldScroll(
                1.0f, viewportAfterInsetsPx - 1, fixedContentPx, chartMinimumPx));
        assertFalse(HomeLayoutPolicy.shouldScroll(
                1.0f, viewportBeforeInsetsPx, fixedContentPx, chartMinimumPx));
    }

    @Test
    public void largeFontScrollsRegardlessOfAvailableViewport() {
        assertTrue(HomeLayoutPolicy.shouldScroll(1.5f, 10_000, 440, 160));
        assertTrue(HomeLayoutPolicy.shouldScroll(2.0f, 0, 0, 0));
    }
}
