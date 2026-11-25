package app.lightscout.feature.entry;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.doubleClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.Result;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.WriteKind;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CP-13 v3 / R-92 native sheet procedures: preset routes, guarded close/IME/control paths, retained write/edit/
 * conflict/restore rules and accessibility. Launching the host stands in for a Home route. Compiled in SS-28;
 * native execution remains pending, not evidence from this S-A.
 */
@RunWith(AndroidJUnit4.class)
public class EntryFormScreenDeviceTest {

    private static final String EDIT_ID = "edit-1";

    private TestEntryApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
    }

    @After
    public void tearDown() {
        DeviceShell.run("settings put system font_scale 1.0");
        DeviceShell.run("cmd uimode night no");
    }

    private ActivityScenario<TestHostActivity> create() {
        return create(EntryType.CARBS);
    }

    private ActivityScenario<TestHostActivity> create(EntryType type) {
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class).putExtra("initialType", type.name()));
    }

    private ActivityScenario<TestHostActivity> edit() {
        app.writer.putEditable(EDIT_ID, EntryType.CARBS, 20, Instant.now().minus(Duration.ofMinutes(30)));
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class)
                .putExtra(TestHostActivity.EXTRA_ENTRY_ID, EDIT_ID));
    }

    /**
     * D-146: the value field takes focus on open, so the keyboard covers the buttons at the bottom of the sheet; a
     * coordinate click there would hit the keyboard's own toolbar (skin / settings). Waits (best effort, max 3 s) for
     * the keyboard of an open form, then closes it.
     */
    private static void quietKeyboard(ActivityScenario<TestHostActivity> scenario) {
        long end = android.os.SystemClock.elapsedRealtime() + 3_000;
        boolean[] visible = new boolean[1];
        while (!visible[0] && android.os.SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(a -> {
                EntryFormFragment f = a.form();
                androidx.core.view.WindowInsetsCompat insets = f == null ? null
                        : androidx.core.view.ViewCompat.getRootWindowInsets(f.requireView());
                visible[0] = f == null
                        || (insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
            });
            if (!visible[0]) {
                android.os.SystemClock.sleep(100);
            }
        }
        onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).perform(closeSoftKeyboard());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Waits (max 8 s) until the sheet window of the current activity instance has window focus. */
    private static void awaitSheetFocus(ActivityScenario<TestHostActivity> scenario) {
        long end = android.os.SystemClock.uptimeMillis() + 8_000;
        boolean[] focused = new boolean[1];
        do {
            scenario.onActivity(a -> {
                EntryFormFragment f = a.form();
                focused[0] = f != null && f.getDialog() != null && f.getDialog().isShowing()
                        && f.getDialog().getWindow().getDecorView().hasWindowFocus();
            });
            if (focused[0]) {
                return;
            }
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.uptimeMillis() < end);
        throw new AssertionError("the sheet window never took focus after the recreate");
    }

    /** Retries a check for up to 4 s: UI that follows an animation is not tracked by Espresso's idling. */
    private static void eventually(Runnable check) {
        long end = android.os.SystemClock.uptimeMillis() + 4_000;
        while (true) {
            try {
                check.run();
                return;
            } catch (RuntimeException | AssertionError e) {
                if (android.os.SystemClock.uptimeMillis() > end) {
                    throw e;
                }
                android.os.SystemClock.sleep(50);
            }
        }
    }

    private static void sync(Runnable r) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(r);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static String text(TestHostActivity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }

    @Test
    public void nativeModalOpensWithTheValueFocusedAndKeyboardUpAndBackWithImeOnlyHidesIt() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            scenario.onActivity(a -> {
                assertTrue(a.form().getDialog() instanceof com.google.android.material.bottomsheet.BottomSheetDialog);
                assertTrue("D-146: the value is focused on open", a.form().requireView().findViewById(R.id.s02_value)
                        .hasFocus());
            });
            awaitIme(scenario, true);
            onView(withId(R.id.s02_value)).perform(replaceText("15"));
            pressBack();
            scenario.onActivity(a -> assertFalse(a.closed()));
            onView(withText("Discard this entry?")).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
            quietKeyboard(scenario);
            onView(withId(R.id.s02_cancel)).perform(scrollTo(), click());
            eventually(() -> onView(withText("Discard this entry?")).inRoot(isDialog()).check(matches(isDisplayed())));
        }
    }

    @Test
    public void tabsKeepIndependentDraftsAndTypingIsTheOnlyInput() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            scenario.onActivity(a -> {
                // D-146: no stepper or quick-adjust controls, and every value starts empty.
                assertNull(a.findViewById(a.getResources().getIdentifier("s02_plus", "id", a.getPackageName())));
                assertNull(a.form().requireView().findViewById(
                        a.getResources().getIdentifier("s02_helper_plus_large", "id", a.getPackageName())));
                assertEquals("", ((TextView) a.form().requireView().findViewById(R.id.s02_value)).getText().toString());
            });
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_value)).perform(replaceText("015"), closeSoftKeyboard());
            onView(withId(R.id.s02_chip_bolus)).perform(click());
            onView(withId(R.id.s02_value)).check(matches(withText("")));
            onView(withId(R.id.s02_value)).perform(replaceText("1."), closeSoftKeyboard());
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_value)).check(matches(withText("015")));
            onView(withId(R.id.s02_chip_bolus)).perform(click());
            onView(withId(R.id.s02_value)).check(matches(withText("1.")));
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
        }
    }

    private static void awaitIme(ActivityScenario<TestHostActivity> scenario, boolean visible) {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        boolean[] now = {!visible};
        while (now[0] != visible && android.os.SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(a -> {
                androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat
                        .getRootWindowInsets(a.form().requireView());
                now[0] = insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            });
            if (now[0] != visible) {
                android.os.SystemClock.sleep(100);
            }
        }
        assertEquals("keyboard visible", visible, now[0]);
    }

    @Test
    public void nativeOutsideAndSwipeTransitionsUseDiscardGuardAndInFlightLock() {
        for (boolean swipe : new boolean[] {false, true}) {
            app.reset();
            try (ActivityScenario<TestHostActivity> scenario = create()) {
                onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
                scenario.onActivity(a -> {
                    com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                            (com.google.android.material.bottomsheet.BottomSheetDialog) a.form().getDialog();
                    assertFalse(dialog.getBehavior().isHideable());
                    if (swipe) {
                        dialog.getBehavior().setState(
                                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
                    } else {
                        assertTrue(dialog.findViewById(com.google.android.material.R.id.touch_outside).performClick());
                    }
                });
                // the swipe is reported when the sheet has settled (animation), so wait for the dialog
                eventually(() -> onView(withText("Discard this entry?")).inRoot(isDialog())
                        .check(matches(isDisplayed())));
                onView(withText("Keep editing")).inRoot(isDialog()).perform(click());
                onView(withId(R.id.s02_save)).perform(scrollTo(), click());
                scenario.onActivity(a -> {
                    com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                            (com.google.android.material.bottomsheet.BottomSheetDialog) a.form().getDialog();
                    assertFalse(dialog.getBehavior().isDraggable());
                    dialog.findViewById(com.google.android.material.R.id.touch_outside).performClick();
                    assertFalse(a.closed());
                });
                assertEquals(1, app.writer.saveCalls.get());
                sync(() -> app.writer.pendingSaves.get(0).onResult(Result.fail(
                        FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT)));
                onView(withId(R.id.s02_value)).check(matches(withText("15")));
                scenario.onActivity(a -> assertTrue(((com.google.android.material.bottomsheet.BottomSheetDialog)
                        a.form().getDialog()).getBehavior().isDraggable()));
            }
        }
    }

    // ---------------------------------------------------------------- GL-2 tap budget

    @Test
    public void carbsInThreeTaps() {
        app.writer.saveResult = DeviceFakes.FakeWriter.ok(WriteKind.CREATED, "draft-1");
        try (ActivityScenario<TestHostActivity> scenario = create()) {          // tap 1: FAB (stand-in)
            onView(withId(R.id.s02_chip_carbs)).perform(click());               // tap 2
            onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
            onView(withId(R.id.s02_save)).perform(click());                     // tap 3
            assertEquals(1, app.writer.saveCalls.get());
            assertEquals(Integer.valueOf(15), app.writer.saved.get(0).value());
            assertEquals(List.of(UiMessage.ENTRY_SAVED), TestHostActivity.RESULTS);
            scenario.onActivity(a -> assertTrue(a.closed()));
        }
    }

    @Test
    public void siteChangeInThreeTaps() {
        app.writer.saveResult = DeviceFakes.FakeWriter.ok(WriteKind.CREATED, "draft-1");
        try (ActivityScenario<TestHostActivity> ignored = create(EntryType.SITE_CHANGE)) {
            onView(withId(R.id.s02_save)).check(matches(isEnabled()));
            onView(withId(R.id.s02_save)).perform(click());                     // tap 3
            assertEquals(1, app.writer.saveCalls.get());
            assertEquals(EntryType.SITE_CHANGE, app.writer.saved.get(0).type());
        }
    }

    // ---------------------------------------------------------------- GIR-1

    @Test
    public void bolusFieldSavesExactMilliAndEditDoesNotTruncate() {
        app.writer.saveResult = DeviceFakes.FakeWriter.ok(WriteKind.CREATED, "draft-1");
        try (ActivityScenario<TestHostActivity> ignored = create()) {
            onView(withId(R.id.s02_chip_bolus)).perform(click());
            onView(withId(R.id.s02_value)).perform(replaceText("1."));
            onView(withText("Enter 0.025 to 25 U")).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());   // D-147: incomplete, no error yet
            onView(withId(R.id.s02_value)).perform(replaceText("0.024"));
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_value)).perform(androidx.test.espresso.action.ViewActions.pressImeActionButton());
            onView(withText("Enter 0.025 to 25 U")).check(matches(isDisplayed()));
            onView(withId(R.id.s02_value)).perform(replaceText("26"));
            onView(withText("Enter 0.025 to 25 U")).check(matches(isDisplayed()));   // above the maximum: at once
            onView(withId(R.id.s02_value)).perform(replaceText("0,025"), closeSoftKeyboard());
            onView(withId(R.id.s02_save)).perform(click());
            assertEquals(Integer.valueOf(25), app.writer.saved.get(0).value());
        }
        app.reset();
        app.writer.putEditable(EDIT_ID, EntryType.BOLUS, 1234, Instant.now().minusSeconds(60));
        app.writer.saveResult = DeviceFakes.FakeWriter.ok(WriteKind.UPDATED, EDIT_ID);
        try (ActivityScenario<TestHostActivity> scenario = ActivityScenario.launch(new Intent(app, TestHostActivity.class)
                .putExtra(TestHostActivity.EXTRA_ENTRY_ID, EDIT_ID))) {
            onView(withId(R.id.s02_value)).check(matches(withText("1.234")));
            quietKeyboard(scenario);
            onView(withId(R.id.s02_save)).perform(click());
            assertEquals(Integer.valueOf(1234), app.writer.saved.get(0).value());
            assertEquals(List.of(UiMessage.ENTRY_UPDATED), TestHostActivity.RESULTS);
        }
    }

    @Test
    public void doubleTapOnSaveSavesOnceAndShowsProgress() {
        try (ActivityScenario<TestHostActivity> ignored = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
            onView(withId(R.id.s02_save)).perform(doubleClick());
            assertEquals(1, app.writer.saveCalls.get());
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_progress)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void doubleTapOnDialogDeleteDeletesOnce() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            quietKeyboard(scenario);
            onView(withId(R.id.s02_delete)).perform(scrollTo(), click());
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(doubleClick());
            assertEquals(1, app.writer.deleteCalls.get());
        }
    }

    // ---------------------------------------------------------------- GIR-2

    @Test
    public void formSurvivesDarkModeAndFontScale() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
            DeviceShell.run("cmd uimode night yes");
            scenario.recreate();
            DeviceShell.run("settings put system font_scale 1.3");
            scenario.recreate();
            awaitSheetFocus(scenario);   // the recreated sheet window takes focus a moment after the activity resumes
            onView(withId(R.id.s02_chip_carbs)).check(matches(isChecked()));
            onView(withId(R.id.s02_value)).check(matches(withText("15")));
        }
    }

    @Test
    public void openDatePickerSurvivesRecreate() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_bg_check)).perform(click());
            quietKeyboard(scenario);
            onView(withId(R.id.s02_pick_time)).perform(scrollTo(), click());
            scenario.recreate();
            scenario.onActivity(a -> assertNotNull(
                    a.form().getChildFragmentManager().findFragmentByTag("s02_date_picker")));
        }
    }

    // ---------------------------------------------------------------- process death

    @Test
    public void processDeathRestoresTheForm() {
        Bundle saved = fillAndSave(false);
        TestHostActivity.restoreNextFrom(saved);
        try (ActivityScenario<TestHostActivity> ignored = create()) {
            onView(withId(R.id.s02_chip_carbs)).check(matches(isChecked()));
            onView(withId(R.id.s02_value)).check(matches(withText("15")));
            onView(withId(R.id.s02_now)).check(matches(isChecked()));
        }
    }

    @Test
    public void processDeathWithSaveInFlightShowsUnsureCopy() {
        Bundle saved = fillAndSave(true);
        TestHostActivity.restoreNextFrom(saved);
        try (ActivityScenario<TestHostActivity> ignored = create()) {
            onView(withId(R.id.s02_message_text)).perform(scrollTo()).check(matches(
                    withText("Not sure the entry was saved — check the chart before saving again.")));
        }
    }

    @Test
    public void processDeathInEditModeReloadsAndKeepsEdits() {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withId(R.id.s02_value)).perform(replaceText("35"), closeSoftKeyboard());
            scenario.onActivity(a -> saved.set(saveState(a)));
        }
        TestHostActivity.restoreNextFrom(saved.get());
        try (ActivityScenario<TestHostActivity> ignored = ActivityScenario.launch(
                new Intent(app, TestHostActivity.class).putExtra(TestHostActivity.EXTRA_ENTRY_ID, EDIT_ID))) {
            onView(withText("Edit entry")).check(matches(isDisplayed()));
            onView(withId(R.id.s02_value)).check(matches(withText("35")));
        }
    }

    private Bundle fillAndSave(boolean saveInFlight) {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
            if (saveInFlight) {
                onView(withId(R.id.s02_save)).perform(click());   // the fake holds the call
                assertEquals(1, app.writer.saveCalls.get());
            }
            scenario.onActivity(a -> saved.set(saveState(a)));
        }
        return saved.get();
    }

    private static Bundle saveState(TestHostActivity a) {
        Bundle out = new Bundle();
        InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(a, out);
        return out;
    }

    // ---------------------------------------------------------------- discard / back

    @Test
    public void untouchedCreateClosesWithoutDialog() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            scenario.onActivity(a -> assertFalse(a.closed()));
            // D-146: the value field has focus on open, so the first Back may only hide the keyboard.
            for (int back = 0; back < 2 && scenario.getState() != Lifecycle.State.DESTROYED; back++) {
                pressBackSafely();
                if (scenario.getState() != Lifecycle.State.DESTROYED) {
                    boolean[] closed = new boolean[1];
                    scenario.onActivity(a -> closed[0] = a.closed());
                    if (closed[0]) {
                        break;
                    }
                }
            }
            // R-92: Back with no changes pops the only destination (the host activity stays), so no dialog can show.
            if (scenario.getState() != Lifecycle.State.DESTROYED) {
                scenario.onActivity(a -> assertTrue("form popped", a.closed()));
            }
            assertTrue(TestHostActivity.RESULTS.isEmpty());
        }
    }

    @Test
    public void typeChosenAsksToDiscardAndKeepEditingKeepsTheForm() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_bolus)).perform(click());
            onView(withId(R.id.s02_value)).perform(closeSoftKeyboard());
            pressBack();
            onView(withText("Discard this entry?")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("Keep editing")).inRoot(isDialog()).perform(click());
            onView(withId(R.id.s02_chip_bolus)).check(matches(isChecked()));
            scenario.onActivity(a -> assertFalse(a.closed()));
        }
    }

    @Test
    public void editWithChangeAsksToDiscardChanges() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withId(R.id.s02_value)).perform(replaceText("35"), closeSoftKeyboard());
            pressBack();
            onView(withText("Discard changes?")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("Discard")).inRoot(isDialog()).perform(click());
            scenario.onActivity(a -> assertTrue(a.closed()));
            assertEquals(0, app.writer.saveCalls.get());
        }
    }

    private static void pressBackSafely() {
        // The form is the only destination of the test graph; back without changes pops it (or ends the activity).
        try {
            pressBack();
        } catch (RuntimeException ignored) {
            // NoActivityResumedException once the activity finished — also a close without dialog
        }
    }

    // ---------------------------------------------------------------- delete

    @Test
    public void deleteAsksAndReturnsDeleted() {
        app.writer.deleteResult = DeviceFakes.FakeWriter.ok(WriteKind.DELETED, EDIT_ID);
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            quietKeyboard(scenario);
            onView(withId(R.id.s02_delete)).perform(scrollTo(), click());
            onView(withText("Delete this entry?")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("It will be removed from Nightscout.")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            assertEquals(1, app.writer.deleteCalls.get());
            assertEquals(List.of(UiMessage.ENTRY_DELETED), TestHostActivity.RESULTS);
        }
    }

    // ---------------------------------------------------------------- offline

    @Test
    public void offlineCreateDisablesSave() {
        app.status.online = false;
        try (ActivityScenario<TestHostActivity> ignored = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_value)).perform(replaceText("15"), closeSoftKeyboard());
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_message_text)).perform(scrollTo()).check(matches(withText(
                    "No internet connection — this entry can't be saved now. Log it later with the right time.")));
        }
    }

    @Test
    public void offlineEditDisablesSaveAndDelete() {
        try (ActivityScenario<TestHostActivity> ignored = edit()) {
            sync(() -> app.status.setOnline(false));
            onView(withId(R.id.s02_save)).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_delete)).perform(scrollTo()).check(matches(not(isEnabled())));
            onView(withId(R.id.s02_message_text)).perform(scrollTo()).check(matches(
                    withText("No internet connection — changes can't be saved now.")));
        }
    }

    // ---------------------------------------------------------------- edit conflict

    @Test
    public void preconditionFailedShowsConflictAndKeepsTheForm() {
        app.writer.saveResult = Result.fail(FetchOutcome.OK, "PRECONDITION_412");
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withId(R.id.s02_value)).perform(replaceText("35"), closeSoftKeyboard());
            onView(withId(R.id.s02_save)).perform(click());
            onView(withId(R.id.s02_message_text)).perform(scrollTo()).check(matches(
                    withText("This entry was changed or deleted elsewhere.")));
            onView(withId(R.id.s02_value)).check(matches(withText("35")));
            scenario.onActivity(a -> assertFalse(a.closed()));
        }
    }

    // ---------------------------------------------------------------- similar entry

    @Test
    public void similarEntryShowsInfoWithSource() {
        Instant earlier = Instant.now().minus(Duration.ofMinutes(8));
        app.writer.similar = List.of(new Entry("other", EntryOrigin.TREATMENTS, EntryType.CARBS, earlier, null, 20,
                null, null, null, null, "xDrip", null, null, false, null));
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            onView(withId(R.id.s02_similar_card)).perform(scrollTo()).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                assertTrue(text(a, R.id.s02_similar_text).startsWith("Similar entry 8 min earlier"));
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                        a.findViewById(R.id.s02_similar_text).getAccessibilityLiveRegion());
            });
        }
    }

    // ---------------------------------------------------------------- 200 % font scale (GIR-6)

    @Test
    public void largeFontKeepsSaveAndValueVisibleWithTheKeyboard() {
        DeviceShell.run("settings put system font_scale 2.0");
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_bg_check)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.s02_chip_carbs)).perform(scrollTo(), click());
            onView(withId(R.id.s02_value)).perform(replaceText("0"),
                    androidx.test.espresso.action.ViewActions.pressImeActionButton());   // Done shows the error (D-147)
            onView(withId(R.id.s02_save)).perform(scrollTo()).check(matches(isCompletelyDisplayed()));
            onView(withId(R.id.s02_value)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText("Enter 1 to 250 g")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                TextView helper = a.findViewById(R.id.s02_type_helper);
                assertTrue(helper.getLayout() == null
                        || helper.getLayout().getEllipsisCount(helper.getLineCount() - 1) == 0);
            });
        }
    }

    // ---------------------------------------------------------------- TalkBack labels

    @Test
    public void talkBackLabels() {
        try (ActivityScenario<TestHostActivity> scenario = create()) {
            onView(withId(R.id.s02_chip_carbs)).perform(click());
            scenario.onActivity(a -> {
                MaterialButtonToggleGroup group = a.findViewById(R.id.s02_types);
                assertTrue(group.isSingleSelection());
                AccessibilityNodeInfo groupInfo = group.createAccessibilityNodeInfo();
                assertNotNull(groupInfo.getCollectionInfo());   // „1 of 5” is composed from collection info
                assertEquals(3, group.getChildCount());
                AccessibilityNodeInfo chip = a.findViewById(R.id.s02_chip_carbs).createAccessibilityNodeInfo();
                assertNotNull(chip.getCollectionItemInfo());
                assertTrue(chip.isChecked() || chip.isSelected());

                AccessibilityNodeInfo value = a.findViewById(R.id.s02_value).createAccessibilityNodeInfo();
                assertTrue(String.valueOf(value.getHintText()).contains("Carbs, grams"));

                AccessibilityNodeInfo time = a.findViewById(R.id.s02_time).createAccessibilityNodeInfo();
                assertEquals("Time, Now, button, double-tap to change", String.valueOf(time.getContentDescription()));

                assertEquals("Cancel", text(a, R.id.s02_cancel));

                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                        a.findViewById(R.id.s02_message_text).getAccessibilityLiveRegion());
            });
            scenario.onActivity(a -> assertEquals("Set time to now",
                    String.valueOf(a.findViewById(R.id.s02_now).getContentDescription())));
        }
    }
}
