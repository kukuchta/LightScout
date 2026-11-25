package app.lightscout.platform.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * TST-13 / SR-07 redaction allow-list (CP-05 §9): secrets, URLs, hosts, bodies, values and free text never pass;
 * the R-13 vocabulary does.
 */
public class LogRedactionTest {

    @Test
    public void forbiddenInputsAreRedacted() {
        String[] forbidden = {
            "lightscout-1a2b3c4d5e6f7a8b",                                  // token-like subject token
            "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc",              // Authorization value / JWT
            "eyJhbGciOiJIUzI1NiJ9",                                          // bare JWT part
            "/api/v2/authorization/request/lightscout-1a2b3c4d5e6f7a8b",    // auth path
            "https://ns.example.com/api/v3/entries?limit=10&token=abc",     // URL with host and query
            "ns.example.com",                                                // host
            "example.com",                                                   // two-label host
            "?token=abc",                                                    // query string
            "{\"sgv\":120,\"direction\":\"Flat\"}",                          // body
            "sgv=120",                                                       // glucose value with label
            "120 mg/dL",                                                     // glucose value with unit
            "1.5 U",                                                         // insulin value
            "Pizza with friends",                                            // free text
            "Failed to connect to ns.example.com/203.0.113.7:443",          // exception message with host
            "NO_INTERNET:https://ns.example.com",                           // mixed: one bad segment
            "SYNC_OK ",                                                      // trailing space
            "ABC123DEF456",                                                  // upper-case token-like blob
            ":NO_INTERNET",
            "NO_INTERNET:",
            "abcdef",                                                        // plain lower-case (IR-55)
            "readable",
            "Flat",                                                          // mixed case
            "DoubleUp",
            "Sensor Change",
            "NO_INTERNET:readable",
        };
        for (String input : forbidden) {
            assertEquals(input, LogRedaction.REDACTED, LogRedaction.detail(input));
        }
    }

    @Test
    public void fixedVocabularyPasses() {
        String[] allowed = {
            "NO_INTERNET:NO_NETWORK",   // SYNC_FAIL FetchOutcome:DetailCode
            "NS_NOT_RESPONDING:HTTP_5XX",
            "TOKEN_REJECTED:AUTH_401",
            "OK:REJECTED_400_422",
            "LOW>URGENT_LOW",           // ALARM_REPLACE from>to
            "ENTRIES:3",                // MAPPING_SKIPPED collection:count
            "1234",                     // TICK_LATE ms
            "readings.upsert",          // STORAGE_ERROR op
            "connection.baseUrl",
            "alarm_state.get",
            "device_status.evictBefore",
            "CREATED",
        };
        for (String input : allowed) {
            assertEquals(input, input, LogRedaction.detail(input));
        }
    }

    @Test
    public void storeOpWithUnknownPrefixIsRedacted() {
        assertEquals(LogRedaction.REDACTED, LogRedaction.detail("nightscout.io"));
        assertEquals(LogRedaction.REDACTED, LogRedaction.detail("herokuapp.com"));
    }

    @Test
    public void emptyOrNullDetailIsNoDetail() {
        assertNull(LogRedaction.detail(null));
        assertNull(LogRedaction.detail(""));
    }

    @Test
    public void overlongDetailIsRedacted() {
        assertEquals(LogRedaction.REDACTED, LogRedaction.detail("A".repeat(200)));
    }

    @Test
    public void javaNamesPassButMessagesDoNot() {
        assertEquals("IllegalStateException", LogRedaction.javaName("IllegalStateException"));
        assertEquals("DefaultSyncCycle.runOnce", LogRedaction.javaName("DefaultSyncCycle.runOnce"));
        assertEquals("Outer$Inner.<init>", LogRedaction.javaName("Outer$Inner.<init>"));
        assertEquals(LogRedaction.REDACTED, LogRedaction.javaName("timeout https://ns.example.com"));
        assertEquals(LogRedaction.REDACTED, LogRedaction.javaName(null));
        assertEquals(LogRedaction.REDACTED, LogRedaction.javaName(""));
    }
}
