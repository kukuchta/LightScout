package app.lightscout.monitor;

/**
 * Implemented by the {@code Application} class in {@code :app} (CP-15 composition root, R-6). The {@code :monitor}
 * Android components obtain their collaborators only through this interface — never by constructing
 * {@code :storage} / {@code :nightscout} classes (R-2).
 */
public interface MonitorRuntimeProvider {

    /**
     * Returns the direct-boot-safe collaborators. Must work while the user is locked and must not build the
     * credential-encrypted part of the container.
     *
     * @return boot dependencies
     */
    MonitorBootDependencies monitorBootDependencies();

    /**
     * Returns the process-wide runtime (one instance per process). Only called after the first unlock (the
     * components that call it are not direct-boot aware).
     *
     * @return the runtime
     */
    MonitorRuntime monitorRuntime();
}
