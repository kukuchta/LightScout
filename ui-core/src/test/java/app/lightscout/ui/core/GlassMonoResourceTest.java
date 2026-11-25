package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Static audit of the Glass Mono foundation (CP-00A R-90, DCR-14): tokens, composed contrast, scoped dark child theme
 * that leaves the legacy theme alone, separate empty placeholder, bundled fonts and touch targets. Native rendering of
 * fonts, glow and translucency is not proven here.
 */
public class GlassMonoResourceTest {

    private static final String THEMES = "values/themes.xml";
    private static final String DARK_TOKENS = "values-night/glass_colors.xml";
    private static final String LIGHT_TOKENS = "values/glass_colors.xml";
    private static final String STYLES = "values/glass_styles.xml";

    /** Dark token set (R-90). */
    private static Map<String, String> tokens() {
        return ResXml.named(DARK_TOKENS, "color");
    }

    private static Map<String, String> lightTokens() {
        return ResXml.named(LIGHT_TOKENS, "color");
    }

    private static int argb(String hex) {
        String h = hex.trim().substring(1);
        return (int) Long.parseLong(h.length() == 6 ? "FF" + h : h, 16);
    }

    private static int composite(int top, int bottom) {
        float a = (top >>> 24) / 255f;
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int t = (top >> shift) & 0xFF;
            int b = (bottom >> shift) & 0xFF;
            out |= Math.round(t * a + b * (1 - a)) << shift;
        }
        return out;
    }

    private static void assertContrast(String what, int fg, int bg, double min) {
        double ratio = ColorSchemeTest.contrast(fg, bg);
        assertTrue(what + " " + ratio, ratio >= min);
    }

    @Test
    public void tokensMatchR90() {
        Map<String, String> t = tokens();
        assertEquals("#0E1013", t.get("glass_background"));
        assertEquals("#F1F5F9", t.get("glass_text"));
        assertEquals("#B4BAC2", t.get("glass_text_muted"));
        assertEquals("#22D3EE", t.get("glass_accent"));
        assertEquals("#04222A", t.get("glass_on_accent"));
        assertEquals("#1B1E23", t.get("glass_glow"));
        assertEquals(0x12FFFFFF, argb(t.get("glass_card")));            // white 7 %
        assertEquals(0x24FFFFFF, argb(t.get("glass_card_border")));     // white 14 %
        assertEquals(0x2EFFFFFF, argb(t.get("glass_hero_border")));     // white 18 %
        assertEquals(0x1FFBBF24, argb(t.get("glass_hero_tint_high")));  // 12 %
        assertEquals(0x24FB7185, argb(t.get("glass_hero_tint_low")));   // 14 %
        assertEquals(0x33EF202A, argb(t.get("glass_hero_tint_urgent_low")));   // 20 %
        assertEquals(0x99FF6268, argb(t.get("glass_hero_border_urgent_low")));   // 60 %
        assertEquals(0x1AFFFFFF, argb(t.get("glass_progress_track")));  // 10 %
        assertEquals(0x7022D3EE, argb(t.get("glass_chip_info_border")));    // accent 44 %
        assertEquals(0x99FB7185, argb(t.get("glass_chip_warning_border")));   // 60 %
        assertEquals("#F4F6FA", t.get("glass_reading_normal"));
        assertEquals("#FDE2A8", t.get("glass_reading_high"));
        assertEquals("#FFD0CC", t.get("glass_reading_low"));
        assertEquals("#FF6268", t.get("glass_reading_urgent_low"));
        assertEquals("#34D399", t.get("glass_band_in_range"));
        assertEquals("#FBBF24", t.get("glass_band_high"));
        assertEquals("#FB7185", t.get("glass_band_low"));
        assertEquals("#EF4444", t.get("glass_band_very_low"));
        assertEquals("#D97706", t.get("glass_band_very_high"));
        assertEquals("18dp", ResXml.named("values/glass_dimens.xml", "dimen").get("glass_card_radius"));
        assertEquals("24dp", ResXml.named("values/glass_dimens.xml", "dimen").get("glass_hero_radius"));
    }

    @Test
    public void composedSurfacesAndContrast() {
        Map<String, String> t = tokens();
        int bg = argb(t.get("glass_background"));
        int card = composite(argb(t.get("glass_card")), bg);
        // Opaque container roles are the real compositions of white 7 / 10 / 14 % over the background (±1 per channel).
        assertNear(composite(0x12FFFFFF, bg), argb(t.get("glass_surface_container")));
        assertNear(composite(0x1AFFFFFF, bg), argb(t.get("glass_surface_container_high")));
        assertNear(composite(0x24FFFFFF, bg), argb(t.get("glass_surface_container_highest")));

        int text = argb(t.get("glass_text"));
        int muted = argb(t.get("glass_text_muted"));
        assertContrast("text on background", text, bg, 4.5);
        assertContrast("text on card", text, card, 4.5);
        assertContrast("muted on card", muted, card, 4.5);
        assertContrast("muted on highest container", muted, argb(t.get("glass_surface_container_highest")), 4.5);
        assertContrast("on-accent on accent", argb(t.get("glass_on_accent")), argb(t.get("glass_accent")), 4.5);
        assertContrast("accent on card", argb(t.get("glass_accent")), card, 4.5);
        assertContrast("info chip", argb(t.get("glass_chip_info_text")), argb(t.get("glass_chip_info_background")),
                4.5);
        assertContrast("warning chip", argb(t.get("glass_chip_warning_text")),
                argb(t.get("glass_chip_warning_background")), 4.5);
        assertContrast("stale chip", argb(t.get("glass_stale_chip_text")),
                argb(t.get("glass_stale_chip_background")), 4.5);
        assertContrast("urgent label", argb(t.get("glass_urgent_label_text")),
                argb(t.get("glass_urgent_label_background")), 4.5);
        assertContrast("onWarning on warning", bg, argb(t.get("glass_band_high")), 4.5);
        assertContrast("onWarning on warningContainer", bg, argb(t.get("glass_band_very_high")), 4.5);
        assertContrast("onError on error", bg, argb(t.get("glass_reading_urgent_low")), 4.5);

        // Reading colours over the composed hero (card plus tint).
        int[][] heroes = {
            {argb(t.get("glass_reading_normal")), card},
            {argb(t.get("glass_reading_high")), composite(argb(t.get("glass_hero_tint_high")), card)},
            {argb(t.get("glass_reading_low")), composite(argb(t.get("glass_hero_tint_low")), card)},
            {argb(t.get("glass_reading_urgent_low")), composite(argb(t.get("glass_hero_tint_urgent_low")), card)},
        };
        for (int[] h : heroes) {
            assertContrast("reading on hero", h[0], h[1], 4.5);
        }
        // Non-text: band colours and outline against the card (3:1).
        for (String band : new String[] {"in_range", "high", "low", "very_low", "very_high"}) {
            assertContrast("band " + band, argb(t.get("glass_band_" + band)), card, 3.0);
        }
        assertContrast("outline", muted, card, 3.0);
        assertContrast("accent track fill on progress track", argb(t.get("glass_accent")),
                composite(argb(t.get("glass_progress_track")), card), 3.0);
    }

    private static void assertNear(int expected, int actual) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int d = Math.abs(((expected >> shift) & 0xFF) - ((actual >> shift) & 0xFF));
            assertTrue(Integer.toHexString(expected) + " vs " + Integer.toHexString(actual), d <= 1);
        }
    }

    @Test
    public void glassChildThemeIsOneDefinitionForLightAndDark() {
        Map<String, String> dark = ResXml.style(THEMES, "Theme.LightScout.Glass");
        assertEquals("Theme.LightScout", dark.get("@parent"));
        assertEquals("@color/glass_background", dark.get("colorSurface"));
        assertEquals("@color/glass_background", dark.get("android:colorBackground"));
        assertEquals("@color/glass_text", dark.get("colorOnSurface"));
        assertEquals("@color/glass_text", dark.get("android:textColorPrimary"));
        assertEquals("@color/glass_text_muted", dark.get("android:textColorSecondary"));
        assertEquals("@color/glass_accent", dark.get("colorPrimary"));
        assertEquals("@drawable/glass_background", dark.get("android:windowBackground"));
        assertEquals("@style/Widget.LightScout.Glass.Card", dark.get("materialCardViewStyle"));
        assertEquals("@font/glass_dm_sans", ResXml.style(STYLES, "TextAppearance.LightScout.Glass.DisplayLarge")
                .get("android:fontFamily"));
        assertEquals("@font/glass_inter", ResXml.style(STYLES, "TextAppearance.LightScout.Glass.BodyLarge")
                .get("android:fontFamily"));
        assertFalse(ResXml.style(STYLES, "TextAppearance.LightScout.Glass.DisplayLarge")
                .containsKey("android:fontFeatureSettings"));
        for (String key : dark.keySet()) {
            assertFalse(key, key.startsWith("android:status") || key.startsWith("android:navigation"));
        }

        // The legacy theme (alarm, services, widgets) keeps its own light/dark values and styles.
        Map<String, String> legacy = ResXml.style(THEMES, "Theme.LightScout");
        assertEquals("@style/Widget.LightScout.CardView.Outlined", legacy.get("materialCardViewStyle"));
        assertEquals("@style/Widget.LightScout.Toolbar", legacy.get("toolbarStyle"));
        assertFalse(legacy.containsKey("android:windowBackground"));
        assertFalse(legacy.containsKey("textAppearanceBodyLarge"));
        assertEquals("@color/md_theme_surface", legacy.get("colorSurface"));
        assertEquals("TextAppearance.Material3.DisplayLarge",
                ResXml.style("values/styles.xml", "TextAppearance.LightScout.CurrentReading").get("@parent"));
    }

    @Test
    public void lightAndDarkTokenSetsHaveTheSameNames() {
        assertEquals(tokens().keySet(), lightTokens().keySet());
    }

    @Test
    public void lightTokensAreNeutralBackgroundWithBlueAccentAndReadable() {
        Map<String, String> t = lightTokens();
        assertEquals("#ECEEF1", t.get("glass_background"));
        assertEquals("#0369A1", t.get("glass_accent"));
        assertEquals("#FFFFFF", t.get("glass_card"));
        // The window glow carries no hue: grey channels stay within 6 of each other.
        int glow = argb(t.get("glass_glow"));
        assertTrue(Math.abs(((glow >> 16) & 0xFF) - (glow & 0xFF)) <= 8);
        assertEquals(argb(t.get("glass_glow")) & 0x00FFFFFF, argb(t.get("glass_glow_end")) & 0x00FFFFFF);
        assertEquals(0, argb(t.get("glass_glow_end")) >>> 24);

        int bg = argb(t.get("glass_background"));
        int card = argb(t.get("glass_card"));
        int text = argb(t.get("glass_text"));
        int muted = argb(t.get("glass_text_muted"));
        assertContrast("text on background", text, bg, 4.5);
        assertContrast("text on card", text, card, 4.5);
        assertContrast("muted on background", muted, bg, 4.5);
        assertContrast("muted on card", muted, card, 4.5);
        assertContrast("muted on highest container", muted, argb(t.get("glass_surface_container_highest")), 4.5);
        assertContrast("on-accent on accent", argb(t.get("glass_on_accent")), argb(t.get("glass_accent")), 4.5);
        assertContrast("accent on card", argb(t.get("glass_accent")), card, 4.5);
        assertContrast("accent on background", argb(t.get("glass_accent")), bg, 4.5);
        for (String chip : new String[] {"info", "warning", "caution"}) {
            assertContrast(chip + " chip", argb(t.get("glass_chip_" + chip + "_text")),
                    argb(t.get("glass_chip_" + chip + "_background")), 4.5);
        }
        assertContrast("stale chip", argb(t.get("glass_stale_chip_text")),
                argb(t.get("glass_stale_chip_background")), 4.5);
        assertContrast("urgent label", argb(t.get("glass_urgent_label_text")),
                argb(t.get("glass_urgent_label_background")), 4.5);
        for (String pill : new String[] {"in_range", "high", "low"}) {
            assertContrast("range pill " + pill, argb(t.get("glass_range_pill_" + pill + "_text")),
                    argb(t.get("glass_range_pill_" + pill + "_bg")), 4.5);
        }
        assertContrast("on selected container", argb(t.get("glass_on_secondary_container")),
                argb(t.get("glass_secondary_container")), 4.5);
        assertTrue("selected container is visibly different from the card",
                ColorSchemeTest.contrast(argb(t.get("glass_secondary_container")), card) >= 1.15);
        assertContrast("accent on action tint", argb(t.get("glass_accent")), argb(t.get("glass_action_background")), 4.5);
        int[][] heroes = {
            {argb(t.get("glass_reading_normal")), card},
            {argb(t.get("glass_reading_high")), composite(argb(t.get("glass_hero_tint_high")), card)},
            {argb(t.get("glass_reading_low")), composite(argb(t.get("glass_hero_tint_low")), card)},
            {argb(t.get("glass_reading_urgent_low")), composite(argb(t.get("glass_hero_tint_urgent_low")), card)},
        };
        for (int[] h : heroes) {
            assertContrast("reading on hero", h[0], h[1], 4.5);
        }
        for (String band : new String[] {"in_range", "high", "low", "very_low", "very_high"}) {
            assertContrast("band " + band, argb(t.get("glass_band_" + band)), card, 3.0);
        }
        for (String marker : new String[] {"site_sensor", "carbs", "bolus", "bg"}) {
            assertContrast("marker " + marker, argb(t.get("glass_marker_" + marker)), card, 3.0);
        }
        for (String guide : new String[] {"low", "high"}) {
            assertContrast("guide " + guide, argb(t.get("glass_guide_" + guide)), card, 3.0);
        }
        assertContrast("card border on background", argb(t.get("glass_card_border")), bg, 1.1);
    }

    @Test
    public void emptyPlaceholderIsSeparateFromLegacyDash() {
        assertEquals("---", ResXml.string("glass_value_empty"));
        assertEquals("—", ResXml.string("s01_reading_empty"));
        assertNotEquals(ResXml.string("glass_value_empty"), ResXml.string("s01_reading_empty"));
    }

    @Test
    public void fontsAreBundledWithLicencesAndNoRuntimeProvider() throws IOException {
        for (String ttf : new String[] {"dm_sans.ttf", "inter.ttf"}) {
            byte[] bytes = Files.readAllBytes(new File(ResXml.RES, "font/" + ttf).toPath());
            assertTrue(ttf, bytes.length > 100_000);
            assertEquals(ttf, 0x00010000, ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                    | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF));
        }
        String dm = Files.readString(new File(ResXml.RES, "font/glass_dm_sans.xml").toPath(), StandardCharsets.UTF_8);
        String inter = Files.readString(new File(ResXml.RES, "font/glass_inter.xml").toPath(), StandardCharsets.UTF_8);
        assertTrue(dm.contains("@font/dm_sans") && dm.contains("android:fontWeight=\"500\""));
        assertTrue(inter.contains("@font/inter"));
        for (String family : new String[] {dm, inter}) {
            assertFalse(family.contains("fontProvider"));
            assertFalse(family.contains("downloadable"));
        }
        for (String licence : new String[] {"DM-Sans-OFL.txt", "Inter-OFL.txt"}) {
            String text = Files.readString(new File("src/main/resources/licenses/" + licence).toPath(),
                    StandardCharsets.UTF_8);
            assertTrue(licence, text.contains("SIL OPEN FONT LICENSE Version 1.1"));
        }
    }

    @Test
    public void compactChipsKeepA48dpTouchTarget() {
        assertEquals("48dp", ResXml.named("values/glass_dimens.xml", "dimen").get("glass_touch_target"));
        for (String style : new String[] {"Widget.LightScout.Glass.Chip"}) {
            Map<String, String> chip = ResXml.style(STYLES, style);
            assertEquals("@dimen/glass_touch_target", chip.get("android:minHeight"));
            assertEquals("@dimen/glass_touch_target", chip.get("android:minWidth"));
            assertFalse(chip.containsKey("android:layout_height"));
        }
    }
}
