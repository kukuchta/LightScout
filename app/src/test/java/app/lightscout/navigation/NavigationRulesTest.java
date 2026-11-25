package app.lightscout.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.StartDestination;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * IF-29 edges (CP-15 §4.1 / §9 „Navigator”) and the start destination with its back stack (CP-15 §7.1, R-72) — JVM
 * part; the on-device part is {@code NavigationDeviceTest}.
 */
public class NavigationRulesTest {

    private final List<NavRequest> navigated = new ArrayList<>();
    private final List<UiMessage> results = new ArrayList<>();
    private final List<String> debug = new ArrayList<>();

    private final NavPort port = new NavPort() {
        @Override
        public void navigate(NavRequest request) {
            navigated.add(request);
        }

        @Override
        public void finishWithResult(UiMessage message) {
            results.add(message);
        }
    };

    private final Logger logger = new Logger() {
        @Override
        public void event(EventCode code) {
            throw new AssertionError("navigation logs no event codes");
        }

        @Override
        public void event(EventCode code, String detail) {
            throw new AssertionError("navigation logs no event codes");
        }

        @Override
        public void crash(String exceptionClass, String topAppFrame) {
            throw new AssertionError();
        }

        @Override
        public void debug(String tag, String message) {
            debug.add(message);
        }
    };

    private final AppNavigator navigator = new AppNavigator(port, logger);

    @Test
    public void s01ToS06Edit_andS04ToS06Edit_openConnectionWithEditMode() {
        navigator.openConnection(ConnectionMode.EDIT);
        assertEquals(List.of(new NavRequest(NavTarget.CONNECTION, ConnectionMode.EDIT, null, null, false)), navigated);
    }

    @Test
    public void s01ToS07Check_andS04ToS07Check_openReadinessWithCheckMode() {
        navigator.openReadiness(ReadinessMode.CHECK);
        assertEquals(List.of(new NavRequest(NavTarget.READINESS, null, ReadinessMode.CHECK, null, false)), navigated);
    }

    @Test
    public void s07Done_opensHomeWithTheOnboardingGraphPoppedInclusively() {
        navigator.finishOnboarding();
        assertEquals(List.of(new NavRequest(NavTarget.HOME, null, null, NavTarget.ONBOARDING, true)), navigated);
    }

    @Test
    public void finishWithResult_handsTheMessageToThePort() {
        navigator.finishWithResult(UiMessage.CONNECTION_SAVED);
        assertEquals(List.of(UiMessage.CONNECTION_SAVED), results);
        assertTrue(navigated.isEmpty());
    }

    @Test
    public void s01ToS02_opensTheEntryGraphForCreateAndEdit() {
        navigator.openNewEntry(app.lightscout.domain.model.EntryType.CARBS);
        navigator.openEntry("abc");
        assertEquals(List.of(NavRequest.newEntry(app.lightscout.domain.model.EntryType.CARBS),
                NavRequest.entry("abc")), navigated);
        assertEquals(new NavRequest(NavTarget.ENTRY, null, null, null, false, "abc"), navigated.get(1));
        assertTrue(debug.isEmpty());
    }

    @Test
    public void s03AndS04NavigateToTheirFeatureGraphs() {
        navigator.openStatistics();
        navigator.openSettings();
        assertEquals(List.of(new NavRequest(NavTarget.STATISTICS, null, null, null, false),
                new NavRequest(NavTarget.SETTINGS, null, null, null, false)), navigated);
        assertTrue(results.isEmpty());
        assertTrue(debug.isEmpty());
    }

    @Test
    public void nullArguments_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> navigator.openEntry(null));
        assertThrows(IllegalArgumentException.class, () -> navigator.openEntry(""));
        assertThrows(IllegalArgumentException.class, () -> navigator.openNewEntry(null));
        assertThrows(IllegalArgumentException.class,
                () -> navigator.openNewEntry(app.lightscout.domain.model.EntryType.PUMP_ALERT));
        assertThrows(NullPointerException.class, () -> navigator.openConnection(null));
        assertThrows(NullPointerException.class, () -> navigator.openReadiness(null));
        assertThrows(NullPointerException.class, () -> navigator.finishWithResult(null));
    }

    @Test
    public void fiveCreateRoutesRemainDistinctFromEditAndUseInternalLinks() {
        for (app.lightscout.domain.model.EntryType type : new app.lightscout.domain.model.EntryType[] {
                app.lightscout.domain.model.EntryType.CARBS, app.lightscout.domain.model.EntryType.BOLUS,
                app.lightscout.domain.model.EntryType.BG_CHECK, app.lightscout.domain.model.EntryType.SITE_CHANGE,
                app.lightscout.domain.model.EntryType.SENSOR_CHANGE}) {
            navigator.openNewEntry(type);
            NavRequest route = navigated.get(navigated.size() - 1);
            assertEquals(type, route.initialType());
            assertEquals(null, route.entryId());
            assertTrue(NavLinks.isDeepLink(route));
            assertEquals("lightscout-internal://entry/CREATE?initialType=" + type.name(), NavLinks.uri(route));
        }
        navigator.openEntry("id/#? a");
        NavRequest edit = navigated.get(navigated.size() - 1);
        assertEquals(null, edit.initialType());
        assertEquals("lightscout-internal://entry/EDIT?entryId=id%2F%23%3F%20a", NavLinks.uri(edit));
        assertThrows(IllegalArgumentException.class, () -> new NavRequest(NavTarget.ENTRY, null, null,
                null, false, "id", app.lightscout.domain.model.EntryType.CARBS));
    }

    @Test
    public void navRequest_modeMustMatchTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new NavRequest(NavTarget.CONNECTION, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NavRequest(NavTarget.HOME, ConnectionMode.EDIT, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NavRequest(NavTarget.READINESS, null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new NavRequest(NavTarget.HOME, null, null, null, false, "abc"));
    }

    // ------------------------------------------------------------------ start destination (R-72, Q-94)

    @Test
    public void start_freshInstall_onboardingGraphOnly() {
        assertEquals(NavTarget.ONBOARDING, StartRoute.startGraph(StartDestination.DISCLAIMER));
        assertEquals(List.of(), StartRoute.plan(StartDestination.DISCLAIMER));
    }

    @Test
    public void start_acceptedWithoutConnection_s05BelowS06Onboarding() {
        assertEquals(NavTarget.ONBOARDING, StartRoute.startGraph(StartDestination.CONNECTION));
        assertEquals(List.of(NavRequest.connection(ConnectionMode.ONBOARDING)),
                StartRoute.plan(StartDestination.CONNECTION));
    }

    @Test
    public void start_storedConnectionNotCompleted_s05S06BelowS07Onboarding() {
        assertEquals(NavTarget.ONBOARDING, StartRoute.startGraph(StartDestination.READINESS));
        assertEquals(List.of(NavRequest.connection(ConnectionMode.ONBOARDING),
                        NavRequest.readiness(ReadinessMode.ONBOARDING)),
                StartRoute.plan(StartDestination.READINESS));
    }

    @Test
    public void start_completed_homeGraphIsTheRootStart_noStep() {
        assertEquals(NavTarget.HOME, StartRoute.startGraph(StartDestination.HOME));
        assertEquals(List.of(), StartRoute.plan(StartDestination.HOME));
    }

    @Test
    public void start_everyStartIsAGraph_andNoStepPopsIt() {
        for (StartDestination d : StartDestination.values()) {
            NavTarget start = StartRoute.startGraph(d);
            assertTrue(d.name(), start == NavTarget.HOME || start == NavTarget.ONBOARDING);
            for (NavRequest step : StartRoute.plan(d)) {
                assertEquals(d.name(), null, step.popUpTo());
                assertEquals(d.name(), null, StartRoute.rootStartAfter(step));
            }
        }
    }

    @Test
    public void start_restoredBackStack_neverResolvesIf27() {
        assertFalse(StartRoute.needsResolve(NavTarget.HOME));
        assertFalse(StartRoute.needsResolve(NavTarget.ONBOARDING));
        assertTrue(StartRoute.needsResolve(null));
    }

    @Test
    public void start_savedRootStart_roundTrips_andAnythingElseResolvesAgain() {
        assertEquals(NavTarget.HOME, StartRoute.restoredStart(StartRoute.saveStart(NavTarget.HOME)));
        assertEquals(NavTarget.ONBOARDING, StartRoute.restoredStart(StartRoute.saveStart(NavTarget.ONBOARDING)));
        assertEquals(null, StartRoute.restoredStart(null));
        assertEquals(null, StartRoute.restoredStart("CONNECTION"));
        assertEquals(null, StartRoute.restoredStart("home"));
        assertThrows(IllegalArgumentException.class, () -> StartRoute.saveStart(NavTarget.CONNECTION));
        assertThrows(IllegalArgumentException.class, () -> StartRoute.saveStart(NavTarget.READINESS));
    }

    @Test
    public void s07Done_movesTheRootStartToHome_otherStepsKeepIt() {
        assertEquals(NavTarget.HOME, StartRoute.rootStartAfter(NavRequest.replacing(NavTarget.HOME,
                NavTarget.ONBOARDING)));
        assertEquals(null, StartRoute.rootStartAfter(NavRequest.connection(ConnectionMode.EDIT)));
        assertEquals(null, StartRoute.rootStartAfter(NavRequest.readiness(ReadinessMode.CHECK)));
        assertEquals(null, StartRoute.rootStartAfter(new NavRequest(NavTarget.HOME, null, null, NavTarget.ONBOARDING,
                false)));
    }
}
