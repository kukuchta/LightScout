package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * BUG-08 / ruling Q-95 (SR-21 unchanged): {@code MainActivity}'s clean intent keeps {@code ACTION_MAIN} and
 * {@code CATEGORY_LAUNCHER} only when the incoming intent has them; data, type, extras, clip data, selector, every
 * other action and every other category are dropped; without {@code ACTION_MAIN} the clean intent is component-only.
 * The rule is {@link CleanLaunch}; a source audit pins that {@code MainActivity.cleanIntent} reads nothing else of the
 * incoming intent (device proof: {@code MainActivityDeviceTest} SR-21 tests).
 */
public class CleanLaunchTest {

    private static final String LAUNCHER = CleanLaunch.CATEGORY_LAUNCHER;
    private static final String MAIN = CleanLaunch.ACTION_MAIN;

    @Test
    public void constants_equalTheFrameworkValues() {
        assertEquals(Intent.ACTION_MAIN, MAIN);
        assertEquals(Intent.CATEGORY_LAUNCHER, LAUNCHER);
    }

    @Test
    public void mainWithLauncher_isKept() {
        CleanLaunch keep = CleanLaunch.of(MAIN, Set.of(LAUNCHER));
        assertEquals(MAIN, keep.action());
        assertEquals(Set.of(LAUNCHER), keep.categories());
    }

    @Test
    public void mainWithLauncher_otherCategoriesAreDropped() {
        CleanLaunch keep = CleanLaunch.of(MAIN, Set.of(LAUNCHER, Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE,
                Intent.CATEGORY_HOME, "app.lightscout.category.EVIL"));
        assertEquals(MAIN, keep.action());
        assertEquals(Set.of(LAUNCHER), keep.categories());
    }

    @Test
    public void mainWithoutLauncher_keepsOnlyMain() {
        assertEquals(MAIN, CleanLaunch.of(MAIN, null).action());
        assertTrue(CleanLaunch.of(MAIN, null).categories().isEmpty());
        CleanLaunch keep = CleanLaunch.of(MAIN, Set.of(Intent.CATEGORY_DEFAULT, Intent.CATEGORY_BROWSABLE));
        assertEquals(MAIN, keep.action());
        assertTrue("non-launcher categories dropped", keep.categories().isEmpty());
    }

    @Test
    public void otherActions_yieldAComponentOnlyIntent() {
        for (String action : new String[] {Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_EDIT,
                "app.lightscout.action.SNOOZE", "android.intent.action.MAIN.EVIL", "android.intent.action.main",
                ""}) {
            CleanLaunch keep = CleanLaunch.of(action, Set.of(LAUNCHER, Intent.CATEGORY_BROWSABLE));
            assertNull(action, keep.action());
            assertTrue("no category without MAIN: " + action, keep.categories().isEmpty());
        }
    }

    @Test
    public void noAction_yieldsAComponentOnlyIntent_evenWithLauncher() {
        assertNull(CleanLaunch.of(null, null).action());
        assertTrue(CleanLaunch.of(null, null).categories().isEmpty());
        assertNull(CleanLaunch.of(null, Set.of(LAUNCHER)).action());
        assertTrue(CleanLaunch.of(null, Set.of(LAUNCHER)).categories().isEmpty());
    }

    @Test
    public void keptCategories_areUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> CleanLaunch.of(MAIN, Set.of(LAUNCHER)).categories().add(Intent.CATEGORY_BROWSABLE));
        assertThrows(UnsupportedOperationException.class,
                () -> CleanLaunch.of(MAIN, null).categories().add(Intent.CATEGORY_BROWSABLE));
    }

    /**
     * Data, type, extras, clip data, selector, flags, identifier and source bounds are dropped because
     * {@code cleanIntent} builds a new intent from the component and {@link CleanLaunch} only, and reads nothing but
     * the action and the categories of the incoming intent; both lifecycle entry points sanitise before anything
     * else. This source audit <b>supports</b> the SR-21 device tests in {@code MainActivityDeviceTest} (which observe
     * the real intent on the activity); it does not replace them.
     */
    @Test
    public void mainActivityCleanIntent_readsOnlyActionAndCategories_dataAndExtrasCannotPass() throws IOException {
        String source = withoutComments(mainActivitySource());
        Matcher m = Pattern.compile("private Intent cleanIntent\\(Intent incoming\\) \\{(.*?)\\n    }",
                Pattern.DOTALL).matcher(source);
        assertTrue("MainActivity.cleanIntent(Intent incoming) exists", m.find());
        String body = m.group(1);

        // every use of the incoming intent: only the null check, getAction() once, getCategories() once
        Map<String, Integer> incoming = new TreeMap<>();
        Matcher uses = Pattern.compile("\\bincoming\\b").matcher(body);
        while (uses.find()) {
            String rest = body.substring(uses.start());
            String use = rest.startsWith("incoming == null") ? "incoming == null"
                    : rest.startsWith("incoming.getAction()") ? "incoming.getAction()"
                    : rest.startsWith("incoming.getCategories()") ? "incoming.getCategories()"
                    : rest.substring(0, Math.min(40, rest.length()));
            incoming.merge(use, 1, Integer::sum);
        }
        assertEquals("uses of the incoming intent", Map.of("incoming == null", 1, "incoming.getAction()", 1,
                "incoming.getCategories()", 1), incoming);

        // the clean intent: created from the component only, then only setAction(...) / addCategory(...)
        Matcher cleanUses = Pattern.compile("\\bclean\\b").matcher(body);
        int cleanCount = 0;
        while (cleanUses.find()) {
            String rest = body.substring(cleanUses.start());
            String before = body.substring(Math.max(0, cleanUses.start() - 7), cleanUses.start());
            boolean allowed = (before.equals("Intent ")
                    && rest.startsWith("clean = new Intent().setComponent(getComponentName());"))
                    || rest.startsWith("clean.setAction(keep.action());")
                    || rest.startsWith("clean.addCategory(category);")
                    || (before.endsWith("return ") && rest.startsWith("clean;"));
            assertTrue("allowed use of the clean intent: " + rest.substring(0, Math.min(60, rest.length())),
                    allowed);
            cleanCount++;
        }
        assertEquals("declaration, setAction, addCategory, return", 4, cleanCount);

        // every method call in cleanIntent is on the allowlist (catches setIdentifier, setSourceBounds, putExtra,
        // setData, chained calls on new Intent(), ...)
        Set<String> allowedCalls = Set.of("of", "getAction", "getCategories", "setComponent", "getComponentName",
                "action", "setAction", "categories", "addCategory");
        Matcher calls = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(body);
        while (calls.find()) {
            String call = calls.group(1);
            if (call.equals("if") || call.equals("for") || call.equals("Intent")) {
                continue; // keywords; "Intent(" only as "new Intent()" (checked below)
            }
            assertTrue("cleanIntent calls only allowlisted methods, found " + call, allowedCalls.contains(call));
        }
        assertEquals("one new Intent(), empty", 1, count(body, "new Intent("));
        assertTrue("starts from an empty intent", body.contains("new Intent().setComponent(getComponentName())"));

        // exactly two setIntent calls: onCreate before super.onCreate, onNewIntent before super.onNewIntent
        Matcher setIntents = Pattern.compile("setIntent\\(([^;]*)\\);").matcher(source);
        List<String> arguments = new ArrayList<>();
        while (setIntents.find()) {
            arguments.add(setIntents.group(1));
        }
        assertEquals("setIntent arguments", List.of("cleanIntent(getIntent())", "clean"), arguments);
        // BUG-12: between the clean intent and super.onCreate only the restored root start is read from the saved
        // state and the fragment factory is installed (neither reads the intent).
        assertTrue("onCreate sanitises first, before super.onCreate", source.matches(
                "(?s).*void onCreate\\(Bundle savedInstanceState\\) \\{\\s*"
                        + "setIntent\\(cleanIntent\\(getIntent\\(\\)\\)\\);\\s*"
                        + "NavTarget restored = savedInstanceState == null \\? null\\s*"
                        + ": StartRoute\\.restoredStart\\(savedInstanceState\\.getString\\(KEY_ROOT_START\\)\\);\\s*"
                        + "getSupportFragmentManager\\(\\)\\.setFragmentFactory\\("
                        + "new RootNavHostFragment\\.Factory\\(restored\\)\\);\\s*"
                        + "super\\.onCreate.*"));
        assertTrue("onNewIntent sanitises before super.onNewIntent", source.matches(
                "(?s).*Intent clean = cleanIntent\\(intent\\);\\s*setIntent\\(clean\\);"
                        + "\\s*super\\.onNewIntent\\(clean\\);.*"));
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    /** Removes block and line comments (MainActivity has no comment markers inside string literals). */
    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String mainActivitySource() throws IOException {
        String dir = System.getProperty("lightscout.rootDir");
        assertTrue("lightscout.rootDir is set by app/build.gradle.kts", dir != null);
        File file = new File(dir, "app/src/main/java/app/lightscout/MainActivity.java");
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
