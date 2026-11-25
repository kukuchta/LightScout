package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.time.ZoneId;
import java.util.List;

import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Trend;

/** ST-01 column encodings (CP-04 §5.1, §8.1). */
public class CacheCodecTest {

    @Test
    public void everyKnownTrendRoundTripsThroughItsNightscoutName() {
        for (Trend trend : Trend.values()) {
            assertEquals(trend, CacheCodec.trend(CacheCodec.direction(trend)));
        }
        assertEquals("DoubleUp", CacheCodec.direction(Trend.DOUBLE_UP));
        assertEquals("FortyFiveDown", CacheCodec.direction(Trend.FORTY_FIVE_DOWN));
    }

    @Test
    public void unknownTrendIsStoredAsNullAndUnknownNamesReadAsUnknown() {
        assertNull(CacheCodec.direction(Trend.UNKNOWN));
        assertEquals(Trend.UNKNOWN, CacheCodec.trend(null));
        assertEquals(Trend.UNKNOWN, CacheCodec.trend("NOT COMPUTABLE"));
        assertEquals(Trend.UNKNOWN, CacheCodec.trend("doubleup"));
    }

    @Test
    public void originUsesTheValuesOfTheDdlCheck() {
        assertEquals("treatments", CacheCodec.origin(EntryOrigin.TREATMENTS));
        assertEquals("entries-mbg", CacheCodec.origin(EntryOrigin.ENTRIES_MBG));
        for (EntryOrigin origin : EntryOrigin.values()) {
            assertEquals(origin, CacheCodec.origin(CacheCodec.origin(origin)));
        }
        assertThrows(IllegalStateException.class, () -> CacheCodec.origin("TREATMENTS"));
    }

    @Test
    public void entryTypeUsesTheConstantName() {
        for (EntryType type : EntryType.values()) {
            assertEquals(type.name(), CacheCodec.entryType(type));
            assertEquals(type, CacheCodec.entryType(CacheCodec.entryType(type)));
        }
        assertThrows(IllegalStateException.class, () -> CacheCodec.entryType("Site Change"));
    }

    @Test
    public void timezoneRoundTripsAndToleratesNullOrInvalidIds() {
        assertEquals("Europe/Warsaw", CacheCodec.timezone(ZoneId.of("Europe/Warsaw")));
        assertEquals(ZoneId.of("Europe/Warsaw"), CacheCodec.timezone("Europe/Warsaw"));
        assertNull(CacheCodec.timezone((ZoneId) null));
        assertNull(CacheCodec.timezone((String) null));
        assertNull(CacheCodec.timezone("Not/AZone"));
    }

    @Test
    public void basalJsonHasTheDdlShapeWithUnitsPerHour() {
        String json = CacheCodec.basalJson(List.of(new BasalStep(0, 100)));
        assertEquals("[{\"timeAsSeconds\":0,\"value\":0.1}]", json.replace(" ", ""));
    }

    @Test
    public void basalJsonRoundTripsMilliUnitsExactly() {
        List<BasalStep> basal = List.of(new BasalStep(0, 100), new BasalStep(3600, 1250),
                new BasalStep(21600, 875), new BasalStep(79200, 0));
        assertEquals(basal, CacheCodec.basal(CacheCodec.basalJson(basal)));
        assertEquals(List.of(), CacheCodec.basal(CacheCodec.basalJson(List.of())));
    }

    @Test
    public void invalidBasalJsonFailsWithAnUncheckedException() {
        assertThrows(IllegalStateException.class, () -> CacheCodec.basal("{not json"));
        assertThrows(IllegalStateException.class, () -> CacheCodec.basal("[{\"value\":1}]"));
    }
}
