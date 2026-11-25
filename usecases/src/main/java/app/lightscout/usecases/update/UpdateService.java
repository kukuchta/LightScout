package app.lightscout.usecases.update;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import app.lightscout.domain.port.UpdateSource;
import app.lightscout.domain.port.UpdateStore;
import app.lightscout.domain.update.AppVersion;
import app.lightscout.domain.update.UpdateOffer;
import app.lightscout.domain.update.UpdatePolicy;

/**
 * The update check (DCR-49): asks GitHub at most once a day, remembers the newest stable version it names, and tells
 * S-01 and S-04 whether a newer version exists. A nightly or debug build, whose version name is not a plain
 * {@code x.y.z}, never checks. Every method is blocking: call off the main thread. A failed check is never an error
 * for the user: it is simply tried again the next day.
 */
public final class UpdateService {

    private final UpdateStore store;
    private final UpdateSource source;
    private final AppVersion current;
    private final UpdatePolicy policy = new UpdatePolicy();

    /**
     * @param store       persisted check state
     * @param source      where the newest stable version is published
     * @param versionName {@code versionName} of this build; only a plain {@code x.y.z} takes part in the check
     */
    public UpdateService(UpdateStore store, UpdateSource source, String versionName) {
        this.store = store;
        this.source = source;
        this.current = AppVersion.parseStable(versionName).orElse(null);
    }

    /**
     * Returns whether the user keeps the check on.
     *
     * @return {@code true} unless turned off
     */
    public boolean isEnabled() {
        return store.enabled();
    }

    /**
     * Turns the check on or off.
     *
     * @param enabled new choice
     */
    public void setEnabled(boolean enabled) {
        store.setEnabled(enabled);
    }

    /**
     * Returns the newer version the check found earlier, for the S-04 row. Reads stored state only, no network.
     *
     * @return the offer, or empty (check off, nightly build, nothing newer known)
     */
    public Optional<UpdateOffer> available() {
        if (!store.enabled()) {
            return Optional.empty();
        }
        AppVersion latest = store.latest();
        return policy.isNewer(current, latest) ? Optional.of(new UpdateOffer(latest)) : Optional.empty();
    }

    /**
     * Returns the offer to show in the dialog now, or empty. Asks GitHub first when a check is due; a network or
     * storage failure gives empty and is tried again later. Nothing is asked in a moment that is not calm.
     *
     * @param now  current instant
     * @param zone device time zone for the daily window
     * @param calm S-01 shows a fresh reading and nothing needs the user
     * @return the offer, or empty
     */
    public Optional<UpdateOffer> offerForDialog(Instant now, ZoneId zone, boolean calm) {
        if (!calm || current == null || !store.enabled()) {
            return Optional.empty();
        }
        if (policy.isCheckDue(now, store.lastCheck(), true, current)) {
            check(now);
        }
        AppVersion latest = store.latest();
        if (!policy.shouldShow(now, zone, true, true, current, latest, store.lastPrompted())) {
            return Optional.empty();
        }
        return Optional.of(new UpdateOffer(latest));
    }

    /**
     * Records that the dialog was shown for an offer, so it is not shown again for that version.
     *
     * @param offer the offer that was shown
     */
    public void markPrompted(UpdateOffer offer) {
        store.markPrompted(offer.version());
    }

    private void check(Instant now) {
        AppVersion named = null;
        try {
            named = source.latestStable();
        } catch (IOException | RuntimeException e) {
            // offline, rate limited or an unexpected reply: nothing to tell the user, the next daily check tries again
        }
        store.recordCheck(now, named);
    }
}
