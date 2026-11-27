package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview.Event;
import app.lightscout.domain.sound.SoundRef;

import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** DCR-51 S7, S-13: the list state and the play buttons, with fake ports. */
public class AlarmSoundsViewModelTest {

    private final SoundFakes.FakeService service = new SoundFakes.FakeService();
    private final SoundFakes.FakePreview preview = new SoundFakes.FakePreview();
    private AlarmSoundsViewModel viewModel;

    @Before
    public void setUp() {
        SoundFakes.useInstantExecutor();
        viewModel = new AlarmSoundsViewModel(service, preview);
    }

    @After
    public void tearDown() {
        SoundFakes.resetExecutor();
    }

    @Test
    public void loadsTheLibraryOnCreation() {
        assertTrue(viewModel.state().getValue().loaded());
        assertEquals(List.of("loadLibrary"), service.calls);
    }

    @Test
    public void refreshReadsTheLibraryAgainAfterAPickerChangedIt() {
        service.library = service.library.addOwn(SoundFakes.own(SoundFakes.KEY_A, "Morning"))
                .select(AlarmType.LOW, SoundRef.own(SoundFakes.KEY_A));
        viewModel.refresh();
        assertEquals(SoundRef.own(SoundFakes.KEY_A),
                viewModel.state().getValue().library().choice().get(AlarmType.LOW));
    }

    @Test
    public void theStateIsNotLoadedUntilTheServiceAnswers() {
        service.hold = true;
        AlarmSoundsViewModel slow = new AlarmSoundsViewModel(service, preview);
        assertFalse(slow.state().getValue().loaded());
        service.release();
        assertTrue(slow.state().getValue().loaded());
    }

    @Test
    public void thePlayButtonTogglesThePreviewOfThatType() {
        viewModel.togglePreview(AlarmType.NO_DATA);
        assertEquals(List.of("play:NO_DATA"), preview.calls);
    }

    @Test
    public void thePlayingTypeFollowsThePreviewEvents() {
        viewModel.togglePreview(AlarmType.HIGH);
        assertNull(viewModel.playing().getValue());
        preview.emit(AlarmType.HIGH, Event.STARTED);
        assertEquals(AlarmType.HIGH, viewModel.playing().getValue());
        preview.emit(AlarmType.HIGH, Event.STOPPED);
        assertNull(viewModel.playing().getValue());
    }

    @Test
    public void aFailedPreviewIsAProblemTheScreenShowsOnce() {
        viewModel.togglePreview(AlarmType.LOW);
        preview.emit(AlarmType.LOW, Event.FAILED);
        assertEquals(SoundPreviewControl.Problem.FAILED, viewModel.problem().getValue());
        viewModel.clearProblem();
        assertNull(viewModel.problem().getValue());
    }

    @Test
    public void aRingingAlarmIsReportedAsItsOwnProblem() {
        viewModel.togglePreview(AlarmType.LOW);
        preview.emit(AlarmType.LOW, Event.ALARM_RINGING);
        assertEquals(SoundPreviewControl.Problem.ALARM_RINGING, viewModel.problem().getValue());
        assertNull(viewModel.playing().getValue());
    }

    @Test
    public void leavingTheScreenStopsThePreview() {
        viewModel.stopPreview();
        assertEquals(List.of("stop"), preview.calls);
    }
}
