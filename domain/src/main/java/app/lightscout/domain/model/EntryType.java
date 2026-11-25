package app.lightscout.domain.model;

/**
 * Kind of an {@link Entry} (G-06, 03 §6.1).
 */
public enum EntryType {
    /** Blood-glucose check (finger stick). */
    BG_CHECK,
    /** Carbohydrates in grams. */
    CARBS,
    /** Insulin dose logged as a note only — never delivered. */
    BOLUS,
    /** Infusion-site change. */
    SITE_CHANGE,
    /** CGM sensor change ({@code Sensor Start} maps here, M-1). */
    SENSOR_CHANGE,
    /** Pump alert from the uploader; read-only, never loggable. */
    PUMP_ALERT,
    /** Temporary basal from the uploader; read-only, never loggable in S-02. */
    TEMP_BASAL
}
