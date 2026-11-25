package app.lightscout.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Static audits of the {@code :alarm} sources (working directory = module directory):
 * <ul>
 *   <li>§7.4 copy verbatim (templates per CP-00A R-83) and no strings beyond §7.4 / §5.3;</li>
 *   <li>TST-13 (CP-07 part): {@code AlarmActivity}, {@code SnoozeReceiver}, {@code AlarmDeleteReceiver} not exported,
 *       lock-screen flags, excluded from recents; only the §8.6 permissions; no {@code READ_PHONE_STATE};</li>
 *   <li>„Do not” list: no {@code :monitor} / {@code :widget} / {@code :storage} / {@code :nightscout} imports; no
 *       DND / interruption-filter / zen-rule calls (SR-26); no notification sound; immutable PendingIntents only;
 *       no {@code System.currentTimeMillis}; no Kotlin.</li>
 * </ul>
 */
public class ResourceAndSourceAuditTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static Document xml(String path) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new File(path));
    }

    private static Map<String, String> strings() throws Exception {
        Map<String, String> out = new HashMap<>();
        NodeList list = xml("src/main/res/values/strings.xml").getElementsByTagName("string");
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            out.put(e.getAttribute("name"), e.getTextContent());
        }
        return out;
    }

    @Test
    public void copy_isVerbatim() throws Exception {
        Map<String, String> s = strings();
        assertEquals("Urgent low", s.get("s09_title_urgent_low"));
        assertEquals("Low", s.get("s09_title_low"));
        assertEquals("High", s.get("s09_title_high"));
        assertEquals("No data", s.get("s09_title_no_data"));
        assertEquals("LightScout is not running", s.get("s09_title_not_running"));
        assertEquals("mg/dL", s.get("s09_unit"));
        assertEquals("No new readings for %1$d min", s.get("s09_no_new_readings"));
        assertEquals("No internet connection on this phone", s.get("s09_cause_no_internet"));
        assertEquals("Nightscout is not responding", s.get("s09_cause_ns_not_responding"));
        assertEquals("Nightscout works, but nothing new arrived from the uploader", s.get("s09_cause_uploader"));
        assertEquals("LightScout stopped checking at %1$s", s.get("s09_stopped_checking"));
        assertEquals("Snooze %1$d min", s.get("s09_snooze"));
        assertEquals("Alarm. %1$s, %2$d milligrams per decilitre, %3$s", s.get("s09_open_a11y"));
        assertEquals("Alarm. %1$s, %2$s", s.get("s09_open_a11y_status"));
        assertEquals("%1$s · %2$s mg/dL %3$s", s.get("s11_alarm_title"));
        assertEquals("Snooze or open LightScout", s.get("s11_alarm_text"));
        assertEquals("No data · %1$d min", s.get("s11_no_data_title"));
        assertEquals("LightScout is not running", s.get("s11_watchdog_title"));
        assertEquals("Last check %1$s. Open the app to restart monitoring.", s.get("s11_watchdog_text"));
        assertEquals("Snooze %1$d min", s.get("s11_snooze_action"));
    }

    @Test
    public void examples_renderToTheSourceExamples() throws Exception {
        Map<String, String> s = strings();
        assertEquals("Urgent low · 52 mg/dL ↓", fmt(s.get("s11_alarm_title"), "Urgent low", "52", "↓"));
        assertEquals("Low · 66 mg/dL ↘", fmt(s.get("s11_alarm_title"), "Low", "66", "↘"));
        assertEquals("High · 262 mg/dL ↗", fmt(s.get("s11_alarm_title"), "High", "262", "↗"));
        assertEquals("No data · 23 min", fmt(s.get("s11_no_data_title"), 23));
        assertEquals("Last check 02:14. Open the app to restart monitoring.", fmt(s.get("s11_watchdog_text"), "02:14"));
        assertEquals("No new readings for 23 min", fmt(s.get("s09_no_new_readings"), 23));
        assertEquals("LightScout stopped checking at 02:14", fmt(s.get("s09_stopped_checking"), "02:14"));
        assertEquals("Snooze 15 min", fmt(s.get("s09_snooze"), 15));
        assertEquals("Alarm. Urgent low, 52 milligrams per decilitre, falling",
                fmt(s.get("s09_open_a11y"), "Urgent low", 52, "falling"));
    }

    private static String fmt(String template, Object... args) {
        return String.format(java.util.Locale.ROOT, template, args);
    }

    @Test
    public void noStringsBeyondTheSlice() throws Exception {
        Set<String> allowed = Set.of("s09_title_urgent_low", "s09_title_low", "s09_title_high", "s09_title_no_data",
                "s09_title_not_running", "s09_unit", "s09_no_new_readings", "s09_cause_no_internet",
                "s09_cause_ns_not_responding", "s09_cause_uploader", "s09_stopped_checking", "s09_snooze",
                "s09_open_a11y", "s09_open_a11y_status", "s11_alarm_title", "s11_alarm_text", "s11_no_data_title",
                "s11_watchdog_title", "s11_watchdog_text", "s11_snooze_action");
        assertEquals(allowed, strings().keySet());
        assertFalse(strings().containsValue("Dismiss"));
    }

    @Test
    public void manifest_componentsNotExported_lockScreenFlags_permissions() throws Exception {
        Document m = xml("src/main/AndroidManifest.xml");
        Element activity = (Element) m.getElementsByTagName("activity").item(0);
        assertEquals("app.lightscout.alarm.AlarmActivity", activity.getAttributeNS(ANDROID_NS, "name"));
        assertEquals("false", activity.getAttributeNS(ANDROID_NS, "exported"));
        assertEquals("true", activity.getAttributeNS(ANDROID_NS, "showWhenLocked"));
        assertEquals("true", activity.getAttributeNS(ANDROID_NS, "turnScreenOn"));
        assertEquals("true", activity.getAttributeNS(ANDROID_NS, "excludeFromRecents"));
        assertEquals("portrait", activity.getAttributeNS(ANDROID_NS, "screenOrientation"));
        assertEquals(0, activity.getElementsByTagName("intent-filter").getLength());

        NodeList receivers = m.getElementsByTagName("receiver");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < receivers.getLength(); i++) {
            Element r = (Element) receivers.item(i);
            names.add(r.getAttributeNS(ANDROID_NS, "name"));
            assertEquals("false", r.getAttributeNS(ANDROID_NS, "exported"));
            assertEquals(0, r.getElementsByTagName("intent-filter").getLength());
        }
        assertEquals(Set.of("app.lightscout.alarm.SnoozeReceiver", "app.lightscout.alarm.AlarmDeleteReceiver"),
                names);

        NodeList perms = m.getElementsByTagName("uses-permission");
        Set<String> p = new HashSet<>();
        for (int i = 0; i < perms.getLength(); i++) {
            p.add(((Element) perms.item(i)).getAttributeNS(ANDROID_NS, "name"));
        }
        assertEquals(Set.of("android.permission.POST_NOTIFICATIONS", "android.permission.USE_FULL_SCREEN_INTENT",
                "android.permission.ACCESS_NOTIFICATION_POLICY", "android.permission.VIBRATE",
                "android.permission.WAKE_LOCK"), p);
        assertEquals(0, m.getElementsByTagName("service").getLength());
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            return s.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    public void doNotList_staticScan() throws IOException {
        for (Path p : mainSources()) {
            String name = p.getFileName().toString();
            assertFalse("Kotlin source " + p, name.endsWith(".kt"));
            if (!name.endsWith(".java")) {
                continue;
            }
            String src = read(p);
            for (String forbidden : new String[] {"app.lightscout.monitor", "app.lightscout.widget",
                    "app.lightscout.storage", "app.lightscout.nightscout", "setInterruptionFilter",
                    "setNotificationPolicy", "AutomaticZenRule", "permission.READ_PHONE_STATE", "System.currentTimeMillis",
                    "setSound(", "setDefaults(", "FLAG_MUTABLE", "MonitorService.class", "startForegroundService",
                    "kotlin."}) {
                assertFalse(forbidden + " in " + p, src.contains(forbidden));
            }
            if (src.contains("PendingIntent.get")) {
                assertTrue("immutable PendingIntent in " + p, src.contains("FLAG_IMMUTABLE"));
            }
        }
    }

    @Test
    public void glucoseValuesNeverLogged() throws IOException {
        for (Path p : mainSources()) {
            if (!p.toString().endsWith(".java")) {
                continue;
            }
            for (String line : read(p).split("\n")) {
                if (line.contains("logger.event(")) {
                    assertFalse("value in log: " + line, line.contains("sgv"));
                    assertFalse("view in log: " + line, line.contains("view"));
                }
            }
        }
    }
}
