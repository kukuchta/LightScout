package app.lightscout.feature.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.home.HomeUiState.BannerAction;
import app.lightscout.feature.home.HomeUiState.BannerKind;
import app.lightscout.feature.home.HomeUiState.EmptyMessage;
import app.lightscout.feature.home.HomeUiState.RangeLabel;
import app.lightscout.usecases.status.SurfaceKeys;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Copy, icons and navigation resources of CP-12a / CP-11: S-01 copy with the verbatim §7.9 text (CP-12 §7.9,
 * CP-11 §7.5, CP-00A §5.3), the copy table {@link HomeCopy} pointing at the right keys, CP-02 {@link SurfaceKeys} keys all
 * present, the toolbar menu (M3-09) and the nested graph contract (CP-12 §4.1, CP-00A R-38). Runs on the JVM with the
 * module directory as working directory.
 */
public class ResourcesTest {

    private static final File RES = new File("src/main/res");

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("s01_title", "LightScout");
        EXPECTED.put("s01_action_statistics", "Statistics");
        EXPECTED.put("s01_action_statistics_cd", "Open statistics");
        EXPECTED.put("s01_action_settings", "Settings");
        EXPECTED.put("s01_action_settings_cd", "Open settings");
        EXPECTED.put("s01_action_support", "Support");
        EXPECTED.put("s01_action_support_cd", "Support LightScout");
        EXPECTED.put("s01_support_title", "Support LightScout");
        EXPECTED.put("s01_support_open", "Donate");
        EXPECTED.put("s01_support_dismiss", "Not now");
        EXPECTED.put("s01_support_message", "LightScout is a free app, made by one person who also lives with the daily rhythm of diabetes. If it makes your days a little easier, you can say thanks by buying me a coffee. It is completely optional, and the app stays exactly the same either way.");
        EXPECTED.put("s01_support_open_failed", "Could not open the page. Try again later.");
        EXPECTED.put("s01_support_url", "https://buycoffee.to/kukuchta");
        EXPECTED.put("s01_update_title", "Update available");
        EXPECTED.put("s01_update_message", "LightScout %1$s is available. Download it from GitHub and install it over the current app. Your settings and history stay. Alarms are off while the update installs, so pick a calm moment.");
        EXPECTED.put("s01_update_download", "Download");
        EXPECTED.put("s01_update_later", "Later");
        EXPECTED.put("s01_range_urgent_low", "Urgent low");
        EXPECTED.put("s01_range_low", "Low");
        EXPECTED.put("s01_range_high", "High");
        EXPECTED.put("s01_range_in_range", "In range");
        EXPECTED.put("s01_unit_mgdl", "mg/dL");
        EXPECTED.put("s01_delta", "Delta %1$s mg/dL");
        EXPECTED.put("s01_delta_unknown", "Delta — mg/dL");
        EXPECTED.put("s01_iob", "IOB %1$s U");
        EXPECTED.put("s01_next_reading_overdue", "Waiting for reading…");
        EXPECTED.put("s01_banner_action_turn_on", "Turn on");
        EXPECTED.put("s01_banner_action_fix", "Fix");
        EXPECTED.put("s01_banner_action_retry", "Retry");
        EXPECTED.put("s01_banner_action_fix_connection", "Fix connection");
        EXPECTED.put("s01_state_first_use", "Waiting for the first reading from Nightscout…");
        EXPECTED.put("s01_state_no_readings_24h", "No readings in the last 24 hours");
        EXPECTED.put("s01_error_ns_not_responding", "Nightscout is not responding");
        EXPECTED.put("s01_error_token_rejected", "Nightscout rejected the access token");
        EXPECTED.put("s01_error_token_permission_read",
                "This token can't read data from Nightscout. Give it a role with read access.");
        EXPECTED.put("s01_error_token_permission_write",
                "This token can't save, edit or delete entries. Give it a role with permission to create, update and delete entries.");
        EXPECTED.put("s01_error_not_nightscout", "This address doesn't look like Nightscout.");
        EXPECTED.put("s01_error_version_too_old", "Nightscout version is too old (15.0.9 or newer needed)");
        EXPECTED.put("s01_error_data_loss",
                "No new readings for %1$d min — Nightscout works, but nothing new arrived from the uploader");
        EXPECTED.put("s01_offline", "No internet connection — showing data from %1$d min ago");
        EXPECTED.put("s01_permission_missing", "Alarms may not ring — some phone settings are missing");
        EXPECTED.put("s01_alarms_off", "Alarms are off");
        EXPECTED.put("s01_low_alarm_off", "Low alarm is off");
        EXPECTED.put("s01_high_alarm_off", "High alarm is off");
        EXPECTED.put("s01_low_high_alarms_off", "Low and high alarms are off");
        EXPECTED.put("s01_chart_empty", "No readings in this period");
        EXPECTED.put("s01_chart_day_label", "EEE d MMM");
        EXPECTED.put("s01_chart_hour_labels", "00 … 23");
        EXPECTED.put("s01_chart_older_offline", "Older data needs an internet connection");
        EXPECTED.put("s01_chart_older_error", "Couldn't load older chart data");
        EXPECTED.put("s01_chart_a11y", "Glucose chart, current %1$s, %2$s");
        EXPECTED.put("s01_tooltip_edit", "Edit");
        EXPECTED.put("s01_now_chip", "Now");
        EXPECTED.put("s01_now_chip_cd", "Back to now");
        EXPECTED.put("s01_chart_type_bg_check", "BG check");
        EXPECTED.put("s01_chart_type_carbs", "Carbs");
        EXPECTED.put("s01_chart_type_bolus", "Bolus");
        EXPECTED.put("s01_chart_type_site_change", "Site change");
        EXPECTED.put("s01_chart_type_sensor_change", "Sensor change");
        EXPECTED.put("s01_chart_type_pump_alert", "Pump alert");
        EXPECTED.put("s01_tooltip_marker_row", "%1$s%2$s, %3$s");
        EXPECTED.put("s01_status_a11y", "Glucose %1$d milligrams per decilitre%2$s, %3$s, %4$s%5$s%6$s");
        EXPECTED.put("s01_status_a11y_range", ", %1$s");
        EXPECTED.put("s01_status_a11y_iob", ", IOB %1$s units");
        EXPECTED.put("s01_counter_site_title", "Site age");
        EXPECTED.put("s01_counter_sensor_title", "Sensor age");
        EXPECTED.put("s01_counter_value", "%1$d d %2$d h");
        EXPECTED.put("s01_counter_change_soon", "Change soon");
        EXPECTED.put("s01_counter_expired", "Expired");
        EXPECTED.put("s01_counter_no_site_change", "No records");
        EXPECTED.put("s01_counter_no_sensor_change", "No records");
        EXPECTED.put("s01_counter_loading", "Loading…");
        EXPECTED.put("s01_doughnut_title", "Last 24 h");
        EXPECTED.put("s01_doughnut_centre", "%1$d %%");
        EXPECTED.put("s01_doughnut_legend", "%1$d %% · %2$d %% · %3$d %% · %4$d %% · %5$d %%");
        EXPECTED.put("s01_doughnut_not_enough_data", "Not enough data");
        EXPECTED.put("s01_doughnut_a11y",
                "Last 24 hours: very low %1$d percent, low %2$d, in range %3$d, high %4$d, very high %5$d. Open statistics");
        EXPECTED.put("s01_fab_add_entry", "Add entry");
        EXPECTED.put("s01_snackbar_entry_saved", "Entry saved");
        EXPECTED.put("s01_snackbar_entry_updated", "Entry updated");
        EXPECTED.put("s01_snackbar_entry_deleted", "Entry deleted");
        EXPECTED.put("s01_doughnut_empty_a11y", "Last 24 hours: Not enough data. Open statistics");
        EXPECTED.put("s01_chip_alarms_off", "Alarms off");
        EXPECTED.put("s01_chip_low_off", "Low off");
        EXPECTED.put("s01_chip_high_off", "High off");
        EXPECTED.put("s01_chip_low_high_off", "Low/High off");
        EXPECTED.put("s01_chip_readiness", "Check settings");
        EXPECTED.put("s01_chip_version", "Old version");
        EXPECTED.put("s01_chip_token", "Token reject");
        EXPECTED.put("s01_chip_server", "Server error");
        EXPECTED.put("s01_chip_token_permissions", "Token perm.");
        EXPECTED.put("s01_chip_not_nightscout", "Wrong server");
        EXPECTED.put("s01_chip_uploader", "Uploader");
        EXPECTED.put("s01_chip_offline", "Offline");
        EXPECTED.put("s01_chip_snoozed", "Snoozed");
        EXPECTED.put("s01_snooze_title", "Snoozed alarms");
        EXPECTED.put("s01_snooze_row", "%1$s: until %2$s, %3$d min left");
        EXPECTED.put("s01_snooze_type_urgent_low", "Urgent low");
        EXPECTED.put("s01_snooze_type_low", "Low");
        EXPECTED.put("s01_snooze_type_high", "High");
        EXPECTED.put("s01_snooze_type_no_data", "No data");
        EXPECTED.put("s01_snooze_type_not_running", "Monitoring stopped");
        EXPECTED.put("s01_counter_ok", "OK");
        EXPECTED.put("s01_dock_bg", "BG");
        EXPECTED.put("s01_counter_replace_soon", "Replace soon");
    }

    private static Document parse(File f) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
    }

    private static Map<String, String> strings() throws Exception {
        Map<String, String> out = new HashMap<>();
        NodeList nodes = parse(new File(RES, "values/strings.xml")).getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.put(e.getAttribute("name"), e.getTextContent().replace("\\'", "'"));
        }
        return out;
    }

    private static String stringName(int id) throws IllegalAccessException {
        for (Field f : R.string.class.getFields()) {
            if (f.getType() == int.class && f.getInt(null) == id) {
                return f.getName();
            }
        }
        throw new AssertionError("no string resource with id " + id);
    }

    private static String drawableName(int id) throws IllegalAccessException {
        for (Field f : R.drawable.class.getFields()) {
            if (f.getType() == int.class && f.getInt(null) == id) {
                return f.getName();
            }
        }
        throw new AssertionError("no drawable resource with id " + id);
    }

    @Test
    public void copyIsVerbatim() throws Exception {
        Map<String, String> actual = strings();
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            assertEquals(e.getKey(), e.getValue(), actual.get(e.getKey()));
        }
        assertEquals("no keys outside the CP-12a list", EXPECTED.keySet(), actual.keySet());
    }

    @Test
    public void noBannedTermsInCopy() throws Exception {
        for (String text : strings().values()) {
            for (String banned : new String[] {"NS ", "SGV", "treatment", "mute", "dismiss"}) {
                assertTrue(text + " contains " + banned, !text.contains(banned));
            }
        }
    }

    @Test
    public void surfaceKeysOfCp02AllHaveStrings() throws Exception {
        Map<String, String> actual = strings();
        for (String key : new String[] {SurfaceKeys.S01_ALARMS_OFF, SurfaceKeys.S01_LOW_ALARM_OFF,
                SurfaceKeys.S01_HIGH_ALARM_OFF, SurfaceKeys.S01_LOW_HIGH_ALARMS_OFF,
                SurfaceKeys.S01_PERMISSION_MISSING, SurfaceKeys.S01_ERROR_NS_NOT_RESPONDING,
                SurfaceKeys.S01_ERROR_TOKEN_REJECTED, SurfaceKeys.S01_ERROR_VERSION_TOO_OLD,
                SurfaceKeys.S01_ERROR_DATA_LOSS, SurfaceKeys.S01_OFFLINE, SurfaceKeys.ACTION_TURN_ON,
                SurfaceKeys.ACTION_FIX, SurfaceKeys.ACTION_RETRY, SurfaceKeys.ACTION_FIX_CONNECTION}) {
            assertTrue(key, actual.containsKey(key));
        }
    }

    @Test
    public void bannerCopyTablePointsAtTheKeys() throws Exception {
        Map<BannerKind, String> expected = new LinkedHashMap<>();
        expected.put(BannerKind.ALARMS_OFF, "s01_alarms_off");
        expected.put(BannerKind.LOW_ALARM_OFF, "s01_low_alarm_off");
        expected.put(BannerKind.HIGH_ALARM_OFF, "s01_high_alarm_off");
        expected.put(BannerKind.LOW_HIGH_ALARMS_OFF, "s01_low_high_alarms_off");
        expected.put(BannerKind.PERMISSION_MISSING, "s01_permission_missing");
        expected.put(BannerKind.NS_NOT_RESPONDING, "s01_error_ns_not_responding");
        expected.put(BannerKind.TOKEN_REJECTED, "s01_error_token_rejected");
        expected.put(BannerKind.TOKEN_PERMISSION_READ, "s01_error_token_permission_read");
        expected.put(BannerKind.TOKEN_PERMISSION_WRITE, "s01_error_token_permission_write");
        expected.put(BannerKind.NOT_NIGHTSCOUT, "s01_error_not_nightscout");
        expected.put(BannerKind.VERSION_TOO_OLD, "s01_error_version_too_old");
        expected.put(BannerKind.DATA_LOSS, "s01_error_data_loss");
        expected.put(BannerKind.OFFLINE, "s01_offline");
        assertEquals(BannerKind.values().length, expected.size());
        for (Map.Entry<BannerKind, String> e : expected.entrySet()) {
            assertEquals(e.getValue(), stringName(HomeCopy.bannerText(e.getKey())));
            boolean takesMinutes = EXPECTED.get(e.getValue()).contains("%1$d");
            assertEquals(e.getKey().name(), takesMinutes, HomeCopy.bannerTakesMinutes(e.getKey()));
        }
    }

    @Test
    public void bannerActionTablePointsAtTheKeys() throws Exception {
        assertEquals(0, HomeCopy.bannerAction(BannerAction.NONE));
        assertEquals("s01_banner_action_turn_on", stringName(HomeCopy.bannerAction(BannerAction.TURN_ON)));
        assertEquals("s01_banner_action_fix", stringName(HomeCopy.bannerAction(BannerAction.FIX)));
        assertEquals("s01_banner_action_retry", stringName(HomeCopy.bannerAction(BannerAction.RETRY)));
        assertEquals("s01_banner_action_fix_connection",
                stringName(HomeCopy.bannerAction(BannerAction.FIX_CONNECTION)));
    }

    @Test
    public void bannerIconsPerType() throws Exception {
        assertEquals("ic_s01_banner_alarms_off", drawableName(HomeCopy.bannerIcon(BannerKind.ALARMS_OFF)));
        assertEquals("ic_s01_banner_alarms_off", drawableName(HomeCopy.bannerIcon(BannerKind.LOW_ALARM_OFF)));
        assertEquals("ic_s01_banner_alarms_off", drawableName(HomeCopy.bannerIcon(BannerKind.HIGH_ALARM_OFF)));
        assertEquals("ic_s01_banner_alarms_off",
                drawableName(HomeCopy.bannerIcon(BannerKind.LOW_HIGH_ALARMS_OFF)));
        assertEquals("ic_s01_banner_warning", drawableName(HomeCopy.bannerIcon(BannerKind.PERMISSION_MISSING)));
        for (BannerKind k : new BannerKind[] {BannerKind.NS_NOT_RESPONDING, BannerKind.TOKEN_REJECTED,
                BannerKind.VERSION_TOO_OLD, BannerKind.DATA_LOSS}) {
            assertEquals("ic_s01_banner_error", drawableName(HomeCopy.bannerIcon(k)));
        }
        assertEquals("ic_s01_banner_offline", drawableName(HomeCopy.bannerIcon(BannerKind.OFFLINE)));
    }

    @Test
    public void rangeAndEmptyTablesPointAtTheKeys() throws Exception {
        assertEquals("s01_range_in_range", stringName(HomeCopy.rangeLabel(RangeLabel.NONE)));
        assertEquals("s01_range_urgent_low", stringName(HomeCopy.rangeLabel(RangeLabel.URGENT_LOW)));
        assertEquals("s01_range_low", stringName(HomeCopy.rangeLabel(RangeLabel.LOW)));
        assertEquals("s01_range_high", stringName(HomeCopy.rangeLabel(RangeLabel.HIGH)));
        assertEquals(0, HomeCopy.emptyMessage(EmptyMessage.NONE));
        assertEquals("s01_state_first_use", stringName(HomeCopy.emptyMessage(EmptyMessage.FIRST_USE)));
        assertEquals("s01_state_no_readings_24h",
                stringName(HomeCopy.emptyMessage(EmptyMessage.NO_READINGS_24H)));
    }

    private static String spoken(Integer iobOrNull, RangeLabel label, String trend, String age) throws Exception {
        Map<String, String> s = strings();
        int range = HomeCopy.rangeLabel(label);
        return StatusA11y.compose(java.util.Locale.ENGLISH, s.get("s01_status_a11y"), s.get("s01_status_a11y_range"),
                s.get("s01_status_a11y_iob"), 52, range == 0 ? null : s.get(stringName(range)), trend, age,
                iobOrNull == null ? null : HomeModel.iobText(iobOrNull), s.get("s01_delta_unknown"));
    }

    @Test
    public void readingGroupSpeaksRangeWordAfterValue() throws Exception {
        assertEquals("Glucose 52 milligrams per decilitre, Urgent low, falling, 3 minutes ago, Delta — mg/dL",
                spoken(null, RangeLabel.URGENT_LOW, "falling", "3 minutes ago"));
        assertEquals("Glucose 52 milligrams per decilitre, Low, falling, 3 minutes ago, Delta — mg/dL",
                spoken(null, RangeLabel.LOW, "falling", "3 minutes ago"));
        assertEquals("Glucose 52 milligrams per decilitre, High, rising, 3 minutes ago, IOB 1.2 units, Delta — mg/dL",
                spoken(120, RangeLabel.HIGH, "rising", "3 minutes ago"));
        assertEquals("Glucose 52 milligrams per decilitre, In range, steady, 3 minutes ago, Delta — mg/dL",
                spoken(null, RangeLabel.NONE, "steady", "3 minutes ago"));
    }

    @Test
    public void readingGroupInRangeAndEmptyReadingRetainTheirAccessibleDetails() throws Exception {
        assertEquals("Glucose 52 milligrams per decilitre, In range, rising, 3 minutes ago, IOB 1.2 units, Delta — mg/dL",
                spoken(120, RangeLabel.NONE, "rising", "3 minutes ago"));
        assertEquals("Glucose 52 milligrams per decilitre, In range, steady, Just now, Delta — mg/dL",
                spoken(null, RangeLabel.NONE, "steady", "Just now"));
    }

    @Test
    public void connectionSavedSnackbarComesFromUiCore() throws Exception {
        assertTrue("moved to :ui-core (Moderator ruling SS-13)", !strings().containsKey("s06_snackbar_saved"));
        NodeList nodes = parse(new File("../ui-core/src/main/res/values/strings.xml")).getElementsByTagName("string");
        String text = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if ("s06_snackbar_saved".equals(e.getAttribute("name"))) {
                text = e.getTextContent();
            }
        }
        assertEquals("Connection saved", text);
    }

    @Test
    public void graphHasOneDestinationWithoutArguments() throws Exception {
        Document d = parse(new File(RES, "navigation/home_graph.xml"));
        Element root = d.getDocumentElement();
        assertEquals("navigation", root.getTagName());
        assertEquals("@+id/home_graph", root.getAttribute("android:id"));
        assertEquals("@id/homeFragment", root.getAttribute("app:startDestination"));
        NodeList fragments = d.getElementsByTagName("fragment");
        assertEquals(1, fragments.getLength());
        Element f = (Element) fragments.item(0);
        assertEquals("@+id/homeFragment", f.getAttribute("android:id"));
        assertEquals(HomeFragment.class.getName(), f.getAttribute("android:name"));
        assertEquals("no focusTime or other argument (DS:CUT-07)", 0, d.getElementsByTagName("argument").getLength());
        assertEquals("exits only through IF-29 (R-4)", 0, d.getElementsByTagName("action").getLength());
    }

    @Test
    public void toolbarMenuHasSupportStatisticsAndSettingsInThatOrder() throws Exception {
        Document d = parse(new File(RES, "menu/menu_home.xml"));
        NodeList items = d.getElementsByTagName("item");
        assertEquals("Support, Statistics (only visible when the home card is off) and Settings", 3, items.getLength());
        Element support = (Element) items.item(0);
        assertEquals("@+id/s01_action_support", support.getAttribute("android:id"));
        assertEquals("@string/s01_action_support", support.getAttribute("android:title"));
        assertEquals("@string/s01_action_support_cd", support.getAttribute("app:contentDescription"));
        assertEquals("@string/s01_action_support_cd", support.getAttribute("app:tooltipText"));
        Element statistics = (Element) items.item(1);
        assertEquals("@string/s01_action_statistics", statistics.getAttribute("android:title"));
        assertEquals("false", statistics.getAttribute("android:visible"));
        assertEquals("@string/s01_action_statistics_cd", statistics.getAttribute("app:contentDescription"));
        assertEquals("@string/s01_action_statistics_cd", statistics.getAttribute("app:tooltipText"));
        Element settings = (Element) items.item(2);
        assertEquals("@string/s01_action_settings", settings.getAttribute("android:title"));
        assertEquals("@string/s01_action_settings_cd", settings.getAttribute("app:contentDescription"));
        assertEquals("@string/s01_action_settings_cd", settings.getAttribute("app:tooltipText"));
    }

    @Test
    public void layoutKeepsBannerLiveRegionAndGroupedReading() throws Exception {
        Document d = parse(new File(RES, "layout/fragment_home.xml"));
        Element banner = byId(d, "@+id/s01_banner");
        assertEquals("none", banner.getAttribute("android:accessibilityLiveRegion"));
        assertEquals("com.google.android.material.button.MaterialButton", banner.getTagName());
        assertEquals("48dp", banner.getAttribute("android:minHeight"));
        Element group = byId(d, "@+id/s01_reading_group");
        assertEquals("true", group.getAttribute("android:focusable"));
        assertEquals("true", group.getAttribute("android:screenReaderFocusable"));
        Element toolbar = byId(d, "@+id/s01_toolbar");
        assertEquals("com.google.android.material.appbar.MaterialToolbar", toolbar.getTagName());
        assertEquals("@string/s01_title", toolbar.getAttribute("app:title"));
        assertNotEquals("no navigation icon on the root", "true", toolbar.getAttribute("app:navigationIcon"));
        assertTrue("CP-11 chart host present", byId(d, "@+id/s01_chart_host") != null);
        assertEquals(HomeContentLayout.class.getName(), byId(d, "@+id/s01_content").getTagName());
        assertEquals(GlucoseChartView.class.getName(), byId(d, "@+id/s01_chart").getTagName());
        assertEquals(HomeContentScrollView.class.getName(), byId(d, "@+id/s01_scroll").getTagName());
        assertEquals("wrap_content", byId(d, "@+id/s01_content").getAttribute("android:layout_height"));
        assertEquals(HomeChartHost.class.getName(), byId(d, "@+id/s01_chart_host").getTagName());
        assertEquals("wrap_content", byId(d, "@+id/s01_chart_host").getAttribute("android:layout_height"));
        assertEquals("1", byId(d, "@+id/s01_chart_host").getAttribute("android:layout_weight"));
        assertEquals("160dp", byId(d, "@+id/s01_chart_host").getAttribute("android:minHeight"));
        assertEquals(byId(d, "@+id/s01_value_row"),
                byId(d, "@+id/s01_reading").getParentNode());
        assertEquals(byId(d, "@+id/s01_value_row"), byId(d, "@+id/s01_unit").getParentNode());
        assertEquals(byId(d, "@+id/s01_value_row"), byId(d, "@+id/s01_trend").getParentNode());
        assertTrue("site and sensor counters are present", byId(d, "@+id/s01_site_card") != null
                && byId(d, "@+id/s01_sensor_card") != null);
        Element doughnut = byId(d, "@+id/s01_doughnut_card");
        assertEquals("true", doughnut.getAttribute("android:focusable"));
        assertEquals("true", doughnut.getAttribute("android:clickable"));
        assertEquals(HomeDistributionView.class.getName(), byId(d, "@+id/s01_distribution").getTagName());
        for (String id : new String[] {"s01_carbs", "s01_bolus", "s01_bg"}) {
            Element action = byId(d, "@+id/" + id);
            assertEquals("equal-width dock", "1", action.getAttribute("android:layout_weight"));
            assertEquals("48dp", action.getAttribute("android:minHeight"));
        }
        assertTrue("FAB retired", d.getElementsByTagName(
                "com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton").getLength() == 0);
    }

    private static Element byId(Document d, String id) {
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("android:id"))) {
                return e;
            }
        }
        throw new AssertionError("missing view " + id);
    }

    @Test
    public void glassChartPaletteIsScopedToTheHomeChartInLightAndDark() throws Exception {
        Document dark = parse(new File(RES, "values/chart_styles.xml"));
        assertEquals("Widget.LightScout.Glass.Card",
                style(dark, "Widget.LightScout.HomeChart.Card").getAttribute("parent"));
        Map<String, String> items = new LinkedHashMap<>();
        NodeList nodes = style(dark, "ThemeOverlay.LightScout.HomeChart").getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            items.put(item.getAttribute("name"), item.getTextContent());
        }
        assertEquals(Map.of(
                "colorPrimary", "@color/glass_band_in_range",
                "colorError", "@color/glass_band_low",
                "colorErrorContainer", "@color/glass_band_very_low",
                "warning", "@color/glass_band_high",
                "warningContainer", "@color/glass_band_very_high",
                "colorOutline", "@color/glass_text_muted",
                "colorTertiary", "@color/glass_accent",
                "colorSurface", "@android:color/transparent"), items);
        assertEquals("@style/Widget.LightScout.HomeChart.Card",
                byId(parse(new File(RES, "layout/fragment_home.xml")), "@+id/s01_chart_host")
                        .getAttribute("style"));
    }

    @Test
    public void chartRetainsBasalDataButHasNoBasalDrawingAndReusesExactDoseFormatter() throws Exception {
        String chart = Files.readString(new File(
                "src/main/java/app/lightscout/feature/home/GlucoseChartView.java").toPath());
        assertTrue("view context opts into the Home-only palette",
                chart.contains("new ContextThemeWrapper(context, R.style.ThemeOverlay_LightScout_HomeChart)"));
        assertTrue("both cached and complete segments retain basal data",
                chart.indexOf("basal.addAll(s.basal())") != chart.lastIndexOf("basal.addAll(s.basal())"));
        assertTrue("no basal drawing or reserved band remains",
                !chart.contains("drawBasal") && !chart.contains("basalPaint") && !chart.contains("basalHeight"));
        assertTrue("existing exact treatment formatter owns dose display",
                chart.contains("case BOLUS -> Formatters.entryValue(getContext(), entry);"));
        assertTrue("no floating-point dose conversion or separate tooltip formatter",
                !chart.contains("ChartTooltipText") && !chart.contains("insulinMilli /"));
    }

    private static Element style(Document document, String name) {
        NodeList styles = document.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Element style = (Element) styles.item(i);
            if (name.equals(style.getAttribute("name"))) {
                return style;
            }
        }
        throw new AssertionError("missing style " + name);
    }
}
