package app.lightscout.feature.onboarding.readiness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.onboarding.Fakes;
import app.lightscout.feature.onboarding.readiness.ReadinessModel.Command;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Action;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Item;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Status;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.ReadinessStatus;
import app.lightscout.platform.readiness.DefaultReadinessChecker;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.domain.alarm.AlarmType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * S-07 logic (CP-14 §7.4, §9 TST-06 S-07 bands / resume / states / Done / item 7) on the JVM. API bands use the real
 * CMP-27 {@link DefaultReadinessChecker} over an SDK-level probe fake (CP-00 §10 CR-04).
 */
public class ReadinessModelTest {

    private final Fakes.FakeProbe probe = new Fakes.FakeProbe();
    private final Fakes.FakeChannels channels = new Fakes.FakeChannels();
    private final Fakes.FakeStatusRepository status = new Fakes.FakeStatusRepository();
    private final Fakes.FakeOnboarding onboarding = new Fakes.FakeOnboarding();
    private final Fakes.QueueExecutor io = new Fakes.QueueExecutor();
    private final List<ReadinessUiState> states = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();

    private ReadinessModel model(ReadinessMode mode, ReadinessChecker checker) {
        ReadinessModel m = new ReadinessModel(mode, checker, channels, status, onboarding,
                () -> Manufacturer.XIAOMI, probe.sdk >= 33, io, Fakes.DIRECT);
        m.setListener(new ReadinessModel.Listener() {
            @Override
            public void onState(ReadinessUiState state) {
                states.add(state);
            }

            @Override
            public void onCommand(Command command) {
                commands.add(command);
            }
        });
        return m;
    }

    private ReadinessModel resumed(ReadinessMode mode) {
        ReadinessModel m = model(mode, new DefaultReadinessChecker(probe));
        m.onResume(false);
        io.runAll();
        return m;
    }

    private static List<ReadinessItemId> ids(ReadinessUiState s) {
        List<ReadinessItemId> out = new ArrayList<>();
        for (Item i : s.items()) {
            out.add(i.id());
        }
        return out;
    }

    private static Item item(ReadinessUiState s, ReadinessItemId id) {
        for (Item i : s.items()) {
            if (i.id() == id) {
                return i;
            }
        }
        return null;
    }

    private ReadinessUiState last() {
        return states.get(states.size() - 1);
    }

    // ---- bands (TST-06 S-07 bands → JVM per CR-04) ----

    @Test
    public void band29ShowsDndBatteryChannelManufacturerOnly() {
        probe.sdk = 29;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(List.of(ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY, ReadinessItemId.ALARM_CHANNEL,
                ReadinessItemId.MANUFACTURER), ids(last()));
    }

    @Test
    public void band29NotificationsOffShowsItem1AndOpensAppNotificationSettings() {
        probe.sdk = 29;
        probe.notifications = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        assertEquals(ReadinessItemId.NOTIFICATIONS, ids(last()).get(0));
        assertEquals(Action.ALLOW, item(last(), ReadinessItemId.NOTIFICATIONS).action());
        m.onItemAction(ReadinessItemId.NOTIFICATIONS);
        assertEquals(new Command(Command.Kind.OPEN_ITEM_SETTINGS, ReadinessItemId.NOTIFICATIONS, null),
                commands.get(0));
    }

    @Test
    public void band30MatchesB1() {   // IR-100
        probe.sdk = 30;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(List.of(ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY, ReadinessItemId.ALARM_CHANNEL,
                ReadinessItemId.MANUFACTURER), ids(last()));
    }

    @Test
    public void band32MatchesB2() {   // IR-100
        probe.sdk = 32;
        probe.exactAlarms = false;
        probe.notifications = false;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(List.of(ReadinessItemId.NOTIFICATIONS, ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY,
                ReadinessItemId.EXACT_ALARM, ReadinessItemId.ALARM_CHANNEL, ReadinessItemId.MANUFACTURER),
                ids(last()));
        assertEquals(2, last().missingRequired());
        probe.exactAlarms = true;
        probe.notifications = true;
        states.clear();
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(List.of(ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY, ReadinessItemId.ALARM_CHANNEL,
                ReadinessItemId.MANUFACTURER), ids(last()));
    }

    @Test
    public void doneIsIgnoredUntilTheFirstCheckResultArrives() {   // IR-98
        probe.sdk = 36;
        ReadinessModel m = model(ReadinessMode.ONBOARDING, new DefaultReadinessChecker(probe));
        assertTrue(last().doneVisible());
        assertFalse(last().doneEnabled());
        m.onResume(false);
        m.onDone();                      // check still queued on io
        assertFalse(last().missingDialogVisible());
        assertEquals(1, io.size());
        io.runAll();
        assertEquals(0, onboarding.completes);
        assertTrue(commands.isEmpty());
        assertTrue(last().doneEnabled());
        m.onDone();
        io.runAll();
        assertEquals(1, onboarding.completes);
    }

    @Test
    public void band31ExactAlarmOnlyWhenNotAllowed() {
        probe.sdk = 31;
        probe.exactAlarms = true;
        resumed(ReadinessMode.ONBOARDING);
        assertFalse(ids(last()).contains(ReadinessItemId.EXACT_ALARM));
        probe.exactAlarms = false;
        states.clear();
        resumed(ReadinessMode.ONBOARDING);
        assertTrue(ids(last()).contains(ReadinessItemId.EXACT_ALARM));
        assertEquals(1, last().missingRequired());
    }

    @Test
    public void band33ShowsNotificationsButNoFullScreen() {
        probe.sdk = 33;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(List.of(ReadinessItemId.NOTIFICATIONS, ReadinessItemId.DND_ACCESS, ReadinessItemId.BATTERY,
                ReadinessItemId.ALARM_CHANNEL, ReadinessItemId.MANUFACTURER), ids(last()));
    }

    @Test
    public void band34And36ShowFullScreen() {
        for (int sdk : new int[] {34, 35, 36}) {
            probe.sdk = sdk;
            states.clear();
            resumed(ReadinessMode.ONBOARDING);
            assertEquals("sdk " + sdk, List.of(ReadinessItemId.NOTIFICATIONS, ReadinessItemId.DND_ACCESS,
                    ReadinessItemId.FULL_SCREEN, ReadinessItemId.BATTERY, ReadinessItemId.ALARM_CHANNEL,
                    ReadinessItemId.MANUFACTURER), ids(last()));
        }
    }

    @Test
    public void displayOrderIsOneToFiveSevenSixWhateverTheCheckerOrder() {
        probe.sdk = 36;
        probe.exactAlarms = false;
        List<ReadinessItem> scrambled = new ArrayList<>(new DefaultReadinessChecker(probe).check());
        java.util.Collections.reverse(scrambled);
        ReadinessModel m = model(ReadinessMode.ONBOARDING, fixed(scrambled));
        m.onResume(false);
        io.runAll();
        assertEquals(List.of(ReadinessItemId.NOTIFICATIONS, ReadinessItemId.DND_ACCESS, ReadinessItemId.FULL_SCREEN,
                ReadinessItemId.BATTERY, ReadinessItemId.EXACT_ALARM, ReadinessItemId.ALARM_CHANNEL,
                ReadinessItemId.MANUFACTURER), ids(last()));
    }

    @Test
    public void countIsShownRequiredNotDoneAndNeverItem6() {
        probe.sdk = 36;
        probe.notifications = false;
        probe.dnd = false;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(2, last().missingRequired());
        assertFalse(last().allSet());
        Item six = item(last(), ReadinessItemId.MANUFACTURER);
        assertEquals(Status.RECOMMENDED, six.status());
        assertEquals(Action.OPEN_GUIDE, six.action());
    }

    @Test
    public void allRequiredDoneShowsAllSet() {
        probe.sdk = 36;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(0, last().missingRequired());
        assertTrue(last().allSet());
        assertEquals(Status.DONE, item(last(), ReadinessItemId.BATTERY).status());
        assertEquals(Action.NONE, item(last(), ReadinessItemId.BATTERY).action());
    }

    @Test
    public void nothingShownBeforeTheFirstCheck() {
        model(ReadinessMode.ONBOARDING, new DefaultReadinessChecker(probe));
        assertTrue(last().items().isEmpty());
        assertFalse(last().allSet());
    }

    @Test
    public void checkRunsOnIoNotOnTheCaller() {
        ReadinessModel m = model(ReadinessMode.ONBOARDING, new DefaultReadinessChecker(probe));
        m.onResume(false);
        assertEquals(1, io.size());
        assertTrue(last().items().isEmpty());
    }

    // ---- resume (TST-06 S-07 resume) ----

    @Test
    public void everyResumeRechecks() {
        probe.sdk = 33;
        probe.notifications = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        assertEquals(Status.NEEDS_ATTENTION, item(last(), ReadinessItemId.NOTIFICATIONS).status());
        probe.notifications = true;          // granted in system settings
        probe.battery = false;               // battery exemption revoked
        m.onResume(false);
        io.runAll();
        assertEquals(Status.DONE, item(last(), ReadinessItemId.NOTIFICATIONS).status());
        assertEquals(Status.NEEDS_ATTENTION, item(last(), ReadinessItemId.BATTERY).status());
    }

    // ---- F7 guard (Q-52) ----

    @Test
    public void resumeRunsF7WhenNoAlarmRings() {
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(1, channels.ensureChannels);
        assertEquals(1, channels.bypass);
    }

    @Test
    public void resumeNeverRunsF7WhileAnAlarmRings() {
        status.ringing = AlarmType.URGENT_LOW;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(0, channels.bypass);
        assertEquals(1, channels.ensureChannels);
    }

    @Test
    public void f7RunsBeforeTheCheckSoItem7ShowsTheNewChannel() {
        probe.sdk = 33;
        probe.channelReady = false;
        channels.onBypass = () -> probe.channelReady = true;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(Status.DONE, item(last(), ReadinessItemId.ALARM_CHANNEL).status());
    }

    @Test
    public void failedRecreationLeavesItem7NeedingAttentionWithChannelSettings() {
        probe.sdk = 36;
        probe.channelReady = false;
        channels.failure = new IllegalStateException("x");
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        Item seven = item(last(), ReadinessItemId.ALARM_CHANNEL);
        assertEquals(Status.NEEDS_ATTENTION, seven.status());
        assertEquals(Action.OPEN_SETTINGS, seven.action());
        m.onItemAction(ReadinessItemId.ALARM_CHANNEL);
        assertEquals(new Command(Command.Kind.OPEN_ITEM_SETTINGS, ReadinessItemId.ALARM_CHANNEL, null),
                commands.get(0));
    }

    @Test
    public void permissionResultRechecksWithoutF7() {
        probe.sdk = 33;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onNotificationPermissionResult(true);
        io.runAll();
        assertEquals(1, channels.bypass);
    }

    // ---- actions and states (TST-06 S-07 states) ----

    @Test
    public void itemActionsMapToTheirTargets() {
        probe.sdk = 36;
        probe.notifications = false;
        probe.dnd = false;
        probe.fullScreen = false;
        probe.battery = false;
        probe.exactAlarms = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        assertEquals(Action.ALLOW, item(last(), ReadinessItemId.DND_ACCESS).action());
        assertEquals(Action.ALLOW, item(last(), ReadinessItemId.FULL_SCREEN).action());
        assertEquals(Action.ALLOW, item(last(), ReadinessItemId.BATTERY).action());
        assertEquals(Action.ALLOW, item(last(), ReadinessItemId.EXACT_ALARM).action());
        m.onItemAction(ReadinessItemId.NOTIFICATIONS);
        m.onItemAction(ReadinessItemId.DND_ACCESS);
        m.onItemAction(ReadinessItemId.MANUFACTURER);
        assertEquals(Command.Kind.REQUEST_NOTIFICATION_PERMISSION, commands.get(0).kind());
        assertEquals(new Command(Command.Kind.OPEN_ITEM_SETTINGS, ReadinessItemId.DND_ACCESS, null), commands.get(1));
        assertEquals(new Command(Command.Kind.OPEN_GUIDE, null, Manufacturer.XIAOMI), commands.get(2));
    }

    @Test
    public void doneItemHasNoAction() {
        probe.sdk = 36;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onItemAction(ReadinessItemId.BATTERY);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void deniedWithRationaleAsksAgain() {
        probe.sdk = 33;
        probe.notifications = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onNotificationPermissionResult(true);
        io.runAll();
        Item one = item(last(), ReadinessItemId.NOTIFICATIONS);
        assertEquals(Status.NEEDS_ATTENTION, one.status());
        assertEquals(Action.ALLOW, one.action());
    }

    @Test
    public void deniedWithoutRationaleAfterARequestIsPermanentlyDenied() {
        probe.sdk = 33;
        probe.notifications = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onNotificationPermissionResult(false);
        io.runAll();
        Item one = item(last(), ReadinessItemId.NOTIFICATIONS);
        assertEquals(Status.PERMANENTLY_DENIED, one.status());
        assertEquals(Action.OPEN_SETTINGS, one.action());
        m.onItemAction(ReadinessItemId.NOTIFICATIONS);
        assertEquals(new Command(Command.Kind.OPEN_APP_NOTIFICATION_SETTINGS, ReadinessItemId.NOTIFICATIONS, null),
                commands.get(0));
    }

    @Test
    public void noRationaleBeforeAnyRequestIsNotPermanentlyDenied() {
        probe.sdk = 33;
        probe.notifications = false;
        resumed(ReadinessMode.ONBOARDING);
        assertEquals(Status.NEEDS_ATTENTION, item(last(), ReadinessItemId.NOTIFICATIONS).status());
    }

    @Test
    public void permanentlyDeniedClearsOnceGranted() {
        probe.sdk = 33;
        probe.notifications = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onNotificationPermissionResult(false);
        io.runAll();
        probe.notifications = true;
        m.onResume(false);
        io.runAll();
        assertEquals(Status.DONE, item(last(), ReadinessItemId.NOTIFICATIONS).status());
    }

    @Test
    public void launchFailureShowsInlineErrorUntilTheItemPasses() {
        probe.sdk = 36;
        probe.dnd = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onLaunchFailed(ReadinessItemId.DND_ACCESS);
        assertTrue(item(last(), ReadinessItemId.DND_ACCESS).error());
        assertFalse(item(last(), ReadinessItemId.BATTERY).error());
        m.onResume(false);
        io.runAll();
        assertTrue("still failing → error stays", item(last(), ReadinessItemId.DND_ACCESS).error());
        probe.dnd = true;
        m.onResume(false);
        io.runAll();
        assertFalse(item(last(), ReadinessItemId.DND_ACCESS).error());
        probe.dnd = false;
        m.onResume(false);
        io.runAll();
        assertFalse("cleared after the passing check", item(last(), ReadinessItemId.DND_ACCESS).error());
    }

    @Test
    public void checkerFailureKeepsThePreviousStatuses() {
        probe.sdk = 33;
        Fakes.FlakyChecker flaky = new Fakes.FlakyChecker(new DefaultReadinessChecker(probe));
        ReadinessModel m = model(ReadinessMode.ONBOARDING, flaky);
        m.onResume(false);
        io.runAll();
        flaky.fail = true;
        m.onResume(false);
        io.runAll();
        assertEquals(5, last().items().size());
    }

    // ---- Done (TST-06 S-07 Done, FL-10) ----

    @Test
    public void doneWithAllRequiredCompletesOnboardingOnceAndFinishes() {
        probe.sdk = 36;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        assertTrue(last().doneVisible());
        m.onDone();
        m.onDone();
        assertFalse(last().doneEnabled());
        assertEquals(0, onboarding.completes);
        io.runAll();
        assertEquals(1, onboarding.completes);
        assertEquals(List.of(new Command(Command.Kind.FINISH_ONBOARDING, null, null)), commands);
    }

    @Test
    public void doneWithMissingItemsAsksFirstAndGoBackStays() {
        probe.sdk = 36;
        probe.battery = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onDone();
        assertTrue(last().missingDialogVisible());
        m.onDone();
        m.onMissingDialogGoBack();
        io.runAll();
        assertFalse(last().missingDialogVisible());
        assertEquals(0, onboarding.completes);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void missingDialogContinueCompletesOnboarding() {
        probe.sdk = 36;
        probe.battery = false;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onDone();
        m.onMissingDialogContinue();
        m.onMissingDialogContinue();
        io.runAll();
        assertEquals(1, onboarding.completes);
        assertEquals(Command.Kind.FINISH_ONBOARDING, commands.get(0).kind());
        assertFalse(last().missingDialogVisible());
    }

    @Test
    public void onlyRecommendedItem6OpenDoesNotAsk() {
        probe.sdk = 29;
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onDone();
        assertFalse(last().missingDialogVisible());
        io.runAll();
        assertEquals(1, onboarding.completes);
    }

    @Test
    public void checkModeHasNoDoneButton() {
        probe.sdk = 36;
        ReadinessModel m = resumed(ReadinessMode.CHECK);
        assertFalse(last().doneVisible());
        assertFalse(last().doneEnabled());
        m.onDone();
        io.runAll();
        assertEquals(0, onboarding.completes);
        assertTrue(commands.isEmpty());
    }

    @Test
    public void completionFailureStaysAndAllowsRetry() {
        probe.sdk = 36;
        onboarding.failure = new IllegalStateException("store");
        ReadinessModel m = resumed(ReadinessMode.ONBOARDING);
        m.onDone();
        io.runAll();
        assertTrue(commands.isEmpty());
        assertTrue(last().doneEnabled());
        onboarding.failure = null;
        m.onDone();
        io.runAll();
        assertEquals(2, onboarding.completes);
        assertEquals(Command.Kind.FINISH_ONBOARDING, commands.get(0).kind());
    }

    @Test
    public void hiddenItemsAreNeverListedOrCounted() {
        List<ReadinessItem> items = List.of(
                new ReadinessItem(ReadinessItemId.NOTIFICATIONS, false, true, ReadinessStatus.NEEDS_ATTENTION),
                new ReadinessItem(ReadinessItemId.MANUFACTURER, true, false, ReadinessStatus.RECOMMENDED));
        ReadinessModel m = model(ReadinessMode.ONBOARDING, fixed(items));
        m.onResume(false);
        io.runAll();
        assertEquals(List.of(ReadinessItemId.MANUFACTURER), ids(last()));
        assertEquals(0, last().missingRequired());
        assertNull(item(last(), ReadinessItemId.NOTIFICATIONS));
    }

    private static ReadinessChecker fixed(List<ReadinessItem> items) {
        return new ReadinessChecker() {
            @Override
            public List<ReadinessItem> check() {
                return items;
            }

            @Override
            public int missingRequiredCount() {
                return 0;
            }
        };
    }
}
