package app.lightscout.platform.manufacturer;

import android.os.Build;

import app.lightscout.platform.Manufacturer;
import app.lightscout.platform.ManufacturerDetector;

/**
 * CMP-28 {@link ManufacturerDetector} on {@code Build.MANUFACTURER} / {@code Build.BRAND}
 * ({@link ManufacturerMapping}, CP-00A R-85).
 */
public final class BuildManufacturerDetector implements ManufacturerDetector {

    @Override
    public Manufacturer detect() {
        return ManufacturerMapping.map(Build.MANUFACTURER, Build.BRAND);
    }
}
