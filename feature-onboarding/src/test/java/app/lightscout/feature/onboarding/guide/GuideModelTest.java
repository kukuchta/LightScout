package app.lightscout.feature.onboarding.guide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.feature.onboarding.Fakes;
import app.lightscout.feature.onboarding.guide.GuideModel.Command;
import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.OemSettingId;
import app.lightscout.platform.intents.SettingsTargets;
import app.lightscout.platform.manufacturer.ManufacturerMapping;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S-08 logic (CP-14 §7.5, §9 TST-06 S-08) on the JVM: detection preselect, selection kept, steps per manufacturer,
 * „Open …” visibility by resolvability, failure Snackbar, Pixel / Other empty state, dontkillmyapp link.
 */
public class GuideModelTest {

    private static final Map<Manufacturer, List<GuideStep>> GUIDES = Map.of(
            Manufacturer.XIAOMI, List.of(new GuideStep("x1", OemSettingId.XIAOMI_AUTOSTART),
                    new GuideStep("x2", OemSettingId.APP_DETAILS), new GuideStep("x3", null),
                    new GuideStep("x4", OemSettingId.APP_DETAILS)),
            Manufacturer.SAMSUNG, List.of(new GuideStep("s1", OemSettingId.APP_DETAILS), new GuideStep("s2", null)));

    private final Fakes.MapStore store = new Fakes.MapStore();
    private final Set<OemSettingId> resolvable = EnumSet.allOf(OemSettingId.class);
    private final List<GuideUiState> states = new ArrayList<>();
    private final List<Command> commands = new ArrayList<>();

    private GuideModel model(Manufacturer detected) {
        GuideModel m = new GuideModel(detected, store, mf -> GUIDES.getOrDefault(mf, List.of()),
                resolvable::contains);
        m.setListener(new GuideModel.Listener() {
            @Override
            public void onState(GuideUiState state) {
                states.add(state);
            }

            @Override
            public void onCommand(Command command) {
                commands.add(command);
            }
        });
        return m;
    }

    private GuideUiState last() {
        return states.get(states.size() - 1);
    }

    @Test
    public void detectedManufacturerIsPreselected() {
        model(Manufacturer.XIAOMI);
        assertEquals(Manufacturer.XIAOMI, last().manufacturer());
        assertEquals(4, last().steps().size());
    }

    @Test
    public void detectionMappingDrivesTheGuide() {
        // R-85 mapping (CMP-28) → argument → guide shown
        assertEquals(Manufacturer.XIAOMI, ManufacturerMapping.map("Xiaomi", "Redmi"));
        model(ManufacturerMapping.map("POCO", null));
        assertEquals(Manufacturer.XIAOMI, last().manufacturer());
        states.clear();
        store.values.clear();
        model(ManufacturerMapping.map("unknown", "unknown"));
        assertEquals(Manufacturer.OTHER, last().manufacturer());
        assertTrue(last().noExtraSteps());
    }

    @Test
    public void stepsAreNumberedWithTotal() {
        model(Manufacturer.XIAOMI);
        GuideUiState.Step second = last().steps().get(1);
        assertEquals(2, second.number());
        assertEquals(4, second.total());
        assertEquals("x2", second.text());
    }

    @Test
    public void openButtonOnlyForResolvableSteps() {
        resolvable.remove(OemSettingId.XIAOMI_BATTERY_SAVER);
        model(Manufacturer.XIAOMI);
        List<GuideUiState.Step> steps = last().steps();
        assertEquals(OemSettingId.XIAOMI_AUTOSTART, steps.get(0).open());
        assertEquals("app-details button remains available when the OEM shortcut is unavailable",
                OemSettingId.APP_DETAILS, steps.get(1).open());
        assertNull("text-only step", steps.get(2).open());
        assertEquals(OemSettingId.APP_DETAILS, steps.get(3).open());
    }

    @Test
    public void resolveCheckFailureHidesTheButton() {
        GuideModel m = new GuideModel(Manufacturer.XIAOMI, store, mf -> GUIDES.getOrDefault(mf, List.of()), id -> {
            throw new IllegalStateException("pm");
        });
        for (GuideUiState.Step s : m.state().steps()) {
            assertNull(s.open());
        }
    }

    @Test
    public void refreshReevaluatesResolvability() {
        GuideModel m = model(Manufacturer.XIAOMI);
        resolvable.remove(OemSettingId.XIAOMI_AUTOSTART);
        m.refresh();
        assertNull(last().steps().get(0).open());
    }

    @Test
    public void openStepSendsItsSetting() {
        GuideModel m = model(Manufacturer.XIAOMI);
        m.onOpenStep(2);
        assertEquals(new Command(Command.Kind.OPEN_SETTING, OemSettingId.APP_DETAILS, null), commands.get(0));
        m.onOpenStep(3);
        assertEquals("text-only step sends nothing", 1, commands.size());
    }

    @Test
    public void openFailureShowsSnackbar() {
        GuideModel m = model(Manufacturer.XIAOMI);
        m.onOpenFailed();
        assertEquals(Command.Kind.SHOW_OPEN_FAILED, commands.get(0).kind());
    }

    @Test
    public void pixelAndOtherShowNoExtraSteps() {
        GuideModel m = model(Manufacturer.PIXEL);
        assertTrue(last().noExtraSteps());
        m.select(Manufacturer.OTHER);
        assertTrue(last().noExtraSteps());
        m.select(Manufacturer.SAMSUNG);
        assertFalse(last().noExtraSteps());
    }

    @Test
    public void selectionSurvivesRecreationAndProcessDeath() {
        GuideModel m = model(Manufacturer.XIAOMI);
        m.select(Manufacturer.SAMSUNG);
        assertEquals("SAMSUNG", store.getString(GuideModel.KEY_MANUFACTURER));
        GuideModel restored = new GuideModel(Manufacturer.XIAOMI, store, mf -> GUIDES.getOrDefault(mf, List.of()),
                resolvable::contains);
        assertEquals(Manufacturer.SAMSUNG, restored.state().manufacturer());
    }

    @Test
    public void corruptSavedValueFallsBackToDetected() {
        store.putString(GuideModel.KEY_MANUFACTURER, "NOKIA");
        model(Manufacturer.HUAWEI);
        assertEquals(Manufacturer.HUAWEI, last().manufacturer());
    }

    @Test
    public void moreDetailsUsesTheSelectedManufacturerWithASafeSlug() {
        GuideModel m = model(Manufacturer.XIAOMI);
        m.select(Manufacturer.PIXEL);
        m.onMoreDetails();
        assertEquals(new Command(Command.Kind.OPEN_MORE_DETAILS, null, Manufacturer.PIXEL), commands.get(0));
        assertEquals("https://dontkillmyapp.com/google", SettingsTargets.dontKillMyAppUrl(Manufacturer.PIXEL));
        for (Manufacturer mf : Manufacturer.values()) {
            String url = SettingsTargets.dontKillMyAppUrl(mf);
            assertTrue(url, url.matches("https://dontkillmyapp\\.com/[a-z0-9-]*"));
        }
    }

    @Test
    public void selectingTheSameOptionPublishesNothing() {
        GuideModel m = model(Manufacturer.XIAOMI);
        int before = states.size();
        m.select(Manufacturer.XIAOMI);
        assertEquals(before, states.size());
    }

    @Test
    public void unknownSettingNamesAreTextOnly() {
        assertNull(ResourceGuideContent.parse(""));
        assertNull(ResourceGuideContent.parse("   "));
        assertNull(ResourceGuideContent.parse("SAMSUNG_SLEEP"));
        assertEquals(OemSettingId.APP_DETAILS, ResourceGuideContent.parse("APP_DETAILS"));
    }
}
