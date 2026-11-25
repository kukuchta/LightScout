package app.lightscout.platform.manufacturer;

import java.util.Locale;

import app.lightscout.platform.Manufacturer;

/**
 * Maps {@code Build.MANUFACTURER} / {@code Build.BRAND} to the six S-08 options (CP-00A R-85). Pure.
 *
 * <p>Each value is trimmed and lower-cased ({@link Locale#ROOT}) and compared exactly: xiaomi / redmi / poco →
 * XIAOMI; samsung → SAMSUNG; huawei / honor → HUAWEI; oneplus / oppo / realme → ONEPLUS; google → PIXEL. The
 * manufacturer is tried first, then the brand; anything else → OTHER.
 */
public final class ManufacturerMapping {

    private ManufacturerMapping() {
    }

    /**
     * Maps the build values.
     *
     * @param manufacturer {@code Build.MANUFACTURER}; may be {@code null}
     * @param brand        {@code Build.BRAND}; may be {@code null}
     * @return the option; {@link Manufacturer#OTHER} when neither value is known
     */
    public static Manufacturer map(String manufacturer, String brand) {
        Manufacturer byManufacturer = mapOne(manufacturer);
        return byManufacturer != Manufacturer.OTHER ? byManufacturer : mapOne(brand);
    }

    private static Manufacturer mapOne(String value) {
        if (value == null) {
            return Manufacturer.OTHER;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "xiaomi", "redmi", "poco" -> Manufacturer.XIAOMI;
            case "samsung" -> Manufacturer.SAMSUNG;
            case "huawei", "honor" -> Manufacturer.HUAWEI;
            case "oneplus", "oppo", "realme" -> Manufacturer.ONEPLUS;
            case "google" -> Manufacturer.PIXEL;
            default -> Manufacturer.OTHER;
        };
    }
}
