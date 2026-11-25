package lightscout.buildlogic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests of the transitive forbidden-library check (IR-11) and the D-052 Kotlin import guard (SS-14). */
public class DependencyGuardsTest {

    private static final String CP = "debugRuntimeClasspath";

    @Test
    public void forbiddenList_rejectsEveryCp00Section2Library_alsoTransitively() {
        String[][] forbidden = {
            {"com.google.code.gson", "gson"},
            {"androidx.room", "room-runtime"},
            {"androidx.datastore", "datastore-preferences"},
            {"androidx.work", "work-runtime"},
            {"androidx.security", "security-crypto"},
            {"androidx.webkit", "webkit"},
            {"com.squareup.retrofit2", "retrofit"},
            {"io.ktor", "ktor-client-core"},
            {"com.squareup.moshi", "moshi"},
            {"com.fasterxml.jackson.core", "jackson-databind"},
            {"com.google.dagger", "hilt-android"},
            {"io.reactivex.rxjava3", "rxjava"},
            {"com.github.PhilJay", "MPAndroidChart"},
            {"com.jakewharton.timber", "timber"},
            {"com.google.firebase", "firebase-analytics"},
            {"io.sentry", "sentry-android"},
            {"com.google.android.gms", "play-services-base"},
            {"com.google.crypto.tink", "tink-android"},
            {"io.socket", "socket.io-client"},
            {"org.mockito", "mockito-core"},
            {"org.robolectric", "robolectric"},
            {"com.squareup.okhttp3", "logging-interceptor"},
        };
        for (String[] c : forbidden) {
            assertNotNull(c[0] + ":" + c[1], DependencyRules.checkForbidden(":app", CP, c[0], c[1]));
        }
    }

    @Test
    public void forbiddenList_acceptsTheApprovedSetAndItsKnownTransitives() {
        String[][] allowed = {
            {"androidx.core", "core"},
            {"androidx.navigation", "navigation-fragment"},
            {"androidx.startup", "startup-runtime"},
            {"com.google.android.material", "material"},
            {"com.squareup.okhttp3", "okhttp"},
            {"com.squareup.okio", "okio"},
            {"org.jetbrains.kotlin", "kotlin-stdlib"},          // D-052: accepted transitively
            {"org.jetbrains.kotlinx", "kotlinx-coroutines-core"}, // D-052: accepted transitively
            {"javax.inject", "javax.inject"},                    // Espresso, not Dagger
            {"com.google.guava", "listenablefuture"},
            {"androidx.roomish", "x"},                           // prefix match is per group segment
        };
        for (String[] c : allowed) {
            assertNull(c[0] + ":" + c[1], DependencyRules.checkForbidden(":app", CP, c[0], c[1]));
        }
    }

    @Test
    public void kotlinImportGuard_findsKotlinAndKotlinxImports() {
        assertTrue(DependencyRules.importsKotlin("package a;\nimport kotlin.Unit;\nclass A {}"));
        assertTrue(DependencyRules.importsKotlin("package a;\n  import static kotlin.collections.CollectionsKt.listOf;"));
        assertTrue(DependencyRules.importsKotlin("package a;\nimport kotlinx.coroutines.flow.Flow;\n"));
    }

    @Test
    public void kotlinImportGuard_ignoresOtherImportsAndText() {
        assertFalse(DependencyRules.importsKotlin("package a;\nimport java.util.List;\n// import kotlin.Unit\nclass A {}"));
        assertFalse(DependencyRules.importsKotlin("package a;\nimport app.kotlinish.Thing;\n"));
        assertFalse(DependencyRules.importsKotlin("String s = \"import kotlin.Unit;\";"));
    }
}
