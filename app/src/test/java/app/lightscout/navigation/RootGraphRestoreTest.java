package app.lightscout.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * BUG-12: on re-creation (process death, configuration change) the root graph exists before the FragmentManager
 * restores the child fragments, while the BUG-05 / Q-94 runtime root start stays (no {@code app:navGraph}, SR-21
 * clean intent first, IF-27 not resolved again). The framework order itself is proven by the instrumented
 * {@code MainActivityDeviceTest} recreate cases; these JVM checks guard the wiring.
 */
public class RootGraphRestoreTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String APP = "http://schemas.android.com/apk/res-auto";

    /** Any fragment class, including dialog / bottom-sheet fragments and intermediate base classes. */
    private static final Pattern EXTENDS_FRAGMENT = Pattern.compile("extends\\s+\\w*Fragment\\b");
    /** Name-based SR-21 matches: also catch method references ({@code ::getIntent}) and {@code getIntent (}. */
    private static final Pattern GET_INTENT = Pattern.compile("\\bgetIntent\\b");
    private static final Pattern HANDLE_DEEP_LINK = Pattern.compile("\\bhandleDeepLink\\b");

    /** Fragments that reach a graph entry ({@code getBackStackEntry}); each has a recreate case on the device. */
    private static final Set<String> GRAPH_SCOPED = Set.of(
            "feature-onboarding/src/main/java/app/lightscout/feature/onboarding/connection/ConnectionFragment.java");

    @Test
    public void activityLayout_hostsRootNavHostFragment_withoutManifestOrLayoutGraph() throws Exception {
        Document layout = xml(new File(root(), "app/src/main/res/layout/activity_main.xml"));
        Element host = layout.getDocumentElement();
        assertEquals("androidx.fragment.app.FragmentContainerView", host.getTagName());
        assertEquals(RootNavHostFragment.class.getName(), host.getAttributeNS(ANDROID, "name"));
        assertFalse("no app:navGraph: the root graph is set at runtime (Q-94)", host.hasAttributeNS(APP, "navGraph"));
    }

    @Test
    public void mainActivity_cleanIntentThenFactoryWithRestoredStart_beforeSuperOnCreate() throws Exception {
        String src = read("app/src/main/java/app/lightscout/MainActivity.java");
        int clean = src.indexOf("setIntent(cleanIntent(getIntent()))");
        int restored = src.indexOf("StartRoute.restoredStart(savedInstanceState.getString(KEY_ROOT_START))");
        int factory = src.indexOf("setFragmentFactory(new RootNavHostFragment.Factory(restored))");
        int superOnCreate = src.indexOf("super.onCreate(savedInstanceState)");
        assertTrue("SR-21 clean intent present", clean >= 0);
        assertTrue("restored start read from KEY_ROOT_START", restored >= 0);
        assertTrue("factory installed with the restored start", factory >= 0);
        assertTrue("super.onCreate present", superOnCreate >= 0);
        assertTrue("SR-21 clean intent comes first", clean < restored && clean < factory);
        assertTrue("restored start is read before super.onCreate", restored < superOnCreate);
        assertTrue("factory is installed before super.onCreate (fragment restore)", factory < superOnCreate);
        assertTrue("IF-27 still guarded by the restored start",
                src.indexOf("StartRoute.needsResolve(restored)") > superOnCreate);
    }

    @Test
    public void rootNavHost_setsTheGraphBeforeTheBaseOnCreateRestoresTheChildren() throws Exception {
        String src = read("app/src/main/java/app/lightscout/navigation/RootNavHostFragment.java");
        int onCreate = src.indexOf("public void onCreate(");
        int setGraph = src.indexOf("NavControllerPort.setRootGraph(getNavController(), restoredStart)", onCreate);
        int superOnCreate = src.indexOf("super.onCreate(savedInstanceState)", onCreate);
        assertTrue(onCreate >= 0 && setGraph > onCreate && superOnCreate > setGraph);
    }

    @Test
    public void factory_acceptsOnlyAGraphOrNoStart() {
        new RootNavHostFragment.Factory(null);
        new RootNavHostFragment.Factory(NavTarget.HOME);
        new RootNavHostFragment.Factory(NavTarget.ONBOARDING);
        assertThrows(IllegalArgumentException.class, () -> new RootNavHostFragment.Factory(NavTarget.CONNECTION));
        assertThrows(IllegalArgumentException.class, () -> new RootNavHostFragment.Factory(NavTarget.READINESS));
    }

    @Test
    public void restoredStart_handsTheSavedGraphToTheHost_orNothingBeforeTheFirstResolve() {
        for (NavTarget start : List.of(NavTarget.HOME, NavTarget.ONBOARDING)) {
            NavTarget restored = StartRoute.restoredStart(StartRoute.saveStart(start));
            assertEquals(start, restored);
            assertFalse("a restored back stack never resolves IF-27 again", StartRoute.needsResolve(restored));
        }
        assertTrue("no saved start (death before the first resolve): no graph, resolve again",
                StartRoute.needsResolve(StartRoute.restoredStart(null)));
    }

    @Test
    public void graphScopedFragments_areKnown_eachHasARecreateCase() throws Exception {
        Set<String> found = new TreeSet<>();
        for (Path p : mainSources(true)) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            if (text.contains(".getBackStackEntry(") && EXTENDS_FRAGMENT.matcher(text).find()) {
                found.add(relative(p));
            }
        }
        assertEquals("a new graph-scoped fragment needs a recreate / process-death device case (BUG-12)",
                new TreeSet<>(GRAPH_SCOPED), found);
    }

    /**
     * SR-21 (🔐 BUG-12 F1, F4): nothing in {@code :app}, {@code :ui-core} or a feature module reads the launch intent or hands an intent
     * to NavController's deep-link handling; the only {@code getIntent()} is MainActivity's sanitising
     * {@code setIntent(cleanIntent(getIntent()))}.
     */
    @Test
    public void noIntentReads_noHandleDeepLink_exceptTheCleanIntent_sr21() throws Exception {
        String allowed = "setIntent(cleanIntent(getIntent()))";
        String mainActivity = "app/src/main/java/app/lightscout/MainActivity.java";
        List<String> violations = new ArrayList<>();
        for (Path p : mainSources(true)) {
            String text = Files.readString(p, StandardCharsets.UTF_8); // comments included: strictest
            String path = relative(p);
            if (path.equals(mainActivity)) {
                assertEquals(mainActivity + ": exactly one sanitising getIntent()", 1, count(text, allowed));
                text = text.replace(allowed, "");
            }
            if (GET_INTENT.matcher(text).find()) {
                violations.add(path + ": getIntent");
            }
            if (HANDLE_DEEP_LINK.matcher(text).find()) {
                violations.add(path + ": handleDeepLink");
            }
        }
        assertEquals("SR-21: no intent reads / deep-link handling", List.of(), violations);
    }

    /** Main Java sources of {@code :app} and of every {@code feature-*} module (plus {@code :ui-core} if asked). */
    private static List<Path> mainSources(boolean withUiCore) throws IOException {
        List<File> modules = new ArrayList<>();
        modules.add(new File(root(), "app"));
        if (withUiCore) {
            modules.add(new File(root(), "ui-core"));
        }
        File[] features = root().listFiles(f -> f.isDirectory() && f.getName().startsWith("feature-"));
        assertTrue("feature modules found", features != null && features.length > 0);
        modules.addAll(Arrays.asList(features));
        List<Path> sources = new ArrayList<>();
        for (File module : modules) {
            Path src = new File(module, "src/main/java").toPath();
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                sources.addAll(files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList()));
            }
        }
        return sources;
    }

    private static String relative(Path p) {
        return root().toPath().relativize(p).toString().replace('\\', '/');
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    private static String read(String path) throws IOException {
        File file = new File(root(), path);
        assertTrue(file + " exists", file.exists());
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static File root() {
        String dir = System.getProperty("lightscout.rootDir");
        assertTrue("lightscout.rootDir is set by app/build.gradle.kts", dir != null);
        return new File(dir);
    }

    private static Document xml(File file) throws Exception {
        assertTrue(file + " exists", file.exists());
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(file);
    }
}
