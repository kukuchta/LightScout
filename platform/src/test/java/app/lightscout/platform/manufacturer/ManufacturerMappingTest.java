package app.lightscout.platform.manufacturer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import app.lightscout.platform.Manufacturer;

/**
 * CP-05 §9 „ManufacturerDetector (JVM)”: known strings map to the six options; unknown → OTHER (R-85).
 */
public class ManufacturerMappingTest {

    @Test
    public void knownManufacturers() {
        assertEquals(Manufacturer.XIAOMI, ManufacturerMapping.map("Xiaomi", "Xiaomi"));
        assertEquals(Manufacturer.SAMSUNG, ManufacturerMapping.map("samsung", "samsung"));
        assertEquals(Manufacturer.HUAWEI, ManufacturerMapping.map("HUAWEI", "HUAWEI"));
        assertEquals(Manufacturer.HUAWEI, ManufacturerMapping.map("HONOR", "HONOR"));
        assertEquals(Manufacturer.ONEPLUS, ManufacturerMapping.map("OnePlus", "OnePlus"));
        assertEquals(Manufacturer.ONEPLUS, ManufacturerMapping.map("OPPO", "OPPO"));
        assertEquals(Manufacturer.ONEPLUS, ManufacturerMapping.map("realme", "realme"));
        assertEquals(Manufacturer.PIXEL, ManufacturerMapping.map("Google", "google"));
    }

    @Test
    public void brandIsUsedWhenManufacturerUnknown() {
        assertEquals(Manufacturer.XIAOMI, ManufacturerMapping.map("unknown-odm", "Redmi"));
        assertEquals(Manufacturer.XIAOMI, ManufacturerMapping.map("", "POCO"));
        assertEquals(Manufacturer.ONEPLUS, ManufacturerMapping.map(null, "realme"));
    }

    @Test
    public void manufacturerWinsOverBrand() {
        assertEquals(Manufacturer.SAMSUNG, ManufacturerMapping.map("samsung", "google"));
    }

    @Test
    public void caseAndWhitespaceInsensitive() {
        assertEquals(Manufacturer.XIAOMI, ManufacturerMapping.map("  XIAOMI ", null));
    }

    @Test
    public void unknownIsOther() {
        assertEquals(Manufacturer.OTHER, ManufacturerMapping.map("Motorola", "motorola"));
        assertEquals(Manufacturer.OTHER, ManufacturerMapping.map("Xiaomi Inc", "Nokia"));
        assertEquals(Manufacturer.OTHER, ManufacturerMapping.map(null, null));
        assertEquals(Manufacturer.OTHER, ManufacturerMapping.map("", ""));
    }
}
