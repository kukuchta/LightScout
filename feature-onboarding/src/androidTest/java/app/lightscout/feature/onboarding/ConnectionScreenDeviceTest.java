package app.lightscout.feature.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.doubleClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.navigation.NavBackStackEntry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.domain.port.Result;
import app.lightscout.ui.core.UiMessage;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.StoredConnection;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TST-06 S-06 (CP-14 §9): states (empty, checking, offline, edit after check), GIR-1 double tap, process death
 * (SR-02: URL restored, token empty + „For security, enter the token again”, token in no Bundle / saved state / nav
 * args), SR-28 field flags, M3-27 back / forward restore, edit mode (discard guard, save → result, predictive back
 * without changes), GIR-2 recreate, TalkBack rows / toggle, 200 % font. Compiled in SS-11; run on RD-2 (API 33).
 */
@RunWith(AndroidJUnit4.class)
public class ConnectionScreenDeviceTest {

    private static final String URL = "https://ns.example.org";
    private static final String TOKEN = "subject-0123456789abcdef";

    private TestOnboardingApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
        TestHostActivity.RESULTS.clear();
    }

    @After
    public void tearDown() {
        DeviceShell.run("settings put system font_scale 1.0");
    }

    private ActivityScenario<TestHostActivity> onboarding() {
        ActivityScenario<TestHostActivity> s = ActivityScenario.launch(new Intent(app, TestHostActivity.class));
        s.onActivity(a -> a.navController().navigate(R.id.action_disclaimerFragment_to_connectionFragment));
        return s;
    }

    private ActivityScenario<TestHostActivity> edit() {
        app.connection.stored = new StoredConnection(URL, "ns.example.org", true);
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class).putExtra(TestHostActivity.EXTRA_EDIT, true));
    }

    private static void fill(String url, String token) {
        onView(withId(R.id.s06_url)).perform(replaceText(url));
        onView(withId(R.id.s06_token)).perform(typeText(token), closeSoftKeyboard());
    }

    private static String tokenText(TestHostActivity a) {
        TextView t = a.findViewById(R.id.s06_token);
        return t.getText().toString();
    }

    // ---------------------------------------------------------------- states

    @Test
    public void emptyStateDisablesConnectWithHelper() {
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            onView(withText("Connect to Nightscout")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {   // Q-74: Up icon + localised description set by NavigationUI
                com.google.android.material.appbar.MaterialToolbar toolbar = a.findViewById(R.id.s06_toolbar);
                assertTrue(toolbar.getNavigationIcon() != null);
                assertTrue(toolbar.getNavigationContentDescription() != null);
            });
            onView(withId(R.id.s06_primary)).check(matches(not(isEnabled())));
            onView(withId(R.id.s06_primary)).check(matches(withText("Connect")));
            onView(withId(R.id.s06_helper)).check(matches(withText("Enter the address and token to connect")));
        }
    }

    @Test
    public void checkingShowsIndicatorAndDisablesButton() {
        app.connection.verifyResult = null;   // hold the check
        try (ActivityScenario<TestHostActivity> ignored = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withId(R.id.s06_progress)).check(matches(isDisplayed()));
            onView(withId(R.id.s06_checking)).check(matches(withText("Checking…")));
            onView(withId(R.id.s06_primary)).check(matches(not(isEnabled())));
        }
    }

    @Test
    public void offlineDisablesConnectWithHelper() {
        app.status.online = false;
        try (ActivityScenario<TestHostActivity> ignored = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).check(matches(not(isEnabled())));
            onView(withId(R.id.s06_helper)).check(matches(
                    withText("No internet connection — connect when you're online.")));
        }
    }

    @Test
    public void offlineDisablesContinueWithHelper() {
        try (ActivityScenario<TestHostActivity> ignored = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withId(R.id.s06_primary)).check(matches(withText("Continue")));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> app.status.setOnline(false));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withId(R.id.s06_primary)).check(matches(not(isEnabled())));
            onView(withId(R.id.s06_helper)).check(matches(
                    withText("No internet connection — connect when you're online.")));
        }
    }

    @Test
    public void editingAFieldAfterACheckClearsResults() {
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withId(R.id.s06_primary)).check(matches(withText("Continue")));
            onView(withId(R.id.s06_url)).perform(replaceText(URL + "/ns"));
            onView(withId(R.id.s06_primary)).check(matches(withText("Connect")));
            scenario.onActivity(a -> assertEquals(0, ((android.view.ViewGroup) a.findViewById(R.id.s06_results))
                    .getChildCount()));
        }
    }

    @Test
    public void failingRowShowsItsMessage() {
        app.connection.verifyResult = Result.ok(List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                new CheckResult(CheckRow.VERSION, false, CheckFailure.VERSION_TOO_OLD, "14.2.0")));
        try (ActivityScenario<TestHostActivity> ignored = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withText("Nightscout 14.2.0 is too old. Version 15.0.9 or newer is needed."))
                    .check(matches(isDisplayed()));
            onView(withText("Version 14.2.0")).check(matches(isDisplayed()));
        }
    }

    // ---------------------------------------------------------------- GIR-1

    @Test
    public void doubleTapConnectSendsOneVerify() {
        app.connection.verifyResult = null;
        try (ActivityScenario<TestHostActivity> ignored = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(doubleClick());
            assertEquals(1, app.connection.count("verify"));
        }
    }

    // ---------------------------------------------------------------- SR-28 field flags

    @Test
    public void tokenFieldHygieneFlags() {
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            scenario.onActivity(a -> {
                TextView token = a.findViewById(R.id.s06_token);
                int type = token.getInputType();
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, type & InputType.TYPE_MASK_VARIATION);
                assertTrue((type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
                assertFalse(token.isSaveEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, token.getImportantForAutofill());
                assertTrue((token.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
            });
        }
    }

    // ---------------------------------------------------------------- process death (SR-02, M3-28)

    @Test
    public void processDeathRestoresUrlAndAsksForTheTokenAgain() {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            scenario.onActivity(a -> {
                Bundle out = new Bundle();
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(a, out);
                saved.set(out);
                for (NavBackStackEntry entry : new NavBackStackEntry[] {
                        a.navController().getCurrentBackStackEntry(), a.navController().getPreviousBackStackEntry()}) {
                    Bundle args = entry == null ? null : entry.getArguments();
                    assertFalse("token found in navigation arguments", args != null && containsToken(args));
                }
            });
        }
        assertFalse("token found in the saved-instance-state Bundle", containsToken(saved.get()));

        TestHostActivity.restoreNextFrom(saved.get());
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(new Intent(app, TestHostActivity.class))) {
            onView(withId(R.id.s06_url)).check(matches(withText(URL)));
            scenario.onActivity(a -> assertEquals("", tokenText(a)));
            onView(withText("For security, enter the token again")).check(matches(isDisplayed()));
        }
    }

    private static boolean containsToken(Bundle b) {
        Parcel p = Parcel.obtain();
        try {
            p.writeBundle(b);
            byte[] bytes = p.marshall();
            String utf16 = new String(bytes, StandardCharsets.UTF_16LE);
            String utf8 = new String(bytes, StandardCharsets.ISO_8859_1);
            return utf16.contains(TOKEN) || utf8.contains(TOKEN);
        } finally {
            p.recycle();
        }
    }

    // ---------------------------------------------------------------- M3-27, GIR-2

    @Test
    public void backToDisclaimerAndForwardRestoresUrlAndToken() {
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            pressBack();
            onView(withText("Before you start")).check(matches(isDisplayed()));
            onView(withId(R.id.s05_acknowledge)).perform(scrollTo(), click());
            onView(withId(R.id.s05_continue)).perform(click());
            onView(withId(R.id.s06_url)).check(matches(withText(URL)));
            scenario.onActivity(a -> assertEquals(TOKEN, tokenText(a)));
        }
    }

    @Test
    public void recreateKeepsInput() {
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            scenario.recreate();
            onView(withId(R.id.s06_url)).check(matches(withText(URL)));
            scenario.onActivity(a -> assertEquals(TOKEN, tokenText(a)));
        }
    }

    // ---------------------------------------------------------------- edit mode (A-S06-3, A-S06-5, F17)

    @Test
    public void editModeShowsStoredUrlAndMaskedToken() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withText("Nightscout connection")).check(matches(isDisplayed()));
            onView(withId(R.id.s06_url)).check(matches(withText(URL)));
            scenario.onActivity(a -> assertEquals("••••••••", tokenText(a)));
            assertEquals(0, app.connection.count("reveal"));
        }
    }

    @Test
    public void backWithChangesAsksAndKeepEditingKeepsInput() {
        try (ActivityScenario<TestHostActivity> ignored = edit()) {
            onView(withId(R.id.s06_url)).perform(replaceText(URL + "/ns"), closeSoftKeyboard());
            pressBack();
            onView(withText("Discard changes?")).check(matches(isDisplayed()));
            onView(withText("Keep editing")).perform(click());
            onView(withText("Discard changes?")).check(doesNotExist());
            onView(withId(R.id.s06_url)).check(matches(withText(URL + "/ns")));
        }
    }

    @Test
    public void discardLeavesTheScreen() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withId(R.id.s06_url)).perform(replaceText(URL + "/ns"), closeSoftKeyboard());
            pressBack();
            onView(withText("Discard")).perform(click());
            scenario.onActivity(a -> assertEquals(R.id.disclaimerFragment,
                    a.navController().getCurrentDestination().getId()));
        }
    }

    @Test
    public void saveAfterCheckStoresAndFinishesWithResult() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withId(R.id.s06_primary)).check(matches(withText("Save")));
            onView(withId(R.id.s06_primary)).perform(click());
            assertEquals(1, app.connection.count("save"));
            assertEquals(List.of(true), app.connection.saveWithStoredToken);   // untouched → stored token (R-73)
            assertEquals(List.of(UiMessage.CONNECTION_SAVED), TestHostActivity.RESULTS);
            scenario.onActivity(a -> assertEquals(R.id.disclaimerFragment,
                    a.navController().getCurrentDestination().getId()));
        }
    }

    @Test
    public void backWithoutChangesHasNoDialog() {
        try (ActivityScenario<TestHostActivity> scenario = edit()) {
            pressBack();
            onView(withText("Discard changes?")).check(doesNotExist());
            scenario.onActivity(a -> assertEquals(R.id.disclaimerFragment,
                    a.navController().getCurrentDestination().getId()));
        }
    }

    // ---------------------------------------------------------------- TalkBack, 200 %

    @Test
    public void rowsAndToggleHaveSpokenLabels() {
        app.connection.verifyResult = Result.ok(List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                new CheckResult(CheckRow.VERSION, true, null, "15.0.6"),
                new CheckResult(CheckRow.READ, true, null, null),
                new CheckResult(CheckRow.WRITE, false, CheckFailure.NO_WRITE, null)));
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withContentDescription("Nightscout reachable, done")).check(matches(isDisplayed()));
            onView(withContentDescription("Can save entries, failed: This token can't save, edit or delete entries. "
                    + "Give it a role allowed to create, update and delete treatments.")).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                TextInputLayout layout = a.findViewById(R.id.s06_token_layout);
                assertEquals("Show token", String.valueOf(layout.getEndIconContentDescription()));
            });
            onView(withContentDescription("Show token")).perform(click());
            scenario.onActivity(a -> {
                TextInputLayout layout = a.findViewById(R.id.s06_token_layout);
                assertEquals("Hide token", String.valueOf(layout.getEndIconContentDescription()));
            });
        }
    }

    @Test
    public void fontScale200WrapsRowsAndErrors() {
        DeviceShell.run("settings put system font_scale 2.0");
        app.connection.verifyResult = Result.ok(List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                new CheckResult(CheckRow.VERSION, true, null, "15.0.6"),
                new CheckResult(CheckRow.READ, true, null, null),
                new CheckResult(CheckRow.WRITE, false, CheckFailure.NO_WRITE, null)));
        try (ActivityScenario<TestHostActivity> scenario = onboarding()) {
            fill(URL, TOKEN);
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withId(R.id.s06_results)).check(matches(hasDescendant(withText(
                    "This token can't save, edit or delete entries. Give it a role allowed to create, update and "
                            + "delete treatments."))));
            onView(withId(R.id.s06_primary)).check(matches(isDisplayed()));
            scenario.onActivity(a -> {
                android.view.ViewGroup rows = a.findViewById(R.id.s06_results);
                View last = rows.getChildAt(rows.getChildCount() - 1);
                TextView message = last.findViewById(R.id.s06_row_message);
                assertTrue(message.getLineCount() > 1);
                assertEquals(0, message.getLayout().getEllipsisCount(message.getLineCount() - 1));
            });
            onView(withId(R.id.s06_url)).perform(replaceText("http://x.org"));
            onView(withId(R.id.s06_primary)).perform(click());
            onView(withText("Use an https:// address")).check(matches(withEffectiveVisibility(Visibility.VISIBLE)));
        }
    }
}
