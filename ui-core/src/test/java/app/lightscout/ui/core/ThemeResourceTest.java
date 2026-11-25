package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Static audit of the theme resources against CP-06 §7.2 / GIR-8, GIR-9 (§9 "Theme"; the on-device resolution is
 * checked by {@code ThemeDeviceTest}): DayNight parent, every colour role a static LightScout resource (no dynamic
 * colour on any API level), Outlined text fields, M3 shape scale, app bar lifts by colour without shadow, and the
 * typography roles.
 */
public class ThemeResourceTest {

    private static final String THEMES = "values/themes.xml";
    private static final String STYLES = "values/styles.xml";

    @Test
    public void themeIsDayNightNoActionBarWithStaticColourRoles() {
        Map<String, String> theme = ResXml.style(THEMES, "Theme.LightScout");
        assertEquals("Theme.Material3.DayNight.NoActionBar", theme.get("@parent"));
        int colourRoles = 0;
        for (Map.Entry<String, String> e : theme.entrySet()) {
            String name = e.getKey();
            boolean colourRole = name.startsWith("color") || name.equals("android:colorBackground")
                    || name.equals("warning") || name.equals("onWarning") || name.equals("warningContainer")
                    || name.equals("onWarningContainer");
            if (colourRole) {
                colourRoles++;
                assertTrue(name + " = " + e.getValue(), e.getValue().startsWith("@color/md_theme_"));
            }
        }
        assertEquals(50, colourRoles);   // 51 resources; scrim is not a theme role
    }

    @Test
    public void noDynamicColourAnywhere() throws IOException {
        for (Path p : files(new File("src/main"))) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            assertFalse(p + " uses DynamicColors", text.contains("DynamicColors"));
            assertFalse(p + " uses system dynamic colours", text.contains("@android:color/system_"));
        }
        assertFalse("no API-31 colour overrides", new File(ResXml.RES, "values-v31").exists());
    }

    @Test
    public void outlinedTextFieldsAppWide() {
        assertEquals("@style/Widget.LightScout.TextInputLayout",
                ResXml.style(THEMES, "Theme.LightScout").get("textInputStyle"));
        assertEquals("Widget.Material3.TextInputLayout.OutlinedBox",
                ResXml.style(STYLES, "Widget.LightScout.TextInputLayout").get("@parent"));
    }

    @Test
    public void shapeScaleIsM3Default() {
        assertEquals("8dp", ResXml.style(STYLES, "ShapeAppearance.LightScout.Small").get("cornerSize"));
        assertEquals("12dp", ResXml.style(STYLES, "ShapeAppearance.LightScout.Medium").get("cornerSize"));
        assertEquals("16dp", ResXml.style(STYLES, "ShapeAppearance.LightScout.Large").get("cornerSize"));
        assertEquals("50%", ResXml.style(STYLES, "ShapeAppearance.LightScout.Full").get("cornerSize"));
        assertEquals("@style/ShapeAppearance.LightScout.Full",
                ResXml.style(STYLES, "Widget.LightScout.Button").get("shapeAppearance"));
        assertEquals("@style/ShapeAppearance.LightScout.Medium",
                ResXml.style(STYLES, "Widget.LightScout.CardView.Outlined").get("shapeAppearance"));
        assertEquals("@style/ShapeAppearance.LightScout.Large",
                ResXml.style(STYLES, "Widget.LightScout.FloatingActionButton").get("shapeAppearanceOverlay"));
        assertEquals("@style/ShapeAppearance.LightScout.Large",
                ResXml.style(STYLES, "Widget.LightScout.BottomSheet.Modal").get("shapeAppearance"));
    }

    @Test
    public void surfacesPerM337() {
        Map<String, String> appBar = ResXml.style(STYLES, "Widget.LightScout.AppBarLayout");
        assertEquals("?attr/colorSurface", appBar.get("android:background"));
        assertEquals("?attr/colorSurfaceContainer", appBar.get("liftOnScrollColor"));
        assertEquals("@null", appBar.get("android:stateListAnimator"));
        assertEquals("0dp", appBar.get("android:elevation"));
        assertEquals("?attr/colorSurface",
                ResXml.style(STYLES, "Widget.LightScout.CardView.Outlined").get("cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerHighest",
                ResXml.style(STYLES, "Widget.LightScout.CardView.Filled").get("cardBackgroundColor"));
        assertEquals("?attr/colorSurfaceContainerHigh",
                ResXml.style(THEMES, "MaterialAlertDialog.LightScout").get("backgroundTint"));
    }

    @Test
    public void typographyRolesPerSection72() {
        String[][] roles = {
            {"ScreenTitle", "TitleLarge"}, {"CurrentReading", "DisplayLarge"},
            {"CurrentReading.Widget", "DisplaySmall"}, {"AlarmTitle", "HeadlineLarge"},
            {"CounterTitle", "TitleSmall"}, {"CounterValue", "HeadlineSmall"}, {"CounterStatus", "BodySmall"},
            {"DoughnutCentre", "TitleLarge"}, {"DoughnutLegend", "BodyMedium"}, {"ChartLabel", "LabelSmall"},
            {"ListPrimary", "BodyLarge"}, {"Supporting", "BodyMedium"}, {"SupportingSmall", "BodySmall"},
        };
        for (String[] r : roles) {
            Map<String, String> style = ResXml.style(STYLES, "TextAppearance.LightScout." + r[0]);
            assertEquals(r[0], "TextAppearance.Material3." + r[1], style.get("@parent"));
        }
    }

    @Test
    public void sharedStylesSetNoFixedTextHeight() throws IOException {
        String styles = Files.readString(new File(ResXml.RES, STYLES).toPath(), StandardCharsets.UTF_8);
        for (String forbidden : new String[] {"android:maxLines", "android:lines\"", "android:height",
            "android:layout_height", "android:singleLine"}) {
            assertFalse(forbidden, styles.contains(forbidden));
        }
    }

    private static List<Path> files(File root) throws IOException {
        try (Stream<Path> s = Files.walk(root.toPath())) {
            return s.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".ttf"))
                    .collect(Collectors.toList());
        }
    }
}
