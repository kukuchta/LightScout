package app.lightscout.domain.model;

import java.util.Objects;

/**
 * Raw, unvalidated result of the Nightscout version call (IF-31, CP-00A R-2).
 * Version and API rules are applied by use cases, not here.
 *
 * @param version    {@code result.version}, e.g. {@code "15.0.6"}
 * @param apiVersion {@code result.apiVersion}; nullable
 */
public record ServerInfo(String version, String apiVersion) {

    /**
     * Creates a server info.
     *
     * @throws NullPointerException if {@code version} is null
     */
    public ServerInfo {
        Objects.requireNonNull(version, "version");
    }
}
