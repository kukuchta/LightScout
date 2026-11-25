package lightscout.buildlogic;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Module map and dependency rules of CP-00 §3 (R-1 … R-6) as data.
 *
 * <p>The allowed-edge table is the single source of truth for every {@code project(...)} dependency
 * (CP-00A R-86). Any edge that is not listed here is forbidden and fails the build (R-5).
 */
public final class ModuleRules {

    /** Gradle plugin type of a module. */
    public enum ModuleKind {
        /** Pure Java {@code java-library}: JDK only, no {@code android.*} (R-1). */
        JAVA_LIBRARY,
        /** Android library ({@code com.android.library}). */
        ANDROID_LIBRARY,
        /** The only Android application ({@code com.android.application}). */
        ANDROID_APPLICATION
    }

    private static final String DOMAIN = ":domain";
    private static final String USECASES = ":usecases";
    private static final String NIGHTSCOUT = ":nightscout";
    private static final String STORAGE = ":storage";
    private static final String PLATFORM = ":platform";
    private static final String UI_CORE = ":ui-core";
    private static final String ALARM = ":alarm";
    private static final String MONITOR = ":monitor";
    private static final String WIDGET = ":widget";
    private static final String APP = ":app";

    /** Feature modules (CP-00 §3); never feature → feature. */
    public static final List<String> FEATURES = List.of(
            ":feature-home", ":feature-entry", ":feature-stats", ":feature-settings", ":feature-onboarding");

    /** Runtime modules (CP-00 §3); never each other. */
    public static final List<String> RUNTIME = List.of(ALARM, MONITOR, WIDGET);

    private static final Map<String, Set<String>> ALLOWED_EDGES = buildAllowedEdges();

    private ModuleRules() {
    }

    private static Map<String, Set<String>> buildAllowedEdges() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put(DOMAIN, Set.of());
        edges.put(USECASES, Set.of(DOMAIN));
        edges.put(NIGHTSCOUT, Set.of(DOMAIN));
        edges.put(STORAGE, Set.of(DOMAIN));
        edges.put(PLATFORM, Set.of(DOMAIN));
        edges.put(UI_CORE, Set.of(DOMAIN, PLATFORM));
        for (String runtime : RUNTIME) {
            edges.put(runtime, Set.of(DOMAIN, USECASES, PLATFORM, UI_CORE));
        }
        for (String feature : FEATURES) {
            edges.put(feature, Set.of(DOMAIN, USECASES, PLATFORM, UI_CORE));
        }
        Set<String> everything = new LinkedHashSet<>(edges.keySet());
        edges.put(APP, Collections.unmodifiableSet(everything));
        return Collections.unmodifiableMap(edges);
    }

    /**
     * Returns the allowed-edge table of CP-00 §3.
     *
     * @return unmodifiable map from module path to the set of module paths it may depend on
     */
    public static Map<String, Set<String>> allowedEdges() {
        return ALLOWED_EDGES;
    }

    /**
     * Tells whether a module path is part of the CP-00 §3 module map.
     *
     * @param path Gradle project path, e.g. {@code ":domain"}
     * @return {@code true} if the module is known
     */
    public static boolean isKnownModule(String path) {
        return ALLOWED_EDGES.containsKey(path);
    }

    /**
     * Tells whether {@code from} may declare a {@code project(...)} dependency on {@code to}.
     *
     * @param from path of the depending module
     * @param to   path of the module depended on
     * @return {@code true} only if the edge is listed in the CP-00 §3 matrix
     */
    public static boolean isEdgeAllowed(String from, String to) {
        Set<String> allowed = ALLOWED_EDGES.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Returns every module reachable from {@code from} through allowed edges (transitive closure of the
     * CP-00 §3 matrix). A resolved classpath may contain exactly these modules (plus the module itself).
     *
     * @param from path of the module
     * @return unmodifiable set of reachable module paths (empty for unknown modules)
     */
    public static Set<String> reachableFrom(String from) {
        Set<String> reachable = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(ALLOWED_EDGES.getOrDefault(from, Set.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (reachable.add(next)) {
                queue.addAll(ALLOWED_EDGES.getOrDefault(next, Set.of()));
            }
        }
        return Collections.unmodifiableSet(reachable);
    }

    /**
     * Returns the Gradle plugin type of a module (CP-00 §3, CP-15 §8.7).
     *
     * @param path Gradle project path
     * @return the module kind
     * @throws IllegalArgumentException if the module is not in the module map
     */
    public static ModuleKind kindOf(String path) {
        if (!isKnownModule(path)) {
            throw new IllegalArgumentException("Module " + path + " is not in the CP-00 §3 module map");
        }
        if (DOMAIN.equals(path) || USECASES.equals(path)) {
            return ModuleKind.JAVA_LIBRARY;
        }
        if (APP.equals(path)) {
            return ModuleKind.ANDROID_APPLICATION;
        }
        return ModuleKind.ANDROID_LIBRARY;
    }

    /**
     * Tells whether the module is a pure Java core module ({@code :domain} or {@code :usecases}).
     *
     * @param path Gradle project path
     * @return {@code true} for the JDK-only modules of R-1
     */
    public static boolean isPureJava(String path) {
        return kindOf(path) == ModuleKind.JAVA_LIBRARY;
    }

    /**
     * Derives the Java package / Android namespace of a module from the base package:
     * the module name with every {@code -} replaced by {@code .} is appended
     * (e.g. {@code :feature-home} → {@code <base>.feature.home}); {@code :app} uses the base package itself.
     *
     * @param basePackage base package of the product
     * @param path        Gradle project path
     * @return the namespace
     */
    public static String namespaceFor(String basePackage, String path) {
        if (APP.equals(path)) {
            return basePackage;
        }
        return basePackage + "." + path.substring(1).replace('-', '.');
    }
}
