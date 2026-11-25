package app.lightscout.monitor;

import static org.junit.Assert.assertEquals;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderStage;

import org.junit.Test;

import java.time.Duration;

public class ReminderNotificationContentTest {

    @Test
    public void rendersSiteAndSensorForBothReminderStages() {
        assertEquals(new ReminderNotificationContent.Content("Site change due tomorrow",
                        "Site age 2 d 0 h · lifetime 3 days"),
                ReminderNotificationContent.create(notice(ConsumableKind.SITE, ReminderStage.DUE_TOMORROW),
                        "2 d 0 h", new TestStrings()));
        assertEquals(new ReminderNotificationContent.Content("Sensor change due tomorrow",
                        "Sensor age 2 d 0 h · lifetime 10 days"),
                ReminderNotificationContent.create(notice(ConsumableKind.SENSOR, ReminderStage.DUE_TOMORROW),
                        "2 d 0 h", new TestStrings()));
        assertEquals(new ReminderNotificationContent.Content("Site change due now", "Site age 3 d 0 h"),
                ReminderNotificationContent.create(notice(ConsumableKind.SITE, ReminderStage.DUE_NOW),
                        "3 d 0 h", new TestStrings()));
        assertEquals(new ReminderNotificationContent.Content("Sensor change due now", "Sensor age 10 d 0 h"),
                ReminderNotificationContent.create(notice(ConsumableKind.SENSOR, ReminderStage.DUE_NOW),
                        "10 d 0 h", new TestStrings()));
    }

    private static ReminderNotice notice(ConsumableKind kind, ReminderStage stage) {
        return new ReminderNotice(kind, stage, "entry-1", Duration.ofDays(kind == ConsumableKind.SITE ? 2 : 10),
                kind == ConsumableKind.SITE ? 3 : 10);
    }

    private static final class TestStrings implements ReminderNotificationContent.Strings {
        @Override public String siteDueTomorrow() { return "Site change due tomorrow"; }
        @Override public String sensorDueTomorrow() { return "Sensor change due tomorrow"; }
        @Override public String siteDueNow() { return "Site change due now"; }
        @Override public String sensorDueNow() { return "Sensor change due now"; }
        @Override public String siteDueTomorrowText(String age, int lifetime) {
            return "Site age " + age + " · lifetime " + lifetime + " days";
        }
        @Override public String sensorDueTomorrowText(String age, int lifetime) {
            return "Sensor age " + age + " · lifetime " + lifetime + " days";
        }
        @Override public String siteDueNowText(String age) { return "Site age " + age; }
        @Override public String sensorDueNowText(String age) { return "Sensor age " + age; }
    }
}
