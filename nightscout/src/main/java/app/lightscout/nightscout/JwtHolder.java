package app.lightscout.nightscout;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import app.lightscout.domain.port.Clock;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;
import app.lightscout.domain.port.Result;

/**
 * In-memory JWT of one configured connection (C-2, SR-03, ST-07, 03 §6.4).
 *
 * <ul>
 *   <li>Kept in memory only; never persisted or logged.</li>
 *   <li>Reused while {@code exp − now ≥ 30 min}; refreshed through IF-30 before that.</li>
 *   <li>Single-flight: one refresh in flight, other callers (monitor and io executors) wait on the monitor lock
 *       and then reuse the fresh JWT.</li>
 *   <li>IF-32 always refreshes ({@link #refresh}, BUG-15): it needs the subject's current
 *       {@code permissionGroups}, which arrive with the JWT; the new JWT replaces the held one.</li>
 *   <li>Discarded on {@code TOKEN_REJECTED}; a new holder is created on every {@code configure()} (SR-13).</li>
 * </ul>
 */
final class JwtHolder {

    /** C-2: refresh when less than 30 min remain. */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(30);

    /**
     * A JWT, its expiry and the IF-30 {@code permissionGroups} that came with it (BUG-15, D-060).
     *
     * @param token            the JWT
     * @param expMs            expiry in epoch ms; {@link Long#MIN_VALUE} if unknown (then it is refreshed on every
     *                         use)
     * @param permissionGroups the subject's permission groups (one immutable list per role, default roles
     *                         included), or {@code null} when the IF-30 body had none or a malformed value; memory
     *                         only, never logged (SR-07)
     */
    record Jwt(String token, long expMs, List<List<String>> permissionGroups) {
        @Override
        public String toString() {
            return "Jwt[masked]";
        }
    }

    private final Clock clock;
    private final Logger logger;
    private final Supplier<Result<Jwt>> fetcher;
    private Jwt current;

    /**
     * Creates a holder.
     *
     * @param clock   IF-15, for the expiry check
     * @param logger  IF-66
     * @param fetcher performs IF-30
     */
    JwtHolder(Clock clock, Logger logger, Supplier<Result<Jwt>> fetcher) {
        this.clock = clock;
        this.logger = logger;
        this.fetcher = fetcher;
    }

    /**
     * Returns a usable JWT, refreshing it through IF-30 if needed.
     *
     * @param rejected the JWT a server just answered 401 for (C-2 refresh-once), or {@code null}
     * @return the JWT, or the IF-30 failure
     */
    synchronized Result<String> obtain(String rejected) {
        if (current != null && !current.token().equals(rejected) && fresh(current)) {
            return Result.ok(current.token());
        }
        Result<Jwt> fetched = refresh();
        return fetched.ok() ? Result.ok(fetched.value().token())
                : Result.fail(fetched.outcome(), fetched.detailCode());
    }

    /**
     * Always performs IF-30 and replaces the held JWT. Used by IF-32 so that the permission groups reflect the
     * subject's current roles (BUG-15, D-060). Serialised with {@link #obtain} (one IF-30 at a time).
     *
     * @return the new JWT with its permission groups, or the IF-30 failure (the old JWT is then discarded)
     */
    synchronized Result<Jwt> refresh() {
        current = null;
        Result<Jwt> fetched = fetcher.get();
        if (!fetched.ok()) {
            logger.event(EventCode.JWT_REFRESH_FAIL, fetched.detailCode());
            return Result.fail(fetched.outcome(), fetched.detailCode());
        }
        current = fetched.value();
        return Result.ok(current);
    }

    /**
     * Discards the JWT after {@code TOKEN_REJECTED} (SR-03), unless another caller already replaced it.
     *
     * @param used the JWT that was rejected
     */
    synchronized void discard(String used) {
        if (current != null && current.token().equals(used)) {
            current = null;
        }
    }

    private boolean fresh(Jwt jwt) {
        if (jwt.expMs() == Long.MIN_VALUE) {
            return false;
        }
        long now = clock.now().toEpochMilli();
        return jwt.expMs() - now >= REFRESH_MARGIN.toMillis();
    }
}
