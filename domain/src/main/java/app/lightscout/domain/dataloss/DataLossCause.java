package app.lightscout.domain.dataloss;

/**
 * The only three No-data cause lines (DS:D-024, CP-00A R-5).
 */
public enum DataLossCause {
    /** No internet on this phone ({@code s09_cause_no_internet}). */
    NO_INTERNET,
    /** Nightscout not responding ({@code s09_cause_ns_not_responding}). */
    NS_NOT_RESPONDING,
    /** Nightscout works, but nothing new arrived from the uploader ({@code s09_cause_uploader}). */
    UPLOADER
}
