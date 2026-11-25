package app.lightscout.usecases.connection;

import app.lightscout.domain.alarm.AlarmState;
import app.lightscout.domain.alarm.AlarmType;
import app.lightscout.domain.alarm.AlarmTypeState;
import app.lightscout.domain.consumable.ConsumableKind;
import app.lightscout.domain.consumable.ReminderState;
import app.lightscout.domain.model.EntryType;
import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.monitor.MonitorState;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.status.TickReason;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.SaveResult;
import app.lightscout.usecases.StoredConnection;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.fakes.Cp02bFakes;
import app.lightscout.usecases.fakes.Fakes;
import app.lightscout.usecases.fakes.Harness;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static app.lightscout.usecases.fakes.Fakes.reading;
import static app.lightscout.usecases.fakes.Fakes.treatment;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** CS-1…CS-5, SR-12, SR-13, DS:A-23 / R-71, R-51 / R-73 row mapping, GIR-1 (CP-02 §9). */
public class ConnectionServiceTest {

    private static final Instant NOW = Harness.NOW;
    private static final String OLD = "https://old.example.org";
    private static final String NEW = "https://ns.example.org";

    /** The S-06 probe gateway (Q-46). */
    private Cp02bFakes.FakeGateway gateway;
    /** The gateway the sync cycle uses; configured only inside the R-71 monitor task. */
    private Cp02bFakes.FakeGateway monitoringGateway;
    private Cp02bFakes.FakeConnectionStore connection;
    private Fakes.FakeReadingStore readings;
    private Fakes.FakeEntryStore entries;
    private Fakes.FakeDeviceStatusStore deviceStatus;
    private Fakes.FakeMonitorStateStore monitorState;
    private Fakes.FakeAlarmStateStore alarmState;
    private Fakes.FakeReminderStateStore reminderState;
    private Fakes.FakeNotifiedAlertStore notified;
    private Fakes.FakeReminderScheduler reminders;
    private Fakes.FakeMonitorControl monitorControl;
    private AlarmStateMemory alarmMemory;
    private MonitorStateMemory monitorMemory;
    private OriginBoundTokenSource originTokens;
    private app.lightscout.usecases.common.ServerEpoch epoch;
    private Fakes.QueueExecutor io;
    private Fakes.QueueExecutor monitor;
    private DefaultConnectionService service;

    @Before
    public void setUp() {
        gateway = new Cp02bFakes.FakeGateway();
        monitoringGateway = new Cp02bFakes.FakeGateway();
        connection = new Cp02bFakes.FakeConnectionStore();
        readings = new Fakes.FakeReadingStore();
        entries = new Fakes.FakeEntryStore();
        deviceStatus = new Fakes.FakeDeviceStatusStore();
        monitorState = new Fakes.FakeMonitorStateStore();
        alarmState = new Fakes.FakeAlarmStateStore();
        reminderState = new Fakes.FakeReminderStateStore();
        notified = new Fakes.FakeNotifiedAlertStore();
        reminders = new Fakes.FakeReminderScheduler();
        monitorControl = new Fakes.FakeMonitorControl();
        alarmMemory = new AlarmStateMemory();
        monitorMemory = new MonitorStateMemory();
        io = new Fakes.QueueExecutor();
        monitor = new Fakes.QueueExecutor();
        alarmState.requiredExecutor = monitor;   // IF-14 writes only on the monitor executor
        originTokens = new OriginBoundTokenSource(connection);
        epoch = new app.lightscout.usecases.common.ServerEpoch();
        ConnectionStateReset reset = new ConnectionStateReset(monitoringGateway, originTokens, epoch, readings, entries, deviceStatus, monitorState,
                alarmState, reminderState, notified, reminders, alarmMemory, monitorMemory, monitorControl);
        service = new DefaultConnectionService(io, monitor, gateway, connection, reset);
    }

    private List<CheckResult> verify(String url, String token) {
        Cp02bFakes.Recorder<List<CheckResult>> r = new Cp02bFakes.Recorder<>();
        service.verify(url, token == null ? null : token.toCharArray(), r);
        io.runAll();
        assertTrue(r.only().ok());
        return r.only().value();
    }

    private Result<SaveResult> save(String url, String token) {
        Cp02bFakes.Recorder<SaveResult> r = new Cp02bFakes.Recorder<>();
        service.save(url, token == null ? null : token.toCharArray(), r);
        io.runAll();
        return r.only();
    }

    private static CheckResult ok(CheckRow row) {
        return new CheckResult(row, true, null, null);
    }

    private static CheckResult ok(CheckRow row, String version) {
        return new CheckResult(row, true, null, version);
    }

    private static CheckResult fail(CheckRow row, CheckFailure f) {
        return new CheckResult(row, false, f, null);
    }

    private static List<CheckResult> allPassed() {
        return List.of(ok(CheckRow.REACHABLE), ok(CheckRow.VERSION, "15.0.9"), ok(CheckRow.READ), ok(CheckRow.WRITE));
    }

    private void storeOld() {
        connection.url = OLD;
        connection.token = "oldtoken".toCharArray();
    }

    // ------------------------------------------------------------------ CS-1 / SR-12

    @Test
    public void cs1_versionCallFails_tokenNeverSent() {
        gateway.version = u -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.UNREACHABLE)), verify(NEW, "tok"));
        assertEquals(List.of("version"), gateway.calls);
        assertTrue(gateway.tokensSentAtPermissions.isEmpty());
    }

    @Test
    public void cs1_version14_2_0_tooOld_configureAndPermissionsNeverCalled() {
        gateway.version = u -> Result.ok(new ServerInfo("14.2.0", "3.0.0"));
        assertEquals(List.of(ok(CheckRow.REACHABLE), new CheckResult(CheckRow.VERSION, false,
                CheckFailure.VERSION_TOO_OLD, "14.2.0")), verify(NEW, "tok"));
        assertEquals(List.of("version"), gateway.calls);
        assertEquals(0, gateway.tokenCalls());
    }

    @Test
    public void cs1_notNightscout_identityFailures_tokenNeverSent() {
        gateway.version = u -> Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT)), verify(NEW, "tok"));
        gateway.version = u -> Result.ok(new ServerInfo("15.0.9", "2.0"));                 // not API v3
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT)), verify(NEW, "tok"));
        gateway.version = u -> Result.ok(new ServerInfo("15.0.9", null));
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT)), verify(NEW, "tok"));
        gateway.version = u -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);  // SR-17
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT)), verify(NEW, "tok"));
        gateway.version = u -> Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.UNREACHABLE)), verify(NEW, "tok"));
        assertEquals(0, gateway.tokenCalls());
        assertEquals(0, gateway.count("configure"));
    }

    @Test
    public void sr12_order_versionWithoutToken_thenConfigure_thenPermissions_thenProbeReset() {
        storeOld();
        assertEquals(allPassed(), verify(NEW, " typed "));
        assertEquals(List.of("version", "configure", "permissions", "configure"), gateway.calls);
        assertEquals(List.of(NEW, NEW), gateway.configuredUrls);
        assertEquals(List.of("typed"), gateway.tokensSentAtPermissions);
        // probe reset: no plaintext from the verify stays reachable
        assertTrue(gateway.tokens.token().isEmpty());
    }

    @Test
    public void q46_verifyConcurrentWithMonitoring_neverTouchesTheMonitoringGateway() {
        storeOld();
        monitoringGateway.configure(OLD, connection);   // as configured by the last save
        monitoringGateway.calls.clear();
        // a tick is queued on the monitor executor while the verify of another server runs on io
        List<String> seenByTick = new java.util.ArrayList<>();
        monitor.execute(() -> seenByTick.add(monitoringGateway.baseUrl));
        assertEquals(allPassed(), verify(NEW, "typed"));
        monitor.runAll();
        assertEquals(List.of(OLD), seenByTick);
        assertTrue(monitoringGateway.calls.isEmpty());
        assertEquals(OLD, monitoringGateway.baseUrl);
        assertSame(connection, monitoringGateway.tokens);
    }

    @Test
    public void verify_duringOnboarding_noStoredConnection_oneShotSourceWipedAfterwards() {
        assertEquals(allPassed(), verify("NS.Example.org/", "tok"));
        assertEquals(List.of(NEW, NEW), gateway.configuredUrls);
        assertTrue("one-shot token source is wiped", gateway.tokens.token().isEmpty());
        assertTrue(monitoringGateway.calls.isEmpty());
    }

    @Test
    public void verify_nullToken_usesStoredToken_d027() {
        storeOld();
        assertEquals(allPassed(), verify(NEW, null));
        assertEquals(List.of("oldtoken"), gateway.tokensSentAtPermissions);
    }

    @Test
    public void verify_nullToken_noStoredToken_tokenRejectedWithoutSending() {
        assertEquals(List.of(ok(CheckRow.REACHABLE), ok(CheckRow.VERSION, "15.0.9"),
                fail(CheckRow.READ, CheckFailure.TOKEN_REJECTED)), verify(NEW, null));
        assertEquals(List.of("version"), gateway.calls);
    }

    // ------------------------------------------------------------------ CS-2 / CS-3 input rules

    @Test
    public void cs2_urlRows() {
        assertEquals(ConnectionInput.InputError.URL_REQUIRED, ConnectionInput.checkUrl("").error());
        assertEquals(ConnectionInput.InputError.URL_REQUIRED, ConnectionInput.checkUrl("   ").error());
        assertEquals(ConnectionInput.InputError.URL_REQUIRED, ConnectionInput.checkUrl(null).error());
        assertEquals(ConnectionInput.InputError.URL_HTTPS, ConnectionInput.checkUrl("http://x").error());
        assertEquals(ConnectionInput.InputError.URL_HTTPS, ConnectionInput.checkUrl("ftp://x.org").error());
        assertEquals(NEW, ConnectionInput.checkUrl("ns.example.org").normalisedUrl());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://user:pw@ns.example.org").error());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://ns.example.org/?a=1").error());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://ns.example.org?").error());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://ns.example.org/#x").error());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://").error());
        assertEquals(ConnectionInput.InputError.URL_INVALID, ConnectionInput.checkUrl("https://exa mple.org").error());
        ConnectionInput.UrlCheck sub = ConnectionInput.checkUrl("https://host/sub/path");
        assertTrue(sub.valid());
        assertEquals("https://host/sub/path", sub.normalisedUrl());
        // R-71 normalisation: lower-case scheme + host, default port elided, no trailing slash, path kept
        assertEquals("https://ns.example.org/NS", ConnectionInput.checkUrl("HTTPS://NS.Example.ORG:443/NS/").normalisedUrl());
        assertEquals("https://ns.example.org:8443", ConnectionInput.checkUrl("ns.example.org:8443").normalisedUrl());
        assertEquals("ns.example.org", ConnectionInput.checkUrl("https://NS.example.org").host());
    }

    @Test
    public void cs2_invalidUrl_verifySendsNothing() {
        assertEquals(List.of(), verify("http://x", "tok"));
        assertEquals(List.of(), verify("", "tok"));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    public void cs3_tokenTrimmedThenRejectedForSpaces_emptyRequired() {
        assertEquals(ConnectionInput.InputError.TOKEN_REQUIRED, ConnectionInput.checkToken(" ab c ".toCharArray()));
        assertEquals(ConnectionInput.InputError.TOKEN_REQUIRED, ConnectionInput.checkToken("".toCharArray()));
        assertEquals(ConnectionInput.InputError.TOKEN_REQUIRED, ConnectionInput.checkToken("   ".toCharArray()));
        assertEquals(ConnectionInput.InputError.TOKEN_REQUIRED, ConnectionInput.checkToken(null));
        assertNull(ConnectionInput.checkToken(" abc ".toCharArray()));
        assertArrayEquals("abc".toCharArray(), ConnectionInput.normaliseToken(" abc\t".toCharArray()));
        assertEquals(List.of(), verify(NEW, " ab c "));
        assertTrue(gateway.calls.isEmpty());
    }

    // ------------------------------------------------------------------ CS-4 permissions / token

    @Test
    public void cs4_permissionRows() {
        gateway.permissions = () -> Result.ok(new Permissions(Map.of(NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "cr")));
        assertEquals(List.of(ok(CheckRow.REACHABLE), ok(CheckRow.VERSION, "15.0.9"), ok(CheckRow.READ),
                fail(CheckRow.WRITE, CheckFailure.NO_WRITE)), verify(NEW, "tok"));
        gateway.permissions = () -> Result.ok(new Permissions(Map.of(NsCollection.ENTRIES, "", NsCollection.TREATMENTS, "crud")));
        assertEquals(List.of(ok(CheckRow.REACHABLE), ok(CheckRow.VERSION, "15.0.9"),
                fail(CheckRow.READ, CheckFailure.NO_READ)), verify(NEW, "tok"));
        gateway.permissions = () -> Result.ok(new Permissions(Map.of(NsCollection.ENTRIES, "r", NsCollection.TREATMENTS, "crud")));
        assertEquals(allPassed(), verify(NEW, "tok"));
    }

    @Test
    public void r51_tokenAndPermissionFailures() {
        gateway.permissions = () -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        assertEquals(fail(CheckRow.READ, CheckFailure.TOKEN_REJECTED), verify(NEW, "tok").get(2));
        gateway.permissions = () -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.FORBIDDEN_403);
        assertEquals(fail(CheckRow.READ, CheckFailure.NO_READ), verify(NEW, "tok").get(2));
        gateway.permissions = () -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        List<CheckResult> rows = verify(NEW, "tok");
        assertEquals(3, rows.size());
        assertEquals(fail(CheckRow.READ, CheckFailure.UNREACHABLE), rows.get(2));
    }

    @Test
    public void gir1_secondVerifyWhileInFlight_joins_sameResultOnce() {
        Cp02bFakes.Recorder<List<CheckResult>> first = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<List<CheckResult>> second = new Cp02bFakes.Recorder<>();
        service.verify(NEW, "tok".toCharArray(), first);
        service.verify(NEW, "tok".toCharArray(), second);
        io.runAll();
        assertEquals(1, first.results.size());
        assertEquals(1, second.results.size());
        assertSame(first.only(), second.only());
        assertEquals(1, gateway.count("version"));
        assertEquals(allPassed(), verify(NEW, "tok"));   // accepted again once the first finished
    }

    // ------------------------------------------------------------------ CS-5 / SR-13 / DS:A-23 / R-71

    private void fillOldServerState() {
        readings.put(reading("r1", NOW.minusSeconds(300), 120));
        entries.put(treatment("t1", NOW.minusSeconds(600), EntryType.CARBS));
        deviceStatus.rows.add(new app.lightscout.domain.model.DeviceStatus("d1", NOW, 100, "pump"));
        monitorState.value = Harness.steadyState(NOW);
        reminderState.rows.put(ConsumableKind.SITE,
                new ReminderState(ConsumableKind.SITE, "s1", NOW, NOW.plusSeconds(60), false, false));
        notified.rows.put("p1", NOW);
        alarmState.value = new AlarmState(AlarmType.LOW, Map.of(
                AlarmType.LOW, new AlarmTypeState(NOW.minus(Duration.ofMinutes(2)), null, 1, null),
                AlarmType.HIGH, new AlarmTypeState(null, NOW.plus(Duration.ofMinutes(30)), 0, null)), 7);
        monitorMemory.set(monitorState.value);
    }

    @Test
    public void cs5_saveWithNewServer_clearsCacheAndState_keepsRingingAlarm_restartsWithSy8() {
        storeOld();
        fillOldServerState();
        assertEquals(allPassed(), verify(NEW, null));
        gateway.calls.clear();
        Result<SaveResult> r = save(NEW, null);
        assertTrue(r.ok());
        assertEquals(new SaveResult(true), r.value());
        // stored with the normalised URL and the kept token
        assertEquals(NEW, connection.url);
        assertEquals("oldtoken", connection.saves.get(0)[1]);
        assertTrue(gateway.calls.isEmpty());                 // the probe is not used by save
        // nothing configured or cleared before the monitor task runs (Q-46, R-32: serial monitorExecutor)
        assertTrue(monitoringGateway.calls.isEmpty());
        assertFalse(readings.rows.isEmpty());
        assertTrue(monitorControl.ticks.isEmpty());
        List<Boolean> configuredOnMonitorBeforeClear = new java.util.ArrayList<>();
        monitoringGateway.onConfigure = () -> configuredOnMonitorBeforeClear.add(monitor.running && !readings.rows.isEmpty());
        monitor.runAll();
        assertEquals(List.of(true), configuredOnMonitorBeforeClear);
        assertEquals(NEW, monitoringGateway.baseUrl);        // JWT discarded by configure (SR-03)
        assertSame(originTokens, monitoringGateway.tokens);  // IR-45
        assertEquals(1, epoch.current());                    // IR-49
        assertTrue(readings.rows.isEmpty());
        assertTrue(entries.rows.isEmpty());
        assertTrue(deviceStatus.rows.isEmpty());
        assertEquals(MonitorState.initial(), monitorState.value);
        assertTrue(reminderState.rows.isEmpty());
        assertTrue(notified.rows.isEmpty());
        // AL-4: the ringing alarm survives, the other types' state (snooze) is cleared; :alarm's volume kept
        assertEquals(new AlarmState(AlarmType.LOW, Map.of(AlarmType.LOW,
                new AlarmTypeState(NOW.minus(Duration.ofMinutes(2)), null, 1, null)), 7), alarmState.value);
        assertNull(monitorMemory.get());
        assertFalse(alarmMemory.known());
        assertEquals(List.of(ConsumableKind.SITE, ConsumableKind.SENSOR), reminders.cancelled);
        assertEquals(List.of(TickReason.RECONFIGURED), monitorControl.ticks);
    }

    @Test
    public void cs5_saveWithSameServer_tokenOnly_noClear_connectionProblemCleared() {
        storeOld();
        fillOldServerState();
        MonitorState s = Harness.steadyState(NOW);
        monitorState.value = new MonitorState(s.monitoringSince(), s.lastSuccessfulCheck(), s.lastCheckCompleted(),
                FetchOutcome.OK, FetchOutcome.PERMISSION_MISSING, s.lastReadingTime(), s.arrivalLagMedianSec(),
                s.recentLagSec(), s.lastDailyMaintenance(), s.cursors());
        assertEquals(allPassed(), verify("OLD.example.org/", "newtoken"));   // IR-46: the token is verified too
        Result<SaveResult> r = save("OLD.example.org/", "newtoken");   // same identity after normalisation
        assertEquals(new SaveResult(false), r.value());
        assertEquals("newtoken", connection.saves.get(0)[1]);
        assertTrue(monitoringGateway.calls.isEmpty());
        monitor.runAll();
        assertEquals(List.of(OLD), monitoringGateway.configuredUrls);   // new token's JWT, configured on the monitor task
        assertFalse(readings.rows.isEmpty());
        assertFalse(entries.rows.isEmpty());
        assertNull(monitorState.value.connectionProblem());
        assertEquals(s.cursors(), monitorState.value.cursors());
        assertEquals(NOW.plus(Duration.ofMinutes(30)),
                alarmState.value.perType().get(AlarmType.HIGH).snoozedUntil());   // snoozes untouched
        assertTrue(reminders.cancelled.isEmpty());
        assertEquals(List.of(TickReason.RECONFIGURED), monitorControl.ticks);
    }

    @Test
    public void sr13_saveWithNewServerWithoutVerify_refused_nothingStoredOrSent() {
        storeOld();
        Result<SaveResult> r = save(NEW, null);
        assertFalse(r.ok());
        assertEquals(FetchOutcome.NOT_NIGHTSCOUT, r.outcome());
        assertTrue(connection.saves.isEmpty());
        assertTrue(gateway.calls.isEmpty());
        assertTrue(monitor.queue.isEmpty());
        // a verify of another server does not unlock this one
        assertEquals(allPassed(), verify("https://other.example.org", null));
        assertFalse(save(NEW, null).ok());
        // a failed verify of the new server does not unlock it either
        gateway.permissions = () -> Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        verify(NEW, null);
        assertFalse(save(NEW, null).ok());
    }

    @Test
    public void save_firstConnectionDuringOnboarding_afterVerify() {
        assertEquals(allPassed(), verify("ns.example.org", " tok "));
        Result<SaveResult> r = save("ns.example.org", " tok ");
        assertEquals(new SaveResult(true), r.value());
        assertEquals("tok", connection.saves.get(0)[1]);
        monitor.runAll();
        assertEquals(List.of(TickReason.RECONFIGURED), monitorControl.ticks);
        // the verification is consumed by the save
        assertFalse(save("https://other.example.org", "tok").ok());
    }

    @Test
    public void save_storageFailure_noReset() {
        assertEquals(allPassed(), verify(NEW, "tok"));
        connection.fail = true;
        Result<SaveResult> r = save(NEW, "tok");
        assertEquals(FetchOutcome.STORAGE_ERROR, r.outcome());
        assertEquals(DetailCode.STORAGE, r.detailCode());
        assertTrue(monitor.queue.isEmpty());
    }

    @Test
    public void reset_storeFailures_doNotStopTheRestart() {
        storeOld();
        fillOldServerState();
        readings.fail = true;
        alarmState.fail = true;   // get fails → nothing written over the store
        verify(NEW, null);
        save(NEW, null);
        monitor.runAll();
        assertTrue(entries.rows.isEmpty());
        assertTrue(alarmState.puts.isEmpty());
        assertEquals(List.of(TickReason.RECONFIGURED), monitorControl.ticks);
    }

    // ------------------------------------------------------------------ loadStored / revealStoredToken

    @Test
    public void loadStored_noDecryption_hostLowerCased() {
        Cp02bFakes.Recorder<StoredConnection> none = new Cp02bFakes.Recorder<>();
        service.loadStored(none);
        io.runAll();
        assertTrue(none.only().ok());
        assertNull(none.only().value());
        connection.url = "https://NS.example.org/sub";
        connection.token = "tok".toCharArray();
        Cp02bFakes.Recorder<StoredConnection> stored = new Cp02bFakes.Recorder<>();
        service.loadStored(stored);
        io.runAll();
        assertEquals(new StoredConnection("https://NS.example.org/sub", "ns.example.org", true), stored.only().value());
        assertEquals(0, connection.tokenReads);
    }

    @Test
    public void revealStoredToken_onDemand() {
        Cp02bFakes.Recorder<char[]> missing = new Cp02bFakes.Recorder<>();
        service.revealStoredToken(missing);
        io.runAll();
        assertEquals(FetchOutcome.TOKEN_REJECTED, missing.only().outcome());
        assertEquals(DetailCode.TOKEN_MISSING, missing.only().detailCode());
        storeOld();
        Cp02bFakes.Recorder<char[]> shown = new Cp02bFakes.Recorder<>();
        service.revealStoredToken(shown);
        io.runAll();
        assertArrayEquals("oldtoken".toCharArray(), shown.only().value());
    }

    // ------------------------------------------------------------------ round 2 (IR-45, IR-46, IR-47, IR-48, IR-51)

    @Test
    public void ir45_saveOfNewServerDuringTick_noTokenReachesTheOldOrigin() {
        storeOld();
        originTokens.bind(OLD);
        monitoringGateway.configure(OLD, originTokens);   // composition-root start-up
        assertEquals("oldtoken", new String(monitoringGateway.tokens.token().orElseThrow()));
        assertEquals(allPassed(), verify(NEW, "newtoken"));
        assertTrue(save(NEW, "newtoken").ok());
        // the reset task has not run yet: a tick now still talks to OLD and must get no token at all
        assertEquals(OLD, monitoringGateway.baseUrl);
        assertTrue(monitoringGateway.tokens.token().isEmpty());
        monitor.runAll();
        assertEquals(NEW, monitoringGateway.baseUrl);
        assertEquals("newtoken", new String(monitoringGateway.tokens.token().orElseThrow()));
    }

    @Test
    public void ir46_saveMustUseTheVerifiedTokenAndMode() {
        storeOld();
        assertEquals(allPassed(), verify(NEW, "T1"));
        assertFalse(save(NEW, "T2").ok());                   // typed token differs from the verified one
        assertEquals(allPassed(), verify(NEW, "typed"));
        assertFalse(save(NEW, null).ok());                   // verified typed, saving the stored (old server's) token
        assertEquals(allPassed(), verify(NEW, null));
        assertFalse(save(NEW, "typed").ok());                // verified stored, saving a typed one
        assertEquals(allPassed(), verify(NEW, " T1 "));
        assertTrue(save(NEW, "T1").ok());                    // same token after trimming
        assertEquals("T1", connection.saves.get(0)[1]);
        assertEquals(1, connection.saves.size());
        assertFalse("the binding is consumed by the save", save(NEW, "T1").ok());
    }

    @Test
    public void ir51_failedReverify_clearsTheEarlierPass() {
        storeOld();
        assertEquals(allPassed(), verify(NEW, null));
        gateway.version = u -> Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        verify(NEW, null);
        assertFalse(save(NEW, null).ok());
        assertTrue(connection.saves.isEmpty());
    }

    @Test
    public void ir47_verifyWithDifferentArguments_serialised_ownResult() {
        gateway.version = u -> u.equals(NEW) ? Result.ok(new ServerInfo("15.0.9", "3.0.4"))
                : Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        Cp02bFakes.Recorder<List<CheckResult>> first = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<List<CheckResult>> otherUrl = new Cp02bFakes.Recorder<>();
        Cp02bFakes.Recorder<List<CheckResult>> otherToken = new Cp02bFakes.Recorder<>();
        service.verify(NEW, "tok".toCharArray(), first);
        service.verify("https://other.example.org", "tok".toCharArray(), otherUrl);
        service.verify(NEW, "tok2".toCharArray(), otherToken);
        io.runAll();
        assertEquals(allPassed(), first.only().value());
        assertEquals(List.of(fail(CheckRow.REACHABLE, CheckFailure.NOT_NIGHTSCOUT)), otherUrl.only().value());
        assertEquals(allPassed(), otherToken.only().value());
        assertEquals(3, gateway.count("version"));
        assertEquals(List.of("tok", "tok2"), gateway.tokensSentAtPermissions);
    }

    @Test
    public void ir48_unexpectedExceptionInVerify_isAFailureNotAnEmptyOk() {
        gateway.version = u -> {
            throw new IllegalStateException("bug");
        };
        Cp02bFakes.Recorder<List<CheckResult>> r = new Cp02bFakes.Recorder<>();
        service.verify(NEW, "tok".toCharArray(), r);
        io.runAll();
        assertFalse(r.only().ok());
        assertEquals(FetchOutcome.STORAGE_ERROR, r.only().outcome());
    }

    @Test
    public void ir53_tokenRequestedWhileGatewayIsBeingRepointed_neverGetsTheNewTokenForTheOldUrl() {
        storeOld();
        originTokens.bind(OLD);
        monitoringGateway.configure(OLD, originTokens);
        assertEquals(allPassed(), verify(NEW, "newtoken"));
        assertTrue(save(NEW, "newtoken").ok());
        List<String> seen = new java.util.ArrayList<>();
        // an io-thread JWT refresh interleaved right when configure starts: the gateway may still use OLD
        monitoringGateway.onConfigure = () -> seen.add(originTokens.token().map(String::new).orElse(null));
        monitor.runAll();
        assertEquals(java.util.Arrays.asList((String) null), seen);
        assertEquals("newtoken", new String(originTokens.token().orElseThrow()));
        assertEquals(NEW, monitoringGateway.baseUrl);
    }
}
