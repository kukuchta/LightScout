package app.lightscout.usecases.connection;

import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.Callback;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.StorageException;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.ConnectionService;
import app.lightscout.usecases.SaveResult;
import app.lightscout.usecases.StoredConnection;
import app.lightscout.usecases.common.ServerPolicy;
import app.lightscout.usecases.common.SingleFlight;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * CMP-14 implementation of IF-24 (design S-06; 05 §5, C-12, C-13; 07 SR-02, SR-12, SR-13, SR-28, ADR-062; D-027;
 * CP-02 §8.6; CP-00A R-28, R-51, R-67, R-71, R-73). Work and callbacks on {@code ioExecutor}.
 *
 * <p>Two gateway instances (Q-46; C-13 / SR-13, DS:A-23): checks 3-6 run on a separate <b>probe</b> gateway
 * (same port, its own JWT holder, injected by the composition root), configured per verify and reset afterwards.
 * {@code verify} therefore never touches the monitoring gateway, so a tick running at the same time can never
 * fetch, store or evaluate another server's data. The monitoring gateway is configured only by {@code save},
 * inside the R-71 task on {@code monitorExecutor} ({@link ConnectionStateReset}), queued between ticks (R-32).
 *
 * <p>Token handling (SR-02): plaintext lives only in short-lived local arrays that are wiped in {@code finally};
 * no field ever holds a token. The probe gets a one-shot token source over such an array; after the checks the
 * source is wiped and the probe is reset to an empty token source.
 *
 * <p>Token-to-host binding (SR-13 / C-13; IR-46, IR-51): {@link #save} is refused unless the last {@link #verify}
 * passed all checks for the same server identity <b>and</b> the same token: the same mode (stored token vs typed
 * token) and, for a typed token, a matching in-memory salted SHA-256 fingerprint (per-process random salt; never
 * persisted, logged or exposed). The binding is cleared when a new verify starts and on every save (a server reset
 * only happens through a save). D-027: the stored token may be reused for a newly typed host after „Connect”.
 *
 * <p>GIR-1 (IR-47, IR-48): verifies run one at a time; an identical verify (same normalised URL, token mode and
 * fingerprint) joins the running one, a different one runs after it ({@link SingleFlight}).
 */
public final class DefaultConnectionService implements ConnectionService {

    private final Executor ioExecutor;
    private final Executor monitorExecutor;
    private final NightscoutGateway probe;
    private final ConnectionStore connectionStore;
    private final ConnectionStateReset reset;
    private final SingleFlight<List<CheckResult>> verifies;
    /** Per-process random salt of the token fingerprints (IR-46); never persisted, logged or exposed. */
    private final byte[] salt = new byte[16];
    /** Binding of the last all-✓ verify; {@code null} when none (IR-46, IR-51). */
    private volatile VerifiedBinding lastVerified;

    /** Server identity, token mode and typed-token fingerprint of an all-✓ verify. */
    private record VerifiedBinding(String identity, boolean typed, String fingerprint) {
    }

    /**
     * Creates the service.
     *
     * @param ioExecutor      executor for network and disk work and callbacks
     * @param monitorExecutor the single monitor thread (R-71 tasks)
     * @param probe           IF-07 instance used only for the S-06 checks, never the monitoring gateway (Q-46)
     * @param connectionStore IF-12
     * @param reset           the R-71 monitor-thread tasks (they hold the monitoring gateway)
     */
    public DefaultConnectionService(Executor ioExecutor, Executor monitorExecutor, NightscoutGateway probe,
                                    ConnectionStore connectionStore, ConnectionStateReset reset) {
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.monitorExecutor = Objects.requireNonNull(monitorExecutor, "monitorExecutor");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.connectionStore = Objects.requireNonNull(connectionStore, "connectionStore");
        this.reset = Objects.requireNonNull(reset, "reset");
        this.verifies = new SingleFlight<>(ioExecutor, Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE));
        new SecureRandom().nextBytes(salt);
    }

    @Override
    public void loadStored(Callback<StoredConnection> cb) {
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> cb.onResult(readStored()));
    }

    private Result<StoredConnection> readStored() {
        Optional<String> url;
        try {
            url = connectionStore.baseUrl();
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
        if (url.isEmpty()) {
            return Result.ok(null);
        }
        ConnectionInput.UrlCheck check = ConnectionInput.checkUrl(url.get());
        String host = check.valid() ? check.host() : url.get().toLowerCase(java.util.Locale.ROOT);
        // URL and token are stored together by ConnectionStore.save, so a stored URL means a stored token (no decryption)
        return Result.ok(new StoredConnection(url.get(), host, true));
    }

    @Override
    public void verify(String url, char[] tokenOrNull, Callback<List<CheckResult>> cb) {
        Objects.requireNonNull(cb, "callback");
        ConnectionInput.UrlCheck u = ConnectionInput.checkUrl(url);
        String urlKey = u.valid() ? u.normalisedUrl() : "invalid:" + url;
        String fingerprint = tokenOrNull == null ? null : fingerprintOf(tokenOrNull);
        verifies.submit("verify", Arrays.asList(urlKey, tokenOrNull != null, fingerprint),
                () -> Result.ok(runChecks(url, tokenOrNull)), cb);
    }

    /** C-12 order: 1 URL, 2 token, 3 IF-31 without token + version / API rule, 4 token, 5 read, 6 write. */
    private List<CheckResult> runChecks(String url, char[] tokenOrNull) {
        lastVerified = null;   // IR-51: a new verify clears an earlier one, also when it fails
        ConnectionInput.UrlCheck u = ConnectionInput.checkUrl(url);
        if (!u.valid()) {
            return List.of();   // check 1: input error, nothing sent (CP-14 shows the field error)
        }
        char[] token = null;
        OneShotTokens tokens = null;
        try {
            if (tokenOrNull != null) {
                token = ConnectionInput.normaliseToken(tokenOrNull);
                if (token == null) {
                    return List.of();   // check 2: input error, nothing sent
                }
            } else {
                token = storedToken();   // null → TOKEN_MISSING at check 4
            }
            List<CheckResult> rows = new ArrayList<>();
            // Check 3: identity + version, without the token (SR-12)
            Result<ServerInfo> v = probe.version(u.normalisedUrl());
            if (!v.ok()) {
                rows.add(failed(CheckRow.REACHABLE, transportFailure(v), null));
                return rows;
            }
            String version = v.value() == null ? null : v.value().version();
            Result<ServerInfo> policy = ServerPolicy.checkServer(v.value());
            if (!policy.ok()) {
                if (policy.outcome() == FetchOutcome.SERVER_TOO_OLD) {
                    rows.add(passed(CheckRow.REACHABLE, null));
                    rows.add(failed(CheckRow.VERSION, CheckFailure.VERSION_TOO_OLD, version));
                } else {
                    rows.add(failed(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT, null));
                }
                return rows;
            }
            rows.add(passed(CheckRow.REACHABLE, null));
            rows.add(passed(CheckRow.VERSION, version));
            // Check 4: only now is the token handed to the probe (JWT via IF-30 inside IF-32)
            if (token == null) {
                rows.add(failed(CheckRow.READ, CheckFailure.TOKEN_REJECTED, null));
                return rows;
            }
            tokens = new OneShotTokens(token);
            probe.configure(u.normalisedUrl(), tokens);
            Result<Permissions> p = probe.permissions();
            if (!p.ok()) {
                rows.add(failed(CheckRow.READ, permissionFailure(p), null));
                return rows;
            }
            Result<Permissions> perm = ServerPolicy.checkPermissions(p.value());
            if (!perm.ok() && DetailCode.MISSING_READ.equals(perm.detailCode())) {
                rows.add(failed(CheckRow.READ, CheckFailure.NO_READ, null));
                return rows;
            }
            rows.add(passed(CheckRow.READ, null));
            if (!perm.ok()) {
                rows.add(failed(CheckRow.WRITE, CheckFailure.NO_WRITE, null));   // connection blocked (DS:D-059)
                return rows;
            }
            rows.add(passed(CheckRow.WRITE, null));
            lastVerified = new VerifiedBinding(u.normalisedUrl(), tokenOrNull != null,
                    tokenOrNull != null ? fingerprintOf(token) : null);
            return rows;
        } finally {
            wipe(token);
            if (tokens != null) {
                tokens.wipe();
                probe.configure(u.normalisedUrl(), Optional::empty);   // reset: JWT discarded, no token reachable
            }
        }
    }

    /** R-51: REDIRECT_REFUSED (SR-17) and NOT_NIGHTSCOUT → not Nightscout; every other failure → unreachable. */
    private static CheckFailure transportFailure(Result<?> r) {
        if (r.outcome() == FetchOutcome.NOT_NIGHTSCOUT || DetailCode.REDIRECT_REFUSED.equals(r.detailCode())) {
            return CheckFailure.NOT_NIGHTSCOUT;
        }
        return CheckFailure.UNREACHABLE;
    }

    /** R-51: 401 / token missing → token rejected; 403 or missing read → no read; network → as check 3. */
    private static CheckFailure permissionFailure(Result<?> r) {
        if (r.outcome() == FetchOutcome.TOKEN_REJECTED) {
            return DetailCode.FORBIDDEN_403.equals(r.detailCode()) ? CheckFailure.NO_READ : CheckFailure.TOKEN_REJECTED;
        }
        if (r.outcome() == FetchOutcome.PERMISSION_MISSING) {
            return CheckFailure.NO_READ;
        }
        return transportFailure(r);
    }

    @Override
    public void revealStoredToken(Callback<char[]> cb) {
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> {
            Result<char[]> r;
            try {
                Optional<char[]> t = connectionStore.token();
                r = t.isPresent() ? Result.ok(t.get())
                        : Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
            } catch (StorageException e) {
                r = Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
            }
            cb.onResult(r);   // the caller wipes the array (R-28)
        });
    }

    @Override
    public void save(String url, char[] tokenOrNull, Callback<SaveResult> cb) {
        Objects.requireNonNull(cb, "callback");
        ioExecutor.execute(() -> cb.onResult(doSave(url, tokenOrNull)));
    }

    /**
     * Stores the connection and posts the R-71 task to {@code monitorExecutor}; that task configures the monitoring
     * gateway (JWT discarded), then resets the server state when the identity changed, else clears
     * {@code connectionProblem}. The monitoring gateway is never configured on the io thread (Q-46).
     */
    private Result<SaveResult> doSave(String url, char[] tokenOrNull) {
        ConnectionInput.UrlCheck u = ConnectionInput.checkUrl(url);
        String previous;
        try {
            previous = ConnectionInput.serverIdentity(connectionStore.baseUrl().orElse(null));
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        }
        String identity = u.normalisedUrl();
        boolean changed = identity == null || !identity.equals(previous);
        VerifiedBinding binding = lastVerified;
        lastVerified = null;   // IR-51: consumed by every save
        if (identity == null || !matches(binding, identity, tokenOrNull)) {
            // SR-13 / IR-46: only the server and the token that passed the last S-06 verification are stored
            return Result.fail(FetchOutcome.NOT_NIGHTSCOUT, null);
        }
        char[] token = null;
        try {
            token = tokenOrNull != null ? ConnectionInput.normaliseToken(tokenOrNull) : storedToken();
            if (token == null) {
                return Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING);
            }
            connectionStore.save(identity, token);
        } catch (StorageException e) {
            return Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE);
        } finally {
            wipe(token);
        }
        if (changed) {
            monitorExecutor.execute(() -> reset.resetServer(identity));
        } else {
            monitorExecutor.execute(() -> reset.clearConnectionProblem(identity));
        }
        return Result.ok(new SaveResult(changed));
    }

    /** IR-46: same identity, same token mode and (typed) the same normalised-token fingerprint. */
    private boolean matches(VerifiedBinding b, String identity, char[] tokenOrNull) {
        if (b == null || !b.identity().equals(identity) || b.typed() != (tokenOrNull != null)) {
            return false;
        }
        if (tokenOrNull == null) {
            return true;
        }
        char[] normalised = ConnectionInput.normaliseToken(tokenOrNull);
        try {
            return normalised != null && fingerprintOf(normalised).equals(b.fingerprint());
        } finally {
            wipe(normalised);
        }
    }

    /**
     * Salted SHA-256 of a token (trimmed first, so the typed form and its normalised form match). Memory only; the
     * intermediate bytes are wiped.
     */
    private String fingerprintOf(char[] token) {
        char[] normalised = ConnectionInput.normaliseToken(token);
        char[] source = normalised != null ? normalised : token;
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(source));
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(raw);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);   // every Java 17 runtime has it
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
            wipe(normalised);
        }
    }

    private char[] storedToken() {
        try {
            return connectionStore.token().orElse(null);
        } catch (StorageException e) {
            return null;
        }
    }

    private static CheckResult passed(CheckRow row, String version) {
        return new CheckResult(row, true, null, version);
    }

    private static CheckResult failed(CheckRow row, CheckFailure f, String version) {
        return new CheckResult(row, false, f, version);
    }

    private static void wipe(char[] a) {
        if (a != null) {
            Arrays.fill(a, '\0');
        }
    }

    /** Token source over one short-lived array; every {@code token()} returns a copy (the caller wipes it — IF-61). */
    private static final class OneShotTokens implements TokenSource {

        private char[] token;

        OneShotTokens(char[] token) {
            this.token = token.clone();
        }

        @Override
        public synchronized Optional<char[]> token() {
            return token == null ? Optional.empty() : Optional.of(token.clone());
        }

        synchronized void wipe() {
            if (token != null) {
                Arrays.fill(token, '\0');
                token = null;
            }
        }
    }
}
