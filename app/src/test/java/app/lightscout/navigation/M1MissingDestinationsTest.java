package app.lightscout.navigation;

import static org.junit.Assert.assertTrue;

import java.io.File;
import org.junit.Test;

/** Feature graphs delivered through SS-19 / SS-20 are included in the root graph. */
public class M1MissingDestinationsTest {

    private static File root() {
        String dir = System.getProperty("lightscout.rootDir");
        assertTrue("lightscout.rootDir is set by app/build.gradle.kts", dir != null);
        return new File(dir);
    }

    @Test
    public void s04Settings_graphExistsAndIsIncluded() throws Exception {
        File graph = new File(root(), "feature-settings/src/main/res/navigation/settings_graph.xml");
        assertTrue("S-04 graph is missing: " + graph, graph.exists());
        String rootGraph = new String(java.nio.file.Files.readAllBytes(
                new File(root(), "app/src/main/res/navigation/root_graph.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rootGraph.contains("@navigation/settings_graph"));
    }

    @Test
    public void existingGraphs_areIncluded() throws Exception {
        assertTrue(new File(root(), "feature-home/src/main/res/navigation/home_graph.xml").exists());
        assertTrue(new File(root(), "feature-onboarding/src/main/res/navigation/onboarding_graph.xml").exists());
        assertTrue(new File(root(), "feature-entry/src/main/res/navigation/entry_graph.xml").exists());
        assertTrue(new File(root(), "feature-stats/src/main/res/navigation/stats_graph.xml").exists());
        String rootGraph = new String(java.nio.file.Files.readAllBytes(
                new File(root(), "app/src/main/res/navigation/root_graph.xml").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(rootGraph.contains("@navigation/entry_graph"));
        assertTrue(rootGraph.contains("@navigation/stats_graph"));
    }
}
