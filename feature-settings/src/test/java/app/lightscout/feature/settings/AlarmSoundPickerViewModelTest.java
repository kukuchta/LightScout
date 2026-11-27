package app.lightscout.feature.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.port.SoundPreview.Event;
import app.lightscout.domain.sound.ImportOutcome;
import app.lightscout.domain.sound.PhoneSound;
import app.lightscout.domain.sound.SoundRef;

import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** DCR-51 S7, S-14: choosing, adding, removing with Undo, and the one preview after a change. */
public class AlarmSoundPickerViewModelTest {

    private final SoundFakes.FakeService service = new SoundFakes.FakeService();
    private final SoundFakes.FakePreview preview = new SoundFakes.FakePreview();
    private AlarmSoundPickerViewModel viewModel;

    @Before
    public void setUp() {
        SoundFakes.useInstantExecutor();
        service.phoneSounds.add(new PhoneSound("content://media/1", "Oxygen"));
        service.library = service.library.addOwn(SoundFakes.own(SoundFakes.KEY_A, "Morning"));
        viewModel = new AlarmSoundPickerViewModel(service, preview, AlarmType.LOW);
    }

    @After
    public void tearDown() {
        SoundFakes.resetExecutor();
    }

    private SoundPickerState state() {
        return viewModel.state().getValue();
    }

    @Test
    public void loadsTheLibraryAndThePhoneSounds() {
        assertTrue(state().loaded());
        assertEquals(1, state().library().ownSounds().size());
        assertEquals(List.of(new PhoneSound("content://media/1", "Oxygen")), state().phoneSounds());
        assertEquals(AlarmType.LOW, viewModel.type());
    }

    @Test
    public void notLoadedUntilTheServiceAnswers() {
        service.hold = true;
        AlarmSoundPickerViewModel slow = new AlarmSoundPickerViewModel(service, preview, AlarmType.HIGH);
        assertFalse(slow.state().getValue().loaded());
        service.release();
        assertTrue(slow.state().getValue().loaded());
    }

    @Test
    public void choosingASoundSelectsItAndPlaysItOnce() {
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        assertEquals(SoundRef.own(SoundFakes.KEY_A), state().library().choice().get(AlarmType.LOW));
        assertEquals(List.of("stop", "play:LOW"), preview.calls);
    }

    @Test
    public void choosingClassicSelectsItToo() {
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        viewModel.select(SoundRef.CLASSIC);
        assertEquals(SoundRef.CLASSIC, state().library().choice().get(AlarmType.LOW));
    }

    @Test
    public void aFailedChoiceShowsAMessageAndPlaysNothing() {
        service.failWith = ImportOutcome.CANNOT_READ;
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        assertEquals(PickerMessage.CANNOT_READ, viewModel.message().getValue());
        assertTrue(preview.calls.isEmpty());
    }

    @Test
    public void aPhoneSoundShowsProgressThenIsSelectedAndPlayed() {
        PhoneSound oxygen = state().phoneSounds().get(0);
        service.hold = true;
        viewModel.selectPhone(oxygen);
        assertEquals("content://media/1", state().busyPhoneUri());
        service.release();
        assertNull(state().busyPhoneUri());
        assertEquals("Oxygen", SoundLabels.customName(state().library(),
                state().library().choice().get(AlarmType.LOW)));
        assertEquals(List.of("stop", "play:LOW"), preview.calls);
    }

    @Test
    public void aFailedPhoneCopyClearsTheProgressAndKeepsTheChoice() {
        service.failWith = ImportOutcome.NOT_PLAYABLE;
        viewModel.selectPhone(state().phoneSounds().get(0));
        assertNull(state().busyPhoneUri());
        assertEquals(SoundRef.CLASSIC, state().library().choice().get(AlarmType.LOW));
        assertEquals(PickerMessage.NOT_PLAYABLE, viewModel.message().getValue());
    }

    @Test
    public void aSecondTapWhileACopyRunsIsIgnored() {
        service.hold = true;
        viewModel.selectPhone(state().phoneSounds().get(0));
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        viewModel.remove(SoundFakes.KEY_A);
        service.release();
        assertEquals(List.of("load", "selectPhone:LOW:content://media/1"), service.calls);
        assertEquals(List.of("stop", "play:LOW"), preview.calls);   // only the copy played, nothing else
        assertEquals(0, state().library().ownSounds().size() - 1);
        assertTrue(state().library().pendingRemoval().isEmpty());
    }

    @Test
    public void addingAFileSelectsItForThisTypeAndPlaysIt() {
        viewModel.addOwn("content://docs/7", "Siren.mp3");
        assertEquals(List.of("load", "addOwn:content://docs/7:Siren.mp3:LOW"), service.calls);
        assertEquals(2, state().library().ownSounds().size());
        assertFalse(state().adding());
        assertEquals(List.of("stop", "play:LOW"), preview.calls);
    }

    @Test
    public void theAddingStateIsShownWhileTheCopyRuns() {
        service.hold = true;
        viewModel.addOwn("content://docs/7", "Siren.mp3");
        assertTrue(state().adding());
        service.release();
        assertFalse(state().adding());
    }

    @Test
    public void everyImportFailureHasItsOwnMessage() {
        ImportOutcome[] outcomes = {ImportOutcome.TOO_LARGE, ImportOutcome.CANNOT_READ,
                ImportOutcome.NOT_PLAYABLE, ImportOutcome.LIMIT_REACHED};
        PickerMessage[] messages = {PickerMessage.TOO_LARGE, PickerMessage.CANNOT_READ,
                PickerMessage.NOT_PLAYABLE, PickerMessage.LIMIT_REACHED};
        for (int i = 0; i < outcomes.length; i++) {
            service.failWith = outcomes[i];
            viewModel.clearMessage();
            viewModel.addOwn("content://docs/7", "x");
            assertEquals(messages[i], viewModel.message().getValue());
        }
        assertTrue(preview.calls.isEmpty());
    }

    @Test
    public void aCancelledImportIsSilent() {
        service.failWith = ImportOutcome.CANCELLED;
        viewModel.addOwn("content://docs/7", "x");
        assertNull(viewModel.message().getValue());
    }

    @Test
    public void theFullLibraryRefusesAnotherFile() {
        for (int i = 0; i < 4; i++) {
            service.library = service.library.addOwn(SoundFakes.own(String.format("%016x", 0x900 + i), "S" + i));
        }
        viewModel.refresh();
        assertFalse(state().library().canAddOwn());
        viewModel.addOwn("content://docs/7", "Siren.mp3");
        assertEquals(PickerMessage.LIMIT_REACHED, viewModel.message().getValue());
    }

    @Test
    public void removingASoundLeavesAnOpenRemovalForTheSnackbar() {
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        viewModel.remove(SoundFakes.KEY_A);
        assertTrue(state().library().pendingRemoval().isPresent());
        assertEquals(List.of(AlarmType.LOW), state().library().pendingRemoval().get().reassigned());
        assertEquals(SoundRef.CLASSIC, state().library().choice().get(AlarmType.LOW));
    }

    @Test
    public void undoBringsTheSoundAndTheChoiceBack() {
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.undo();
        assertTrue(state().library().pendingRemoval().isEmpty());
        assertEquals(SoundRef.own(SoundFakes.KEY_A), state().library().choice().get(AlarmType.LOW));
    }

    @Test
    public void finaliseClosesTheRemoval() {
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.finalise();
        assertTrue(state().library().pendingRemoval().isEmpty());
        assertEquals(0, state().library().ownSounds().size());
    }

    @Test
    public void leavingTheScreenStopsThePreviewAndClosesAnOpenRemoval() {
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.onCleared();
        assertEquals("stop", preview.calls.get(preview.calls.size() - 1));
        assertEquals("finalise", service.calls.get(service.calls.size() - 1));
    }

    @Test
    public void stoppingTheViewStopsThePreviewButKeepsTheOpenRemoval() {
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.stopPreview();
        assertEquals("stop", preview.calls.get(preview.calls.size() - 1));
        assertTrue(state().library().pendingRemoval().isPresent());
        assertFalse(service.calls.contains("finalise"));
    }

    @Test
    public void leavingTheScreenWithoutARemovalLeavesTheLibraryAsItIs() {
        viewModel.onCleared();
        assertEquals(0, state().library().ownSounds().size() - 1);   // the own sound is still there
        assertTrue(service.library.pendingRemoval().isEmpty());
    }

    @Test
    public void leavingWhileARemovalIsStillRunningClosesItAfterwards() {
        service.hold = true;
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.onCleared();
        service.release();
        assertTrue("the removal that ran after the view's last state is closed",
                service.library.pendingRemoval().isEmpty());
        assertEquals(0, service.library.ownSounds().size());
    }

    @Test
    public void aSecondRemovalReplacesTheOpenOne() {
        service.library = service.library.addOwn(SoundFakes.own(SoundFakes.KEY_B, "Evening"));
        viewModel.refresh();
        viewModel.remove(SoundFakes.KEY_A);
        viewModel.remove(SoundFakes.KEY_B);
        assertEquals(SoundFakes.KEY_B, state().library().pendingRemoval().get().sound().key());
    }

    @Test
    public void undoAskedWhileACopyRunsIsAppliedInItsTurn() {
        viewModel.remove(SoundFakes.KEY_A);
        service.hold = true;
        viewModel.selectPhone(state().phoneSounds().get(0));
        viewModel.undo();
        assertEquals("content://media/1", state().busyPhoneUri());   // the copy keeps its progress state
        service.release();
        assertTrue(service.calls.contains("undo"));
        assertTrue(state().library().pendingRemoval().isEmpty());
        assertEquals(1, state().library().ownSounds().size());
    }

    @Test
    public void finaliseAskedWhileACopyRunsIsAppliedInItsTurn() {
        viewModel.remove(SoundFakes.KEY_A);
        service.hold = true;
        viewModel.selectPhone(state().phoneSounds().get(0));
        viewModel.finalise();
        service.release();
        assertTrue(service.calls.contains("finalise"));
        assertTrue(state().library().pendingRemoval().isEmpty());
        assertEquals(0, state().library().ownSounds().size());
    }

    @Test
    public void aChangeThatFinishesAfterTheViewStoppedPlaysNothing() {
        service.hold = true;
        viewModel.selectPhone(state().phoneSounds().get(0));
        viewModel.stopPreview();
        preview.calls.clear();
        service.release();
        assertTrue(preview.calls.isEmpty());
        assertEquals("Oxygen", SoundLabels.customName(state().library(),
                state().library().choice().get(AlarmType.LOW)));
    }

    @Test
    public void afterTheViewIsShownAgainAChangePlaysAgain() {
        viewModel.stopPreview();
        viewModel.onShown();
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        assertEquals(List.of("stop", "stop", "play:LOW"), preview.calls);
    }

    @Test
    public void aChangeThatFinishesAfterTheModelWasClearedChangesNothingOnScreen() {
        service.hold = true;
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        SoundPickerState before = state();
        viewModel.onCleared();
        preview.calls.clear();
        service.release();
        assertEquals(before, state());
        assertTrue(preview.calls.isEmpty());
    }

    @Test
    public void aPreviewThatFailsAfterAChoiceIsAProblemTheScreenShows() {
        viewModel.select(SoundRef.own(SoundFakes.KEY_A));
        preview.emit(AlarmType.LOW, Event.FAILED);
        assertEquals(SoundPreviewControl.Problem.FAILED, viewModel.problem().getValue());
    }

}
