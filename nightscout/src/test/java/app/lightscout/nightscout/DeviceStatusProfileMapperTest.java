package app.lightscout.nightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import app.lightscout.domain.model.BasalStep;
import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Profile;

/**
 * TST-03 mapper tests for {@code devicestatus}, {@code profile} and history (CP-03 §9.2 TM-19, TM-20; IF-37).
 */
public class DeviceStatusProfileMapperTest {

    /** TM-19 (05 §4.3, R-40): IOB from the first present source; phone-only doc → null. */
    @Test
    public void tm19_iobFromFirstPresentSource() throws Exception {
        Mapped<DeviceStatus> m = DeviceStatusMapper.map(Fixtures.resultArray("FX-20-devicestatus.json"));
        assertEquals(6, m.items().size());
        assertEquals(0, m.skipped());
        assertNull(m.items().get(0).iobCenti());
        assertEquals("phone-model-placeholder", m.items().get(0).device());
        assertEquals(Instant.ofEpochMilli(Fakes.T0), m.items().get(0).time());
        assertEquals(Integer.valueOf(123), m.items().get(1).iobCenti());   // openaps wins over loop
        assertEquals(Integer.valueOf(46), m.items().get(2).iobCenti());    // loop 0.456
        assertEquals(Integer.valueOf(201), m.items().get(3).iobCenti());   // pump 2.01
        assertEquals(Integer.valueOf(75), m.items().get(4).iobCenti());    // top-level number
        assertEquals(Integer.valueOf(-25), m.items().get(5).iobCenti());   // top-level object
    }

    /** M-8: a devicestatus without created_at is skipped and counted. */
    @Test
    public void deviceStatusWithoutTimeIsSkipped() throws Exception {
        Mapped<DeviceStatus> m = DeviceStatusMapper.map(new org.json.JSONArray()
                .put(new JSONObject("{\"identifier\":\"x\",\"iob\":1}")));
        assertEquals(0, m.items().size());
        assertEquals(1, m.skipped());
    }

    /** TM-20 (05 §4.3): zone from timezone, basal steps from store[defaultProfile].basal[] (× 1000, rounded). */
    @Test
    public void tm20_profile() throws Exception {
        JSONObject doc = Fixtures.resultArray("FX-21-profile.json").getJSONObject(0);
        Profile p = ProfileMapper.map(doc, ZoneId.of("UTC"));
        assertEquals("fx21-0001", p.id());
        assertEquals(ZoneId.of("Europe/Warsaw"), p.zone());
        assertEquals(Instant.parse("2029-12-01T00:00:00Z"), p.start());
        assertEquals(List.of(new BasalStep(0, 550), new BasalStep(23_400, 813), new BasalStep(79_200, 400)),
                p.basal());
    }

    /** Profile without timezone uses the phone zone; without the default profile → invalid (M-8). */
    @Test
    public void profileFallbacks() throws Exception {
        Profile p = ProfileMapper.map(new JSONObject("{\"identifier\":\"p\",\"defaultProfile\":\"A\","
                + "\"startDate\":\"2030-01-01T00:00:00Z\",\"store\":{\"A\":{\"basal\":[{\"timeAsSeconds\":0,"
                + "\"value\":1}]}}}"), ZoneId.of("Europe/Warsaw"));
        assertEquals(ZoneId.of("Europe/Warsaw"), p.zone());
        assertThrows(Json.InvalidDocument.class, () -> ProfileMapper.map(new JSONObject(
                "{\"identifier\":\"p\",\"defaultProfile\":\"B\",\"startDate\":\"2030-01-01T00:00:00Z\","
                        + "\"store\":{\"A\":{\"basal\":[]}}}"), ZoneId.of("UTC")));
    }

    /** IF-37 / SY-7: isValid:false → deletion with null payloads; sgv / mbg payloads; missing srvModified skipped. */
    @Test
    public void historyMapping() throws Exception {
        Mapped<Change> m = HistoryMapper.map(NsCollection.ENTRIES, Fixtures.resultArray("FX-26-history.json"));
        assertEquals(3, m.items().size());
        assertEquals(1, m.skipped());
        Change sgv = m.items().get(0);
        assertEquals(1_893_456_001_000L, sgv.srvModifiedMs());
        assertEquals(130, sgv.reading().sgv());
        Change deleted = m.items().get(1);
        assertEquals(true, deleted.deleted());
        assertEquals("fx26-deleted", deleted.id());
        assertNull(deleted.reading());
        assertNull(deleted.entry());
        assertNull(deleted.deviceStatus());
        assertEquals(Integer.valueOf(120), m.items().get(2).entry().glucose());
    }

    /** IR-34 / Q-39: a treatments history document that maps to no shown entry → deletion of the base id. */
    @Test
    public void historyHiddenTreatmentIsDeletion() throws Exception {
        Mapped<Change> m = HistoryMapper.map(NsCollection.TREATMENTS, new org.json.JSONArray()
                .put(new JSONObject("{\"identifier\":\"x1\",\"eventType\":\"Note\","
                        + "\"created_at\":\"2030-01-01T00:00:00Z\",\"srvModified\":9}"))
                .put(new JSONObject("{\"identifier\":\"x2\",\"eventType\":\"Announcement\","
                        + "\"created_at\":\"2030-01-01T00:00:00Z\",\"srvModified\":10}")));
        assertEquals(2, m.items().size());
        assertEquals(0, m.skipped());
        for (Change ch : m.items()) {
            assertEquals(true, ch.deleted());
            assertNull(ch.entry());
        }
        assertEquals(List.of("x1", "x2"), m.items().stream().map(Change::id).toList());
        assertEquals(9L, m.items().get(0).srvModifiedMs());
    }

    /** IF-37 treatments: a split document yields one change per entry. */
    @Test
    public void historySplitTreatment() throws Exception {
        Mapped<Change> m = HistoryMapper.map(NsCollection.TREATMENTS, new org.json.JSONArray().put(new JSONObject(
                "{\"identifier\":\"h\",\"carbs\":5,\"insulin\":1,\"created_at\":\"2030-01-01T00:00:00Z\","
                        + "\"srvModified\":7}")));
        assertEquals(List.of("h#carbs", "h#insulin"), m.items().stream().map(Change::id).toList());
    }
}
