package app.lightscout.usecases.onboarding;

import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.StartDestination;
import app.lightscout.usecases.fakes.Cp02bFakes;
import app.lightscout.usecases.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** ONB-1 and the R-72 start destination rules (CP-02 §9, design §9.1, DS:D-058). */
public class OnboardingStateTest {

    private Cp02bFakes.FakeAppFlags flags;
    private Cp02bFakes.FakeConnectionStore connection;
    private Fakes.FakeMonitorControl monitorControl;
    private DefaultOnboardingState onboarding;

    @Before
    public void setUp() {
        flags = new Cp02bFakes.FakeAppFlags();
        connection = new Cp02bFakes.FakeConnectionStore();
        monitorControl = new Fakes.FakeMonitorControl();
        onboarding = new DefaultOnboardingState(flags, connection, monitorControl);
    }

    @Test
    public void onb1_notAccepted_disclaimer_evenWithStoredConnectionAndCompletedFlag() {
        assertEquals(StartDestination.DISCLAIMER, onboarding.resolve());
        connection.url = "https://ns.example.org";
        connection.token = "tok".toCharArray();
        flags.onboardingCompleted = true;
        assertEquals(StartDestination.DISCLAIMER, onboarding.resolve());   // DS:D-058
    }

    @Test
    public void onb1_acceptedAndCompleted_home_evenWithoutConnection() {
        flags.disclaimerAccepted = true;
        flags.onboardingCompleted = true;
        assertEquals(StartDestination.HOME, onboarding.resolve());          // §9.1 no automatic redirect
    }

    @Test
    public void r72_acceptedNoConnection_connection_storedConnection_readiness() {
        flags.disclaimerAccepted = true;
        assertEquals(StartDestination.CONNECTION, onboarding.resolve());
        connection.url = "https://ns.example.org";
        connection.token = "tok".toCharArray();
        assertEquals(StartDestination.READINESS, onboarding.resolve());
        assertEquals("resolve never decrypts the token", 0, connection.tokenReads);
    }

    @Test
    public void onb1_completeOnboarding_setsBothFlags_startsMonitoring() {
        onboarding.acceptDisclaimer();
        assertTrue(flags.disclaimerAccepted);
        onboarding.completeOnboarding();
        assertTrue(flags.onboardingCompleted);
        assertTrue(flags.monitoringConfigured);
        assertEquals(1, monitorControl.starts);
        assertEquals(List.of(TickReason.ONBOARDING_DONE), monitorControl.ticks);
        assertEquals(StartDestination.HOME, onboarding.resolve());
    }

    @Test
    public void storeFailure_neverThrows_readsAsNotSet_monitoringStillStarted() {
        flags.fail = true;
        assertEquals(StartDestination.DISCLAIMER, onboarding.resolve());
        onboarding.acceptDisclaimer();
        onboarding.completeOnboarding();
        assertEquals(1, monitorControl.starts);
        assertEquals(List.of(TickReason.ONBOARDING_DONE), monitorControl.ticks);
        flags.fail = false;
        flags.disclaimerAccepted = true;
        connection.fail = true;
        assertEquals(StartDestination.CONNECTION, onboarding.resolve());
    }

    @Test
    public void a4_ceFlagReadFails_deMonitoringConfiguredDecides() {
        flags.failCe = true;
        flags.monitoringConfigured = true;
        assertEquals(StartDestination.HOME, onboarding.resolve());       // never pushed back to S-05
        flags.monitoringConfigured = false;
        assertEquals(StartDestination.DISCLAIMER, onboarding.resolve());
    }
}
