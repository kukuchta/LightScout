package app.lightscout.feature.home;

import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.Navigator;
import app.lightscout.ui.core.ReadinessMode;

import java.util.Objects;

/**
 * Maps {@link HomeCommand}s to IF-29 {@link Navigator} methods (CP-12 §7.4, §7.5; CP-00A R-12). The navigator is
 * implemented in {@code :app} (CP-15); S-01 never references another feature (R-4).
 */
public final class HomeNavigation {

    private HomeNavigation() {
    }

    /**
     * Executes a command on the navigator. Main thread only.
     *
     * @param command   the command
     * @param navigator IF-29 of the hosting activity
     */
    public static void execute(HomeCommand command, Navigator navigator) {
        execute(command, navigator, null);
    }

    /** Executes an optional chart edit command with its selected entry identifier. */
    public static void execute(HomeCommand command, Navigator navigator, String entryIdentifier) {
        Objects.requireNonNull(navigator, "navigator");
        switch (Objects.requireNonNull(command, "command")) {
            case ADD_CARBS:
                navigator.openNewEntry(app.lightscout.domain.model.EntryType.CARBS);
                break;
            case ADD_BOLUS:
                navigator.openNewEntry(app.lightscout.domain.model.EntryType.BOLUS);
                break;
            case ADD_BG:
                navigator.openNewEntry(app.lightscout.domain.model.EntryType.BG_CHECK);
                break;
            case ADD_SITE:
                navigator.openNewEntry(app.lightscout.domain.model.EntryType.SITE_CHANGE);
                break;
            case ADD_SENSOR:
                navigator.openNewEntry(app.lightscout.domain.model.EntryType.SENSOR_CHANGE);
                break;
            case EDIT_ENTRY:
                navigator.openEntry(entryIdentifier);
                break;
            case OPEN_STATISTICS:
                navigator.openStatistics();
                break;
            case OPEN_SETTINGS:
                navigator.openSettings();
                break;
            case OPEN_READINESS_CHECK:
                navigator.openReadiness(ReadinessMode.CHECK);
                break;
            case OPEN_CONNECTION_EDIT:
            default:
                navigator.openConnection(ConnectionMode.EDIT);
                break;
        }
    }
}
