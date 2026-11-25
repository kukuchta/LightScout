/**
 * Platform services shared by the runtime modules and the UI (CP-05, MOD-05): notification channels (CMP-26),
 * alarm readiness (CMP-27), and CMP-28 — {@code Clock} (IF-15), {@code Connectivity} (IF-19), {@code Logger}
 * (IF-66), {@link app.lightscout.platform.ManufacturerDetector} and
 * {@link app.lightscout.platform.SystemSettingsIntents} (IF-55, IF-56, IF-57).
 *
 * <p>This package holds the API types of CP-00A R-11; implementations live in the sub-packages. Everything that may
 * run before the first unlock (channels, the logger, the crash recorder) never touches credential-encrypted storage
 * (CP-00 §6).
 */
package app.lightscout.platform;
