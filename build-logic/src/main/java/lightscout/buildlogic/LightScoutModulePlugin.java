package lightscout.buildlogic;

import com.android.build.api.dsl.ApkSigningConfig;
import com.android.build.api.dsl.ApplicationBuildType;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.CommonExtension;
import com.android.build.api.dsl.LibraryExtension;
import com.android.build.api.dsl.Lint;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.VariantOutput;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * The single LightScout convention plugin {@code lightscout.module} (CP-15 §8.7, CP-00A R-86),
 * applied by every module.
 *
 * <p>It chooses the module's plugin type from the CP-00 §3 module map ({@code java-library} for
 * {@code :domain} / {@code :usecases}, {@code com.android.application} for {@code :app},
 * {@code com.android.library} otherwise), applies Java 17 and the SDK levels of CP-00 §2, makes Lint
 * {@code HardcodedText} an error, and fails the build on any forbidden module edge (R-5), any dependency
 * outside the CP-00 §2 allow-list, any Kotlin plugin and any Kotlin source file.
 */
public final class LightScoutModulePlugin implements Plugin<Project> {

    /** minSdk of CP-00 §2. */
    public static final int MIN_SDK = 29;
    /** targetSdk of CP-00 §2. */
    public static final int TARGET_SDK = 36;
    /** compileSdk of CP-00 §2. */
    public static final int COMPILE_SDK = 37;

    /** Gradle property holding the product base package (see session notes SS-01, Q-new-1). */
    public static final String BASE_PACKAGE_PROPERTY = "lightscout.basePackage";
    /** Gradle property holding the SemVer {@code versionName} of the app. */
    public static final String VERSION_NAME_PROPERTY = "lightscout.versionName";
    /** Gradle property naming the PO-signed release APK for {@code lightscoutReleaseArtifact}. */
    public static final String SIGNED_APK_PROPERTY = "lightscout.signedApk";
    /** Gradle property: suffix of the nightly {@code versionName}, e.g. {@code -nightly.20261125.abc1234}. */
    public static final String NIGHTLY_VERSION_SUFFIX_PROPERTY = "lightscout.nightlyVersionSuffix";
    /** Gradle property: {@code versionCode} of the nightly build (the CI run number); default = release code. */
    public static final String NIGHTLY_VERSION_CODE_PROPERTY = "lightscout.nightlyVersionCode";
    /** Prefix of the CI environment variables that sign the release build (STORE_FILE, STORE_PASSWORD, ...). */
    public static final String RELEASE_SIGNING_ENV = "LIGHTSCOUT_RELEASE_";
    /** Prefix of the CI environment variables that sign the nightly build. */
    public static final String NIGHTLY_SIGNING_ENV = "LIGHTSCOUT_NIGHTLY_";
    /** AGP property that must be {@code false}: built-in Kotlin support disabled (CP-15 §8.7). */
    public static final String BUILT_IN_KOTLIN_PROPERTY = "android.builtInKotlin";

    /** Plugin ids that bring Kotlin into a module; none may be applied (CP-00 §2). */
    static final List<String> KOTLIN_PLUGIN_IDS = List.of(
            "org.jetbrains.kotlin.android", "kotlin-android",
            "org.jetbrains.kotlin.jvm", "kotlin",
            "org.jetbrains.kotlin.kapt", "kotlin-kapt",
            "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.parcelize", "kotlin-parcelize",
            "com.android.built-in-kotlin",
            "com.google.devtools.ksp",
            "androidx.navigation.safeargs.kotlin");

    private static final String HARDCODED_TEXT = "HardcodedText";
    private static final String DEBUG = "debug";
    private static final String DEBUG_PROXY = "debugProxy";
    private static final String RELEASE = "release";
    private static final String NIGHTLY = "nightly";

    @Override
    public void apply(Project project) {
        String path = project.getPath();
        if (!ModuleRules.isKnownModule(path)) {
            throw new GradleException("Module " + path + " is not in the CP-00 §3 module map. "
                    + "New modules need an architecture decision.");
        }
        forbidKotlin(project);
        enforceDeclaredDependencies(project);
        switch (ModuleRules.kindOf(path)) {
            case JAVA_LIBRARY:
                configureJavaLibrary(project);
                break;
            case ANDROID_LIBRARY:
                project.getPluginManager().apply("com.android.library");
                configureAndroidLibrary(project);
                break;
            case ANDROID_APPLICATION:
                project.getPluginManager().apply("com.android.application");
                configureApplication(project);
                break;
            default:
                throw new GradleException("Unknown module kind for " + path);
        }
    }

    // ---------------------------------------------------------------- rules (R-1 … R-6, CP-00 §2)

    private static void forbidKotlin(Project project) {
        for (String id : KOTLIN_PLUGIN_IDS) {
            project.getPluginManager().withPlugin(id, applied -> {
                throw new GradleException("Kotlin plugin '" + id + "' applied in " + project.getPath()
                        + ". LightScout modules are Java 17 only: no Kotlin plugin (CP-00 §2, TST-12 d).");
            });
        }
        String builtInKotlin = project.getProviders().gradleProperty(BUILT_IN_KOTLIN_PROPERTY).getOrNull();
        if (!"false".equals(builtInKotlin)) {
            throw new GradleException(BUILT_IN_KOTLIN_PROPERTY + " must be 'false' in gradle.properties "
                    + "(AGP built-in Kotlin support disabled, CP-15 §8.7); found: " + builtInKotlin);
        }
        File sources = project.file("src");
        if (sources.isDirectory()) {
            Set<File> kotlinFiles = project.fileTree(sources, tree -> tree.include("**/*.kt")).getFiles();
            if (!kotlinFiles.isEmpty()) {
                throw new GradleException("Kotlin source files in " + project.getPath() + " (CP-00 §2 forbids Kotlin): "
                        + kotlinFiles.stream().map(File::getName).sorted().collect(Collectors.joining(", ")));
            }
            // D-052 source guard: transitive Kotlin from approved AndroidX libraries is accepted on runtime
            // classpaths, but LightScout code never imports kotlin.* / kotlinx.* types.
            Set<File> javaFiles = project.fileTree(sources, tree -> tree.include("**/*.java")).getFiles();
            List<String> offenders = javaFiles.stream()
                    .filter(file -> DependencyRules.importsKotlin(read(file)))
                    .map(File::getName).sorted().collect(Collectors.toList());
            if (!offenders.isEmpty()) {
                throw new GradleException("Java sources in " + project.getPath() + " import kotlin.* / kotlinx.* "
                        + "(D-052: no Kotlin standard-library or coroutines use in LightScout code): "
                        + String.join(", ", offenders));
            }
        }
    }

    private static String read(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Cannot read " + file + " for the D-052 Kotlin import check", e);
        }
    }

    private static void enforceDeclaredDependencies(Project project) {
        String from = project.getPath();
        project.getConfigurations().configureEach(configuration -> {
            boolean declarable = DependencyRules.isDeclarable(configuration.getName());
            configuration.getDependencies().configureEach(dependency -> {
                if (dependency instanceof ProjectDependency) {
                    // Module edges are checked in EVERY configuration (IR-01): a custom configuration
                    // cannot carry a forbidden edge.
                    checkProjectDependency(from, configuration, (ProjectDependency) dependency);
                } else if (declarable) {
                    checkDependency(from, configuration, dependency);
                }
            });
            if (DependencyRules.isClasspath(configuration.getName())) {
                // Final gate when a classpath is resolved (after every afterEvaluate / extendsFrom change).
                configuration.getIncoming().beforeResolve(incoming -> checkHierarchy(from, configuration));
                // IR-09: substitution rules rewrite declared dependencies during resolution, so the RESOLVED
                // graph is checked too (compile and runtime classpaths, incl. unit and instrumented tests).
                configuration.getIncoming().afterResolve(incoming ->
                        checkResolved(from, configuration.getName(), incoming.getResolutionResult().getRoot()));
            }
        });
        // Configuration-time gate once all build scripts ran: nothing may feed a classpath through a
        // configuration that is not declarable (custom configuration route, IR-01).
        project.getGradle().projectsEvaluated(gradle -> project.getConfigurations().forEach(configuration -> {
            if (DependencyRules.isClasspath(configuration.getName())) {
                checkHierarchy(from, configuration);
            }
        }));
    }

    /**
     * Checks every configuration a classpath configuration inherits from: project dependencies against the
     * edge matrix; external dependencies against the allow-list, and only in declarable configurations.
     */
    private static void checkHierarchy(String from, Configuration classpath) {
        for (Configuration member : classpath.getHierarchy()) {
            boolean declarable = DependencyRules.isDeclarable(member.getName());
            for (Dependency dependency : member.getDependencies()) {
                if (dependency instanceof ProjectDependency) {
                    checkProjectDependency(from, member, (ProjectDependency) dependency);
                } else if (declarable) {
                    checkDependency(from, member, dependency);
                } else {
                    throw new GradleException("Dependency " + dependency.getGroup() + ":" + dependency.getName()
                            + " in " + from + " is declared in configuration '" + member.getName()
                            + "', which is not a declarable configuration but feeds '" + classpath.getName()
                            + "'. Declare dependencies only in implementation / api / compileOnly / runtimeOnly "
                            + "(and their variant / test forms) (CP-00 §2, CP-15 §8.7).");
                }
            }
        }
    }

    /**
     * Checks a resolved classpath (IR-09): every resolved project component must be reachable through allowed
     * edges, and every first-level external module must be on the CP-00 §2 allow-list. This catches
     * {@code dependencySubstitution}, {@code eachDependency}, {@code force} and similar rewrites.
     */
    private static void checkResolved(String from, String classpathName, ResolvedComponentResult root) {
        Set<String> reachable = ModuleRules.reachableFrom(from);
        for (ResolvedComponentResult component : root.getDependencies().stream()
                .filter(ResolvedDependencyResult.class::isInstance)
                // Constraints are not dependencies: AGP aligns an app's androidTest classpath with the tested app's
                // runtime classpath through constraints (SS-14). The components they select are reached through a
                // real edge anyway and are covered by the transitive forbidden-list check below (IR-11).
                .filter(result -> !result.isConstraint())
                .map(result -> ((ResolvedDependencyResult) result).getSelected())
                .collect(Collectors.toSet())) {
            ComponentIdentifier id = component.getId();
            if (id instanceof ModuleComponentIdentifier) {
                ModuleComponentIdentifier module = (ModuleComponentIdentifier) id;
                String violation = DependencyRules.checkResolvedExternal(from, classpathName,
                        module.getGroup(), module.getModule());
                if (violation != null) {
                    throw new GradleException(violation);
                }
            }
        }
        for (ResolvedComponentResult component : allComponents(root)) {
            if (component.getId() instanceof ModuleComponentIdentifier) {
                // IR-11: every resolved external component, transitive ones included, is checked against the
                // CP-00 §2 forbidden list (component metadata rules / resolution rules cannot add one).
                ModuleComponentIdentifier module = (ModuleComponentIdentifier) component.getId();
                String forbidden = DependencyRules.checkForbidden(from, classpathName,
                        module.getGroup(), module.getModule());
                if (forbidden != null) {
                    throw new GradleException(forbidden);
                }
            }
            if (component.getId() instanceof ProjectComponentIdentifier) {
                String to = ((ProjectComponentIdentifier) component.getId()).getProjectPath();
                if (!to.equals(from) && ModuleRules.isKnownModule(to) && !reachable.contains(to)) {
                    throw new GradleException("Forbidden module edge " + from + " -> " + to
                            + " in the resolved classpath '" + classpathName + "' (CP-00 §3, R-5; "
                            + "dependency substitution or another resolution rule changed the declared edges).");
                }
            }
        }
    }

    private static Set<ResolvedComponentResult> allComponents(ResolvedComponentResult root) {
        Set<ResolvedComponentResult> seen = new LinkedHashSet<>();
        Deque<ResolvedComponentResult> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            ResolvedComponentResult current = queue.poll();
            if (seen.add(current)) {
                for (DependencyResult dependency : current.getDependencies()) {
                    if (dependency instanceof ResolvedDependencyResult) {
                        queue.add(((ResolvedDependencyResult) dependency).getSelected());
                    }
                }
            }
        }
        return seen;
    }

    private static void checkProjectDependency(String from, Configuration configuration, ProjectDependency dependency) {
        String to = dependency.getPath();
        if (from.equals(to)) {
            return; // a module's own test / fixture wiring is not a module edge
        }
        String violation = DependencyRules.checkProjectEdge(from, to);
        if (violation != null) {
            throw new GradleException(violation + " [configuration '" + configuration.getName() + "']");
        }
    }

    private static void checkDependency(String from, Configuration configuration, Dependency dependency) {
        String violation;
        if (dependency instanceof ExternalModuleDependency) {
            violation = DependencyRules.checkExternal(from, configuration.getName(),
                    dependency.getGroup(), dependency.getName());
        } else {
            violation = "Dependency type " + dependency.getClass().getSimpleName() + " (" + dependency
                    + ") in " + from + " (" + configuration.getName() + ") is not allowed: "
                    + "only project(...) edges and catalog libraries (CP-15 §8.7).";
        }
        if (violation != null) {
            throw new GradleException(violation);
        }
    }

    // ---------------------------------------------------------------- module types

    private static void configureJavaLibrary(Project project) {
        project.getPluginManager().apply("java-library");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(JavaVersion.VERSION_17);
        java.setTargetCompatibility(JavaVersion.VERSION_17);
        project.getTasks().withType(JavaCompile.class).configureEach(compile -> {
            // --release 17 compiles against the Java 17 API only (no android.jar on the classpath: R-1).
            compile.getOptions().getRelease().set(17);
            compile.getOptions().setEncoding("UTF-8");
        });
    }

    private static void configureCommonAndroid(Project project, CommonExtension android) {
        android.setNamespace(ModuleRules.namespaceFor(basePackage(project), project.getPath()));
        android.setCompileSdk(COMPILE_SDK);
        android.getDefaultConfig().setMinSdk(MIN_SDK);
        android.getCompileOptions().setSourceCompatibility(JavaVersion.VERSION_17);
        android.getCompileOptions().setTargetCompatibility(JavaVersion.VERSION_17);
        Lint lint = android.getLint();
        lint.getError().add(HARDCODED_TEXT);
        lint.setAbortOnError(true);
        project.getTasks().withType(JavaCompile.class).configureEach(compile ->
                compile.getOptions().setEncoding("UTF-8"));
    }

    private static void configureAndroidLibrary(Project project) {
        LibraryExtension android = project.getExtensions().getByType(LibraryExtension.class);
        configureCommonAndroid(project, android);
        // Libraries have no targetSdk of their own; tests and Lint use the app's targetSdk.
        android.getTestOptions().setTargetSdk(TARGET_SDK);
        android.getLint().setTargetSdk(TARGET_SDK);
    }

    private static void configureApplication(Project project) {
        ApplicationExtension android = project.getExtensions().getByType(ApplicationExtension.class);
        configureCommonAndroid(project, android);
        String versionName = requiredProperty(project, VERSION_NAME_PROPERTY);
        android.getDefaultConfig().setApplicationId(basePackage(project));
        android.getDefaultConfig().setTargetSdk(TARGET_SDK);
        android.getDefaultConfig().setVersionName(versionName);
        android.getDefaultConfig().setVersionCode(Versioning.versionCode(versionName));

        ApplicationBuildType debug = android.getBuildTypes().getByName(DEBUG);
        debug.setApplicationIdSuffix(".debug");

        // CP-00A R-86: debugProxy = initWith(debug), same ".debug" id; only difference (CP-15b): its own
        // network security config trusting user CAs. Libraries fall back to their debug variant.
        ApplicationBuildType debugProxy = android.getBuildTypes().create(DEBUG_PROXY);
        debugProxy.initWith(debug);
        debugProxy.setApplicationIdSuffix(".debug");
        debugProxy.getMatchingFallbacks().add(DEBUG);

        // CP-15 §8.6: release is not debuggable, R8 shrinking + optimisation + obfuscation, unsigned
        // (no signingConfig: the PO signs manually, SR-39 / CP-00A R-86).
        ApplicationBuildType release = android.getBuildTypes().getByName(RELEASE);
        release.setDebuggable(false);
        release.setMinifyEnabled(true);
        release.setShrinkResources(true);
        release.proguardFiles(android.getDefaultProguardFile("proguard-android-optimize.txt"),
                project.file("proguard-rules.pro"));

        // DCR-48: signing only in CI, from environment variables (never from files in the repository). Without them
        // the build stays unsigned, as before. The nightly build is a separate app (".nightly") with its own key.
        ApkSigningConfig releaseSigning = signingFromEnvironment(project, android, RELEASE, RELEASE_SIGNING_ENV);
        if (releaseSigning != null) {
            release.setSigningConfig(releaseSigning);
        }
        ApplicationBuildType nightly = android.getBuildTypes().create(NIGHTLY);
        nightly.initWith(release);
        nightly.setApplicationIdSuffix(".nightly");
        nightly.setVersionNameSuffix(project.getProviders().gradleProperty(NIGHTLY_VERSION_SUFFIX_PROPERTY)
                .getOrElse("-nightly"));
        nightly.getMatchingFallbacks().add(RELEASE);
        nightly.setSigningConfig(signingFromEnvironment(project, android, NIGHTLY, NIGHTLY_SIGNING_ENV));
        Provider<String> nightlyCode = project.getProviders().gradleProperty(NIGHTLY_VERSION_CODE_PROPERTY);
        ApplicationAndroidComponentsExtension components =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        components.onVariants(components.selector().withBuildType(NIGHTLY), variant -> {
            if (nightlyCode.isPresent()) {
                int code = Integer.parseInt(nightlyCode.get().trim());
                for (VariantOutput output : variant.getOutputs()) {
                    output.getVersionCode().set(code);
                }
            }
        });

        // A relative path is resolved against the root directory (where gradlew is run).
        File rootDir = project.getRootDir();
        Provider<File> signedApk = project.getProviders().gradleProperty(SIGNED_APK_PROPERTY).map(value -> {
            File file = new File(value);
            return file.isAbsolute() ? file : new File(rootDir, value);
        });
        project.getTasks().register("lightscoutReleaseArtifact", ReleaseArtifactTask.class, task -> {
            task.setGroup("lightscout");
            task.setDescription("Names the PO-signed release APK lightscout-<versionName>-release.apk and writes "
                    + "its .sha256 (-P" + SIGNED_APK_PROPERTY + "=<signed apk>). Never signs.");
            task.getVersionName().set(versionName);
            task.getSignedApk().set(project.getLayout().file(signedApk));
            task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("outputs/lightscout-release"));
        });
    }

    /**
     * DCR-48: a signing config from {@code <prefix>STORE_FILE}, {@code STORE_PASSWORD}, {@code KEY_ALIAS} and
     * {@code KEY_PASSWORD}; {@code null} when {@code STORE_FILE} is not set (local builds stay unsigned).
     */
    private static ApkSigningConfig signingFromEnvironment(Project project, ApplicationExtension android, String name,
                                                           String prefix) {
        String storeFile = environment(project, prefix + "STORE_FILE");
        if (storeFile == null) {
            return null;
        }
        ApkSigningConfig config = android.getSigningConfigs().create(name + "Ci");
        config.setStoreFile(new File(storeFile));
        config.setStorePassword(requiredEnvironment(project, prefix + "STORE_PASSWORD"));
        config.setKeyAlias(requiredEnvironment(project, prefix + "KEY_ALIAS"));
        config.setKeyPassword(requiredEnvironment(project, prefix + "KEY_PASSWORD"));
        return config;
    }

    private static String environment(Project project, String name) {
        String value = project.getProviders().environmentVariable(name).getOrNull();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requiredEnvironment(Project project, String name) {
        String value = environment(project, name);
        if (value == null) {
            throw new GradleException("Environment variable " + name + " is missing (CI signing).");
        }
        return value;
    }

    // ---------------------------------------------------------------- properties

    private static String basePackage(Project project) {
        return requiredProperty(project, BASE_PACKAGE_PROPERTY);
    }

    private static String requiredProperty(Project project, String name) {
        String value = project.getProviders().gradleProperty(name).getOrNull();
        if (value == null || value.isBlank()) {
            throw new GradleException("Gradle property " + name + " is missing (gradle.properties).");
        }
        return value.trim();
    }
}
