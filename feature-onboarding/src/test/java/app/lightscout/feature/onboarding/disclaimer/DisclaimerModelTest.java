package app.lightscout.feature.onboarding.disclaimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.onboarding.Fakes;
import app.lightscout.feature.onboarding.Fakes.FakeOnboarding;
import app.lightscout.feature.onboarding.Fakes.MapStore;
import app.lightscout.feature.onboarding.Fakes.QueueExecutor;

import org.junit.Before;
import org.junit.Test;

/**
 * JVM tests of the S-05 model (CP-14 §7.2, §9 TST-06 S-05 logic part; DS:D-058; GIR-1).
 */
public class DisclaimerModelTest {

    private FakeOnboarding onboarding;
    private MapStore store;
    private QueueExecutor io;
    private DisclaimerUiState last;
    private int acceptedEvents;

    @Before
    public void setUp() {
        onboarding = new FakeOnboarding();
        store = new MapStore();
        io = new QueueExecutor();
        acceptedEvents = 0;
    }

    private DisclaimerModel model() {
        DisclaimerModel m = new DisclaimerModel(onboarding, store, io, Fakes.DIRECT);
        m.setListener(new DisclaimerModel.Listener() {
            @Override
            public void onState(DisclaimerUiState state) {
                last = state;
            }

            @Override
            public void onAccepted() {
                acceptedEvents++;
            }
        });
        return m;
    }

    @Test
    public void continueIsDisabledWithHelperUntilTicked() {
        model();
        assertFalse(last.acknowledged());
        assertFalse(last.continueEnabled());
        assertTrue(last.helperVisible());
    }

    @Test
    public void tickEnablesContinueAndHidesHelper() {
        DisclaimerModel m = model();
        m.setAcknowledged(true);
        assertTrue(last.continueEnabled());
        assertFalse(last.helperVisible());
        m.setAcknowledged(false);
        assertFalse(last.continueEnabled());
        assertTrue(last.helperVisible());
    }

    @Test
    public void tickAloneStoresNoAcceptance() {
        DisclaimerModel m = model();
        m.setAcknowledged(true);
        io.runAll();
        assertEquals(0, onboarding.accepts);   // back now exits with nothing stored (A-S05-2)
    }

    @Test
    public void continueAcceptsOnceThenOpensConnection() {
        DisclaimerModel m = model();
        m.setAcknowledged(true);
        m.onContinue();
        assertEquals(0, onboarding.accepts);   // blocking IF-27 call runs on ioExecutor, not here
        assertFalse(last.continueEnabled());
        io.runAll();
        assertEquals(1, onboarding.accepts);
        assertEquals(1, acceptedEvents);
        assertTrue(last.continueEnabled());
    }

    @Test
    public void doubleTapAcceptsOnce() {
        DisclaimerModel m = model();
        m.setAcknowledged(true);
        m.onContinue();
        m.onContinue();
        io.runAll();
        assertEquals(1, onboarding.accepts);
        assertEquals(1, acceptedEvents);
    }

    @Test
    public void continueWithoutTickDoesNothing() {
        DisclaimerModel m = model();
        m.onContinue();
        io.runAll();
        assertEquals(0, onboarding.accepts);
        assertEquals(0, acceptedEvents);
    }

    @Test
    public void storeFailureStaysOnTheScreen() {
        onboarding.failure = new IllegalStateException("store");
        DisclaimerModel m = model();
        m.setAcknowledged(true);
        m.onContinue();
        io.runAll();
        assertEquals(0, acceptedEvents);
        assertTrue(last.continueEnabled());
    }

    @Test
    public void tickSurvivesRecreation() {
        model().setAcknowledged(true);
        model();   // new model over the same saved state (rotation without VM, or process death)
        assertTrue(last.acknowledged());
        assertTrue(last.continueEnabled());
        assertEquals(0, onboarding.accepts);
    }
}
