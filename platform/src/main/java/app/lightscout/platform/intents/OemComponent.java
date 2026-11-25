package app.lightscout.platform.intents;

/**
 * Explicit activity component of an IF-56 OEM settings page.
 *
 * @param packageName package of the OEM app (declared in the manifest queries)
 * @param className   fully qualified activity class
 */
public record OemComponent(String packageName, String className) {
}
