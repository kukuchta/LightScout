package app.lightscout.monitor;

import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderNotice;
import app.lightscout.domain.consumable.ReminderStage;

/** Pure S-11 reminder copy selection (CP-09 §7.2, CP-00A R-70/R-81). */
final class ReminderNotificationContent {

    interface Strings {
        String siteDueTomorrow();
        String sensorDueTomorrow();
        String siteDueNow();
        String sensorDueNow();
        String siteDueTomorrowText(String age, int lifetimeDays);
        String sensorDueTomorrowText(String age, int lifetimeDays);
        String siteDueNowText(String age);
        String sensorDueNowText(String age);
    }

    record Content(String title, String text) {
    }

    private ReminderNotificationContent() {
    }

    static Content create(ReminderNotice notice, String age, Strings strings) {
        boolean site = notice.kind() == ConsumableKind.SITE;
        if (notice.stage() == ReminderStage.DUE_TOMORROW) {
            return new Content(site ? strings.siteDueTomorrow() : strings.sensorDueTomorrow(),
                    site ? strings.siteDueTomorrowText(age, notice.lifetimeDays())
                            : strings.sensorDueTomorrowText(age, notice.lifetimeDays()));
        }
        return new Content(site ? strings.siteDueNow() : strings.sensorDueNow(),
                site ? strings.siteDueNowText(age) : strings.sensorDueNowText(age));
    }
}
