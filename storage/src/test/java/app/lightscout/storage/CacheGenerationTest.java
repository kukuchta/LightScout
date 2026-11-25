package app.lightscout.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** Cache re-creation counter used to reset the sync cursors (A-5 ruling, R-21). */
public class CacheGenerationTest {

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final CacheGeneration generation = new CacheGeneration(prefs);

    @Test
    public void startsAtZeroAndBumpsByOneWithCommit() {
        assertEquals(0L, generation.current());
        generation.bump();
        generation.bump();
        assertEquals(2L, generation.current());
        assertEquals(2, prefs.commits);
        assertEquals(0, prefs.applies);
        assertEquals(1, prefs.values.get(PrefsSchema.KEY));
    }

    @Test
    public void aValueOfTheWrongTypeIsUnreadableAndReplacedByTheNextBump() {
        prefs.values.put(CacheGeneration.KEY, "x");
        assertEquals(CacheGeneration.UNREADABLE, generation.current());
        generation.bump();
        assertEquals(1L, generation.current());
    }

    @Test
    public void createBumpsOnlyWhenAMonitorStateIsStored() {
        generation.bumpIfMonitorStateExists();
        assertEquals("fresh install (IR-20)", 0L, generation.current());
        prefs.values.put(PrefsMonitorStateStore.KEY, "{}");
        generation.bumpIfMonitorStateExists();
        assertEquals(1L, generation.current());
    }

    @Test
    public void failedCommitMakesTheDatabaseOpenFail() {
        prefs.failCommits = true;
        assertThrows(IllegalStateException.class, generation::bump);
    }
}
