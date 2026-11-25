package lightscout.buildlogic;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for declared dependencies (CP-00 §2, §3; CP-15 §8.7).
 *
 * <p>Checks every dependency a module declares in a user-declarable configuration:
 * {@code project(...)} edges against the CP-00 §3 matrix (R-5) and external modules against the
 * CP-00 §2 allow-list (anything else needs a PO decision). Transitive dependencies are not checked here;
 * they are pinned by Gradle dependency verification (SR-32).
 */
public final class DependencyRules {

    /** AndroidX and Material runtime libraries allowed in Android modules (CP-00 §2). */
    private static final Set<String> ANDROID_RUNTIME = Set.of(
            "androidx.appcompat:appcompat",
            "androidx.core:core",
            "androidx.activity:activity",
            "androidx.fragment:fragment",
            "androidx.lifecycle:lifecycle-viewmodel",
            "androidx.lifecycle:lifecycle-livedata",
            "androidx.lifecycle:lifecycle-viewmodel-savedstate",
            "androidx.lifecycle:lifecycle-process",
            "androidx.navigation:navigation-fragment",
            "androidx.navigation:navigation-ui",
            "androidx.constraintlayout:constraintlayout",
            "androidx.recyclerview:recyclerview",
            "com.google.android.material:material");

    /** OkHttp 5: {@code :nightscout} only (R-6 of 03 §3, CP-00 §2). */
    private static final String OKHTTP = "com.squareup.okhttp3:okhttp";

    private static final String NIGHTSCOUT = ":nightscout";

    /** JUnit 4.13: tests of every module. */
    private static final String JUNIT = "junit:junit";

    /** Test-only libraries allowed in Android modules (CP-00 §2). */
    private static final Set<String> ANDROID_TEST_ONLY = Set.of(
            "com.squareup.okhttp3:mockwebserver3",
            "org.json:json",
            "androidx.test:runner",
            "androidx.test:core",
            "androidx.test.ext:junit",
            "androidx.test.espresso:espresso-core",
            "androidx.test.uiautomator:uiautomator",
            "androidx.benchmark:benchmark-macro-junit4");

    /** Configuration name suffixes (lower case) that users declare dependencies in. */
    private static final String[] DECLARABLE_SUFFIXES = {
        "implementation", "api", "compileonly", "compileonlyapi", "runtimeonly",
        "annotationprocessor", "lintchecks", "lintpublish", "corelibrarydesugaring"
    };

    /**
     * CP-00 §2 „Forbidden” libraries (plus {@code okhttp-logging-interceptor}, SR-07), as group prefixes
     * ({@code group} or {@code group.*}) or exact {@code group:name} coordinates. Checked against EVERY resolved
     * external component of every classpath, transitive ones included (IR-11): a component metadata rule or a
     * resolution rule in a module script cannot bring one in through an allowed library.
     */
    private static final List<String> FORBIDDEN = List.of(
            "androidx.room",                          // Room
            "androidx.datastore",                     // DataStore
            "androidx.work",                          // WorkManager (not chosen)
            "androidx.security",                      // Jetpack Security Crypto
            "androidx.webkit",                        // WebView
            "com.squareup.retrofit",                  // Retrofit 1
            "com.squareup.retrofit2",                 // Retrofit 2
            "io.ktor",                                // Ktor
            "com.google.code.gson",                   // Gson
            "com.squareup.moshi",                     // Moshi
            "com.fasterxml.jackson",                  // Jackson
            "com.google.dagger",                      // Hilt / Dagger
            "io.reactivex",                           // RxJava
            "com.github.PhilJay",                     // MPAndroidChart
            "com.jakewharton.timber",                 // Timber
            "com.google.firebase",                    // Firebase / Crashlytics
            "com.crashlytics",                        // Crashlytics (legacy)
            "io.fabric",                              // Fabric / Crashlytics (legacy)
            "io.sentry",                              // Sentry
            "com.google.android.gms",                 // Google Play Services (incl. analytics)
            "com.google.crypto.tink",                 // Tink
            "io.socket",                              // socket.io
            "org.mockito",                            // Mockito
            "org.robolectric",                        // Robolectric
            "com.squareup.okhttp3:logging-interceptor" // SR-07: no okhttp-logging-interceptor
    );

    /** A Java import of Kotlin standard-library or kotlinx packages (D-052 source guard). */
    private static final Pattern KOTLIN_IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?kotlinx?\\.", Pattern.MULTILINE);

    private DependencyRules() {
    }

    /**
     * Checks one resolved external component, first-level or transitive, against the CP-00 §2 forbidden list
     * (IR-11).
     *
     * @param from          path of the module
     * @param classpathName resolved configuration
     * @param group         module group
     * @param name          module name
     * @return {@code null} if not forbidden, otherwise the violation message
     */
    public static String checkForbidden(String from, String classpathName, String group, String name) {
        String coordinates = group + ":" + name;
        for (String rule : FORBIDDEN) {
            boolean match = rule.indexOf(':') >= 0
                    ? rule.equals(coordinates)
                    : group.equals(rule) || group.startsWith(rule + ".");
            if (match) {
                return "Forbidden dependency " + coordinates + " in " + from + " (" + classpathName
                        + "): it is on the CP-00 §2 forbidden list (transitive or first-level; IR-11). "
                        + "A component metadata rule or resolution rule may have added it. Any new dependency "
                        + "needs a PO decision.";
            }
        }
        return null;
    }

    /**
     * Tells whether Java source text imports a {@code kotlin.*} or {@code kotlinx.*} type (D-052: no Kotlin
     * standard-library or coroutines use in LightScout code, even though transitive Kotlin from approved AndroidX
     * libraries is accepted on runtime classpaths).
     *
     * @param javaSource the content of one {@code .java} file
     * @return {@code true} if the file imports {@code kotlin.*} / {@code kotlinx.*}
     */
    public static boolean importsKotlin(String javaSource) {
        return KOTLIN_IMPORT.matcher(javaSource).find();
    }

    /**
     * Tells whether a configuration is one in which build scripts declare dependencies.
     * Internal configurations (prefix {@code _}) and resolvable classpaths are ignored.
     *
     * @param configurationName Gradle configuration name
     * @return {@code true} if dependencies declared in it are checked
     */
    public static boolean isDeclarable(String configurationName) {
        if (configurationName.startsWith("_")) {
            return false;
        }
        String lower = configurationName.toLowerCase(Locale.ROOT);
        for (String suffix : DECLARABLE_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a configuration is a resolved compile / runtime classpath or a published variant
     * ({@code *CompileClasspath}, {@code *RuntimeClasspath}, {@code *ApiElements}, {@code *RuntimeElements}).
     * Everything such a configuration inherits from is checked (IR-01).
     *
     * @param configurationName Gradle configuration name
     * @return {@code true} for classpath and outgoing-variant configurations
     */
    public static boolean isClasspath(String configurationName) {
        String lower = configurationName.toLowerCase(Locale.ROOT);
        return lower.endsWith("compileclasspath") || lower.endsWith("runtimeclasspath")
                || lower.endsWith("apielements") || lower.endsWith("runtimeelements");
    }

    /**
     * Checks a first-level external module of a RESOLVED classpath (IR-09) against the same allow-list as a
     * declared dependency. Test classpaths ({@code test*}, {@code androidTest*}, {@code *UnitTest*},
     * {@code *AndroidTest*}) are checked as test configurations.
     *
     * @param from          path of the module
     * @param classpathName resolved configuration, e.g. {@code debugUnitTestRuntimeClasspath}
     * @param group         module group
     * @param name          module name
     * @return {@code null} if allowed, otherwise the violation message
     */
    public static String checkResolvedExternal(String from, String classpathName, String group, String name) {
        boolean test = isTestConfiguration(classpathName) || classpathName.contains("UnitTest")
                || classpathName.contains("AndroidTest");
        String violation = checkExternal(from, test ? "testImplementation" : "implementation", group, name);
        if (violation == null) {
            return null;
        }
        return "Resolved dependency " + group + ":" + name + " in " + from + " (" + classpathName
                + ") is not allowed by CP-00 §2 / §3 (a resolution rule such as dependencySubstitution may have "
                + "replaced a declared dependency). Any new dependency needs a PO decision.";
    }

    /**
     * Tells whether a configuration belongs to tests (unit, instrumented or fixtures).
     *
     * @param configurationName Gradle configuration name
     * @return {@code true} for test configurations
     */
    public static boolean isTestConfiguration(String configurationName) {
        return configurationName.startsWith("test") || configurationName.startsWith("androidTest");
    }

    /**
     * Tells whether a configuration feeds a compile or runtime classpath (as opposed to annotation
     * processors, lint checks or desugaring, where no library is allowed).
     *
     * @param configurationName Gradle configuration name
     * @return {@code true} for implementation / api / compileOnly / runtimeOnly configurations
     */
    public static boolean isClasspathConfiguration(String configurationName) {
        String lower = configurationName.toLowerCase(Locale.ROOT);
        return lower.endsWith("implementation") || lower.endsWith("api") || lower.endsWith("compileonly")
                || lower.endsWith("runtimeonly");
    }

    /**
     * Checks a {@code project(...)} dependency.
     *
     * @param from path of the declaring module
     * @param to   path of the module depended on
     * @return {@code null} if allowed, otherwise the violation message
     */
    public static String checkProjectEdge(String from, String to) {
        if (ModuleRules.isEdgeAllowed(from, to)) {
            return null;
        }
        return "Forbidden module edge " + from + " -> " + to
                + " (CP-00 §3, R-5). Allowed for " + from + ": " + ModuleRules.allowedEdges().get(from);
    }

    /**
     * Checks an external module dependency against the CP-00 §2 allow-list.
     *
     * @param from              path of the declaring module
     * @param configurationName configuration the dependency is declared in
     * @param group             module group
     * @param name              module name
     * @return {@code null} if allowed, otherwise the violation message
     */
    public static String checkExternal(String from, String configurationName, String group, String name) {
        String coordinates = group + ":" + name;
        boolean test = isTestConfiguration(configurationName);
        boolean allowed;
        if (!isClasspathConfiguration(configurationName)) {
            allowed = false;
        } else if (ModuleRules.isPureJava(from)) {
            allowed = test && JUNIT.equals(coordinates);
        } else if (JUNIT.equals(coordinates) || ANDROID_TEST_ONLY.contains(coordinates)) {
            allowed = test;
        } else if (OKHTTP.equals(coordinates)) {
            allowed = NIGHTSCOUT.equals(from);
        } else {
            allowed = ANDROID_RUNTIME.contains(coordinates);
        }
        if (allowed) {
            return null;
        }
        return "Dependency " + coordinates + " in " + from + " (" + configurationName
                + ") is not allowed by CP-00 §2 / §3. Any new dependency needs a PO decision.";
    }
}
