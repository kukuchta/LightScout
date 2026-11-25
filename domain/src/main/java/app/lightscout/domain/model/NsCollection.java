package app.lightscout.domain.model;

/**
 * Nightscout API v3 collections used by LightScout (03 §6.2, renamed from {@code Collection} — CP-00A R-1).
 */
public enum NsCollection {
    /** {@code entries} — CGM readings ({@code sgv}) and meter BG ({@code mbg}). */
    ENTRIES,
    /** {@code treatments} — entries such as BG check, carbs, bolus, site / sensor change. */
    TREATMENTS,
    /** {@code devicestatus} — uploader / pump status (IOB). */
    DEVICESTATUS,
    /** {@code profile} — therapy profile (basal schedule). */
    PROFILE
}
