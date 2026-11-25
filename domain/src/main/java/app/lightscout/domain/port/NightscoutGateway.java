package app.lightscout.domain.port;

import app.lightscout.domain.model.Change;
import app.lightscout.domain.model.DeviceStatus;
import app.lightscout.domain.model.Entry;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.Profile;
import app.lightscout.domain.model.Reading;
import app.lightscout.domain.model.ServerInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Nightscout API v3 client port (IF-07, contracts IF-30…IF-41; CP-00A R-1, R-23), implemented by
 * {@code :nightscout}. Executes exactly one HTTP call per method call; never throws.
 */
public interface NightscoutGateway {

    /**
     * Sets the server and token source; discards any JWT.
     *
     * @param baseUrl base URL of the server
     * @param tokens  token source
     */
    void configure(String baseUrl, TokenSource tokens);

    /**
     * IF-31 version call; sends no token.
     *
     * @param baseUrl base URL to probe
     * @return raw server info
     */
    Result<ServerInfo> version(String baseUrl);

    /**
     * IF-32 permissions of the token.
     *
     * @return permissions
     */
    Result<Permissions> permissions();

    /**
     * IF-33 last modification per collection in epoch ms.
     *
     * @return last modified per collection
     */
    Result<Map<NsCollection, Long>> lastModified();

    /**
     * IF-34 readings ({@code type=sgv}) dated after a time.
     *
     * @param fromMsExclusive exclusive lower bound in epoch ms
     * @param limit           maximum documents
     * @return a page of readings
     */
    Result<Page<Reading>> readingsSince(long fromMsExclusive, int limit);

    /**
     * IF-34 meter BG ({@code type=mbg}) dated after a time.
     *
     * @param fromMsExclusive exclusive lower bound in epoch ms
     * @param limit           maximum documents
     * @return a page of entries
     */
    Result<Page<Entry>> meterBgSince(long fromMsExclusive, int limit);

    /**
     * IF-34 readings ({@code type=sgv}) with date in {@code [from, to)} (R-23).
     *
     * @param from  inclusive start
     * @param to    exclusive end
     * @param limit maximum documents
     * @return a page of readings
     */
    Result<Page<Reading>> readingsWindow(Instant from, Instant to, int limit);

    /**
     * IF-34 meter BG ({@code type=mbg}) with date in {@code [from, to)} (R-23).
     *
     * @param from  inclusive start
     * @param to    exclusive end
     * @param limit maximum documents
     * @return a page of entries
     */
    Result<Page<Entry>> meterBgWindow(Instant from, Instant to, int limit);

    /**
     * IF-35 treatments in a window.
     *
     * @param from  inclusive start
     * @param to    exclusive end
     * @param limit maximum documents
     * @return a page of entries
     */
    Result<Page<Entry>> entriesWindow(Instant from, Instant to, int limit);

    /**
     * IF-36 device status since a time.
     *
     * @param from lower bound
     * @return device status documents
     */
    Result<List<DeviceStatus>> deviceStatusSince(Instant from);

    /**
     * IF-36 latest profile.
     *
     * @return the profile
     */
    Result<Profile> latestProfile();

    /**
     * IF-37 history of a collection.
     *
     * @param c        collection
     * @param cursorMs {@code srvModified} cursor in epoch ms
     * @param limit    maximum documents
     * @return a page of changes
     */
    Result<Page<Change>> history(NsCollection c, long cursorMs, int limit);

    /**
     * IF-38 create an entry.
     *
     * @param e the entry
     * @return the acknowledgement
     */
    Result<WriteAck> create(Entry e);

    /**
     * IF-39 read one entry.
     *
     * @param id identifier
     * @return the entry
     */
    Result<Entry> get(String id);

    /**
     * IF-40 update an entry with a precondition.
     *
     * @param e                     the entry
     * @param expectedSrvModifiedMs expected server modification time
     * @return the acknowledgement
     */
    Result<WriteAck> update(Entry e, long expectedSrvModifiedMs);

    /**
     * IF-41 delete an entry.
     *
     * @param id identifier
     * @return the acknowledgement
     */
    Result<WriteAck> delete(String id);
}
