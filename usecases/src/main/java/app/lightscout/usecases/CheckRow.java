package app.lightscout.usecases;

/** S-06 result rows 1–4 (C-S06-05, CP-00A R-10): „Nightscout reachable”, „Version”, „Can read data”, „Can save entries”. */
public enum CheckRow {
    /** Row 1: reachable and identified as Nightscout (IF-31, no token). */
    REACHABLE,
    /** Row 2: version ≥ 15.0.9 and API v3. */
    VERSION,
    /** Row 3: token accepted and read permission present. */
    READ,
    /** Row 4: write permission on treatments ({@code c}, {@code u}, {@code d}). */
    WRITE
}
