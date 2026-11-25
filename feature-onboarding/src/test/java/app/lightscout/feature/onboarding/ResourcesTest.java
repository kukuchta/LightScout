package app.lightscout.feature.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.onboarding.connection.ConnectionCopy;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Helper;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Primary;
import app.lightscout.feature.onboarding.readiness.ReadinessCopy;
import app.lightscout.feature.onboarding.readiness.ReadinessUiState.Action;
import app.lightscout.platform.ReadinessItemId;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.connection.ConnectionInput.InputError;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import app.lightscout.feature.onboarding.guide.GuideCopy;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Copy and navigation resources of CP-14 (a + b): every S-05…S-08 key with the verbatim §17 text (CP-14 §7.7, CP-00A
 * §5.3), the R-51 mapping to those keys, and the nested graph contract (CP-14 §4.1, CP-00A R-38: destinations,
 * Safe Args types, no token argument — SR-28). Runs on the JVM with the module directory as working directory.
 */
public class ResourcesTest {

    private static final File RES = new File("src/main/res");

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("s05_title", "Before you start");
        EXPECTED.put("s05_bullet_1", "LightScout is not a medical device. It shows data from your Nightscout and does "
                + "not replace your diabetes team's advice.");
        EXPECTED.put("s05_bullet_2", "LightScout alarms are a second layer of protection. Keep the alarms on the pump "
                + "and the sensor turned on.");
        EXPECTED.put("s05_bullet_3", "LightScout never delivers insulin. A bolus you log is only a note in Nightscout.");
        EXPECTED.put("s05_bullet_4", "Readings can be late or missing when the phone, the internet, Nightscout or the "
                + "uploader has a problem.");
        EXPECTED.put("s05_bullet_5", "You use LightScout at your own risk.");
        EXPECTED.put("s05_acknowledge", "I have read and understand");
        EXPECTED.put("s05_continue", "Continue");
        EXPECTED.put("s05_helper_tick", "Tick the box to continue");
        EXPECTED.put("s05_continue_disabled_a11y", "Continue, disabled. Tick the box to continue");
        EXPECTED.put("s06_title_onboarding", "Connect to Nightscout");
        EXPECTED.put("s06_title_edit", "Nightscout connection");
        EXPECTED.put("s06_url_label", "Nightscout address");
        EXPECTED.put("s06_url_placeholder", "https://");
        EXPECTED.put("s06_token_label", "Access token");
        EXPECTED.put("s06_token_helper", "Create a token in Nightscout (Admin tools → Subjects) with the readable role "
                + "and a role allowed to create, update and delete treatments.");
        EXPECTED.put("s06_token_masked", "••••••••");
        EXPECTED.put("s06_token_show_cd", "Show token");
        EXPECTED.put("s06_token_hide_cd", "Hide token");
        EXPECTED.put("s06_token_reenter", "For security, enter the token again");
        EXPECTED.put("s06_check_reachable", "Nightscout reachable");
        EXPECTED.put("s06_check_version", "Version %1$s");
        EXPECTED.put("s06_check_read", "Can read data");
        EXPECTED.put("s06_check_write", "Can save entries");
        EXPECTED.put("s06_check_done_a11y", "%1$s, done");
        EXPECTED.put("s06_check_failed_a11y", "%1$s, failed: %2$s");
        EXPECTED.put("s06_connect", "Connect");
        EXPECTED.put("s06_continue", "Continue");
        EXPECTED.put("s06_save", "Save");
        EXPECTED.put("s06_checking", "Checking…");
        EXPECTED.put("s06_discard_title", "Discard changes?");
        EXPECTED.put("s06_discard_keep_editing", "Keep editing");
        EXPECTED.put("s06_discard_confirm", "Discard");
        EXPECTED.put("s06_helper_empty", "Enter the address and token to connect");
        EXPECTED.put("s06_offline", "No internet connection — connect when you're online.");
        EXPECTED.put("s06_error_url_required", "Enter your Nightscout address");
        EXPECTED.put("s06_error_url_https", "Use an https:// address");
        EXPECTED.put("s06_error_url_invalid", "This doesn't look like a web address");
        EXPECTED.put("s06_error_token_required", "Enter the access token");
        EXPECTED.put("s06_error_unreachable", "Can't reach this address. Check it and your internet connection.");
        EXPECTED.put("s06_error_not_nightscout", "This address doesn't look like Nightscout.");
        EXPECTED.put("s06_error_version", "Nightscout %1$s is too old. Version 15.0.9 or newer is needed.");
        EXPECTED.put("s06_error_token_rejected", "Nightscout rejected this token.");
        EXPECTED.put("s06_error_no_read", "This token can't read data. Give it the readable role in Nightscout.");
        EXPECTED.put("s06_error_no_write", "This token can't save, edit or delete entries. Give it a role allowed to "
                + "create, update and delete treatments.");
        EXPECTED.put("s07_title", "Alarm readiness");
        EXPECTED.put("s07_item_notifications", "Notifications");
        EXPECTED.put("s07_item_notifications_rationale", "Needed to show alarms and the current reading.");
        EXPECTED.put("s07_item_dnd", "Alarms during Do Not Disturb");
        EXPECTED.put("s07_item_dnd_rationale", "Lets urgent alarms ring even when Do Not Disturb is on.");
        EXPECTED.put("s07_item_full_screen", "Full-screen alarms");
        EXPECTED.put("s07_item_full_screen_rationale",
                "Shows the alarm over the lock screen so you can snooze without unlocking.");
        EXPECTED.put("s07_item_battery", "Battery optimisation");
        EXPECTED.put("s07_item_battery_rationale", "Stops Android from pausing LightScout in the background.");
        EXPECTED.put("s07_item_exact_alarm", "Alarms & reminders");
        EXPECTED.put("s07_item_exact_alarm_rationale", "Lets LightScout check itself on time, even at night.");
        EXPECTED.put("s07_item_manufacturer", "Manufacturer settings");
        EXPECTED.put("s07_item_manufacturer_rationale",
                "Some phones stop background apps anyway. Follow the steps for your phone.");
        EXPECTED.put("s07_item_alarm_channel", "Alarm notifications");
        EXPECTED.put("s07_item_alarm_channel_rationale", "Lets alarms make sound and show on the lock screen.");
        EXPECTED.put("s07_action_allow", "Allow");
        EXPECTED.put("s07_action_open_settings", "Open settings");
        EXPECTED.put("s07_action_open_guide", "Open guide");
        EXPECTED.put("s07_status_done", "Done");
        EXPECTED.put("s07_status_needs_attention", "Needs attention");
        EXPECTED.put("s07_permanently_denied", "Turned off in system settings");
        EXPECTED.put("s07_error_open_setting", "Couldn't open this setting. Open Settings → Apps → LightScout.");
        EXPECTED.put("s07_all_set", "All set — alarms can ring.");
        EXPECTED.put("s07_done", "Done");
        EXPECTED.put("s07_missing_dialog_title", "Some settings are missing");
        EXPECTED.put("s07_missing_dialog_body", "Alarms may not ring. Continue anyway?");
        EXPECTED.put("s07_missing_dialog_back", "Go back");
        EXPECTED.put("s07_missing_dialog_continue", "Continue");
        EXPECTED.put("s07_item_a11y", "%1$s, %2$s, %3$s");
        EXPECTED.put("s08_title", "Manufacturer settings");
        EXPECTED.put("s08_selector_label", "Phone manufacturer");
        EXPECTED.put("s08_mfr_xiaomi", "Xiaomi / Redmi / POCO");
        EXPECTED.put("s08_mfr_samsung", "Samsung");
        EXPECTED.put("s08_mfr_huawei", "Huawei / Honor");
        EXPECTED.put("s08_mfr_oneplus", "OnePlus / OPPO / realme");
        EXPECTED.put("s08_mfr_pixel", "Google Pixel");
        EXPECTED.put("s08_mfr_other", "Other");
        EXPECTED.put("s08_step_open", "Open %1$s");
        EXPECTED.put("s08_more_details", "More details on dontkillmyapp.com");
        EXPECTED.put("s08_no_extra_steps", "Your phone needs no extra steps beyond battery optimisation.");
        EXPECTED.put("s08_error_open_setting", "Couldn't open this setting. Find it manually as described above.");
        EXPECTED.put("s08_step_a11y", "Step %1$d of %2$d, %3$s");
    }

    private static Document parse(String path) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            return f.newDocumentBuilder().parse(new File(RES, path));
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + path, e);
        }
    }

    private static Map<String, String> strings() {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = parse("values/strings.xml").getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.put(e.getAttribute("name"), e.getTextContent().replace("\\'", "'"));
        }
        return out;
    }

    @Test
    public void copyIsVerbatim() {
        Map<String, String> actual = strings();
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            assertEquals(e.getKey(), e.getValue(), actual.get(e.getKey()));
        }
        assertEquals("only the CP-14 §7.7 / CP-00A keys", EXPECTED.keySet(), actual.keySet());
    }

    @Test
    public void versionCopyFormatsLikeTheSlice() {
        Map<String, String> s = strings();
        assertEquals("Nightscout 14.2.0 is too old. Version 15.0.9 or newer is needed.",
                String.format(s.get("s06_error_version"), "14.2.0"));
        assertEquals("Version 15.0.8", String.format(s.get("s06_check_version"), "15.0.8"));
        assertEquals("Nightscout reachable, done", String.format(s.get("s06_check_done_a11y"), "Nightscout reachable"));
        assertEquals("Can save entries, failed: x", String.format(s.get("s06_check_failed_a11y"), "Can save entries", "x"));
    }

    private static Element view(String layoutPath, String id) {
        NodeList nodes = parse(layoutPath).getElementsByTagName("*");
        String resourceId = "@+id/" + id;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (resourceId.equals(element.getAttribute("android:id"))) {
                return element;
            }
        }
        throw new AssertionError("missing view " + id + " in " + layoutPath);
    }

    @Test
    public void glassOnboardingTypographyAndActionsRemainCompactAndReachable() {
        for (String id : new String[] {"s06_url", "s06_token"}) {
            assertEquals(id, "@style/TextAppearance.LightScout.Glass.BodyMedium",
                    view("layout/fragment_connection.xml", id).getAttribute("android:textAppearance"));
        }
        assertEquals("@style/TextAppearance.LightScout.Glass.BodyMedium",
                view("layout/fragment_manufacturer_guide.xml", "s08_selector")
                        .getAttribute("android:textAppearance"));
        Element dropdownOption = view("layout/item_s08_dropdown.xml", "s08_option");
        assertEquals("@style/TextAppearance.LightScout.Glass.BodyMedium",
                dropdownOption.getAttribute("android:textAppearance"));
        assertEquals("48dp", dropdownOption.getAttribute("android:minHeight"));

        String[][] actions = {
            {"layout/fragment_disclaimer.xml", "s05_continue", "end"},
            {"layout/fragment_connection.xml", "s06_primary", "end"},
            {"layout/fragment_readiness.xml", "s07_done", "end"},
            {"layout/item_s07_readiness.xml", "s07_item_action", "end"},
            {"layout/item_s08_step.xml", "s08_step_open", "end"},
            {"layout/fragment_manufacturer_guide.xml", "s08_more_details", "end"}
        };
        for (String[] action : actions) {
            Element button = view(action[0], action[1]);
            assertEquals(action[1], "wrap_content", button.getAttribute("android:layout_width"));
            assertEquals(action[1], action[2], button.getAttribute("android:layout_gravity"));
            assertEquals(action[1], "48dp", button.getAttribute("android:minHeight"));
        }
    }

    @Test
    public void noBannedTerms() {
        for (String text : strings().values()) {
            for (String banned : new String[] {"NS ", "SGV", "treatment ", "mute", "dismiss"}) {
                assertFalse(text, text.contains(banned));
            }
        }
    }

    @Test
    public void r51Mapping() {
        assertEquals(R.string.s06_error_unreachable, ConnectionCopy.failureMessage(CheckFailure.UNREACHABLE));
        assertEquals(R.string.s06_error_not_nightscout, ConnectionCopy.failureMessage(CheckFailure.NOT_NIGHTSCOUT));
        assertEquals(R.string.s06_error_version, ConnectionCopy.failureMessage(CheckFailure.VERSION_TOO_OLD));
        assertEquals(R.string.s06_error_token_rejected, ConnectionCopy.failureMessage(CheckFailure.TOKEN_REJECTED));
        assertEquals(R.string.s06_error_no_read, ConnectionCopy.failureMessage(CheckFailure.NO_READ));
        assertEquals(R.string.s06_error_no_write, ConnectionCopy.failureMessage(CheckFailure.NO_WRITE));
        assertEquals(R.string.s06_check_reachable, ConnectionCopy.rowLabel(CheckRow.REACHABLE));
        assertEquals(R.string.s06_check_version, ConnectionCopy.rowLabel(CheckRow.VERSION));
        assertEquals(R.string.s06_check_read, ConnectionCopy.rowLabel(CheckRow.READ));
        assertEquals(R.string.s06_check_write, ConnectionCopy.rowLabel(CheckRow.WRITE));
        assertEquals(R.string.s06_error_url_required, ConnectionCopy.inputError(InputError.URL_REQUIRED));
        assertEquals(R.string.s06_error_url_https, ConnectionCopy.inputError(InputError.URL_HTTPS));
        assertEquals(R.string.s06_error_url_invalid, ConnectionCopy.inputError(InputError.URL_INVALID));
        assertEquals(R.string.s06_error_token_required, ConnectionCopy.inputError(InputError.TOKEN_REQUIRED));
        assertEquals(R.string.s06_connect, ConnectionCopy.primary(Primary.CONNECT));
        assertEquals(R.string.s06_continue, ConnectionCopy.primary(Primary.CONTINUE));
        assertEquals(R.string.s06_save, ConnectionCopy.primary(Primary.SAVE));
        assertEquals(R.string.s06_helper_empty, ConnectionCopy.helper(Helper.EMPTY_FIELDS));
        assertEquals(R.string.s06_offline, ConnectionCopy.helper(Helper.OFFLINE));
        assertEquals(0, ConnectionCopy.helper(Helper.NONE));
        assertEquals(R.string.s06_title_onboarding, ConnectionCopy.title(ConnectionMode.ONBOARDING));
        assertEquals(R.string.s06_title_edit, ConnectionCopy.title(ConnectionMode.EDIT));
    }

    @Test
    public void s07ItemTemplateReadsStatusFirst() {
        Map<String, String> s = strings();
        assertEquals("Notifications, Needs attention, Needed to show alarms and the current reading.",
                String.format(s.get("s07_item_a11y"), s.get("s07_item_notifications"),
                        s.get("s07_status_needs_attention"), s.get("s07_item_notifications_rationale")));
    }

    @Test
    public void s08TemplatesFormatLikeTheSlice() {
        Map<String, String> s = strings();
        assertEquals("Step 2 of 4, Turn on Autostart.", String.format(s.get("s08_step_a11y"), 2, 4, "Turn on Autostart."));
        assertEquals("Open app settings", String.format(s.get("s08_step_open"), guideStrings().get("s08_setting_app_details")));
    }

    @Test
    public void guideArraysAreParallelAndUseKnownSettings() {
        for (String option : new String[] {"xiaomi", "samsung", "huawei", "oneplus"}) {
            List<String> texts = array("s08_steps_" + option);
            List<String> open = array("s08_steps_" + option + "_open");
            assertFalse(option, texts.isEmpty());
            assertEquals(option, texts.size(), open.size());
            for (String t : texts) {
                assertFalse(option, t.isBlank());
                assertTrue(option + ": " + t, t.length() <= 200);
            }
            for (String o : open) {
                assertTrue(option + ": " + o, o.isEmpty() || EnumSet.allOf(OemSettingId.class).stream()
                        .anyMatch(id -> id.name().equals(o)));
            }
        }
        assertEquals("translatable=false", "false", arrayElement("s08_steps_xiaomi_open").getAttribute("translatable"));
    }

    @Test
    public void xiaomiGuideHasTheSevenRequiredStepsInOrder() {
        List<String> texts = array("s08_steps_xiaomi");
        List<String> open = array("s08_steps_xiaomi_open");
        assertEquals(7, texts.size());
        assertTrue(texts.get(0).contains("Autostart"));
        assertEquals("In LightScout's app settings, open Power (Zasilanie), then choose No restrictions under Battery saver.",
                texts.get(1));
        assertTrue(texts.get(2).contains("recent apps") && texts.get(2).contains("lock"));
        assertTrue(texts.get(3).contains("Show on lock screen"));
        assertTrue(texts.get(4).contains("Display pop-up windows while running in background"));
        assertTrue(texts.get(5).contains("Floating notifications"));
        assertTrue(texts.get(6).contains("Lock screen notifications"));
        // IR-99: the whole Open-button mapping is pinned.
        assertEquals(List.of("XIAOMI_AUTOSTART", "APP_DETAILS", "", "APP_DETAILS", "APP_DETAILS",
                "APP_DETAILS", "APP_DETAILS"), open);
    }

    @Test
    public void pixelAndOtherHaveNoGuideArrays() {
        assertTrue(array("s08_steps_pixel").isEmpty());
        assertTrue(array("s08_steps_google").isEmpty());
        assertTrue(array("s08_steps_other").isEmpty());
        assertEquals(0, GuideCopy.steps(Manufacturer.PIXEL));
        assertEquals(0, GuideCopy.steps(Manufacturer.OTHER));
    }

    @Test
    public void guideCopyMapping() {
        assertEquals(R.string.s08_mfr_xiaomi, GuideCopy.label(Manufacturer.XIAOMI));
        assertEquals(R.string.s08_mfr_samsung, GuideCopy.label(Manufacturer.SAMSUNG));
        assertEquals(R.string.s08_mfr_huawei, GuideCopy.label(Manufacturer.HUAWEI));
        assertEquals(R.string.s08_mfr_oneplus, GuideCopy.label(Manufacturer.ONEPLUS));
        assertEquals(R.string.s08_mfr_pixel, GuideCopy.label(Manufacturer.PIXEL));
        assertEquals(R.string.s08_mfr_other, GuideCopy.label(Manufacturer.OTHER));
        assertEquals(R.array.s08_steps_xiaomi, GuideCopy.steps(Manufacturer.XIAOMI));
        assertEquals(R.array.s08_steps_samsung, GuideCopy.steps(Manufacturer.SAMSUNG));
        assertEquals(R.array.s08_steps_huawei, GuideCopy.steps(Manufacturer.HUAWEI));
        assertEquals(R.array.s08_steps_oneplus, GuideCopy.steps(Manufacturer.ONEPLUS));
        assertEquals(R.array.s08_steps_xiaomi_open, GuideCopy.settings(Manufacturer.XIAOMI));
        assertEquals(R.array.s08_steps_samsung_open, GuideCopy.settings(Manufacturer.SAMSUNG));
        assertEquals(R.array.s08_steps_huawei_open, GuideCopy.settings(Manufacturer.HUAWEI));
        assertEquals(R.array.s08_steps_oneplus_open, GuideCopy.settings(Manufacturer.ONEPLUS));
        assertEquals(R.string.s08_setting_autostart, GuideCopy.settingName(OemSettingId.XIAOMI_AUTOSTART));
        assertEquals(R.string.s08_setting_battery_saver, GuideCopy.settingName(OemSettingId.XIAOMI_BATTERY_SAVER));
        assertEquals(R.string.s08_setting_app_details, GuideCopy.settingName(OemSettingId.APP_DETAILS));
        Map<String, String> g = guideStrings();
        assertEquals(Set.of("s08_setting_autostart", "s08_setting_battery_saver", "s08_setting_app_details"),
                g.keySet());
    }

    private static Map<String, String> guideStrings() {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = parse("values/strings_s08_guides.xml").getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.put(e.getAttribute("name"), e.getTextContent().replace("\\'", "'"));
        }
        return out;
    }

    private static Element arrayElement(String name) {
        NodeList arrays = parse("values/strings_s08_guides.xml").getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element a = (Element) arrays.item(i);
            if (name.equals(a.getAttribute("name"))) {
                return a;
            }
        }
        return null;
    }

    private static List<String> array(String name) {
        Element a = arrayElement(name);
        List<String> out = new ArrayList<>();
        if (a == null) {
            return out;
        }
        NodeList items = a.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            out.add(items.item(i).getTextContent().replace("\\'", "'").trim());
        }
        return out;
    }

    @Test
    public void moduleManifestAddsNoPackageVisibilityOrPermissions() throws java.io.IOException {
        // TST-12 / TST-13 (module part): the IF-56 <queries> live in the :platform manifest (R-85); this module adds
        // no QUERY_ALL_PACKAGES, no <queries> and no permission (SR-27). The merged-manifest audit is CP-15's.
        File manifest = new File("src/main/AndroidManifest.xml");
        if (manifest.exists()) {
            String text = new String(java.nio.file.Files.readAllBytes(manifest.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(text.contains("QUERY_ALL_PACKAGES"));
            assertFalse(text.contains("<queries"));
            assertFalse(text.contains("uses-permission"));
        }
    }

    @Test
    public void readinessCopyMapping() {
        assertEquals(R.string.s07_item_notifications, ReadinessCopy.headline(ReadinessItemId.NOTIFICATIONS));
        assertEquals(R.string.s07_item_dnd, ReadinessCopy.headline(ReadinessItemId.DND_ACCESS));
        assertEquals(R.string.s07_item_full_screen, ReadinessCopy.headline(ReadinessItemId.FULL_SCREEN));
        assertEquals(R.string.s07_item_battery, ReadinessCopy.headline(ReadinessItemId.BATTERY));
        assertEquals(R.string.s07_item_exact_alarm, ReadinessCopy.headline(ReadinessItemId.EXACT_ALARM));
        assertEquals(R.string.s07_item_manufacturer, ReadinessCopy.headline(ReadinessItemId.MANUFACTURER));
        assertEquals(R.string.s07_item_alarm_channel, ReadinessCopy.headline(ReadinessItemId.ALARM_CHANNEL));
        assertEquals(R.string.s07_item_notifications_rationale,
                ReadinessCopy.rationale(ReadinessItemId.NOTIFICATIONS));
        assertEquals(R.string.s07_item_dnd_rationale, ReadinessCopy.rationale(ReadinessItemId.DND_ACCESS));
        assertEquals(R.string.s07_item_full_screen_rationale, ReadinessCopy.rationale(ReadinessItemId.FULL_SCREEN));
        assertEquals(R.string.s07_item_battery_rationale, ReadinessCopy.rationale(ReadinessItemId.BATTERY));
        assertEquals(R.string.s07_item_exact_alarm_rationale, ReadinessCopy.rationale(ReadinessItemId.EXACT_ALARM));
        assertEquals(R.string.s07_item_manufacturer_rationale,
                ReadinessCopy.rationale(ReadinessItemId.MANUFACTURER));
        assertEquals(R.string.s07_item_alarm_channel_rationale,
                ReadinessCopy.rationale(ReadinessItemId.ALARM_CHANNEL));
        assertEquals(R.string.s07_action_allow, ReadinessCopy.action(Action.ALLOW));
        assertEquals(R.string.s07_action_open_settings, ReadinessCopy.action(Action.OPEN_SETTINGS));
        assertEquals(R.string.s07_action_open_guide, ReadinessCopy.action(Action.OPEN_GUIDE));
        assertEquals(0, ReadinessCopy.action(Action.NONE));
    }

    @Test
    public void graphDeclaresTheFourDestinationsWithTheirArguments() {
        Document g = parse("navigation/onboarding_graph.xml");
        Element root = g.getDocumentElement();
        assertEquals("@+id/onboarding_graph", root.getAttribute("android:id"));
        assertEquals("@id/disclaimerFragment", root.getAttribute("app:startDestination"));
        Map<String, Element> fragments = new LinkedHashMap<>();
        NodeList nodes = g.getElementsByTagName("fragment");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element f = (Element) nodes.item(i);
            fragments.put(f.getAttribute("android:id"), f);
        }
        assertEquals(java.util.List.of("@+id/disclaimerFragment", "@+id/connectionFragment", "@+id/readinessFragment",
                "@+id/manufacturerGuideFragment"), new java.util.ArrayList<>(fragments.keySet()));
        assertTrue(directArguments(fragments.get("@+id/disclaimerFragment")).isEmpty());
        assertEquals(Map.of("mode", "app.lightscout.ui.core.ConnectionMode"),
                directArguments(fragments.get("@+id/connectionFragment")));
        assertEquals(Map.of("mode", "app.lightscout.ui.core.ReadinessMode"),
                directArguments(fragments.get("@+id/readinessFragment")));
        assertEquals(Map.of("manufacturer", "app.lightscout.platform.Manufacturer"),
                directArguments(fragments.get("@+id/manufacturerGuideFragment")));
    }

    @Test
    public void noArgumentCarriesAToken() {
        NodeList args = parse("navigation/onboarding_graph.xml").getElementsByTagName("argument");
        assertTrue(args.getLength() > 0);
        for (int i = 0; i < args.getLength(); i++) {
            String name = ((Element) args.item(i)).getAttribute("android:name").toLowerCase(java.util.Locale.ROOT);
            assertFalse(name, name.contains("token") || name.contains("secret") || name.contains("url"));
            assertNotEquals("string", ((Element) args.item(i)).getAttribute("app:argType"));
        }
    }

    private static Map<String, String> directArguments(Element fragment) {
        Map<String, String> out = new LinkedHashMap<>();
        NodeList children = fragment.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element && "argument".equals(((Element) children.item(i)).getTagName())) {
                Element a = (Element) children.item(i);
                out.put(a.getAttribute("android:name"), a.getAttribute("app:argType"));
            }
        }
        return out;
    }
}
