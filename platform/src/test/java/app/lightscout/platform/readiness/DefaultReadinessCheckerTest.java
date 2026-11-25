package app.lightscout.platform.readiness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.lightscout.platform.ChannelState;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.ReadinessStatus;

/**
 * CP-05 §9 „Readiness per band” with an SDK-level fake (CR-04 replaces the emulator bands 29/31/33/34/35/36) and
 * „Readiness item 7”. Band-gated APIs are never called below their band.
 */
public class DefaultReadinessCheckerTest {

    private static final ChannelState READY =
            new ChannelState("alarms", true, true, NotificationManager.IMPORTANCE_HIGH, true);

    private static final List<ReadinessItemId> DISPLAY_ORDER = List.of(
            ReadinessItemId.NOTIFICATIONS, ReadinessItemId.DND_ACCESS, ReadinessItemId.FULL_SCREEN,
            ReadinessItemId.BATTERY, ReadinessItemId.EXACT_ALARM, ReadinessItemId.ALARM_CHANNEL,
            ReadinessItemId.MANUFACTURER);

    @Test
    public void displayOrderAndItem6LastNotCountedOnEveryBand() {
        for (int sdk = 29; sdk <= 37; sdk++) {
            FakeProbe probe = FakeProbe.allGranted(sdk);
            List<ReadinessItem> items = new DefaultReadinessChecker(probe).check();
            List<ReadinessItemId> ids = new ArrayList<>();
            for (ReadinessItem item : items) {
                ids.add(item.id());
            }
            assertEquals("sdk " + sdk, DISPLAY_ORDER, ids);
            ReadinessItem item6 = items.get(6);
            assertTrue(item6.shown());
            assertFalse(item6.required());
            assertEquals(ReadinessStatus.RECOMMENDED, item6.status());
            assertFalse(item6.missingRequired());
            assertEquals("sdk " + sdk, 0, new DefaultReadinessChecker(probe).missingRequiredCount());
        }
    }

    @Test
    public void item1ShownOnB3PlusAlways() {
        for (int sdk : new int[] {33, 34, 35, 36}) {
            assertTrue(item(FakeProbe.allGranted(sdk), ReadinessItemId.NOTIFICATIONS).shown());
        }
    }

    @Test
    public void item1BelowB3ShownAndCountedOnlyWhenNotificationsOff() {
        for (int sdk : new int[] {29, 30, 31, 32}) {
            FakeProbe granted = FakeProbe.allGranted(sdk);
            assertFalse(item(granted, ReadinessItemId.NOTIFICATIONS).shown());

            FakeProbe off = FakeProbe.allGranted(sdk);
            off.notifications = false;
            ReadinessItem item = item(off, ReadinessItemId.NOTIFICATIONS);
            assertTrue(item.shown());
            assertEquals(ReadinessStatus.NEEDS_ATTENTION, item.status());
            assertEquals(1, new DefaultReadinessChecker(off).missingRequiredCount());
        }
    }

    @Test
    public void item3OnlyOnB4Plus() {
        for (int sdk : new int[] {29, 30, 31, 32, 33}) {
            FakeProbe probe = FakeProbe.allGranted(sdk);
            probe.fullScreen = false;
            ReadinessItem item = item(probe, ReadinessItemId.FULL_SCREEN);
            assertFalse(item.shown());
            assertEquals(0, new DefaultReadinessChecker(probe).missingRequiredCount());
            assertEquals("not called below 34", 0, probe.fullScreenCalls);
        }
        for (int sdk : new int[] {34, 35, 36}) {
            FakeProbe probe = FakeProbe.allGranted(sdk);
            probe.fullScreen = false;
            ReadinessItem item = item(probe, ReadinessItemId.FULL_SCREEN);
            assertTrue(item.shown());
            assertEquals(ReadinessStatus.NEEDS_ATTENTION, item.status());
            assertEquals(1, new DefaultReadinessChecker(probe).missingRequiredCount());
        }
    }

    @Test
    public void item5OnlyOnB2PlusAndOnlyWhenExactAlarmsNotAllowed() {
        for (int sdk : new int[] {29, 30}) {
            FakeProbe probe = FakeProbe.allGranted(sdk);
            probe.exactAlarms = false;
            assertFalse(item(probe, ReadinessItemId.EXACT_ALARM).shown());
            assertEquals("not called below 31", 0, probe.exactAlarmCalls);
        }
        for (int sdk : new int[] {31, 32, 33, 34, 35, 36}) {
            FakeProbe allowed = FakeProbe.allGranted(sdk);
            assertFalse("hidden when true", item(allowed, ReadinessItemId.EXACT_ALARM).shown());

            FakeProbe denied = FakeProbe.allGranted(sdk);
            denied.exactAlarms = false;
            ReadinessItem item = item(denied, ReadinessItemId.EXACT_ALARM);
            assertTrue(item.shown());
            assertTrue(item.required());
            assertEquals(ReadinessStatus.NEEDS_ATTENTION, item.status());
            assertEquals(1, new DefaultReadinessChecker(denied).missingRequiredCount());
        }
    }

    @Test
    public void items2And4And7AlwaysShownAndRequired() {
        for (int sdk = 29; sdk <= 36; sdk++) {
            FakeProbe probe = FakeProbe.allGranted(sdk);
            probe.policyAccess = false;
            probe.battery = false;
            probe.channel = new ChannelState("alarms", true, true, NotificationManager.IMPORTANCE_HIGH, false);
            for (ReadinessItemId id : List.of(ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY,
                    ReadinessItemId.ALARM_CHANNEL)) {
                ReadinessItem item = item(probe, id);
                assertTrue(item.shown());
                assertTrue(item.required());
                assertEquals(ReadinessStatus.NEEDS_ATTENTION, item.status());
            }
            assertEquals(3, new DefaultReadinessChecker(probe).missingRequiredCount());
        }
    }

    @Test
    public void missingRequiredCountsEveryShownRequiredNotDoneItem() {
        FakeProbe probe = FakeProbe.allGranted(36);
        probe.notifications = false;
        probe.policyAccess = false;
        probe.fullScreen = false;
        probe.battery = false;
        probe.exactAlarms = false;
        probe.channel = new ChannelState("alarms", false, false, NotificationManager.IMPORTANCE_NONE, false);
        assertEquals(6, new DefaultReadinessChecker(probe).missingRequiredCount());

        FakeProbe b1 = FakeProbe.allGranted(29);
        b1.notifications = false;
        b1.policyAccess = false;
        b1.fullScreen = false;
        b1.battery = false;
        b1.exactAlarms = false;
        b1.channel = probe.channel;
        assertEquals("items 3 and 5 do not exist on B1", 4, new DefaultReadinessChecker(b1).missingRequiredCount());
    }

    @Test
    public void item7NeedsAttentionWhenImportanceLoweredOrBypassOff() {
        FakeProbe lowered = FakeProbe.allGranted(33);
        lowered.channel = new ChannelState("alarms", true, true, NotificationManager.IMPORTANCE_DEFAULT, true);
        assertEquals(ReadinessStatus.NEEDS_ATTENTION, item(lowered, ReadinessItemId.ALARM_CHANNEL).status());

        FakeProbe bypassOff = FakeProbe.allGranted(33);
        bypassOff.channel = new ChannelState("alarms_v2", true, true, NotificationManager.IMPORTANCE_HIGH, false);
        assertEquals(ReadinessStatus.NEEDS_ATTENTION, item(bypassOff, ReadinessItemId.ALARM_CHANNEL).status());

        assertEquals(ReadinessStatus.DONE, item(FakeProbe.allGranted(33), ReadinessItemId.ALARM_CHANNEL).status());
    }

    private static ReadinessItem item(FakeProbe probe, ReadinessItemId id) {
        for (ReadinessItem item : new DefaultReadinessChecker(probe).check()) {
            if (item.id() == id) {
                return item;
            }
        }
        throw new AssertionError(id);
    }

    /** SDK-level / capability fake; band-gated queries fail the test when called below their band. */
    private static final class FakeProbe implements ReadinessProbe {
        final int sdk;
        boolean notifications = true;
        boolean policyAccess = true;
        boolean fullScreen = true;
        boolean battery = true;
        boolean exactAlarms = true;
        ChannelState channel = READY;
        int fullScreenCalls;
        int exactAlarmCalls;

        private FakeProbe(int sdk) {
            this.sdk = sdk;
        }

        static FakeProbe allGranted(int sdk) {
            return new FakeProbe(sdk);
        }

        @Override
        public int sdkInt() {
            return sdk;
        }

        @Override
        public boolean notificationsEnabled() {
            return notifications;
        }

        @Override
        public boolean notificationPolicyAccessGranted() {
            return policyAccess;
        }

        @Override
        public boolean canUseFullScreenIntent() {
            fullScreenCalls++;
            if (sdk < 34) {
                throw new AssertionError("canUseFullScreenIntent called on API " + sdk);
            }
            return fullScreen;
        }

        @Override
        public boolean ignoringBatteryOptimizations() {
            return battery;
        }

        @Override
        public boolean canScheduleExactAlarms() {
            exactAlarmCalls++;
            if (sdk < 31) {
                throw new AssertionError("canScheduleExactAlarms called on API " + sdk);
            }
            return exactAlarms;
        }

        @Override
        public ChannelState alarmChannel() {
            return channel;
        }
    }
}
