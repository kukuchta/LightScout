package app.lightscout.nightscout;

import java.time.Duration;

/**
 * Transport budget and policy of the Nightscout client (C-3, C-10, C-11, SR-15, SR-18).
 *
 * @param connectTimeout   connect timeout (C-3: 10 s)
 * @param readTimeout      socket read timeout (C-3: 15 s)
 * @param readCallTimeout  whole-call timeout for reads and verification (C-3: 20 s)
 * @param writeCallTimeout whole-call timeout for writes IF-38…IF-41 (C-3, A-09: 15 s)
 * @param maxBodyBytes     decompressed response size cap per call (C-11, SR-18: 8 MB)
 * @param maxRedirects     maximum same-origin https redirect hops followed per call (CP-00A R-40: 3)
 * @param allowCleartext   {@code true} only for the JVM test client on plain-http mockwebserver (CP-00A R-40);
 *                         production never allows cleartext (C-1)
 */
record HttpSettings(Duration connectTimeout, Duration readTimeout, Duration readCallTimeout,
                    Duration writeCallTimeout, long maxBodyBytes, int maxRedirects, boolean allowCleartext) {

    /** The approved production values (C-3, C-11, CP-00A R-40). */
    static final HttpSettings PRODUCTION = new HttpSettings(
            Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(15),
            8L * 1024 * 1024, 3, false);
}
