package app.lightscout.feature.onboarding;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withParent;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.EnumSet;

/**
 * TST-06 S-08 (CP-14 §9): detected manufacturer preselected; „Open …” hidden when the intent does not resolve;
 * resolves but fails to launch → Snackbar {@code s08_error_open_setting}; Pixel / Other → {@code s08_no_extra_steps};
 * link opens {@code https://dontkillmyapp.com/{slug}}; selection kept on configuration change (GIR-2); TalkBack
 * („Step 2 of 7, …”, heading on the manufacturer). Compiled in SS-12; run on RD-2 (API 33) over USB (CR-04), then
 * {@code adb uninstall} of app and test APKs (SR-34). The real HyperOS pages are VR-05 (RD-1).
 */
@RunWith(AndroidJUnit4.class)
public class ManufacturerGuideScreenDeviceTest {

    private TestOnboardingApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.reset();
    }

    @After
    public void tearDown() {
        DeviceShell.run("settings put system font_scale 1.0");
    }

    private ActivityScenario<TestHostActivity> launch(Manufacturer m) {
        return ActivityScenario.launch(new Intent(app, TestHostActivity.class)
                .putExtra(TestHostActivity.EXTRA_GUIDE, m.name()));
    }

    private static ViewGroup steps(TestHostActivity a) {
        return a.findViewById(R.id.s08_steps);
    }

    @Test
    public void detectedManufacturerIsPreselectedWithItsSteps() {
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            onView(withId(R.id.s08_selector)).check(matches(withText("Xiaomi / Redmi / POCO")));
            scenario.onActivity(a -> assertEquals(7, steps(a).getChildCount()));
            onView(withText("Open Autostart")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void openButtonHiddenWhenTheIntentDoesNotResolve() {
        app.intents.resolvable = EnumSet.of(OemSettingId.XIAOMI_AUTOSTART);
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            scenario.onActivity(a -> {
                int visible = 0;
                for (int i = 0; i < steps(a).getChildCount(); i++) {
                    if (steps(a).getChildAt(i).findViewById(R.id.s08_step_open).getVisibility() == View.VISIBLE) {
                        visible++;
                    }
                }
                assertEquals(1, visible);
                assertEquals(View.VISIBLE, steps(a).getChildAt(0).findViewById(R.id.s08_step_open).getVisibility());
            });
        }
    }

    @Test
    public void openButtonOpensThePage() {
        app.intents.resolvable = EnumSet.of(OemSettingId.APP_DETAILS);
        try (ActivityScenario<TestHostActivity> ignored = launch(Manufacturer.XIAOMI)) {
            onView(allOf(withId(R.id.s08_step_open), withText("Open app settings"),
                    withParent(hasDescendant(allOf(withId(R.id.s08_step_number), withText("2"))))))
                    .perform(scrollTo(), click());
            waitFor(() -> !RecordingSettingsActivity.OPENED.isEmpty());
            assertEquals("oem.APP_DETAILS", RecordingSettingsActivity.OPENED.get(0).getAction());
        }
    }

    @Test
    public void launchFailureShowsSnackbar() {
        app.intents.failing = true;
        try (ActivityScenario<TestHostActivity> ignored = launch(Manufacturer.XIAOMI)) {
            onView(withText("Open Autostart")).perform(scrollTo(), click());
            onView(withText("Couldn't open this setting. Find it manually as described above."))
                    .check(matches(isDisplayed()));
        }
    }

    @Test
    public void pixelAndOtherShowNoExtraSteps() {
        for (Manufacturer m : new Manufacturer[] {Manufacturer.PIXEL, Manufacturer.OTHER}) {
            try (ActivityScenario<TestHostActivity> scenario = launch(m)) {
                onView(withId(R.id.s08_no_extra_steps)).check(matches(
                        withText("Your phone needs no extra steps beyond battery optimisation.")));
                onView(withId(R.id.s08_no_extra_steps)).check(matches(isDisplayed()));
                scenario.onActivity(a -> assertEquals(0, steps(a).getChildCount()));
                onView(withText("More details on dontkillmyapp.com")).check(matches(isDisplayed()));
            }
        }
    }

    @Test
    public void linkOpensDontKillMyAppWithTheSlug() {
        try (ActivityScenario<TestHostActivity> ignored = launch(Manufacturer.XIAOMI)) {
            onView(withId(R.id.s08_more_details)).perform(scrollTo(), click());
            waitFor(() -> !RecordingSettingsActivity.OPENED.isEmpty());
            Intent opened = RecordingSettingsActivity.OPENED.get(0);
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://dontkillmyapp.com/xiaomi", String.valueOf(opened.getData()));
            assertTrue(String.valueOf(opened.getData()).matches("https://dontkillmyapp\\.com/[a-z0-9-]*"));
        }
    }

    @Test
    public void selectionIsKeptOnConfigurationChange() {
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            onView(withId(R.id.s08_selector)).perform(click());
            onView(withText("Samsung")).inRoot(isPlatformPopup()).perform(click());
            onView(withId(R.id.s08_selector)).check(matches(withText("Samsung")));
            scenario.recreate();
            onView(withId(R.id.s08_selector)).check(matches(withText("Samsung")));
            scenario.onActivity(a -> assertEquals(3, steps(a).getChildCount()));
        }
    }

    @Test
    public void dropdownListsAllOptionsAfterRecreate() {   // IR-97
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            scenario.recreate();
            onView(withId(R.id.s08_selector)).check(matches(withText("Xiaomi / Redmi / POCO")));
            scenario.onActivity(a -> {
                android.widget.AutoCompleteTextView field = a.findViewById(R.id.s08_selector);
                assertTrue(!field.isSaveEnabled());
                field.showDropDown();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            for (String option : new String[] {"Xiaomi / Redmi / POCO", "Samsung", "Huawei / Honor",
                "OnePlus / OPPO / realme", "Google Pixel", "Other"}) {
                onView(withText(option)).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            }
            scenario.onActivity(a -> {
                android.widget.AutoCompleteTextView field = a.findViewById(R.id.s08_selector);
                assertEquals(6, field.getAdapter().getCount());
                field.dismissDropDown();
            });
        }
    }

    @Test
    public void dropdownListsAllOptionsAfterProcessDeath() {   // IR-97
        java.util.concurrent.atomic.AtomicReference<android.os.Bundle> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            onView(withId(R.id.s08_selector)).perform(click());
            onView(withText("Samsung")).inRoot(isPlatformPopup()).perform(click());
            scenario.onActivity(a -> {
                android.os.Bundle out = new android.os.Bundle();
                InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(a, out);
                saved.set(out);
            });
        }
        TestHostActivity.restoreNextFrom(saved.get());
        try (ActivityScenario<TestHostActivity> scenario =
                     ActivityScenario.launch(new Intent(app, TestHostActivity.class))) {
            onView(withId(R.id.s08_selector)).check(matches(withText("Samsung")));
            scenario.onActivity(a -> {
                android.widget.AutoCompleteTextView field = a.findViewById(R.id.s08_selector);
                field.showDropDown();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText("Google Pixel")).inRoot(isPlatformPopup()).check(matches(isDisplayed()));
            scenario.onActivity(a -> assertEquals(6,
                    ((android.widget.AutoCompleteTextView) a.findViewById(R.id.s08_selector)).getAdapter().getCount()));
        }
    }

    @Test
    public void talkBackStepsAndHeading() {
        try (ActivityScenario<TestHostActivity> scenario = launch(Manufacturer.XIAOMI)) {
            scenario.onActivity(a -> {
                View second = steps(a).getChildAt(1).findViewById(R.id.s08_step_text_block);
                assertTrue(second.getContentDescription().toString().startsWith("Step 2 of 7, "));
                AccessibilityNodeInfo info = a.findViewById(R.id.s08_selector).createAccessibilityNodeInfo();
                assertTrue("heading on manufacturer", info.isHeading());
            });
            onView(withId(R.id.s08_no_extra_steps)).check(matches(withEffectiveVisibility(Visibility.GONE)));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) {
        long end = android.os.SystemClock.uptimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (android.os.SystemClock.uptimeMillis() > end) {
                throw new AssertionError("condition not met within 5 s");
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            android.os.SystemClock.sleep(50);
        }
    }
}
