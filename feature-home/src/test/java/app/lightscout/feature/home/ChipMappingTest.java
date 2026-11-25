package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.home.HomeUiState.BannerKind;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

/** Every chip kind is reachable from a surface key, has a slot and a tier (DCR-21 / DCR-24, review SS-35 B5). */
public class ChipMappingTest {

    @Test
    public void everyBannerKindIsProducedByASurfaceKey() {
        Set<BannerKind> mapped = EnumSet.noneOf(BannerKind.class);
        mapped.addAll(HomeModel.BANNER_KINDS.values());
        assertEquals(EnumSet.allOf(BannerKind.class), mapped);
    }

    @Test
    public void everyBannerKindHasASlotAndATier() {
        for (BannerKind kind : BannerKind.values()) {
            assertTrue(kind.name(), HomeFragment.slotOf(kind) >= 0 && HomeFragment.slotOf(kind) <= 3);
            assertNotNull(kind.name(), HomeFragment.tierOf(kind));
        }
    }

    @Test
    public void slotsGroupAlarmsReadinessConnectionAndOffline() {
        assertEquals(0, HomeFragment.slotOf(BannerKind.ALARMS_OFF));
        assertEquals(0, HomeFragment.slotOf(BannerKind.LOW_HIGH_ALARMS_OFF));
        assertEquals(1, HomeFragment.slotOf(BannerKind.PERMISSION_MISSING));
        assertEquals(2, HomeFragment.slotOf(BannerKind.TOKEN_REJECTED));
        assertEquals(2, HomeFragment.slotOf(BannerKind.DATA_LOSS));
        assertEquals(3, HomeFragment.slotOf(BannerKind.OFFLINE));
    }

    @Test
    public void tiersFollowTheApprovedColours() {
        assertEquals(HomeFragment.ChipTier.CAUTION, HomeFragment.tierOf(BannerKind.LOW_ALARM_OFF));
        assertEquals(HomeFragment.ChipTier.CAUTION, HomeFragment.tierOf(BannerKind.HIGH_ALARM_OFF));
        assertEquals(HomeFragment.ChipTier.CAUTION, HomeFragment.tierOf(BannerKind.TOKEN_PERMISSION_WRITE));
        assertEquals(HomeFragment.ChipTier.INFO, HomeFragment.tierOf(BannerKind.OFFLINE));
        assertEquals(HomeFragment.ChipTier.WARNING, HomeFragment.tierOf(BannerKind.ALARMS_OFF));
        assertEquals(HomeFragment.ChipTier.WARNING, HomeFragment.tierOf(BannerKind.TOKEN_REJECTED));
        assertEquals(HomeFragment.ChipTier.WARNING, HomeFragment.tierOf(BannerKind.VERSION_TOO_OLD));
    }
}
