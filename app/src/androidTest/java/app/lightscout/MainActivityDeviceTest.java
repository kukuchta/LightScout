package app.lightscout;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.StrictMode;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;

import androidx.lifecycle.Lifecycle;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.Navigation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.alarm.AlarmActivity;
import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.model.EntryType;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.ui.core.NavResults;
import app.lightscout.ui.core.NavigatorHost;
import app.lightscout.ui.core.ReadinessMode;
import app.lightscout.ui.core.UiMessage;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * CP-15 §9 device tests of {@code MainActivity} with the real composition root over in-memory stores (TST-06 start
 * destination, onboarding back stack, process death in onboarding, Navigator edges; TST-07 ringing on resume; SR-21
 * exported input; StrictMode). Compiled in SS-14; run on RD-2 (API 33) over USB in the batched M1 run (D-050).
 *
 * <p>BUG-08 / Q-95: {@code ActivityScenario} recognises its activity only while {@code getIntent()} matches the launch
 * intent (action, data, type, component, categories). {@code MainActivity}'s SR-21 clean intent keeps MAIN / LAUNCHER,
 * so plain launches use {@code ActivityScenario}; tests whose launch or new intent carries data, extras or another
 * action (SR-21 attack input) launch with {@code Context.startActivity} and observe through {@link MonitoredLaunch}.
 */
@RunWith(AndroidJUnit4.class)
public class MainActivityDeviceTest {

    private static final long TIMEOUT_MS = 5_000;
    private static final int HOME = app.lightscout.feature.home.R.id.homeFragment;
    private static final int DISCLAIMER = app.lightscout.feature.onboarding.R.id.disclaimerFragment;
    private static final int CONNECTION = app.lightscout.feature.onboarding.R.id.connectionFragment;
    private static final int READINESS = app.lightscout.feature.onboarding.R.id.readinessFragment;
    private static final int GUIDE = app.lightscout.feature.onboarding.R.id.manufacturerGuideFragment;
    private static final int ONBOARDING_GRAPH = app.lightscout.feature.onboarding.R.id.onboarding_graph;
    private static final int HOME_GRAPH = app.lightscout.feature.home.R.id.home_graph;
    private static final int ENTRY_FORM = app.lightscout.feature.entry.R.id.entryFormFragment;
    private static final int ENTRY_GRAPH = app.lightscout.feature.entry.R.id.entry_graph;
    private static final int STATISTICS = app.lightscout.feature.stats.R.id.statisticsFragment;
    private static final int SETTINGS = app.lightscout.feature.settings.R.id.settingsFragment;
    private static final String INTERNAL_S06_EDIT = "lightscout-internal://connection/EDIT";
    /** NavController's deep-link extras (androidx.navigation internals; SR-21 test input). */
    private static final String NAV_DEEP_LINK_IDS = "android-support-nav:controller:deepLinkIds";
    private static final String NAV_DEEP_LINK_ARGS = "android-support-nav:controller:deepLinkArgs";
    private static final String NAV_DEEP_LINK_EXTRAS = "android-support-nav:controller:deepLinkExtras";

    private DeviceComponents components;

    @Before
    public void setUp() {
        components = TestLightScoutApplication.components;
        assertNotNull("LightScoutTestRunner starts TestLightScoutApplication", components);
        components.reset();
    }

    // ------------------------------------------------------------------ start destination (TST-06)

    @Test
    public void start_freshInstall_opensS05() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, DISCLAIMER);
        }
    }

    @Test
    public void start_configured_opensS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            assertFalse("onboarding not in the back stack", inBackStack(s, ONBOARDING_GRAPH));
        }
    }

    @Test
    public void start_configuredWithTokenRejected_opensS01_noRedirectToS06() {
        components.configuredWithTokenRejected();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            SystemClock.sleep(500);
            assertEquals(HOME, currentId(s));
        }
    }

    @Test
    public void start_acceptedWithoutConnection_s06OnTopOfS05() {
        components.flags.disclaimerAccepted = true;
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, CONNECTION);
            assertTrue(inBackStack(s, DISCLAIMER));
            pressBackUnconditionally();
            awaitDestination(s, DISCLAIMER);
        }
    }

    /**
     * BUG-14 (VS-01): S-06 with the production gateway wiring under the debug StrictMode. Opening S-06 obtains IF-24
     * on the main thread; building an {@code OkHttpClient} there (SSLContext init) is a fatal slow-call violation.
     * No stored connection and no check are started, so nothing is configured and no request is sent.
     */
    @Test
    public void s06WithTheRealGateway_opensAndStays_noGatewayEverCreatedOnTheMainThread() throws Exception {
        assertNull("no stored connection: nothing is configured, no request can be sent", components.connection.url);
        assertNull(components.connection.token);
        components.realGateway = true;
        try {
            components.flags.disclaimerAccepted = true;
            try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
                awaitDestination(s, CONNECTION);
                SystemClock.sleep(2_000);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                assertEquals(Lifecycle.State.RESUMED, s.getState());
                assertEquals(CONNECTION, currentId(s));
            }
            // build the probe gateway off main without any request (if no earlier test created it already)
            AppContainer container = ((LightScoutApplication) ApplicationProvider.getApplicationContext()).container();
            FutureTask<Object> build =
                    new FutureTask<>(() -> ((DeferredGateway) container.probeGateway()).delegate());
            container.ioExecutor().execute(build);
            assertNotNull(build.get(10, TimeUnit.SECONDS));
            List<String> threads = components.gatewayThreads;
            assertFalse("a gateway was created", threads.isEmpty());
            assertTrue("last gateway created on io: " + threads, threads.get(threads.size() - 1)
                    .startsWith("lightscout-io-"));
            // whole process so far, i.e. also every earlier test that opened S-06 / S-01 / S-07
            assertFalse("gateway created on the main thread: " + threads, threads.contains("main"));
        } finally {
            components.realGateway = false;
        }
    }

    @Test
    public void start_storedConnectionNotCompleted_s07OnTopOfS06AndS05() {
        components.flags.disclaimerAccepted = true;
        components.connection.url = "https://ns.example.org";
        components.connection.token = "test-token";
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, READINESS);
            assertTrue(inBackStack(s, CONNECTION));
            assertTrue(inBackStack(s, DISCLAIMER));
        }
    }

    // ------------------------------------------------------------------ back stack (TST-06)

    @Test
    public void backOnS05_exitsTheApp() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, DISCLAIMER);
            pressBackUnconditionally();
            awaitState(s, Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void s07Done_opensS01WithTheOnboardingStackCleared_backExits() {
        components.flags.disclaimerAccepted = true;
        components.connection.url = "https://ns.example.org";
        components.connection.token = "test-token";
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, READINESS);
            s.onActivity(a -> a.navigator().finishOnboarding());
            awaitDestination(s, HOME);
            assertFalse(inBackStack(s, ONBOARDING_GRAPH));
            assertFalse(inBackStack(s, DISCLAIMER));
            assertEquals("the root start follows S-01 (BUG-05)", HOME_GRAPH, rootStart(s));
            AtomicBoolean navigatedUp = new AtomicBoolean(true);
            s.onActivity(a -> navigatedUp.set(nav(a).navigateUp()));
            assertFalse("Up on S-01 after onboarding never relaunches the task", navigatedUp.get());
            pressBackUnconditionally();
            awaitState(s, Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void upOnS01_neverLeavesTheApp() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            assertEquals("root start = home_graph (R-38 runtime start, BUG-05)", HOME_GRAPH, rootStart(s));
            AtomicBoolean upIcon = new AtomicBoolean(true);
            s.onActivity(a -> upIcon.set(((MaterialToolbar) a.findViewById(
                    app.lightscout.feature.home.R.id.s01_toolbar)).getNavigationIcon() != null));
            assertFalse("S-01 shows no Up icon", upIcon.get());
            AtomicBoolean navigatedUp = new AtomicBoolean(true);
            s.onActivity(a -> navigatedUp.set(nav(a).navigateUp()));
            assertFalse("navigateUp() on S-01 returns false (no generated-stack relaunch)", navigatedUp.get());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(Lifecycle.State.RESUMED, s.getState());
            assertEquals(HOME, currentId(s));
        }
    }

    @Test
    public void processDeathOnS01_restoresS01_rootStartKept_if27NotResolvedAgain() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, HOME);
            assertEquals(HOME_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
            AtomicBoolean navigatedUp = new AtomicBoolean(true);
            s.onActivity(a -> navigatedUp.set(nav(a).navigateUp()));
            assertFalse(navigatedUp.get());
        }
    }

    // ------------------------------------------------------------------ process death in onboarding (TST-06)

    @Test
    public void processDeathOnS06_restoresS06WithTheUrl_tokenEmpty_if27NotResolvedAgain() {
        components.flags.disclaimerAccepted = true;
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, CONNECTION);
            onView(withId(app.lightscout.feature.onboarding.R.id.s06_url))
                    .perform(replaceText("https://typed.example.org"));
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, CONNECTION);
            onView(withId(app.lightscout.feature.onboarding.R.id.s06_url))
                    .check(matches(withText("https://typed.example.org")));
            AtomicReference<String> token = new AtomicReference<>();
            s.onActivity(a -> token.set(((EditText) a.findViewById(
                    app.lightscout.feature.onboarding.R.id.s06_token)).getText().toString()));
            assertEquals("", token.get());
            assertEquals("IF-27 resolve() is not called after a restore", resolvesBefore,
                    components.flags.resolveReads.get());
        }
    }

    // BUG-12: on re-creation the root graph exists before the child fragments are restored (RootNavHostFragment), so
    // graph-scoped ViewModels (S-06: getBackStackEntry(onboarding_graph)) and SavedState survive on every destination.

    @Test
    public void recreateOnS05_restoresS05_rootStartOnboarding_if27NotResolvedAgain() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, DISCLAIMER);
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, DISCLAIMER);
            assertEquals(ONBOARDING_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
            pressBackUnconditionally();
            awaitState(s, Lifecycle.State.DESTROYED);
        }
    }

    @Test
    public void recreateOnS06Edit_restoresS06EditAboveS01_withTheUrl_tokenNeverPlain_if27NotResolvedAgain() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openConnection(ConnectionMode.EDIT));
            awaitDestination(s, CONNECTION);
            awaitS06Url(s, "https://ns.example.org"); // edit-mode prefill loaded first (baseline)
            onView(withId(app.lightscout.feature.onboarding.R.id.s06_url))
                    .perform(replaceText("https://edited.example.org"));
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, CONNECTION);
            onView(withId(app.lightscout.feature.onboarding.R.id.s06_url))
                    .check(matches(withText("https://edited.example.org")));
            AtomicReference<Object> mode = new AtomicReference<>();
            AtomicReference<String> token = new AtomicReference<>();
            s.onActivity(a -> {
                mode.set(app.lightscout.feature.onboarding.connection.ConnectionFragmentArgs
                        .fromBundle(nav(a).getCurrentBackStackEntry().getArguments()).getMode());
                token.set(((EditText) a.findViewById(
                        app.lightscout.feature.onboarding.R.id.s06_token)).getText().toString());
            });
            assertEquals(ConnectionMode.EDIT, mode.get());
            assertFalse("the stored token is never shown in plain text", token.get().contains("test-token"));
            assertEquals("S-01 stays below S-06", HOME, previousId(s));
            assertEquals(HOME_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
        }
    }

    @Test
    public void recreateOnS07Onboarding_restoresS07_thenS06IsRestoredFromTheBackStack() {
        components.flags.disclaimerAccepted = true;
        components.connection.url = "https://ns.example.org";
        components.connection.token = "test-token";
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, READINESS);
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, READINESS);
            assertTrue(inBackStack(s, CONNECTION));
            assertTrue(inBackStack(s, DISCLAIMER));
            assertEquals(ONBOARDING_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
            // S-06 stays CREATED on the back stack and is re-created (onCreate: graph-scoped ViewModel) during the
            // restore itself (crashed before BUG-12); Back only creates its view
            pressBackUnconditionally();
            awaitDestination(s, CONNECTION);
            awaitS06Url(s, "https://ns.example.org");
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }

    @Test
    public void recreateOnS07Check_restoresS07CheckAboveS01_backReturnsToS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openReadiness(ReadinessMode.CHECK));
            awaitDestination(s, READINESS);
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, READINESS);
            AtomicReference<Object> mode = new AtomicReference<>();
            s.onActivity(a -> mode.set(app.lightscout.feature.onboarding.readiness.ReadinessFragmentArgs
                    .fromBundle(nav(a).getCurrentBackStackEntry().getArguments()).getMode()));
            assertEquals(ReadinessMode.CHECK, mode.get());
            assertEquals("S-01 stays below S-07", HOME, previousId(s));
            assertEquals(HOME_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
            pressBackUnconditionally();
            awaitDestination(s, HOME);
            assertFalse(inBackStack(s, ONBOARDING_GRAPH));
        }
    }

    @Test
    public void recreateOnS08_restoresS08_backReturnsToS07() {
        components.flags.disclaimerAccepted = true;
        components.connection.url = "https://ns.example.org";
        components.connection.token = "test-token";
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, READINESS);
            s.onActivity(a -> nav(a).navigate(
                    app.lightscout.feature.onboarding.R.id.action_readinessFragment_to_manufacturerGuideFragment,
                    new app.lightscout.feature.onboarding.guide.ManufacturerGuideFragmentArgs.Builder(
                            app.lightscout.platform.Manufacturer.XIAOMI).build().toBundle()));
            awaitDestination(s, GUIDE);
            int resolvesBefore = components.flags.resolveReads.get();
            s.recreate();
            awaitDestination(s, GUIDE);
            assertEquals(ONBOARDING_GRAPH, rootStart(s));
            assertEquals(resolvesBefore, components.flags.resolveReads.get());
            pressBackUnconditionally();
            awaitDestination(s, READINESS);
            assertTrue(inBackStack(s, CONNECTION));
        }
    }

    // ------------------------------------------------------------------ Navigator edges (TST-06)

    @Test
    public void navigator_s01ToS06Edit_saveReturnsToS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openConnection(ConnectionMode.EDIT));
            awaitDestination(s, CONNECTION);
            AtomicReference<Object> mode = new AtomicReference<>();
            s.onActivity(a -> mode.set(app.lightscout.feature.onboarding.connection.ConnectionFragmentArgs
                    .fromBundle(nav(a).getCurrentBackStackEntry().getArguments()).getMode()));
            assertEquals(ConnectionMode.EDIT, mode.get());
            assertEquals("S-01 stays below S-06 (R-38)", HOME, previousId(s));
            s.onActivity(a -> a.navigator().finishWithResult(UiMessage.CONNECTION_SAVED));
            awaitDestination(s, HOME);
            assertFalse(inBackStack(s, ONBOARDING_GRAPH));
            // the result reached the caller entry: S-01 consumed it and shows "Connection saved"
            onView(withText(app.lightscout.ui.core.R.string.s06_snackbar_saved)).check(matches(isDisplayed()));
            AtomicReference<Object> left = new AtomicReference<>("unset");
            s.onActivity(a -> left.set(nav(a).getCurrentBackStackEntry().getSavedStateHandle().get(NavResults.KEY)));
            assertNull("delivered once, then cleared", left.get());
        }
    }

    @Test
    public void navigator_s07ToS06Edit_saveReturnsToS07AndShowsConnectionSaved() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openSettings());
            awaitDestination(s, SETTINGS);
            s.onActivity(a -> a.navigator().openConnection(ConnectionMode.EDIT));
            awaitDestination(s, CONNECTION);
            assertEquals("S-07 stays below S-06", SETTINGS, previousId(s));
            s.onActivity(a -> a.navigator().finishWithResult(UiMessage.CONNECTION_SAVED));
            awaitDestination(s, SETTINGS);
            // Q-117: S-07 consumes the result and shows "Connection saved", as S-01 does
            onView(withText(app.lightscout.ui.core.R.string.s06_snackbar_saved)).check(matches(isDisplayed()));
            awaitTrue(() -> {
                AtomicReference<UiMessage> pending = new AtomicReference<>();
                s.onActivity(a -> pending.set(nav(a).getCurrentBackStackEntry().getSavedStateHandle()
                        .<UiMessage>getLiveData(NavResults.KEY).getValue()));
                return pending.get() == null;
            });
        }
    }

    @Test
    public void navigator_s01ToS06Edit_backReturnsToS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openConnection(ConnectionMode.EDIT));
            awaitDestination(s, CONNECTION);
            pressBackUnconditionally();
            awaitDestination(s, HOME);
            assertFalse(inBackStack(s, ONBOARDING_GRAPH));
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }

    @Test
    public void s06_isExcludedFromAutofill_tokenFieldKeepsItsFlags() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openConnection(ConnectionMode.EDIT));
            awaitDestination(s, CONNECTION);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicInteger root = new AtomicInteger(-1);
            AtomicInteger token = new AtomicInteger(-1);
            s.onActivity(a -> {
                root.set(a.findViewById(app.lightscout.feature.onboarding.R.id.s06_root).getImportantForAutofill());
                token.set(a.findViewById(app.lightscout.feature.onboarding.R.id.s06_token).getImportantForAutofill());
            });
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, root.get());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, token.get());
        }
    }

    @Test
    public void navigator_s01ToS07Check_backReturnsToS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openReadiness(ReadinessMode.CHECK));
            awaitDestination(s, READINESS);
            AtomicReference<Object> mode = new AtomicReference<>();
            s.onActivity(a -> mode.set(app.lightscout.feature.onboarding.readiness.ReadinessFragmentArgs
                    .fromBundle(nav(a).getCurrentBackStackEntry().getArguments()).getMode()));
            assertEquals(ReadinessMode.CHECK, mode.get());
            assertEquals("S-01 stays below S-07", HOME, previousId(s));
            pressBackUnconditionally();
            awaitDestination(s, HOME);
            assertFalse(inBackStack(s, ONBOARDING_GRAPH));
        }
    }

    @Test
    public void navigator_statisticsAndSettings_openAndBackReturnsToS01() {
        // M1 had no S-03 / S-07 and the navigator stayed on S-01; both destinations exist now.
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openStatistics());
            awaitDestination(s, STATISTICS);
            pressBackUnconditionally();
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openSettings());
            awaitDestination(s, SETTINGS);
            pressBackUnconditionally();
            awaitDestination(s, HOME);
            assertEquals(Lifecycle.State.RESUMED, s.getState());
        }
    }

    @Test
    public void navigator_openEntry_stacksS02OnceAboveS01_andBackReturns() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> {
                a.navigator().openNewEntry(EntryType.CARBS);
                a.navigator().openNewEntry(EntryType.CARBS); // GIR-1: one sheet
            });
            awaitDestination(s, ENTRY_FORM);
            assertEquals("S-01 stays below S-02", HOME, previousId(s));
            AtomicReference<String> entryId = new AtomicReference<>("unset");
            s.onActivity(a -> entryId.set(nav(a).getCurrentBackStackEntry().getArguments()
                    .getString("entryId")));
            assertEquals(null, entryId.get());
            pressBackUntil(s, HOME);
            assertFalse(inBackStack(s, ENTRY_GRAPH));
        }
    }

    @Test
    public void navigator_openEntry_withId_forwardsEntryIdToS02() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openEntry("some-id"));
            awaitDestination(s, ENTRY_FORM);
            AtomicReference<String> entryId = new AtomicReference<>("unset");
            s.onActivity(a -> entryId.set(nav(a).getCurrentBackStackEntry().getArguments()
                    .getString("entryId")));
            assertEquals("some-id", entryId.get());
            pressBackUntil(s, HOME);
        }
    }

    @Test
    public void entryRoutesRestoreTypeAndDeliverOneResultWithOnePop() {
        components.configured();
        for (EntryType type : new EntryType[] {EntryType.CARBS, EntryType.BOLUS, EntryType.BG_CHECK,
                EntryType.SITE_CHANGE, EntryType.SENSOR_CHANGE}) {
            try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
                awaitDestination(s, HOME);
                s.onActivity(a -> a.navigator().openNewEntry(type));
                awaitDestination(s, ENTRY_FORM);
                s.recreate();
                awaitDestination(s, ENTRY_FORM);
                s.onActivity(a -> {
                    assertEquals("CREATE", nav(a).getCurrentBackStackEntry().getArguments().getString("mode"));
                    assertEquals(type.name(),
                            nav(a).getCurrentBackStackEntry().getArguments().getString("initialType"));
                    a.navigator().finishWithResult(UiMessage.ENTRY_SAVED);
                    a.navigator().finishWithResult(UiMessage.ENTRY_SAVED);
                });
                awaitDestination(s, HOME);
                // Home consumes the result once it is resumed (BUG-43: also after the activity was recreated with the sheet
                // open). The LiveData value is read, not SavedStateHandle.get(): after a recreation the handle's map keeps
                // the delivered value although the LiveData was cleared.
                awaitTrue(() -> {
                    AtomicReference<UiMessage> pending = new AtomicReference<>();
                    s.onActivity(a -> pending.set(nav(a).getCurrentBackStackEntry().getSavedStateHandle()
                            .<UiMessage>getLiveData(NavResults.KEY).getValue()));
                    return pending.get() == null;
                });
                assertFalse(inBackStack(s, ENTRY_GRAPH));
            }
        }
    }

    @Test
    public void entrySheetSurvivesAlarmPreemptionAndBackgroundReturn() {
        components.configured();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AlarmActivity.class.getName(), null, true);
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openNewEntry(EntryType.BOLUS));
            awaitDestination(s, ENTRY_FORM);
            s.moveToState(Lifecycle.State.CREATED);
            components.liveScreen = AlarmType.LOW;
            s.moveToState(Lifecycle.State.RESUMED);
            awaitTrue(() -> monitor.getHits() > 0);
            assertEquals(ENTRY_FORM, currentId(s));
            components.liveScreen = null;
            s.recreate();
            awaitDestination(s, ENTRY_FORM);
        } finally {
            components.liveScreen = null;
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void navigator_doubleOpenConnection_stacksS06Once() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> {
                a.navigator().openConnection(ConnectionMode.EDIT);
                a.navigator().openConnection(ConnectionMode.EDIT);
            });
            awaitDestination(s, CONNECTION);
            assertEquals("S-06 stacked once (GIR-1): S-01 directly below", HOME, previousId(s));
            pressBackUnconditionally();
            awaitDestination(s, HOME);
        }
    }

    // ------------------------------------------------------------------ app open (R-86) and ringing (TST-07)

    @Test
    public void onStart_completed_startsMonitoringAndAppOpenTick_thenBootCheck() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            awaitTrue(() -> components.control.calls.contains("bootCountCheck"));
            assertEquals("startMonitoring", components.control.calls.get(0));
            assertEquals("requestTick:APP_OPEN", components.control.calls.get(1));
        }
    }

    @Test
    public void onStart_notCompleted_startsNothing() {
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, DISCLAIMER);
            SystemClock.sleep(500);
            assertTrue(components.control.calls.toString(), components.control.calls.isEmpty());
        }
    }

    @Test
    public void onResume_liveAlarmScreenLow_startsAlarmActivity() {
        components.configured();
        components.liveScreen = AlarmType.LOW;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AlarmActivity.class.getName(), null, true);
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitTrue(() -> monitor.getHits() > 0);
        } finally {
            instrumentation.removeMonitor(monitor);
            components.liveScreen = null;
        }
    }

    @Test
    public void onResume_snapshotStillRingingButNoLiveScreen_doesNotReopenS09_ir109() {
        components.configured();
        components.alarmState.value = new AlarmState(AlarmType.LOW, Map.of(), null);
        LightScoutApplication app = ApplicationProvider.getApplicationContext();
        app.container().statusRepository().refresh();
        awaitTrue(() -> app.container().statusRepository().current().ringing() == AlarmType.LOW);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(AlarmActivity.class.getName(), null, true);
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            SystemClock.sleep(500);
            assertEquals("S-09 already finished (Snooze): no relaunch from a stale snapshot", 0, monitor.getHits());
        } finally {
            instrumentation.removeMonitor(monitor);
            components.alarmState.value = AlarmState.empty();
            app.container().statusRepository().refresh();
        }
    }

    @Test
    public void processStart_runsNoBootCountCheck_q67_ir108() {
        assertEquals("Application.onCreate never creates or runs the F5 boot-count check", 0,
                TestLightScoutApplication.bootCountChecksAfterOnCreate);
    }

    // ------------------------------------------------------------------ SR-21 exported input

    @Test
    public void exportedLaunch_extrasAndDataAreIgnored_noStateChange() {
        components.configured();
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ApplicationProvider.getApplicationContext(), MainActivity.class.getName())
                .setData(Uri.parse("lightscout://evil/connection?url=http://attacker"))
                .putExtra("mode", "EDIT")
                .putExtra("url", "http://attacker.example")
                .putExtra("alarmType", "LOW")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int writesBefore = components.flags.flagWrites.get();
        try (MonitoredLaunch<MainActivity> s = MonitoredLaunch.start(MainActivity.class, intent)) {
            awaitDestination(s::onActivity, HOME);
            SystemClock.sleep(500);
            assertEquals(HOME, currentId(s::onActivity));
            assertEquals(writesBefore, components.flags.flagWrites.get());
            assertEquals("https://ns.example.org", components.connection.url);
            assertCleanIntent(s::onActivity, Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER);
        }
    }

    @Test
    public void explicitIntentWithInternalDeepLink_opensOnlyTheNormalStart_noS06_sr21() {
        components.configured();
        try (MonitoredLaunch<MainActivity> s = MonitoredLaunch.start(MainActivity.class, deepLinkIntent())) {
            awaitDestination(s::onActivity, HOME);
            SystemClock.sleep(500);
            assertEquals(HOME, currentId(s::onActivity));
            assertFalse("no S-06 from external data / extras", inBackStack(s::onActivity, CONNECTION));
            assertFalse(inBackStack(s::onActivity, ONBOARDING_GRAPH));
            assertCleanIntent(s::onActivity, null, null);
        }
    }

    @Test
    public void explicitIntentWithInternalDeepLink_freshInstall_opensOnlyS05_sr21() {
        try (MonitoredLaunch<MainActivity> s = MonitoredLaunch.start(MainActivity.class, deepLinkIntent())) {
            awaitDestination(s::onActivity, DISCLAIMER);
            SystemClock.sleep(500);
            assertEquals(DISCLAIMER, currentId(s::onActivity));
            assertFalse(inBackStack(s::onActivity, CONNECTION));
            assertCleanIntent(s::onActivity, null, null);
        }
    }

    @Test
    public void newIntentWithInternalDeepLink_onS01_staysOnS01_sr21() {
        assertNewIntentOnS01_staysOnS01(deepLinkIntent(), null, null);
    }

    // BUG-08 review (🔐 F1): the newly kept MAIN / LAUNCHER surface next to the internal deep link and nav extras.

    @Test
    public void mainLauncherIntentWithInternalDeepLink_opensOnlyTheNormalStart_noS06_sr21() {
        components.configured();
        int writesBefore = components.flags.flagWrites.get();
        try (MonitoredLaunch<MainActivity> s =
                     MonitoredLaunch.start(MainActivity.class, mainLauncherDeepLinkIntent())) {
            awaitDestination(s::onActivity, HOME);
            SystemClock.sleep(500);
            assertEquals(HOME, currentId(s::onActivity));
            assertFalse("no S-06 from external data / extras", inBackStack(s::onActivity, CONNECTION));
            assertFalse(inBackStack(s::onActivity, ONBOARDING_GRAPH));
            assertEquals("no state change", writesBefore, components.flags.flagWrites.get());
            assertEquals("https://ns.example.org", components.connection.url);
            assertCleanIntent(s::onActivity, Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER);
        }
    }

    @Test
    public void mainLauncherIntentWithInternalDeepLink_freshInstall_opensOnlyS05_sr21() {
        int writesBefore = components.flags.flagWrites.get();
        try (MonitoredLaunch<MainActivity> s =
                     MonitoredLaunch.start(MainActivity.class, mainLauncherDeepLinkIntent())) {
            awaitDestination(s::onActivity, DISCLAIMER);
            SystemClock.sleep(500);
            assertEquals(DISCLAIMER, currentId(s::onActivity));
            assertFalse(inBackStack(s::onActivity, CONNECTION));
            assertEquals("no state change", writesBefore, components.flags.flagWrites.get());
            assertNull("no connection stored", components.connection.url);
            assertCleanIntent(s::onActivity, Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER);
        }
    }

    @Test
    public void newMainLauncherIntentWithInternalDeepLink_onS01_staysOnS01_sr21() {
        assertNewIntentOnS01_staysOnS01(mainLauncherDeepLinkIntent(), Intent.ACTION_MAIN, Intent.CATEGORY_LAUNCHER);
    }

    /**
     * Plain launch to S-01, then {@code newIntent} (clear-top, single-top) to the running instance. Observed through
     * the lifecycle monitor: a component-only clean intent would no longer match {@code ActivityScenario} (BUG-08).
     */
    private void assertNewIntentOnS01_staysOnS01(Intent newIntent, String cleanAction, String cleanCategory) {
        components.configured();
        Intent plain = Intent.makeMainActivity(
                new ComponentName(ApplicationProvider.getApplicationContext(), MainActivity.class));
        AtomicInteger newIntents = new AtomicInteger();
        androidx.core.util.Consumer<Intent> listener = i -> newIntents.incrementAndGet();
        try (MonitoredLaunch<MainActivity> s = MonitoredLaunch.start(MainActivity.class, plain)) {
            awaitDestination(s::onActivity, HOME);
            s.onActivity(a -> a.addOnNewIntentListener(listener));
            int writesBefore = components.flags.flagWrites.get();
            ApplicationProvider.getApplicationContext().startActivity(newIntent
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(500);
            assertEquals("delivered to the same instance", 1, s.instances());
            assertEquals("onNewIntent ran once", 1, newIntents.get());
            assertEquals(HOME, currentId(s::onActivity));
            assertFalse(inBackStack(s::onActivity, CONNECTION));
            assertFalse(inBackStack(s::onActivity, ONBOARDING_GRAPH));
            assertEquals("no state change", writesBefore, components.flags.flagWrites.get());
            assertEquals("https://ns.example.org", components.connection.url);
            assertCleanIntent(s::onActivity, cleanAction, cleanCategory);
            s.onActivity(a -> a.removeOnNewIntentListener(listener));
        }
    }

    @Test
    public void newIntent_fromANotificationTap_returnsToS01() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            awaitDestination(s, HOME);
            s.onActivity(a -> a.navigator().openReadiness(ReadinessMode.CHECK));
            awaitDestination(s, READINESS);
            ApplicationProvider.getApplicationContext().startActivity(
                    app.lightscout.ui.core.AppLaunch.mainActivityIntent(ApplicationProvider.getApplicationContext())
                            .putExtra("ignored", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitDestination(s, HOME);
        }
    }

    // ------------------------------------------------------------------ StrictMode (debug)

    @Test
    public void debugBuild_mainThreadStrictModeIsOn() {
        AtomicReference<StrictMode.ThreadPolicy> policy = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> policy.set(StrictMode.getThreadPolicy()));
        assertTrue(BuildConfig.DEBUG);
        assertNotSame(StrictMode.ThreadPolicy.LAX, policy.get());
        assertFalse("detectAll + penaltyDeath set (not LAX)",
                policy.get().toString().equals(StrictMode.ThreadPolicy.LAX.toString()));
    }

    @Test
    public void mainActivity_isTheNavigatorHost() {
        components.configured();
        try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
            AtomicBoolean host = new AtomicBoolean();
            s.onActivity(a -> host.set(a instanceof NavigatorHost && a.navigator() != null));
            assertTrue(host.get());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static NavController nav(Activity a) {
        return Navigation.findNavController(a, R.id.nav_host);
    }

    /** {@link #deepLinkIntent()} with {@code ACTION_MAIN} + {@code CATEGORY_LAUNCHER} (the surface Q-95 keeps). */
    private static Intent mainLauncherDeepLinkIntent() {
        return deepLinkIntent().setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    /** Explicit intent with the internal S-06 URI plus NavController's deep-link extras (SR-21 attack input). */
    private static Intent deepLinkIntent() {
        Bundle args = new Bundle();
        args.putString("mode", "EDIT");
        ArrayList<Bundle> argsList = new ArrayList<>(List.of(new Bundle(), args));
        return new Intent(Intent.ACTION_VIEW)
                .setClassName(ApplicationProvider.getApplicationContext(), MainActivity.class.getName())
                .setData(Uri.parse(INTERNAL_S06_EDIT))
                .putExtra(NAV_DEEP_LINK_IDS, new int[] {ONBOARDING_GRAPH, CONNECTION})
                .putParcelableArrayListExtra(NAV_DEEP_LINK_ARGS, argsList)
                .putExtra(NAV_DEEP_LINK_EXTRAS, args)
                .putExtra(NavController.KEY_DEEP_LINK_INTENT, new Intent(Intent.ACTION_VIEW,
                        Uri.parse(INTERNAL_S06_EDIT)))
                .putExtra("mode", "EDIT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Runs an action with the observed MainActivity on the main thread (ActivityScenario or lifecycle monitor). */
    private interface ActivityAccess {
        void onActivity(ActivityScenario.ActivityAction<MainActivity> action);
    }

    /**
     * SR-21 clean intent (Q-95): component only, plus MAIN / LAUNCHER exactly when the launch intent had them.
     *
     * @param action expected action ({@code ACTION_MAIN}) or {@code null}
     * @param category expected single category ({@code CATEGORY_LAUNCHER}) or {@code null} for none
     */
    private static void assertCleanIntent(ActivityAccess s, String action, String category) {
        AtomicReference<Intent> intent = new AtomicReference<>();
        s.onActivity(a -> intent.set(a.getIntent()));
        assertNull("no data", intent.get().getData());
        assertNull("no type", intent.get().getType());
        assertNull("no extras", intent.get().getExtras());
        assertNull("no clip data", intent.get().getClipData());
        assertNull("no selector", intent.get().getSelector());
        assertEquals("action", action, intent.get().getAction());
        if (category == null) {
            assertNull("no categories", intent.get().getCategories());
        } else {
            assertEquals("categories", Set.of(category), intent.get().getCategories());
        }
        assertEquals(MainActivity.class.getName(), intent.get().getComponent().getClassName());
    }

    private static int rootStart(ActivityScenario<MainActivity> s) {
        AtomicInteger id = new AtomicInteger();
        s.onActivity(a -> id.set(nav(a).getGraph().getStartDestinationId()));
        return id.get();
    }

    private static int previousId(ActivityScenario<MainActivity> s) {
        AtomicInteger id = new AtomicInteger();
        s.onActivity(a -> {
            NavBackStackEntry previous = nav(a).getPreviousBackStackEntry();
            id.set(previous == null ? 0 : previous.getDestination().getId());
        });
        return id.get();
    }

    private static int currentId(ActivityScenario<MainActivity> s) {
        return currentId(s::onActivity);
    }

    private static int currentId(ActivityAccess s) {
        AtomicInteger id = new AtomicInteger();
        s.onActivity(a -> {
            NavDestination d = nav(a).getCurrentDestination();
            id.set(d == null ? 0 : d.getId());
        });
        return id.get();
    }

    private static boolean inBackStack(ActivityScenario<MainActivity> s, int id) {
        return inBackStack(s::onActivity, id);
    }

    private static boolean inBackStack(ActivityAccess s, int id) {
        AtomicBoolean found = new AtomicBoolean();
        s.onActivity(a -> {
            try {
                nav(a).getBackStackEntry(id);
                found.set(true);
            } catch (IllegalArgumentException e) {
                found.set(false);
            }
        });
        return found.get();
    }

    /**
     * Back key from the instrumentation, without Espresso's root lookup: the entry sheet window gains focus late, and a
     * new sheet opens with the keyboard (D-146), so the first Back may only close the keyboard.
     */
    private static void pressBackUntil(ActivityScenario<MainActivity> s, int destination) {
        for (int back = 0; back < 4 && currentId(s) != destination; back++) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(600);
        }
        awaitDestination(s, destination);
    }

    private static void awaitDestination(ActivityScenario<MainActivity> s, int id) {
        awaitDestination(s::onActivity, id);
    }

    private static void awaitDestination(ActivityAccess s, int id) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (currentId(s) == id) {
                return;
            }
            SystemClock.sleep(50);
        }
        assertEquals("destination", id, currentId(s));
    }

    /** Waits until the S-06 URL field shows {@code expected} (the stored connection loads on {@code ioExecutor}). */
    private static void awaitS06Url(ActivityScenario<MainActivity> s, String expected) {
        awaitTrue(() -> {
            AtomicReference<String> url = new AtomicReference<>();
            s.onActivity(a -> {
                EditText field = a.findViewById(app.lightscout.feature.onboarding.R.id.s06_url);
                url.set(field == null ? null : field.getText().toString());
            });
            return expected.equals(url.get());
        });
    }

    private static void awaitState(ActivityScenario<MainActivity> s, Lifecycle.State state) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < end && s.getState() != state) {
            SystemClock.sleep(50);
        }
        assertEquals(state, s.getState());
    }

    private interface Condition {
        boolean holds();
    }

    private static void awaitTrue(Condition c) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < end) {
            if (c.holds()) {
                return;
            }
            SystemClock.sleep(50);
        }
        assertTrue("condition not reached within " + TIMEOUT_MS + " ms", c.holds());
    }
}
