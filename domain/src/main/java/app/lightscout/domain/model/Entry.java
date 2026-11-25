package app.lightscout.domain.model;

import java.time.Instant;

/**
 * Any event of Nightscout treatments or meter BG entries (G-06 „Entry”, ENT-02, 03 §6.1).
 *
 * <p>Uploader documents that hold both carbs and insulin become two read-only entries with ids
 * {@code {id}#carbs} and {@code {id}#insulin} (M-2); they are always {@code own = false}.
 *
 * @param id             Nightscout {@code identifier} (or a split id)
 * @param origin         source collection
 * @param type           entry kind
 * @param time           event time (UTC)
 * @param glucose        mg/dL; nullable
 * @param carbs          grams; nullable
 * @param insulinCenti   compatible rounded insulin projection in centi-units (0.01 U, M-5); nullable
 * @param insulinMilli   authoritative treatment dose in milli-units (0.001 U); nullable. Missing bolus milli
 *                       falls back to legacy centi times ten; edit, write and display use milli (R-91).
 * @param basalRateMilli temp-basal rate in milli-U/h; nullable
 * @param durationMin    duration in minutes; nullable
 * @param text           notes (capped and stripped by the mapping); nullable
 * @param enteredBy      Nightscout {@code enteredBy}; nullable
 * @param app            Nightscout {@code app}; nullable
 * @param eventTypeRaw   Nightscout {@code eventType} as received; nullable
 * @param own            {@code true} when created by LightScout ({@code app == "LightScout"}, M-4)
 * @param srvModifiedMs  server modification time in epoch ms, only when really stored; nullable
 */
public record Entry(String id, EntryOrigin origin, EntryType type, Instant time,
                    Integer glucose, Integer carbs, Integer insulinCenti, Long insulinMilli,
                    Integer basalRateMilli, Integer durationMin, String text,
                    String enteredBy, String app, String eventTypeRaw,
                    boolean own, Long srvModifiedMs) {

    /** Creates an entry, retaining exact milli or deriving it only for a genuinely centi-only legacy bolus. */
    public Entry {
        if (insulinMilli == null && type == EntryType.BOLUS && insulinCenti != null) {
            insulinMilli = insulinCenti.longValue() * 10L;
        }
    }

    /**
     * Backward-compatible constructor for callers that only supply the existing centi-unit value.
     */
    public Entry(String id, EntryOrigin origin, EntryType type, Instant time,
                 Integer glucose, Integer carbs, Integer insulinCenti,
                 Integer basalRateMilli, Integer durationMin, String text,
                 String enteredBy, String app, String eventTypeRaw,
                 boolean own, Long srvModifiedMs) {
        this(id, origin, type, time, glucose, carbs, insulinCenti, null, basalRateMilli, durationMin, text,
                enteredBy, app, eventTypeRaw, own, srvModifiedMs);
    }
}
