package lightscout.buildlogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** JVM tests of the CP-00 §3 module map and the declared-dependency rules (R-1 … R-6, CP-00 §2). */
public class ModuleRulesTest {

    private static final List<String> ALL = List.of(":domain", ":usecases", ":nightscout", ":storage", ":platform",
            ":ui-core", ":alarm", ":monitor", ":widget", ":feature-home", ":feature-entry", ":feature-stats",
            ":feature-settings", ":feature-onboarding", ":app");

    @Test
    public void moduleMap_containsExactlyTheCp00Modules() {
        assertEquals(Set.copyOf(ALL), ModuleRules.allowedEdges().keySet());
    }

    @Test
    public void matrix_equalsCp00Section3() {
        Map<String, Set<String>> e = ModuleRules.allowedEdges();
        assertEquals(Set.of(), e.get(":domain"));
        assertEquals(Set.of(":domain"), e.get(":usecases"));
        assertEquals(Set.of(":domain"), e.get(":nightscout"));
        assertEquals(Set.of(":domain"), e.get(":storage"));
        assertEquals(Set.of(":domain"), e.get(":platform"));
        assertEquals(Set.of(":domain", ":platform"), e.get(":ui-core"));
        Set<String> runtimeAndFeatures = Set.of(":domain", ":usecases", ":platform", ":ui-core");
        for (String m : List.of(":alarm", ":monitor", ":widget", ":feature-home", ":feature-entry",
                ":feature-stats", ":feature-settings", ":feature-onboarding")) {
            assertEquals(m, runtimeAndFeatures, e.get(m));
        }
        Set<String> appTargets = new java.util.HashSet<>(ALL);
        appTargets.remove(":app");
        assertEquals(appTargets, e.get(":app"));
    }

    @Test
    public void forbiddenEdges_fromTst12_areRejected() {
        assertNotNull(DependencyRules.checkProjectEdge(":feature-stats", ":feature-home"));
        assertNotNull(DependencyRules.checkProjectEdge(":feature-home", ":storage"));
        assertNotNull(DependencyRules.checkProjectEdge(":alarm", ":monitor"));
        assertNotNull(DependencyRules.checkProjectEdge(":domain", ":usecases"));
        assertNotNull(DependencyRules.checkProjectEdge(":usecases", ":nightscout"));
        assertNotNull(DependencyRules.checkProjectEdge(":feature-home", ":app"));
        assertNotNull(DependencyRules.checkProjectEdge(":ui-core", ":usecases"));
        assertNull(DependencyRules.checkProjectEdge(":feature-home", ":ui-core"));
        assertNull(DependencyRules.checkProjectEdge(":app", ":storage"));
    }

    @Test
    public void noModule_mayDependOnItself_orOnApp() {
        for (String m : ALL) {
            assertFalse(m, ModuleRules.isEdgeAllowed(m, m));
            assertFalse(m, ModuleRules.isEdgeAllowed(m, ":app"));
        }
    }

    @Test
    public void onlyApp_mayReferenceAdapters_R2() {
        for (String m : ALL) {
            boolean expected = ":app".equals(m);
            assertEquals(m, expected, ModuleRules.isEdgeAllowed(m, ":nightscout"));
            assertEquals(m, expected, ModuleRules.isEdgeAllowed(m, ":storage"));
        }
    }

    @Test
    public void kinds_matchCp00() {
        assertEquals(ModuleRules.ModuleKind.JAVA_LIBRARY, ModuleRules.kindOf(":domain"));
        assertEquals(ModuleRules.ModuleKind.JAVA_LIBRARY, ModuleRules.kindOf(":usecases"));
        assertEquals(ModuleRules.ModuleKind.ANDROID_APPLICATION, ModuleRules.kindOf(":app"));
        for (String m : ALL.subList(2, ALL.size() - 1)) {
            assertEquals(m, ModuleRules.ModuleKind.ANDROID_LIBRARY, ModuleRules.kindOf(m));
        }
        assertThrows(IllegalArgumentException.class, () -> ModuleRules.kindOf(":unknown"));
    }

    @Test
    public void namespaces_areDerivedFromTheBasePackage() {
        assertEquals("b.p", ModuleRules.namespaceFor("b.p", ":app"));
        assertEquals("b.p.ui.core", ModuleRules.namespaceFor("b.p", ":ui-core"));
        assertEquals("b.p.feature.home", ModuleRules.namespaceFor("b.p", ":feature-home"));
    }

    @Test
    public void externalDependencies_followCp00Section2() {
        // OkHttp only in :nightscout (R-6 of 03 §3)
        assertNull(DependencyRules.checkExternal(":nightscout", "implementation", "com.squareup.okhttp3", "okhttp"));
        assertNotNull(DependencyRules.checkExternal(":app", "implementation", "com.squareup.okhttp3", "okhttp"));
        // R-1: :domain / :usecases JDK only; JUnit in tests
        assertNotNull(DependencyRules.checkExternal(":domain", "implementation", "org.json", "json"));
        assertNotNull(DependencyRules.checkExternal(":usecases", "implementation", "androidx.core", "core"));
        assertNotNull(DependencyRules.checkExternal(":domain", "testImplementation", "org.json", "json"));
        assertNull(DependencyRules.checkExternal(":domain", "testImplementation", "junit", "junit"));
        // test-only libraries
        assertNull(DependencyRules.checkExternal(":nightscout", "testImplementation",
                "com.squareup.okhttp3", "mockwebserver3"));
        assertNotNull(DependencyRules.checkExternal(":nightscout", "implementation",
                "com.squareup.okhttp3", "mockwebserver3"));
        assertNotNull(DependencyRules.checkExternal(":nightscout", "implementation", "org.json", "json"));
        assertNull(DependencyRules.checkExternal(":app", "androidTestImplementation",
                "androidx.test.espresso", "espresso-core"));
        // AndroidX allow-list, no -ktx, forbidden libraries
        assertNull(DependencyRules.checkExternal(":ui-core", "implementation",
                "com.google.android.material", "material"));
        assertNotNull(DependencyRules.checkExternal(":ui-core", "implementation", "androidx.core", "core-ktx"));
        assertNotNull(DependencyRules.checkExternal(":storage", "implementation", "androidx.room", "room-runtime"));
        assertNotNull(DependencyRules.checkExternal(":app", "implementation", "com.jakewharton.timber", "timber"));
        assertNotNull(DependencyRules.checkExternal(":app", "testImplementation", "org.mockito", "mockito-core"));
        assertNotNull(DependencyRules.checkExternal(":app", "annotationProcessor", "androidx.core", "core"));
        // Macrobenchmark, not microbenchmark (IMPL Q-23)
        assertNull(DependencyRules.checkExternal(":app", "androidTestImplementation",
                "androidx.benchmark", "benchmark-macro-junit4"));
        assertNotNull(DependencyRules.checkExternal(":app", "androidTestImplementation",
                "androidx.benchmark", "benchmark-junit4"));
    }

    @Test
    public void reachableModules_areTheClosureOfTheMatrix() {
        assertEquals(Set.of(), ModuleRules.reachableFrom(":domain"));
        assertEquals(Set.of(":domain", ":platform"), ModuleRules.reachableFrom(":ui-core"));
        assertEquals(Set.of(":domain", ":usecases", ":platform", ":ui-core"), ModuleRules.reachableFrom(":feature-stats"));
        assertFalse(ModuleRules.reachableFrom(":feature-stats").contains(":feature-home"));
    }

    @Test
    public void resolvedExternals_followTheAllowList() {
        assertNotNull(DependencyRules.checkResolvedExternal(":app", "debugRuntimeClasspath", "com.google.code.gson", "gson"));
        assertNull(DependencyRules.checkResolvedExternal(":app", "debugRuntimeClasspath", "androidx.core", "core"));
        assertNull(DependencyRules.checkResolvedExternal(":app", "debugUnitTestRuntimeClasspath", "junit", "junit"));
        assertNull(DependencyRules.checkResolvedExternal(":app", "debugAndroidTestRuntimeClasspath",
                "androidx.test.espresso", "espresso-core"));
        assertNotNull(DependencyRules.checkResolvedExternal(":app", "debugRuntimeClasspath", "junit", "junit"));
        assertNull(DependencyRules.checkResolvedExternal(":domain", "testRuntimeClasspath", "junit", "junit"));
    }

    @Test
    public void classpathConfigurations_areRecognised() {
        assertTrue(DependencyRules.isClasspath("debugCompileClasspath"));
        assertTrue(DependencyRules.isClasspath("releaseRuntimeClasspath"));
        assertTrue(DependencyRules.isClasspath("compileClasspath"));
        assertTrue(DependencyRules.isClasspath("runtimeElements"));
        assertTrue(DependencyRules.isClasspath("debugApiElements"));
        assertFalse(DependencyRules.isClasspath("implementation"));
        assertFalse(DependencyRules.isClasspath("sneaky"));
    }

    @Test
    public void declarableConfigurations_areRecognised() {
        assertTrue(DependencyRules.isDeclarable("implementation"));
        assertTrue(DependencyRules.isDeclarable("debugProxyImplementation"));
        assertTrue(DependencyRules.isDeclarable("androidTestImplementation"));
        assertTrue(DependencyRules.isDeclarable("api"));
        assertTrue(DependencyRules.isDeclarable("lintChecks"));
        assertFalse(DependencyRules.isDeclarable("debugRuntimeClasspath"));
        assertFalse(DependencyRules.isDeclarable("_internal_aapt2_binary"));
        assertTrue(DependencyRules.isTestConfiguration("testDebugImplementation"));
        assertFalse(DependencyRules.isTestConfiguration("debugImplementation"));
    }
}
