package app.lightscout.feature.onboarding.guide;

import app.lightscout.feature.onboarding.StateStore;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * S-08 screen logic, free of Android types so that it is JVM-tested (CP-14 §7.5, §9 TST-06 S-08).
 *
 * <ul>
 *   <li>The detected manufacturer (argument) is preselected; A-S08-1 selects another option. The selection lives in
 *       saved state, so it survives rotation, background and process death.</li>
 *   <li>Steps come from the bundled guide ({@link GuideContent}). A step's „Open …” button is shown only when its
 *       IF-56 page resolves on this phone (A-S08-2, VR-05 fallback).</li>
 *   <li>A page that resolves but does not open → the {@code s08_error_open_setting} Snackbar
 *       ({@link Command.Kind#SHOW_OPEN_FAILED}).</li>
 *   <li>A-S08-3 opens dontkillmyapp.com for the selected option (IF-57).</li>
 * </ul>
 * Main thread only.
 */
public final class GuideModel {

    static final String KEY_MANUFACTURER = "s08_manufacturer";

    /** Receives state changes and commands on the main thread. */
    public interface Listener {

        /**
         * The visible state changed.
         *
         * @param state the new state
         */
        void onState(GuideUiState state);

        /**
         * The fragment must open a page or show the failure Snackbar.
         *
         * @param command the command
         */
        void onCommand(Command command);
    }

    /**
     * An action for the fragment.
     *
     * @param kind         what to do
     * @param setting      the IF-56 page ({@link Kind#OPEN_SETTING}); otherwise {@code null}
     * @param manufacturer the option for the IF-57 link ({@link Kind#OPEN_MORE_DETAILS}); otherwise {@code null}
     */
    public record Command(Kind kind, OemSettingId setting, Manufacturer manufacturer) {

        /** Command kinds. */
        public enum Kind {
            /** Start the IF-56 intent of {@link Command#setting()}. */
            OPEN_SETTING,
            /** Start the IF-57 browser intent for {@link Command#manufacturer()}. */
            OPEN_MORE_DETAILS,
            /** Show the {@code s08_error_open_setting} Snackbar. */
            SHOW_OPEN_FAILED
        }
    }

    private final StateStore store;
    private final GuideContent content;
    private final Predicate<OemSettingId> resolvable;
    private Manufacturer selected;
    private GuideUiState state;
    private Listener listener;

    /**
     * Creates the model.
     *
     * @param detected   the {@code manufacturer} argument (detected by CMP-28)
     * @param store      saved state (selected option only)
     * @param content    bundled guides
     * @param resolvable IF-56 resolve check ({@code SystemSettingsIntents.oem(id).isPresent()})
     */
    public GuideModel(Manufacturer detected, StateStore store, GuideContent content,
            Predicate<OemSettingId> resolvable) {
        this.store = Objects.requireNonNull(store, "store");
        this.content = Objects.requireNonNull(content, "content");
        this.resolvable = Objects.requireNonNull(resolvable, "resolvable");
        Manufacturer restored = parse(store.getString(KEY_MANUFACTURER));
        if (restored != null) {
            this.selected = restored;
        } else {
            this.selected = detected != null ? detected : Manufacturer.OTHER;
        }
        this.state = build(selected);
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
     * Returns the current state.
     *
     * @return the state
     */
    public GuideUiState state() {
        return state;
    }

    /**
     * A-S08-1: an option was selected in C-S08-03.
     *
     * @param manufacturer the option
     */
    public void select(Manufacturer manufacturer) {
        Objects.requireNonNull(manufacturer, "manufacturer");
        store.putString(KEY_MANUFACTURER, manufacturer.name());
        if (manufacturer == selected) {
            return;
        }
        selected = manufacturer;
        state = build(manufacturer);
        publish();
    }

    /** Re-evaluates the „Open …” buttons (the screen resumed; an OEM app may have been installed or removed). */
    public void refresh() {
        GuideUiState next = build(selected);
        if (!next.equals(state)) {
            state = next;
            publish();
        }
    }

    /**
     * A-S08-2: the „Open …” button of a step was tapped.
     *
     * @param number the 1-based step number
     */
    public void onOpenStep(int number) {
        for (GuideUiState.Step s : state.steps()) {
            if (s.number() == number && s.open() != null) {
                send(new Command(Command.Kind.OPEN_SETTING, s.open(), null));
                return;
            }
        }
    }

    /** The IF-56 page did not open (start failed or no longer resolvable) → Snackbar. */
    public void onOpenFailed() {
        send(new Command(Command.Kind.SHOW_OPEN_FAILED, null, null));
    }

    /** A-S08-3: „More details on dontkillmyapp.com”. */
    public void onMoreDetails() {
        send(new Command(Command.Kind.OPEN_MORE_DETAILS, null, selected));
    }

    private GuideUiState build(Manufacturer m) {
        List<GuideStep> steps = content.steps(m);
        List<GuideUiState.Step> out = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            GuideStep s = steps.get(i);
            OemSettingId open = s.setting() != null && resolves(s.setting()) ? s.setting() : null;
            out.add(new GuideUiState.Step(i + 1, steps.size(), s.text(), open));
        }
        return new GuideUiState(m, out);
    }

    private boolean resolves(OemSettingId id) {
        try {
            return resolvable.test(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Manufacturer parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Manufacturer.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void send(Command c) {
        if (listener != null) {
            listener.onCommand(c);
        }
    }

    private void publish() {
        if (listener != null) {
            listener.onState(state);
        }
    }
}
