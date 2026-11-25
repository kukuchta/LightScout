package app.lightscout.feature.onboarding.readiness;

import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Action;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Item;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Status;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.ManufacturerDetector;
import app.lightscout.platform.NotificationChannels;
import app.lightscout.platform.ReadinessChecker;
import app.lightscout.platform.ReadinessItem;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.platform.ReadinessStatus;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.usecases.OnboardingState;
import app.lightscout.usecases.StatusRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * S-07 screen logic, free of Android types so that it is JVM-tested (CP-14 §7.4, §9 TST-06 S-07).
 *
 * <ul>
 *   <li>Items come from CMP-27 {@link ReadinessChecker#check()} (all seven, hidden ones {@code shown = false});
 *       only shown items are listed, in display order 1–5, 7, 6 (§7.4.1). The missing count is
 *       shown ∧ required ∧ not done; item 6 is never counted.</li>
 *   <li>Every resume re-checks (WF-08). Before the check, the resume calls CMP-26 {@code ensureChannels()} and the
 *       F7 re-creation {@code ensureAlarmChannelBypass()} — the latter <b>only while no alarm rings</b>
 *       ({@code StatusRepository.current().ringing() == null}, Q-52). Both run on {@code ioExecutor}.</li>
 *   <li>„Permanently denied” exists only for item 1 on the runtime-permission band (API 33+): denied ∧ no
 *       rationale ∧ requested at least once in this screen session (memory) — CP-00A R-74.</li>
 *   <li>A settings page that cannot be opened shows the inline error (M3-30) in that item until a later check
 *       reports the item done.</li>
 *   <li>„Done” (onboarding only): all required done → IF-27 {@code completeOnboarding()} (blocking, on
 *       {@code ioExecutor}), then {@link Command.Kind#FINISH_ONBOARDING}; otherwise C-S07-07 first (DS:D-060).
 *       Repeated taps while it runs are ignored (GIR-1).</li>
 * </ul>
 * Main thread only, except the work posted to {@code io}.
 */
public final class ReadinessModel {

    /** Display order 1–5, 7, 6 (§7.4.1; „Manufacturer settings” always last). */
    static final List<ReadinessItemId> DISPLAY_ORDER = List.of(ReadinessItemId.NOTIFICATIONS,
            ReadinessItemId.DND_ACCESS, ReadinessItemId.FULL_SCREEN, ReadinessItemId.BATTERY,
            ReadinessItemId.EXACT_ALARM, ReadinessItemId.ALARM_CHANNEL, ReadinessItemId.MANUFACTURER);

    /** Receives state changes and commands on the main thread. */
    public interface Listener {

        /**
         * The visible state changed.
         *
         * @param state the new state
         */
        void onState(ReadinessUiState state);

        /**
         * The fragment must perform an action (open a page, ask for a permission, navigate).
         *
         * @param command the command
         */
        void onCommand(Command command);
    }

    /**
     * An action for the fragment.
     *
     * @param kind         what to do
     * @param item         the item whose page to open ({@link Kind#OPEN_ITEM_SETTINGS},
     *                     {@link Kind#OPEN_APP_NOTIFICATION_SETTINGS}); otherwise {@code null}
     * @param manufacturer the detected manufacturer ({@link Kind#OPEN_GUIDE}); otherwise {@code null}
     */
    public record Command(Kind kind, ReadinessItemId item, Manufacturer manufacturer) {

        /** Command kinds. */
        public enum Kind {
            /** Runtime {@code POST_NOTIFICATIONS} request (item 1, API 33+). */
            REQUEST_NOTIFICATION_PERMISSION,
            /** IF-55 page of {@link Command#item()} ({@code SystemSettingsIntents.forItem}). */
            OPEN_ITEM_SETTINGS,
            /** App notification settings (item 1 permanently denied — A-S07-2, R-74). */
            OPEN_APP_NOTIFICATION_SETTINGS,
            /** S-08 with the detected manufacturer (A-S07-3). */
            OPEN_GUIDE,
            /** Onboarding completed: S-01 with the onboarding stack cleared (IF-29 {@code finishOnboarding}). */
            FINISH_ONBOARDING
        }
    }

    private final ReadinessMode mode;
    private final ReadinessChecker checker;
    private final NotificationChannels channels;
    private final StatusRepository status;
    private final OnboardingState onboarding;
    private final ManufacturerDetector detector;
    private final boolean runtimeNotificationPermission;
    private final Executor io;
    private final Executor main;

    private final Set<ReadinessItemId> errors = EnumSet.noneOf(ReadinessItemId.class);
    private List<ReadinessItem> lastItems = List.of();
    private Listener listener;
    private boolean notificationRequested;
    private boolean notificationRationale;
    private boolean missingDialogVisible;
    private boolean completing;
    private int checkSequence;
    private int appliedSequence;

    /**
     * Creates the model.
     *
     * @param mode                          onboarding or check
     * @param checker                       CMP-27
     * @param channels                      CMP-26 (resume-time channel checks, F7)
     * @param status                        IF-21 ({@code ringing} for the F7 guard — Q-52)
     * @param onboarding                    IF-27 ({@code completeOnboarding} on „Done”)
     * @param detector                      CMP-28 manufacturer detection for item 6 → S-08
     * @param runtimeNotificationPermission {@code true} on API 33+ (item 1 uses the runtime request)
     * @param io                            {@code ioExecutor}
     * @param main                          main-thread executor for results
     */
    public ReadinessModel(ReadinessMode mode, ReadinessChecker checker, NotificationChannels channels,
            StatusRepository status, OnboardingState onboarding, ManufacturerDetector detector,
            boolean runtimeNotificationPermission, Executor io, Executor main) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.checker = Objects.requireNonNull(checker, "checker");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.status = Objects.requireNonNull(status, "status");
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.runtimeNotificationPermission = runtimeNotificationPermission;
        this.io = Objects.requireNonNull(io, "io");
        this.main = Objects.requireNonNull(main, "main");
    }

    /**
     * Sets the listener and delivers the current state to it.
     *
     * @param l the listener, or {@code null} to detach
     */
    public void setListener(Listener l) {
        listener = l;
        publish();
    }

    /**
     * The screen resumed (first show, or return from a system page): F7 channel check (guarded) and a re-check.
     *
     * @param notificationRationale current {@code shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)}
     *                              ({@code false} below API 33)
     */
    public void onResume(boolean notificationRationale) {
        this.notificationRationale = notificationRationale;
        recheck(true);
    }

    /**
     * Result of the runtime {@code POST_NOTIFICATIONS} request (A-S07-1, item 1 on API 33+).
     *
     * @param notificationRationale {@code shouldShowRequestPermissionRationale} after the answer
     */
    public void onNotificationPermissionResult(boolean notificationRationale) {
        notificationRequested = true;
        this.notificationRationale = notificationRationale;
        recheck(false);
    }

    /**
     * An item's action button was tapped.
     *
     * @param id the item
     */
    public void onItemAction(ReadinessItemId id) {
        Item item = find(id);
        if (item == null || item.action() == Action.NONE) {
            return;
        }
        Command command;
        if (item.action() == Action.OPEN_GUIDE) {
            command = new Command(Command.Kind.OPEN_GUIDE, null, detector.detect());
        } else if (id == ReadinessItemId.NOTIFICATIONS && item.status() == Status.PERMANENTLY_DENIED) {
            command = new Command(Command.Kind.OPEN_APP_NOTIFICATION_SETTINGS, id, null);
        } else if (id == ReadinessItemId.NOTIFICATIONS && runtimeNotificationPermission) {
            command = new Command(Command.Kind.REQUEST_NOTIFICATION_PERMISSION, id, null);
        } else {
            command = new Command(Command.Kind.OPEN_ITEM_SETTINGS, id, null);
        }
        if (listener != null) {
            listener.onCommand(command);
        }
    }

    /**
     * The page of an item could not be opened, the {@code appDetails()} fallback included (M3-30 inline error).
     *
     * @param id the item
     */
    public void onLaunchFailed(ReadinessItemId id) {
        errors.add(id);
        publish();
    }

    /** A-S07-4: „Done” tapped (onboarding mode only). */
    public void onDone() {
        if (mode != ReadinessMode.ONBOARDING || completing || missingDialogVisible || lastItems.isEmpty()) {
            return;
        }
        if (missingRequired() > 0) {
            missingDialogVisible = true;
            publish();
            return;
        }
        complete();
    }

    /** C-S07-07 „Continue”: complete onboarding although required items are missing (DS:D-060). */
    public void onMissingDialogContinue() {
        if (!missingDialogVisible) {
            return;
        }
        missingDialogVisible = false;
        complete();
    }

    /** C-S07-07 „Go back” (or the dialog was cancelled): stay on S-07. */
    public void onMissingDialogGoBack() {
        if (!missingDialogVisible) {
            return;
        }
        missingDialogVisible = false;
        publish();
    }

    /**
     * Returns the current state.
     *
     * @return the state
     */
    public ReadinessUiState state() {
        List<Item> items = new ArrayList<>();
        for (ReadinessItem r : lastItems) {
            if (r.shown()) {
                items.add(toUi(r));
            }
        }
        int missing = missingRequired();
        boolean onboardingMode = mode == ReadinessMode.ONBOARDING;
        return new ReadinessUiState(items, missing, !lastItems.isEmpty() && missing == 0, onboardingMode,
                onboardingMode && !completing && !lastItems.isEmpty(), missingDialogVisible);
    }

    private void recheck(boolean resume) {
        final int sequence = ++checkSequence;
        io.execute(() -> {
            if (resume) {
                ensureChannelsGuarded();
            }
            List<ReadinessItem> checked;
            try {
                checked = checker.check();
            } catch (RuntimeException e) {
                // Local system queries only; on an unexpected failure the previous statuses stay visible.
                checked = null;
            }
            final List<ReadinessItem> result = checked;
            main.execute(() -> onChecked(sequence, result));
        });
    }

    private void ensureChannelsGuarded() {
        try {
            channels.ensureChannels();
            // Q-52 (F7 safety): never delete / re-create CH-01 while an alarm rings.
            if (status.current().ringing() == null) {
                channels.ensureAlarmChannelBypass();
            }
        } catch (RuntimeException e) {
            // The channel stays as it is; item 7 then reports its real state (VR-27 fallback).
        }
    }

    private void onChecked(int sequence, List<ReadinessItem> result) {
        if (sequence < appliedSequence || result == null) {
            return;
        }
        appliedSequence = sequence;
        List<ReadinessItem> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparingInt(r -> DISPLAY_ORDER.indexOf(r.id())));
        lastItems = List.copyOf(sorted);
        for (ReadinessItem r : lastItems) {
            if (r.status() == ReadinessStatus.DONE) {
                errors.remove(r.id());
            }
        }
        publish();
    }

    private void complete() {
        completing = true;
        publish();
        io.execute(() -> {
            boolean stored;
            try {
                onboarding.completeOnboarding();
                stored = true;
            } catch (RuntimeException e) {
                // Store failure (StorageException, R-20): stay on S-07; „Done” can be tapped again.
                stored = false;
            }
            final boolean ok = stored;
            main.execute(() -> onCompleted(ok));
        });
    }

    private void onCompleted(boolean ok) {
        completing = false;
        publish();
        if (ok && listener != null) {
            listener.onCommand(new Command(Command.Kind.FINISH_ONBOARDING, null, null));
        }
    }

    private int missingRequired() {
        int n = 0;
        for (ReadinessItem r : lastItems) {
            if (r.missingRequired()) {
                n++;
            }
        }
        return n;
    }

    private Item toUi(ReadinessItem r) {
        Status s;
        Action a;
        if (r.status() == ReadinessStatus.RECOMMENDED || r.id() == ReadinessItemId.MANUFACTURER) {
            s = Status.RECOMMENDED;
            a = Action.OPEN_GUIDE;
        } else if (r.status() == ReadinessStatus.DONE) {
            s = Status.DONE;
            a = Action.NONE;
        } else if (r.id() == ReadinessItemId.NOTIFICATIONS && runtimeNotificationPermission
                && notificationRequested && !notificationRationale) {
            s = Status.PERMANENTLY_DENIED;
            a = Action.OPEN_SETTINGS;
        } else if (r.id() == ReadinessItemId.ALARM_CHANNEL) {
            s = Status.NEEDS_ATTENTION;
            a = Action.OPEN_SETTINGS;
        } else {
            s = Status.NEEDS_ATTENTION;
            a = Action.ALLOW;
        }
        return new Item(r.id(), s, a, s != Status.DONE && errors.contains(r.id()));
    }

    private Item find(ReadinessItemId id) {
        for (Item i : state().items()) {
            if (i.id() == id) {
                return i;
            }
        }
        return null;
    }

    private void publish() {
        if (listener != null) {
            listener.onState(state());
        }
    }
}
