package app.lightscout.platform.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

/**
 * {@link FileLineFile} on a real file: append, read, atomic rewrite over an existing file.
 */
public class FileLineFileTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void missingFileReadsEmptyAndHasZeroLength() throws Exception {
        FileLineFile file = new FileLineFile(new File(folder.getRoot(), "log.txt"));
        assertEquals(0, file.length());
        assertTrue(file.readLines().isEmpty());
    }

    @Test
    public void appendThenReadKeepsOrderAndLength() throws Exception {
        File target = new File(folder.getRoot(), "log.txt");
        FileLineFile file = new FileLineFile(target);
        file.append("a");
        file.append("bc");
        assertEquals(List.of("a", "bc"), file.readLines());
        assertEquals(5, file.length());
    }

    @Test
    public void rewriteReplacesContentAndLeavesNoTemporaryFile() throws Exception {
        File target = new File(folder.getRoot(), "log.txt");
        FileLineFile file = new FileLineFile(target);
        file.append("old1");
        file.append("old2");
        file.rewrite(List.of("new"));
        assertEquals(List.of("new"), file.readLines());
        assertFalse(new File(folder.getRoot(), "log.txt.tmp").exists());
    }
}
