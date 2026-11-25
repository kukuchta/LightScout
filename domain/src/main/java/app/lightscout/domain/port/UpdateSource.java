package app.lightscout.domain.port;

import java.io.IOException;

import app.lightscout.domain.update.AppVersion;

/**
 * Where the newest stable LightScout version is published (DCR-49): the project's GitHub releases. Blocking: call
 * off the main thread. Sends nothing about the user or the app (no identifier, no token, no version).
 */
public interface UpdateSource {

    /**
     * Returns the newest published stable version (pre-releases such as nightlies do not count).
     *
     * @return the version, or {@code null} when no stable release exists yet
     * @throws IOException when the source cannot be reached or answers something unexpected
     */
    AppVersion latestStable() throws IOException;
}
