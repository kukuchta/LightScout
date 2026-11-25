package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** TST-05-01 (static part): current cache schema with {@code sync_meta} dropped (ADR-071, CP-00A R-21). */
public class CacheSchemaTest {

    @Test
    public void versionIsTwoAndFileIsCacheDb() {
        assertEquals(2, CacheSchema.VERSION);
        assertEquals("cache.db", CacheSchema.DB_NAME);
    }

    @Test
    public void createsExactlyTheFourCacheTables() {
        List<String> created = new ArrayList<>();
        for (String statement : CacheSchema.CREATE_STATEMENTS) {
            if (statement.startsWith("CREATE TABLE ")) {
                created.add(statement.substring("CREATE TABLE ".length(), statement.indexOf(' ', 13)));
            }
        }
        assertEquals(List.of("readings", "entries", "device_status", "profile"), created);
        assertEquals(created, CacheSchema.TABLES);
    }

    @Test
    public void createsTheFourIndexes() {
        List<String> created = new ArrayList<>();
        for (String statement : CacheSchema.CREATE_STATEMENTS) {
            if (statement.startsWith("CREATE INDEX ")) {
                created.add(statement.substring("CREATE INDEX ".length(), statement.indexOf(' ', 13)));
            }
        }
        assertEquals(CacheSchema.INDEXES, created);
    }

    @Test
    public void noSyncMetaTableAndNoCompleteFromColumn() {
        for (String statement : CacheSchema.CREATE_STATEMENTS) {
            String lower = statement.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("sync_meta"));
            assertFalse("completeFrom lives in MonitorState (R-21)", lower.contains("complete_from"));
        }
        assertEquals(List.of("sync_meta"), CacheSchema.LEGACY_TABLES);
    }

    @Test
    public void keyColumnsOfTheDdlArePresent() {
        String readings = CacheSchema.CREATE_STATEMENTS.get(0);
        assertTrue(readings.contains("id TEXT PRIMARY KEY"));
        assertTrue(readings.contains("fetched_at_ms INTEGER NOT NULL"));
        assertTrue(readings.contains("direction TEXT,"));
        String entries = CacheSchema.CREATE_STATEMENTS.get(2);
        assertTrue(entries.contains("CHECK (origin IN ('treatments','entries-mbg'))"));
        assertTrue(entries.contains("insulin_centi INTEGER"));
        assertTrue(entries.contains("insulin_milli INTEGER"));
        assertTrue(entries.contains("is_own INTEGER NOT NULL DEFAULT 0"));
        assertTrue(entries.contains("srv_modified_ms INTEGER"));
        String profile = CacheSchema.CREATE_STATEMENTS.get(7);
        assertTrue(profile.contains("basal_json TEXT NOT NULL"));
    }

    @Test
    public void dropStatementAcceptsOnlyCacheTableNames() {
        assertEquals("DROP TABLE IF EXISTS readings", CacheSchema.dropStatement("readings"));
        assertEquals("DROP TABLE IF EXISTS sync_meta", CacheSchema.dropStatement("sync_meta"));
        assertThrows(IllegalArgumentException.class, () -> CacheSchema.dropStatement("readings; DROP TABLE x"));
    }
}
