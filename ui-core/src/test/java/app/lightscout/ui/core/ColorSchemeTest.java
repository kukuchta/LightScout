package app.lightscout.ui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

/**
 * GIR-10 / M3-07 contrast spot checks (§9 "Contrast", TST-06/07 spot checks) and the colour role table of CP-00A
 * R-84, computed from the committed light and dark colour resources (WCAG 2.x relative luminance).
 */
public class ColorSchemeTest {

    private static final String[] FOLDERS = {"values", "values-night"};

    private static final String[] ROLES = {
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondary", "onSecondary",
        "secondaryContainer", "onSecondaryContainer", "tertiary", "onTertiary", "tertiaryContainer",
        "onTertiaryContainer", "error", "onError", "errorContainer", "onErrorContainer", "background",
        "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant", "outline", "outlineVariant",
        "scrim", "inverseSurface", "inverseOnSurface", "inversePrimary", "primaryFixed", "onPrimaryFixed",
        "primaryFixedDim", "onPrimaryFixedVariant", "secondaryFixed", "onSecondaryFixed", "secondaryFixedDim",
        "onSecondaryFixedVariant", "tertiaryFixed", "onTertiaryFixed", "tertiaryFixedDim", "onTertiaryFixedVariant",
        "surfaceDim", "surfaceBright", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
        "surfaceContainerHigh", "surfaceContainerHighest", "warning", "onWarning", "warningContainer",
        "onWarningContainer",
    };

    static double luminance(int argb) {
        double[] c = new double[3];
        int[] channel = {(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
        for (int i = 0; i < 3; i++) {
            double s = channel[i] / 255.0;
            c[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static void assertContrast(String folder, Map<String, Integer> c, String fg, String bg, double min) {
        double ratio = contrast(c.get("md_theme_" + fg), c.get("md_theme_" + bg));
        assertTrue(String.format("%s: %s on %s = %.2f < %.1f", folder, fg, bg, ratio, min), ratio >= min);
    }

    @Test
    public void lightAndDarkDefineTheSameFullRoleSet() {
        for (String folder : FOLDERS) {
            Map<String, Integer> c = ResXml.colors(folder);
            assertEquals(folder, ROLES.length, c.size());
            for (String role : ROLES) {
                assertTrue(folder + " " + role, c.containsKey("md_theme_" + role));
            }
        }
    }

    @Test
    public void seedAndWarningTonesPerR84() {
        Map<String, Integer> light = ResXml.colors("values");
        Map<String, Integer> dark = ResXml.colors("values-night");
        assertEquals(0xFF3F5F90, (int) light.get("md_theme_primary"));
        assertEquals(0xFFFFFFFF, (int) light.get("md_theme_onWarning"));          // tone 100
        assertEquals(0xFF7E5700, (int) light.get("md_theme_warning"));            // tone 40 of #FFB300
        assertEquals(0xFFFFBA38, (int) dark.get("md_theme_warning"));             // tone 80
        assertEquals(light.get("md_theme_warningContainer"), dark.get("md_theme_onWarningContainer")); // tone 90
    }

    @Test
    public void noPureBlackSurfaces() {
        for (String folder : FOLDERS) {
            Map<String, Integer> c = ResXml.colors(folder);
            for (String role : ROLES) {
                if (role.startsWith("surface") || role.equals("background")) {
                    assertNotEquals(folder + " " + role, 0xFF000000, (int) c.get("md_theme_" + role));
                }
            }
        }
    }

    @Test
    public void textRolesReachFourPointFiveToOne() {
        for (String folder : FOLDERS) {
            Map<String, Integer> c = ResXml.colors(folder);
            assertContrast(folder, c, "onSurface", "surface", 4.5);            // in range text
            assertContrast(folder, c, "onSurfaceVariant", "surface", 4.5);     // greyed reading, supporting text
            assertContrast(folder, c, "error", "surface", 4.5);                // Low, Expired, stale age
            assertContrast(folder, c, "warning", "surface", 4.5);              // High, Change soon
            assertContrast(folder, c, "onErrorContainer", "errorContainer", 4.5);     // URGENT LOW badge
            assertContrast(folder, c, "onWarningContainer", "warningContainer", 4.5); // very high
            assertContrast(folder, c, "onTertiaryContainer", "tertiaryContainer", 4.5); // M3-08 banners
            assertContrast(folder, c, "onSecondaryContainer", "secondaryContainer", 4.5);
            assertContrast(folder, c, "onPrimary", "primary", 4.5);
            for (String surface : new String[] {"surfaceContainer", "surfaceContainerHigh",
                "surfaceContainerHighest"}) {
                assertContrast(folder, c, "onSurface", surface, 4.5);
                assertContrast(folder, c, "error", surface, 4.5);
            }
        }
    }

    @Test
    public void drawnElementsReachThreeToOne() {
        for (String folder : FOLDERS) {
            Map<String, Integer> c = ResXml.colors(folder);
            for (String surface : new String[] {"surface", "surfaceContainer", "surfaceContainerHighest"}) {
                assertContrast(folder, c, "primary", surface, 3.0);     // chart and mini-chart line / dots in range
                assertContrast(folder, c, "outline", surface, 3.0);     // threshold lines
                assertContrast(folder, c, "tertiary", surface, 3.0);    // markers
                assertContrast(folder, c, "error", surface, 3.0);       // pump-alert marker, Low dots
                assertContrast(folder, c, "warning", surface, 3.0);     // High dots, very-high stroke (Q-55)
                assertContrast(folder, c, "secondary", surface, 3.0);   // basal band edge line (Q-55)
            }
        }
    }
}
