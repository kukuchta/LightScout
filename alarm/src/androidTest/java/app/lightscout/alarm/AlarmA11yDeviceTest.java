package app.lightscout.alarm;

import static app.lightscout.alarm.AlarmDeviceSupport.app;
import static app.lightscout.alarm.AlarmDeviceSupport.context;
import static app.lightscout.alarm.AlarmDeviceSupport.glucose;
import static app.lightscout.alarm.AlarmDeviceSupport.grantNotifications;
import static app.lightscout.alarm.AlarmDeviceSupport.noData;
import static app.lightscout.alarm.AlarmDeviceSupport.ring;
import static app.lightscout.alarm.AlarmDeviceSupport.shell;
import static app.lightscout.alarm.AlarmDeviceSupport.stopAll;
import static app.lightscout.alarm.AlarmDeviceSupport.wakeScreen;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.dataloss.DataLossCause;
import app.lightscout.domain.model.Trend;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * TST-07 / TST-06 accessibility and theme of S-09 (CP-07 §9; GIR-7, DS:D-061): TalkBack announcement text (window
 * title / pane title), Snooze first focus, 200 % font scale, dark 22:00–07:00 with the system light theme, GIR-10
 * title text present (no colour-only meaning). Target: RD-2 (API 33) over USB (CR-04). The spoken output of TalkBack
 * itself is observed in VR-17 notes.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class AlarmA11yDeviceTest {

    @Before
    public void setUp() {
        grantNotifications();
        wakeScreen();
        app().clock.offset = Duration.ZERO;
        app().clock.zone = ZoneId.systemDefault();
    }

    @After
    public void tearDown() {
        stopAll();
        shell("settings put system font_scale 1.0");
        shell("cmd uimode night no");
        app().clock.offset = Duration.ZERO;
    }

    private ActivityScenario<AlarmActivity> launch(AlarmType type) {
        return ActivityScenario.launch(AlarmIntents.screen(context(), type));
    }

    @Test
    public void announcement_andSnoozeFirstFocus() {
        ring(glucose(AlarmType.URGENT_LOW, 52, Trend.SINGLE_DOWN));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.URGENT_LOW)) {
            s.onActivity(a -> {
                assertEquals("Alarm. Urgent low, 52 milligrams per decilitre, falling", a.getTitle().toString());
                assertEquals("Alarm. Urgent low, 52 milligrams per decilitre, falling",
                        a.findViewById(R.id.alarm_root).getAccessibilityPaneTitle().toString());
                assertEquals("Urgent low", ((TextView) a.findViewById(R.id.alarm_title)).getText().toString());
            });
            android.view.accessibility.AccessibilityManager am =
                    context().getSystemService(android.view.accessibility.AccessibilityManager.class);
            if (am.isTouchExplorationEnabled()) {
                // TalkBack on (VR-17 precondition): Snooze gets the first accessibility focus
                AlarmDeviceSupport.waitUntil(() -> {
                    boolean[] focused = new boolean[1];
                    s.onActivity(a -> focused[0] = a.findViewById(R.id.alarm_snooze).isAccessibilityFocused());
                    return focused[0];
                }, 3_000);
                s.onActivity(a -> assertTrue(a.findViewById(R.id.alarm_snooze).isAccessibilityFocused()));
            }
        }
    }

    @Test
    public void noDataAnnouncement_titleAndAgeAndCause() {
        ring(noData(DataLossCause.NO_INTERNET));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.NO_DATA)) {
            s.onActivity(a -> assertEquals(
                    "Alarm. No data, No new readings for 23 min, No internet connection on this phone",
                    a.getTitle().toString()));
        }
    }

    @Test
    public void fontScale200_snoozeFullyVisible_atLeast96dp() {
        shell("settings put system font_scale 2.0");
        ring(noData(DataLossCause.UPLOADER));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.NO_DATA)) {
            s.onActivity(a -> {
                View snooze = a.findViewById(R.id.alarm_snooze);
                Rect visible = new Rect();
                assertTrue(snooze.getGlobalVisibleRect(visible));
                assertEquals(snooze.getHeight(), visible.height());
                float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 96,
                        a.getResources().getDisplayMetrics());
                assertTrue(snooze.getHeight() >= Math.round(min));
            });
        }
    }

    @Test
    public void darkWindow_withSystemLightTheme_usesDark() {
        shell("cmd uimode night no");
        app().clock.offset = offsetTo(LocalTime.of(23, 0));
        ring(glucose(AlarmType.LOW, 66, Trend.FLAT));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.LOW)) {
            s.onActivity(a -> assertEquals(Configuration.UI_MODE_NIGHT_YES,
                    a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
        }
    }

    @Test
    public void dayTime_followsSystemLightTheme() {
        shell("cmd uimode night no");
        app().clock.offset = offsetTo(LocalTime.of(12, 0));
        ring(glucose(AlarmType.LOW, 66, Trend.FLAT));
        try (ActivityScenario<AlarmActivity> s = launch(AlarmType.LOW)) {
            s.onActivity(a -> assertEquals(Configuration.UI_MODE_NIGHT_NO,
                    a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
        }
    }

    private static Duration offsetTo(LocalTime target) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime t = now.with(target);
        return Duration.between(now, t);
    }
}
