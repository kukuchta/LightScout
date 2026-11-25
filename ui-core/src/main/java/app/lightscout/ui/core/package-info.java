/**
 * Shared presentation layer (CP-06, MOD-06, R-6): Material 3 theme and semantic colours (CMP-30, resources),
 * {@link app.lightscout.ui.core.Formatters} (CMP-31), {@link app.lightscout.ui.core.GlucoseRendering} (CMP-32),
 * {@link app.lightscout.ui.core.SingleActionGuard} (GIR-1) and {@link app.lightscout.ui.core.EdgeToEdgeInsets}
 * (GIR-6) (CMP-33), the use-case → {@code LiveData} adapters (§8.4) and the cross-module interfaces IF-28
 * {@link app.lightscout.ui.core.Dependencies} and IF-29 {@link app.lightscout.ui.core.Navigator}
 * (implemented in {@code :app}).
 *
 * <p>The public types follow CP-00A R-12. {@code :ui-core} depends on {@code :domain} and {@code :platform} only
 * (CP-00 §3); package-private helpers hold the pure rules so that they are JVM-testable.
 */
package app.lightscout.ui.core;
