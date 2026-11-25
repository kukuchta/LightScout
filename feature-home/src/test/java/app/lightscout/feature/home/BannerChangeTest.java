package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;

import app.lightscout.feature.home.HomeUiState.BannerAction;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.feature.home.HomeUiState.BannerRole;
import app.lightscout.feature.home.HomeUiState.BannerUi;

import org.junit.Test;

/**
 * IR-103 (M3-14, Moderator decision): banner views change only when the content changes; the polite live region
 * announces a new banner or a different kind, never the per-minute {n} or action-state change of the same banner.
 */
public class BannerChangeTest {

    private static BannerUi offline(long minutes) {
        return new BannerUi(BannerKind.OFFLINE, minutes, BannerAction.NONE, BannerRole.SECONDARY, true);
    }

    private static BannerUi retry(boolean enabled) {
        return new BannerUi(BannerKind.NS_NOT_RESPONDING, 0, BannerAction.RETRY, BannerRole.ERROR, enabled);
    }

    @Test
    public void firstRenderShowsWithoutAnnouncementDecision() {
        assertEquals(BannerChange.INITIAL, BannerChange.of(true, null, offline(3)));
        assertEquals(BannerChange.HIDE, BannerChange.of(true, null, null));
    }

    @Test
    public void newBannerIsAnnounced() {
        assertEquals(BannerChange.ANNOUNCE, BannerChange.of(false, null, offline(3)));
    }

    @Test
    public void differentKindIsAnnounced() {
        assertEquals(BannerChange.ANNOUNCE, BannerChange.of(false, offline(3), retry(true)));
    }

    @Test
    public void minuteChangeIsQuiet() {
        assertEquals(BannerChange.QUIET, BannerChange.of(false, offline(3), offline(4)));
    }

    @Test
    public void actionStateChangeIsQuiet() {
        assertEquals(BannerChange.QUIET, BannerChange.of(false, retry(true), retry(false)));
    }

    @Test
    public void sameContentTouchesNothing() {
        assertEquals(BannerChange.NONE, BannerChange.of(false, offline(3), offline(3)));
        assertEquals(BannerChange.NONE, BannerChange.of(false, null, null));
    }

    @Test
    public void disappearingBannerIsHidden() {
        assertEquals(BannerChange.HIDE, BannerChange.of(false, offline(3), null));
    }
}
