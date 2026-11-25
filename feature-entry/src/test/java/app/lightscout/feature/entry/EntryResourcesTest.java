package app.lightscout.feature.entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.model.EntryType;
import app.lightscout.feature.entry.EntryFormUiState.Direction;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * S-02 copy and graph (CP-13 §7.12 verbatim, CP-00A DCR-04 / §5.3 derived keys, R-38 graph contract). Runs on the
 * JVM with the module directory as working directory.
 */
public class EntryResourcesTest {

    private static final File RES = new File("src/main/res");
    private static final String APP = "http://schemas.android.com/apk/res-auto";
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("s02_type_bg", "BG");
        EXPECTED.put("s02_pick_time", "Pick time");
        EXPECTED.put("s02_cancel", "Cancel");
        EXPECTED.put("s02_value_hint", "0");
        EXPECTED.put("s02_range_bg", "20–600 mg/dL");
        EXPECTED.put("s02_range_carbs", "1–250 g");
        EXPECTED.put("s02_range_bolus", "0.025–25 U");
        EXPECTED.put("s02_title_new", "New entry");
        EXPECTED.put("s02_title_edit", "Edit entry");
        EXPECTED.put("s02_close", "×");
        EXPECTED.put("s02_close_a11y", "Close");
        EXPECTED.put("s02_save", "Save");
        EXPECTED.put("s02_type_prompt", "What do you want to log?");
        EXPECTED.put("s02_type_bg_check", "BG check");
        EXPECTED.put("s02_type_carbs", "Carbs");
        EXPECTED.put("s02_type_bolus", "Bolus");
        EXPECTED.put("s02_type_site_change", "Site change");
        EXPECTED.put("s02_type_sensor_change", "Sensor change");
        EXPECTED.put("s02_type_a11y", "1 of 5, selected");
        EXPECTED.put("s02_value_a11y", "%1$s, %2$s");
        EXPECTED.put("a11y_unit_mgdl", "milligrams per decilitre");
        EXPECTED.put("a11y_unit_g", "grams");
        EXPECTED.put("a11y_unit_u", "units");
        EXPECTED.put("s02_bolus_helper", "Logs a bolus given outside the pump. LightScout never delivers insulin.");
        EXPECTED.put("s02_time_label", "Time");
        EXPECTED.put("s02_picker_date_title", "Select date");
        EXPECTED.put("s02_picker_time_title", "Select time");
        EXPECTED.put("s02_time_now", "Now");
        EXPECTED.put("s02_time_now_cd", "Set time to now");
        EXPECTED.put("s02_time_a11y", "Time, %1$s, button, double-tap to change");
        EXPECTED.put("s02_similar_entry_earlier", "Similar entry %1$d min earlier: %2$s (%3$s)");
        EXPECTED.put("s02_similar_entry_later", "Similar entry %1$d min later: %2$s (%3$s)");
        EXPECTED.put("s02_similar_entry_same_time", "Similar entry at the same time: %1$s (%2$s)");
        EXPECTED.put("s01_tooltip_source_lightscout", "LightScout");
        EXPECTED.put("s01_tooltip_source_nightscout", "Nightscout");
        EXPECTED.put("s02_delete_entry", "Delete entry");
        EXPECTED.put("s02_delete_dialog_title", "Delete this entry?");
        EXPECTED.put("s02_delete_dialog_body", "It will be removed from Nightscout.");
        EXPECTED.put("s02_delete_dialog_cancel", "Cancel");
        EXPECTED.put("s02_delete_dialog_confirm", "Delete");
        EXPECTED.put("s02_discard_title_create", "Discard this entry?");
        EXPECTED.put("s02_discard_title_edit", "Discard changes?");
        EXPECTED.put("s02_discard_keep_editing", "Keep editing");
        EXPECTED.put("s02_discard_confirm", "Discard");
        EXPECTED.put("s02_helper_choose_type", "Choose what to log");
        EXPECTED.put("s02_error_bg_range", "Enter a value from 20 to 600");
        EXPECTED.put("s02_error_carbs_range", "Enter 1 to 250 g");
        EXPECTED.put("s02_error_bolus_range", "Enter 0.025 to 25 U");
        EXPECTED.put("s02_error_time_future", "Time can't be in the future");
        EXPECTED.put("s02_error_time_too_old", "Time can't be more than 7 days ago");
        EXPECTED.put("s02_offline_create",
                "No internet connection — this entry can't be saved now. Log it later with the right time.");
        EXPECTED.put("s02_offline_edit", "No internet connection — changes can't be saved now.");
        EXPECTED.put("s02_error_ns_not_responding", "Couldn't save — Nightscout is not responding. Try again.");
        EXPECTED.put("s02_error_token_rejected", "Couldn't save — Nightscout rejected the access token.");
        EXPECTED.put("s02_error_rejected", "Couldn't save — Nightscout didn't accept this entry.");
        EXPECTED.put("s02_error_conflict", "This entry was changed or deleted elsewhere.");
        EXPECTED.put("s02_error_unsure_saved",
                "Not sure the entry was saved — check the chart before saving again.");
    }

    private static Document parse(String path) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
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
        assertEquals("only the CP-13 §7.12 / CP-00A / D-073 keys (suffixes live in :ui-core)", EXPECTED.keySet(),
                actual.keySet());
    }

    @Test
    public void templatesFormatLikeTheSlice() {
        Map<String, String> s = strings();
        assertEquals("Carbs, grams", String.format(s.get("s02_value_a11y"), "Carbs", "grams"));
        assertEquals("Time, Now, button, double-tap to change", String.format(s.get("s02_time_a11y"), "Now"));
        assertEquals("Similar entry 8 min earlier: 20 g (xDrip+)",
                String.format(s.get("s02_similar_entry_earlier"), 8, "20 g", "xDrip+"));
        assertEquals("Similar entry 3 min later: 20 g (LightScout)",
                String.format(s.get("s02_similar_entry_later"), 3, "20 g", "LightScout"));
        assertEquals("Similar entry at the same time: 20 g (Nightscout)",
                String.format(s.get("s02_similar_entry_same_time"), "20 g", "Nightscout"));
    }

    @Test
    public void noBannedTerms() {
        for (String text : strings().values()) {
            for (String banned : new String[] {"NS ", "SGV", "treatment", "mute", "dismiss"}) {
                assertFalse(text, text.contains(banned));
            }
        }
    }

    @Test
    public void typeAndUnitMapping() {
        assertEquals(R.string.s02_type_bg_check, EntryCopy.typeName(EntryType.BG_CHECK));
        assertEquals(R.string.s02_type_carbs, EntryCopy.typeName(EntryType.CARBS));
        assertEquals(R.string.s02_type_bolus, EntryCopy.typeName(EntryType.BOLUS));
        assertEquals(R.string.s02_type_site_change, EntryCopy.typeName(EntryType.SITE_CHANGE));
        assertEquals(R.string.s02_type_sensor_change, EntryCopy.typeName(EntryType.SENSOR_CHANGE));
        assertEquals(app.lightscout.ui.core.R.string.s02_suffix_mgdl, EntryCopy.suffix(EntryType.BG_CHECK));
        assertEquals(app.lightscout.ui.core.R.string.s02_suffix_g, EntryCopy.suffix(EntryType.CARBS));
        assertEquals(app.lightscout.ui.core.R.string.s02_suffix_u, EntryCopy.suffix(EntryType.BOLUS));
        assertEquals(0, EntryCopy.suffix(EntryType.SITE_CHANGE));
        assertEquals(R.string.a11y_unit_mgdl, EntryCopy.spokenUnit(EntryType.BG_CHECK));
        assertEquals(R.string.a11y_unit_g, EntryCopy.spokenUnit(EntryType.CARBS));
        assertEquals(R.string.a11y_unit_u, EntryCopy.spokenUnit(EntryType.BOLUS));
        assertEquals(R.string.s02_similar_entry_later, EntryCopy.similar(Direction.LATER));
        assertEquals(R.string.s02_similar_entry_same_time, EntryCopy.similar(Direction.SAME_TIME));
        assertTrue(EntryCopy.isOffline(EntryFormModel.Message.OFFLINE_EDIT));
        assertFalse(EntryCopy.isOffline(EntryFormModel.Message.CONFLICT));
    }

    @Test
    public void compactSheetHasExplicitCancelAndNoLegacyToolbarClose() {
        NodeList toolbars = parse("layout/fragment_entry_form.xml")
                .getElementsByTagName("com.google.android.material.appbar.MaterialToolbar");
        assertEquals(1, toolbars.getLength());
        Element toolbar = (Element) toolbars.item(0);
        assertEquals("@+id/s02_toolbar", toolbar.getAttributeNS(ANDROID, "id"));
        assertEquals("", toolbar.getAttributeNS(APP, "navigationIcon"));
    }

    @Test
    public void graphContract() {
        Element graph = parse("navigation/entry_graph.xml").getDocumentElement();
        assertEquals("@+id/entry_graph", graph.getAttributeNS(ANDROID, "id"));
        assertEquals("@id/entryFormFragment", graph.getAttributeNS(APP, "startDestination"));
        NodeList fragments = graph.getElementsByTagName("dialog");
        assertEquals(1, fragments.getLength());
        Element f = (Element) fragments.item(0);
        assertEquals(EntryFormFragment.class.getName(), f.getAttributeNS(ANDROID, "name"));
        NodeList args = f.getElementsByTagName("argument");
        assertEquals("R-95 explicit mode and mutually exclusive arguments", 3, args.getLength());
        Element a = (Element) args.item(1);
        assertEquals("entryId", a.getAttributeNS(ANDROID, "name"));
        assertEquals("string", a.getAttributeNS(APP, "argType"));
        assertEquals("true", a.getAttributeNS(APP, "nullable"));
        assertEquals("@null", a.getAttributeNS(ANDROID, "defaultValue"));
        assertEquals("internal cross-graph route", 1, graph.getElementsByTagName("deepLink").getLength());
    }
}
