import com.google.android.material.color.utilities.DynamicColor;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.MaterialDynamicColors;
import com.google.android.material.color.utilities.SchemeTonalSpot;
import com.google.android.material.color.utilities.TonalPalette;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Design-time generator of the LightScout colour resources (CP-00A R-84; SS-08). Not part of the build.
 *
 * <p>Uses material-color-utilities, the engine of Material Theme Builder, as bundled in MDC 1.14.0
 * ({@code classes.jar} of {@code material-1.14.0.aar} from the Gradle cache). Scheme: seed #3F5F90, Tonal Spot,
 * standard contrast (0.0). Extended colour "warning": custom seed #FFB300, harmonisation off, palette as a Theme
 * Builder custom colour (hue of the seed, chroma max(48, seed chroma)), tones 40/100/90/10 light and 80/20/30/90
 * dark.
 *
 * <p>Usage (JDK 17+): {@code javac -cp classes.jar GenerateScheme.java} then
 * {@code java -cp classes.jar;. GenerateScheme light|dark}; paste the output into
 * {@code ui-core/src/main/res/values[-night]/colors.xml}.
 */
public final class GenerateScheme {

    private GenerateScheme() {
    }

    /**
     * Prints the {@code <color>} lines.
     *
     * @param args {@code light} or {@code dark}
     */
    public static void main(String[] args) {
        boolean dark = args.length > 0 && "dark".equals(args[0]);
        DynamicScheme s = new SchemeTonalSpot(Hct.fromInt(0xFF3F5F90), dark, 0.0);
        MaterialDynamicColors m = new MaterialDynamicColors();
        Map<String, DynamicColor> roles = new LinkedHashMap<>();
        roles.put("primary", m.primary());
        roles.put("onPrimary", m.onPrimary());
        roles.put("primaryContainer", m.primaryContainer());
        roles.put("onPrimaryContainer", m.onPrimaryContainer());
        roles.put("secondary", m.secondary());
        roles.put("onSecondary", m.onSecondary());
        roles.put("secondaryContainer", m.secondaryContainer());
        roles.put("onSecondaryContainer", m.onSecondaryContainer());
        roles.put("tertiary", m.tertiary());
        roles.put("onTertiary", m.onTertiary());
        roles.put("tertiaryContainer", m.tertiaryContainer());
        roles.put("onTertiaryContainer", m.onTertiaryContainer());
        roles.put("error", m.error());
        roles.put("onError", m.onError());
        roles.put("errorContainer", m.errorContainer());
        roles.put("onErrorContainer", m.onErrorContainer());
        roles.put("background", m.background());
        roles.put("onBackground", m.onBackground());
        roles.put("surface", m.surface());
        roles.put("onSurface", m.onSurface());
        roles.put("surfaceVariant", m.surfaceVariant());
        roles.put("onSurfaceVariant", m.onSurfaceVariant());
        roles.put("outline", m.outline());
        roles.put("outlineVariant", m.outlineVariant());
        roles.put("scrim", m.scrim());
        roles.put("inverseSurface", m.inverseSurface());
        roles.put("inverseOnSurface", m.inverseOnSurface());
        roles.put("inversePrimary", m.inversePrimary());
        roles.put("primaryFixed", m.primaryFixed());
        roles.put("onPrimaryFixed", m.onPrimaryFixed());
        roles.put("primaryFixedDim", m.primaryFixedDim());
        roles.put("onPrimaryFixedVariant", m.onPrimaryFixedVariant());
        roles.put("secondaryFixed", m.secondaryFixed());
        roles.put("onSecondaryFixed", m.onSecondaryFixed());
        roles.put("secondaryFixedDim", m.secondaryFixedDim());
        roles.put("onSecondaryFixedVariant", m.onSecondaryFixedVariant());
        roles.put("tertiaryFixed", m.tertiaryFixed());
        roles.put("onTertiaryFixed", m.onTertiaryFixed());
        roles.put("tertiaryFixedDim", m.tertiaryFixedDim());
        roles.put("onTertiaryFixedVariant", m.onTertiaryFixedVariant());
        roles.put("surfaceDim", m.surfaceDim());
        roles.put("surfaceBright", m.surfaceBright());
        roles.put("surfaceContainerLowest", m.surfaceContainerLowest());
        roles.put("surfaceContainerLow", m.surfaceContainerLow());
        roles.put("surfaceContainer", m.surfaceContainer());
        roles.put("surfaceContainerHigh", m.surfaceContainerHigh());
        roles.put("surfaceContainerHighest", m.surfaceContainerHighest());
        for (Map.Entry<String, DynamicColor> e : roles.entrySet()) {
            System.out.printf("    <color name=\"md_theme_%s\">#%06X</color>%n", e.getKey(),
                    e.getValue().getArgb(s) & 0xFFFFFF);
        }
        Hct amber = Hct.fromInt(0xFFFFB300);
        TonalPalette warning = TonalPalette.fromHueAndChroma(amber.getHue(), Math.max(48.0, amber.getChroma()));
        int[] tones = dark ? new int[] {80, 20, 30, 90} : new int[] {40, 100, 90, 10};
        String[] names = {"warning", "onWarning", "warningContainer", "onWarningContainer"};
        for (int i = 0; i < names.length; i++) {
            System.out.printf("    <color name=\"md_theme_%s\">#%06X</color>%n", names[i],
                    warning.tone(tones[i]) & 0xFFFFFF);
        }
    }
}
