package app.lightscout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * TST-13 merged-manifest audit (CP-15 §8.5, §9; SR-06, SR-14…SR-23, SR-25, SR-27; Q-36, Q-48, Q-63, Q-73 / SS-10 §5.4)
 * on the merged manifests produced by {@code process<Variant>Manifest} (paths from {@code app/build.gradle.kts}),
 * plus the network security config and backup rule sources.
 */
public class Tst13ManifestAuditTest {

    private static final String A = "http://schemas.android.com/apk/res/android";
    private static final String P = "android.permission.";

    /** PERM-01 … PERM-12 (SR-27). */
    private static final Set<String> PERMISSIONS = Set.of(P + "INTERNET", P + "ACCESS_NETWORK_STATE",
            P + "POST_NOTIFICATIONS", P + "FOREGROUND_SERVICE", P + "FOREGROUND_SERVICE_SPECIAL_USE",
            P + "RECEIVE_BOOT_COMPLETED", P + "USE_EXACT_ALARM", P + "SCHEDULE_EXACT_ALARM",
            P + "USE_FULL_SCREEN_INTENT", P + "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", P + "ACCESS_NOTIFICATION_POLICY",
            P + "VIBRATE", P + "WAKE_LOCK");

    private static final String[] VARIANTS = {"release", "nightly", "debug", "debugProxy"};

    // ------------------------------------------------------------------ permissions

    @Test
    public void permissions_exactlyTheAllowListPlusTheAndroidXSignaturePermission_eachOnce() throws Exception {
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            String pkg = m.getDocumentElement().getAttribute("package");
            List<String> declared = new ArrayList<>();
            for (Element e : elements(m, "uses-permission")) {
                declared.add(e.getAttributeNS(A, "name"));
            }
            assertTrue(variant + ": no uses-permission-sdk-23", elements(m, "uses-permission-sdk-23").isEmpty());
            Set<String> expected = new TreeSet<>(PERMISSIONS);
            String dynamic = pkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
            expected.add(dynamic);
            assertEquals(variant + ": permission set", expected, new TreeSet<>(declared));
            assertEquals(variant + ": every permission exactly once (PERM-10 incl.)", expected.size(),
                    declared.size());
            List<Element> defined = elements(m, "permission");
            assertEquals(variant + ": only the AndroidX signature permission is defined", 1, defined.size());
            assertEquals(dynamic, defined.get(0).getAttributeNS(A, "name"));
            assertEquals("signature", defined.get(0).getAttributeNS(A, "protectionLevel"));
        }
    }

    @Test
    public void scheduleExactAlarm_maxSdk32_andNoQueryAllPackages() throws Exception {
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            for (Element e : elements(m, "uses-permission")) {
                if ((P + "SCHEDULE_EXACT_ALARM").equals(e.getAttributeNS(A, "name"))) {
                    assertEquals(variant, "32", e.getAttributeNS(A, "maxSdkVersion"));
                } else {
                    assertEquals(variant + " " + e.getAttributeNS(A, "name"), "", e.getAttributeNS(A, "maxSdkVersion"));
                }
            }
            assertFalse(text(m).contains("QUERY_ALL_PACKAGES"));
        }
    }

    // ------------------------------------------------------------------ components

    @Test
    public void exportedComponents_release_mainActivityAndWidget() throws Exception {
        assertEquals(Set.of("app.lightscout.MainActivity", "app.lightscout.widget.GlucoseWidgetProvider"),
                exported(manifest("release")));
    }

    @Test
    public void exportedComponents_debug_addOnlyTheDumpProtectedLoopBreakReceiver() throws Exception {
        for (String variant : new String[] {"debug", "debugProxy"}) {
            Document m = manifest(variant);
            assertEquals(variant, Set.of("app.lightscout.MainActivity", "app.lightscout.widget.GlucoseWidgetProvider",
                            "app.lightscout.monitor.DebugLoopBreakReceiver"),
                    exported(m));
            assertEquals(P + "DUMP", component(m, "receiver", "app.lightscout.monitor.DebugLoopBreakReceiver")
                    .getAttributeNS(A, "permission"));
        }
    }

    @Test
    public void release_hasNoDebugLoopBreakReceiver_q63() throws Exception {
        assertFalse(text(manifest("release")).contains("DebugLoopBreakReceiver"));
    }

    @Test
    public void mainActivity_launcherFilterOnly_portrait_adjustResize_neverOverKeyguard() throws Exception {
        for (String variant : VARIANTS) {
            Element main = component(manifest(variant), "activity", "app.lightscout.MainActivity");
            List<Element> filters = children(main, "intent-filter");
            assertEquals(variant + ": exactly one intent filter (SR-20)", 1, filters.size());
            Element filter = filters.get(0);
            assertEquals(List.of("android.intent.action.MAIN"), names(children(filter, "action")));
            assertEquals(List.of("android.intent.category.LAUNCHER"), names(children(filter, "category")));
            assertTrue("no data / deep link", children(filter, "data").isEmpty());
            assertTrue("no shortcuts / app links meta-data", children(main, "meta-data").isEmpty());
            assertEquals("portrait", main.getAttributeNS(A, "screenOrientation"));
            assertEquals("adjustResize", main.getAttributeNS(A, "windowSoftInputMode"));
            assertEquals("", main.getAttributeNS(A, "showWhenLocked"));
            assertEquals("", main.getAttributeNS(A, "turnScreenOn"));
            assertEquals("true", main.getAttributeNS(A, "exported"));
        }
    }

    @Test
    public void internalDeepLinks_areNoExternalSurface_noNavGraph_noInternalScheme_q94() throws Exception {
        // BUG-05 / Q-94: S-06 / S-07 carry internal <deepLink>s (scheme lightscout-internal) in onboarding_graph.xml;
        // they must never become intent filters (no <nav-graph> in any manifest, nothing of the scheme merged).
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            assertTrue(variant + ": no <nav-graph>", elements(m, "nav-graph").isEmpty());
            assertFalse(variant + ": internal scheme not in the manifest", text(m).contains("lightscout-internal"));
            for (Element filter : elements(m, "intent-filter")) {
                for (Element data : children(filter, "data")) {
                    assertFalse(variant + ": no internal scheme in an intent filter",
                            data.getAttributeNS(A, "scheme").startsWith("lightscout"));
                }
            }
        }
        File root = new File(System.getProperty("lightscout.rootDir"));
        File[] modules = root.listFiles(File::isDirectory);
        assertNotNull(modules);
        int checked = 0;
        for (File module : modules) {
            File src = new File(module, "src");
            File[] sourceSets = src.listFiles(File::isDirectory);
            if (sourceSets == null) {
                continue;
            }
            for (File sourceSet : sourceSets) {
                File manifest = new File(sourceSet, "AndroidManifest.xml");
                if (manifest.exists()) {
                    String content = Files.readString(manifest.toPath(), StandardCharsets.UTF_8);
                    assertFalse(manifest + ": no <nav-graph>", content.contains("<nav-graph"));
                    assertFalse(manifest + ": no internal scheme", content.contains("lightscout-internal"));
                    checked++;
                }
            }
        }
        assertTrue("source manifests found", checked > 0);
    }

    @Test
    public void portraitLock_onMainActivityAndAlarmActivity_andOnlyAlarmActivityShowsWhenLocked() throws Exception {
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            List<Element> activities = elements(m, "activity");
            assertEquals(variant + ": two activities (ADR-022)", 2, activities.size());
            Element alarm = component(m, "activity", "app.lightscout.alarm.AlarmActivity");
            assertEquals("portrait", alarm.getAttributeNS(A, "screenOrientation"));
            assertEquals("true", alarm.getAttributeNS(A, "showWhenLocked"));
            assertEquals("false", alarm.getAttributeNS(A, "exported"));
            assertTrue("S-09 has no intent filter", children(alarm, "intent-filter").isEmpty());
        }
    }

    @Test
    public void receivers_bootTimeAlarmMonitor_notExported_bootDirectBootAware() throws Exception {
        Document m = manifest("release");
        for (String name : List.of("app.lightscout.monitor.BootReceiver", "app.lightscout.monitor.TimeChangeReceiver",
                "app.lightscout.monitor.MonitorAlarmReceiver", "app.lightscout.monitor.WatchdogReceiver",
                "app.lightscout.alarm.SnoozeReceiver", "app.lightscout.alarm.AlarmDeleteReceiver")) {
            assertEquals(name, "false", component(m, "receiver", name).getAttributeNS(A, "exported"));
        }
        Element boot = component(m, "receiver", "app.lightscout.monitor.BootReceiver");
        assertEquals("true", boot.getAttributeNS(A, "directBootAware"));
        assertEquals(List.of("android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED"), names(children(children(boot, "intent-filter").get(0),
                "action")));
        Element time = component(m, "receiver", "app.lightscout.monitor.TimeChangeReceiver");
        assertEquals(List.of("android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED"),
                names(children(children(time, "intent-filter").get(0), "action")));
        assertEquals("exactly the seven app receivers, including S-12", 7, elements(m, "receiver").size());
    }

    @Test
    public void widgetProvider_exportedForUpdateOnly_andBindingPermission() throws Exception {
        for (String variant : VARIANTS) {
            Element widget = component(manifest(variant), "receiver", "app.lightscout.widget.GlucoseWidgetProvider");
            assertEquals(variant, "true", widget.getAttributeNS(A, "exported"));
            assertEquals(variant, P + "BIND_APPWIDGET", widget.getAttributeNS(A, "permission"));
            List<Element> filters = children(widget, "intent-filter");
            assertEquals(variant, 1, filters.size());
            assertEquals(List.of("android.appwidget.action.APPWIDGET_UPDATE"),
                    names(children(filters.get(0), "action")));
            assertTrue(children(filters.get(0), "category").isEmpty());
            assertTrue(children(filters.get(0), "data").isEmpty());
        }
    }

    @Test
    public void monitorService_specialUseWithSubtype_notExported_jobServiceProtected() throws Exception {
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            Element service = component(m, "service", "app.lightscout.monitor.MonitorService");
            assertEquals("specialUse", service.getAttributeNS(A, "foregroundServiceType"));
            assertEquals("false", service.getAttributeNS(A, "exported"));
            Element property = children(service, "property").get(0);
            assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", property.getAttributeNS(A, "name"));
            assertEquals("Continuous glucose follower: monitoring and alarms", property.getAttributeNS(A, "value"));
            Element job = component(m, "service", "app.lightscout.monitor.MonitorJobService");
            assertEquals(P + "BIND_JOB_SERVICE", job.getAttributeNS(A, "permission"));
            assertEquals("false", job.getAttributeNS(A, "exported"));
            assertEquals(2, elements(m, "service").size());
        }
    }

    @Test
    public void providers_onlyTheNonExportedInitializationProvider_noEmojiFontInitializer_q36() throws Exception {
        for (String variant : VARIANTS) {
            Document m = manifest(variant);
            List<Element> providers = elements(m, "provider");
            assertEquals(variant, 1, providers.size());
            Element startup = providers.get(0);
            assertEquals("androidx.startup.InitializationProvider", startup.getAttributeNS(A, "name"));
            assertEquals("false", startup.getAttributeNS(A, "exported"));
            List<String> initializers = new ArrayList<>();
            for (Element meta : children(startup, "meta-data")) {
                initializers.add(meta.getAttributeNS(A, "name"));
            }
            assertTrue(initializers.toString(), initializers.contains("okhttp3.internal.platform.PlatformInitializer"));
            assertTrue(initializers.toString(), initializers.contains("androidx.lifecycle.ProcessLifecycleInitializer"));
            assertFalse("SR-19 (no font download)", initializers.contains("androidx.emoji2.text.EmojiCompatInitializer"));
            assertFalse("SR-20: exported ProfileInstallReceiver removed", text(m).contains("ProfileInstallReceiver"));
        }
    }

    @Test
    public void noUserUnlockedBroadcast_f5() throws Exception {
        for (String variant : VARIANTS) {
            assertFalse(variant, text(manifest(variant)).contains("USER_UNLOCKED"));
        }
    }

    @Test
    public void queries_fromPlatform_only() throws Exception {
        Document m = manifest("release");
        List<Element> queries = elements(m, "queries");
        assertEquals(1, queries.size());
        Set<String> packages = new TreeSet<>();
        for (Element p : children(queries.get(0), "package")) {
            packages.add(p.getAttributeNS(A, "name"));
        }
        assertEquals(Set.of("com.miui.securitycenter", "com.miui.powerkeeper"), packages);
        Set<String> actions = new TreeSet<>();
        for (Element intent : children(queries.get(0), "intent")) {
            actions.addAll(names(children(intent, "action")));
        }
        assertEquals(new TreeSet<>(Set.of("android.settings.APP_NOTIFICATION_SETTINGS",
                "android.settings.CHANNEL_NOTIFICATION_SETTINGS", "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
                "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
                "android.settings.APPLICATION_DETAILS_SETTINGS")), actions);
        assertTrue("no <provider> query", children(queries.get(0), "provider").isEmpty());
    }

    // ------------------------------------------------------------------ application attributes

    @Test
    public void application_backupOff_cleartextOff_networkConfig_releaseNotDebuggable() throws Exception {
        for (String variant : VARIANTS) {
            Element app = elements(manifest(variant), "application").get(0);
            assertEquals("app.lightscout.LightScoutApplication", app.getAttributeNS(A, "name"));
            assertEquals("false", app.getAttributeNS(A, "allowBackup"));
            assertEquals("@xml/data_extraction_rules", app.getAttributeNS(A, "dataExtractionRules"));
            assertEquals("@xml/backup_rules", app.getAttributeNS(A, "fullBackupContent"));
            assertEquals("false", app.getAttributeNS(A, "usesCleartextTraffic"));
            assertEquals("@xml/network_security_config", app.getAttributeNS(A, "networkSecurityConfig"));
            assertEquals("true", app.getAttributeNS(A, "enableOnBackInvokedCallback"));
            if ("release".equals(variant) || "nightly".equals(variant)) {
                assertFalse("release not debuggable", "true".equals(app.getAttributeNS(A, "debuggable")));
                assertEquals("", app.getAttributeNS(A, "testOnly"));
            }
        }
        assertEquals("app.lightscout", manifest("release").getDocumentElement().getAttribute("package"));
        assertEquals("app.lightscout.nightly", manifest("nightly").getDocumentElement().getAttribute("package"));
        assertEquals("app.lightscout.debug", manifest("debug").getDocumentElement().getAttribute("package"));
        assertEquals("app.lightscout.debug", manifest("debugProxy").getDocumentElement().getAttribute("package"));
    }

    @Test
    public void backupRules_excludeEveryDomain_inCloudBackupAndDeviceTransfer() throws Exception {
        Set<String> all = Set.of("root", "file", "database", "sharedpref", "external", "device_root", "device_file",
                "device_database", "device_sharedpref");
        Document rules = xml(source("src/main/res/xml/data_extraction_rules.xml"));
        for (String section : List.of("cloud-backup", "device-transfer")) {
            Element s = elements(rules, section).get(0);
            assertEquals(section, all, excludedDomains(s));
            assertTrue(section + ": no include", children(s, "include").isEmpty());
        }
        Document full = xml(source("src/main/res/xml/backup_rules.xml"));
        assertEquals(all, excludedDomains(full.getDocumentElement()));
        assertTrue(children(full.getDocumentElement(), "include").isEmpty());
    }

    // ------------------------------------------------------------------ network security config (TST-13 NSC)

    @Test
    public void networkSecurityConfig_releaseAndDebug_systemCasOnly_noCleartext_noExceptions_noPins()
            throws Exception {
        Document nsc = xml(source("src/main/res/xml/network_security_config.xml"));
        assertEquals(List.of("system"), trustAnchors(nsc));
        assertNsHardening(nsc);
        for (String sourceSet : List.of("debug", "release")) {
            assertFalse(sourceSet + " must use the main config (system CAs only)",
                    source("src/" + sourceSet + "/res/xml/network_security_config.xml").exists());
        }
    }

    @Test
    public void networkSecurityConfig_debugProxy_onlyVariantWithUserCas() throws Exception {
        Document nsc = xml(source("src/debugProxy/res/xml/network_security_config.xml"));
        assertEquals(List.of("system", "user"), trustAnchors(nsc));
        assertNsHardening(nsc);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertNsHardening(Document nsc) {
        Element base = elements(nsc, "base-config").get(0);
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"));
        assertTrue("no domain exceptions", elements(nsc, "domain-config").isEmpty());
        assertTrue("no debug-overrides", elements(nsc, "debug-overrides").isEmpty());
        assertTrue("no pinning", elements(nsc, "pin-set").isEmpty());
    }

    private static List<String> trustAnchors(Document nsc) {
        List<String> sources = new ArrayList<>();
        for (Element c : elements(nsc, "certificates")) {
            sources.add(c.getAttribute("src"));
        }
        return sources;
    }

    private static Set<String> excludedDomains(Element section) {
        Set<String> domains = new TreeSet<>();
        for (Element e : children(section, "exclude")) {
            assertEquals(".", e.getAttribute("path"));
            domains.add(e.getAttribute("domain"));
        }
        return domains;
    }

    private static final Map<String, Document> CACHE = new HashMap<>();

    private static synchronized Document manifest(String variant) throws Exception {
        Document cached = CACHE.get(variant);
        if (cached != null) {
            return cached;
        }
        String path = System.getProperty("lightscout.mergedManifest." + variant);
        assertNotNull("merged manifest path for " + variant + " (set by app/build.gradle.kts)", path);
        Document d = xml(new File(path));
        CACHE.put(variant, d);
        return d;
    }

    private static File source(String relative) {
        String root = System.getProperty("lightscout.rootDir");
        assertNotNull(root);
        return new File(new File(root, "app"), relative);
    }

    private static Document xml(File file) throws Exception {
        assertTrue(file + " exists", file.exists());
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(file);
    }

    private static String text(Document d) throws Exception {
        return Files.readString(new File(new java.net.URI(d.getDocumentURI())).toPath(), StandardCharsets.UTF_8);
    }

    private static List<Element> elements(Document d, String tag) {
        NodeList list = d.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(((Element) n).getTagName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static List<String> names(List<Element> elements) {
        List<String> out = new ArrayList<>();
        for (Element e : elements) {
            out.add(e.getAttributeNS(A, "name"));
        }
        return out;
    }

    private static Element component(Document d, String tag, String name) {
        for (Element e : elements(d, tag)) {
            if (name.equals(e.getAttributeNS(A, "name"))) {
                return e;
            }
        }
        throw new AssertionError(tag + " " + name + " not in the merged manifest");
    }

    /** Components with exported=true (or exported implied by an intent filter without an explicit value). */
    private static Set<String> exported(Document d) {
        Set<String> out = new TreeSet<>();
        for (String tag : List.of("activity", "activity-alias", "service", "receiver", "provider")) {
            for (Element e : elements(d, tag)) {
                String exported = e.getAttributeNS(A, "exported");
                boolean implied = exported.isEmpty() && !children(e, "intent-filter").isEmpty();
                if ("true".equals(exported) || implied) {
                    out.add(e.getAttributeNS(A, "name"));
                }
            }
        }
        return out;
    }
}
