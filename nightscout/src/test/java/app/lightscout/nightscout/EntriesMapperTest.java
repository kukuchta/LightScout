package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.time.Instant;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.EntryOrigin;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.Trend;

/**
 * TST-03 mapper tests for the {@code entries} collection (CP-03 §9.2 TM-01…TM-04, TM-15…TM-17).
 */
public class EntriesMapperTest {

    /** TM-01 (T-1, UG-08, UG-13): FX-01 → Reading(id, time = date, sgv, trend, device); extra fields ignored. */
    @Test
    public void tm01_sgvDocumentMapsToReadingIgnoringOffsetsAndNumericTrend() throws Exception {
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-01-sgv.json"));
        assertEquals(2, m.items().size());
        assertEquals(0, m.skipped());
        assertEquals(new Reading("fx01-sgv-0001", Instant.ofEpochMilli(Fakes.T0), 123, Trend.FLAT, "share2"),
                m.items().get(0));
        assertEquals(Trend.FORTY_FIVE_UP, m.items().get(1).trend());
    }

    /** TM-02 (05 §4.1, M3-34): the 7 directions map 1:1; FX-02 values → UNKNOWN. */
    @Test
    public void tm02_directionsMapToTrendAndUnknownValuesToUnknown() throws Exception {
        assertEquals(Trend.DOUBLE_UP, EntriesMapper.trend("DoubleUp"));
        assertEquals(Trend.SINGLE_UP, EntriesMapper.trend("SingleUp"));
        assertEquals(Trend.FORTY_FIVE_UP, EntriesMapper.trend("FortyFiveUp"));
        assertEquals(Trend.FLAT, EntriesMapper.trend("Flat"));
        assertEquals(Trend.FORTY_FIVE_DOWN, EntriesMapper.trend("FortyFiveDown"));
        assertEquals(Trend.SINGLE_DOWN, EntriesMapper.trend("SingleDown"));
        assertEquals(Trend.DOUBLE_DOWN, EntriesMapper.trend("DoubleDown"));
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-02-sgv-unknown-direction.json"));
        assertEquals(5, m.items().size());
        for (Reading r : m.items()) {
            assertEquals(Trend.UNKNOWN, r.trend());
        }
        assertEquals(Trend.UNKNOWN, EntriesMapper.trend("flat"));
    }

    /** TM-03 (DR-1): two near-duplicate readings stay two readings (no dedup in the mapper). */
    @Test
    public void tm03_nearDuplicatesAreBothReturned() throws Exception {
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-03-sgv-near-duplicates.json"));
        assertEquals(List.of("fx03-a", "fx03-b"), m.items().stream().map(Reading::id).toList());
    }

    /** TM-04 (M-3): FX-04 → Entry(BG_CHECK, ENTRIES_MBG, glucose = mbg, own = false). */
    @Test
    public void tm04_mbgMapsToReadOnlyBgCheck() throws Exception {
        Mapped<Entry> m = EntriesMapper.meterBg(Fixtures.resultArray("FX-04-mbg.json"));
        assertEquals(1, m.items().size());
        Entry e = m.items().get(0);
        assertEquals("fx04-mbg-0001", e.id());
        assertEquals(EntryOrigin.ENTRIES_MBG, e.origin());
        assertEquals(EntryType.BG_CHECK, e.type());
        assertEquals(Integer.valueOf(111), e.glucose());
        assertEquals(Instant.ofEpochMilli(Fakes.T0), e.time());
        assertFalse(e.own());
        assertNull(e.srvModifiedMs());
    }

    /** Type filter safety: an sgv document is not a meter BG and vice versa (ignored, not counted). */
    @Test
    public void otherTypesAreIgnoredNotCounted() throws Exception {
        JSONArray mixed = new JSONArray()
                .put(new JSONObject("{\"identifier\":\"a\",\"type\":\"sgv\",\"sgv\":100,\"date\":1}"))
                .put(new JSONObject("{\"identifier\":\"b\",\"type\":\"mbg\",\"mbg\":100,\"date\":1}"))
                .put(new JSONObject("{\"identifier\":\"c\",\"type\":\"cal\",\"date\":1}"));
        Mapped<Reading> readings = EntriesMapper.readings(mixed);
        assertEquals(1, readings.items().size());
        assertEquals(0, readings.skipped());
        assertEquals(3, readings.rawCount());
        assertEquals(1, EntriesMapper.meterBg(mixed).items().size());
    }

    /** TM-15 (M-8, SR-29): invalid documents inside a valid array are skipped and counted, no exception. */
    @Test
    public void tm15_malformedEntriesAreSkippedAndCounted() throws Exception {
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-16-malformed-entries.json"));
        assertEquals(List.of("fx16-ok-1", "fx16-ok-2"), m.items().stream().map(Reading::id).toList());
        assertEquals(5, m.skipped());
        assertEquals(7, m.rawCount());
    }

    /** TM-16 (M-9): future-dated readings (incl. a low one) are returned, not dropped. */
    @Test
    public void tm16_futureDatedReadingsAreKept() throws Exception {
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-17-future-dated.json"));
        assertEquals(2, m.items().size());
        assertEquals(50, m.items().get(0).sgv());
    }

    /** TM-17 (M-10, VR-21 default): every sgv ≥ 1 is a reading (1, 39, 40, 401); sgv 0 is skipped. */
    @Test
    public void tm17_everySgvAtLeastOneIsAReading() throws Exception {
        Mapped<Reading> m = EntriesMapper.readings(Fixtures.resultArray("FX-18-special-sgv.json"));
        assertEquals(List.of(1, 39, 40, 401), m.items().stream().map(Reading::sgv).toList());
        assertEquals(1, m.skipped());
    }

    /** M-11 applies to the free-text device name as well. */
    @Test
    public void deviceIsSanitized() throws Exception {
        Reading r = EntriesMapper.reading(new JSONObject(
                "{\"identifier\":\"d\",\"type\":\"sgv\",\"sgv\":90,\"date\":1,\"device\":\"dev\\u0000ice\\nname\"}"));
        assertEquals("device name", r.device());
    }
}
