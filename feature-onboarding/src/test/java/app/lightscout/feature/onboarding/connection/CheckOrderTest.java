package app.lightscout.feature.onboarding.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.model.NsCollection;
import app.lightscout.domain.model.Permissions;
import app.lightscout.domain.model.ServerInfo;
import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.NightscoutGateway;
import app.lightscout.domain.port.Result;
import app.lightscout.domain.port.TokenSource;
import app.lightscout.feature.onboarding.Fakes;
import app.lightscout.feature.onboarding.Fakes.MapStore;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Primary;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.alarm.AlarmStateMemory;
import app.lightscout.usecases.common.MonitorStateMemory;
import app.lightscout.usecases.common.ServerEpoch;
import app.lightscout.usecases.connection.ConnectionStateReset;
import app.lightscout.usecases.connection.DefaultConnectionService;
import app.lightscout.usecases.connection.OriginBoundTokenSource;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CP-14 §9 „JVM S-06 check order (fake IF-24 / gateway)” and „result mapping”: the S-06 model drives the merged
 * IF-24 {@link DefaultConnectionService} over a scripted probe gateway (no network, never a Nightscout server —
 * CP-00 §8). Proves the C-12 order end to end from the screen: the token is not handed to any call before IF-31
 * succeeded with ≥ 15.0.0 and API v3, and every outcome lands on the R-51 row / copy.
 */
public class CheckOrderTest {

    private static final String URL = "https://ns.example.org";
    private static final String TOKEN = "subject-0123456789abcdef";

    private ScriptedGateway gateway;
    private ConnectionUiState last;

    @Before
    public void setUp() {
        gateway = new ScriptedGateway();
    }

    private ConnectionModel connect(ConnectionMode mode) {
        ConnectionStore store = nullObject(ConnectionStore.class);
        ConnectionStateReset reset = new ConnectionStateReset(nullObject(NightscoutGateway.class),
                new OriginBoundTokenSource(store), new ServerEpoch(),
                nullObject(app.lightscout.domain.port.ReadingStore.class),
                nullObject(app.lightscout.domain.port.EntryStore.class),
                nullObject(app.lightscout.domain.port.DeviceStatusStore.class),
                nullObject(app.lightscout.domain.port.MonitorStateStore.class),
                nullObject(app.lightscout.domain.port.AlarmStateStore.class),
                nullObject(app.lightscout.domain.port.ReminderStateStore.class),
                nullObject(app.lightscout.domain.port.NotifiedAlertStore.class),
                nullObject(app.lightscout.domain.port.ReminderScheduler.class),
                new AlarmStateMemory(), new MonitorStateMemory(),
                nullObject(app.lightscout.domain.port.MonitorControl.class));
        DefaultConnectionService service = new DefaultConnectionService(Fakes.DIRECT, Fakes.DIRECT, gateway.proxy, store,
                reset);
        ConnectionModel m = new ConnectionModel(mode, service, new MapStore(), Fakes.DIRECT, true);
        m.setListener(new ConnectionModel.Listener() {
            @Override
            public void onState(ConnectionUiState state) {
                last = state;
            }

            @Override
            public void onEvent(ConnectionModel.Event event) {
                // not needed
            }
        });
        m.start();
        m.onUrlEdited(URL);
        m.onTokenTyped(TOKEN.toCharArray());
        m.onPrimaryAction();
        return m;
    }

    @Test
    public void nonJsonVersionShowsOnlyNotNightscoutAndNoTokenCall() {
        gateway.version = Result.fail(FetchOutcome.NOT_NIGHTSCOUT, DetailCode.NO_VERSION);
        connect(ConnectionMode.ONBOARDING);
        assertEquals(List.of(new CheckResult(CheckRow.REACHABLE, false, CheckFailure.NOT_NIGHTSCOUT, null)),
                last.results());
        assertFalse(gateway.tokenEverRead);
        assertFalse(gateway.events.contains("permissions"));
    }

    @Test
    public void oldServerShowsVersionTooOldAndNoTokenCall() {
        gateway.version = Result.ok(new ServerInfo("14.2.0", "3.0.3-alpha"));
        connect(ConnectionMode.ONBOARDING);
        assertEquals(2, last.results().size());
        CheckResult version = last.results().get(1);
        assertEquals(CheckRow.VERSION, version.row());
        assertEquals(CheckFailure.VERSION_TOO_OLD, version.failure());
        assertEquals("14.2.0", version.serverVersion());   // → „Nightscout 14.2.0 is too old. …” (StringsTest)
        assertFalse(gateway.tokenEverRead);
    }

    @Test
    public void apiNotV3ShowsNotNightscoutAndNoTokenCall() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "2.0.0"));
        connect(ConnectionMode.ONBOARDING);
        assertEquals(CheckFailure.NOT_NIGHTSCOUT, last.results().get(0).failure());
        assertFalse(gateway.tokenEverRead);
    }

    @Test
    public void tokenIsReadOnlyAfterTheVersionCheck() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.ok(perms("r", "crud"));
        connect(ConnectionMode.ONBOARDING);
        assertTrue(gateway.tokenEverRead);
        int versionAt = gateway.events.indexOf("version:" + URL);
        int tokenAt = gateway.events.indexOf("token");
        assertTrue(versionAt >= 0);
        assertTrue(tokenAt > versionAt);
        assertFalse(gateway.versionCalledWithToken);
    }

    @Test
    public void unreachableMapsToReachableRow() {
        gateway.version = Result.fail(FetchOutcome.NO_INTERNET, DetailCode.NO_NETWORK);
        connect(ConnectionMode.ONBOARDING);
        assertEquals(List.of(new CheckResult(CheckRow.REACHABLE, false, CheckFailure.UNREACHABLE, null)),
                last.results());
    }

    @Test
    public void refusedRedirectMapsToNotNightscout() {
        gateway.version = Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.REDIRECT_REFUSED);
        connect(ConnectionMode.ONBOARDING);
        assertEquals(CheckFailure.NOT_NIGHTSCOUT, last.results().get(0).failure());
    }

    @Test
    public void tokenRejectedMapsToReadRow() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.AUTH_401);
        connect(ConnectionMode.ONBOARDING);
        assertEquals(3, last.results().size());
        assertEquals(new CheckResult(CheckRow.READ, false, CheckFailure.TOKEN_REJECTED, null), last.results().get(2));
    }

    @Test
    public void treatmentsReadOnlyIsNoWrite() {
        assertWriteFailsWith("r");
    }

    @Test
    public void treatmentsCreateReadIsNoWrite() {
        assertWriteFailsWith("cr");
    }

    private void assertWriteFailsWith(String treatments) {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.ok(perms("r", treatments));
        connect(ConnectionMode.ONBOARDING);
        assertEquals(4, last.results().size());
        assertEquals(new CheckResult(CheckRow.WRITE, false, CheckFailure.NO_WRITE, null), last.results().get(3));
        assertEquals(Primary.CONNECT, last.primary());
    }

    @Test
    public void crudWithReadableEntriesPassesAll() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.ok(perms("r", "crud"));
        connect(ConnectionMode.ONBOARDING);
        assertEquals(4, last.results().size());
        assertTrue(last.results().stream().allMatch(CheckResult::passed));
        assertEquals("15.0.9", last.results().get(1).serverVersion());
        assertEquals(Primary.CONTINUE, last.primary());
    }

    @Test
    public void crudInEditModeTurnsIntoSave() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.ok(perms("r", "crud"));
        connect(ConnectionMode.EDIT);
        assertEquals(Primary.SAVE, last.primary());
    }

    @Test
    public void noReadOnEntriesIsNoRead() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        gateway.permissions = Result.ok(perms("", "crud"));
        connect(ConnectionMode.ONBOARDING);
        assertEquals(new CheckResult(CheckRow.READ, false, CheckFailure.NO_READ, null), last.results().get(2));
        assertEquals(3, last.results().size());
    }

    @Test
    public void inputErrorSendsNothing() {
        gateway.version = Result.ok(new ServerInfo("15.0.9", "3.0.3-alpha"));
        ConnectionModel m = connect(ConnectionMode.ONBOARDING);
        gateway.events.clear();
        m.onUrlEdited("http://ns.example.org");
        m.onPrimaryAction();
        assertTrue(gateway.events.isEmpty());
    }

    private static Permissions perms(String entries, String treatments) {
        return new Permissions(Map.of(NsCollection.ENTRIES, entries, NsCollection.TREATMENTS, treatments));
    }

    @SuppressWarnings("unchecked")
    private static <T> T nullObject(Class<T> type) {
        InvocationHandler h = (proxy, method, args) -> {
            Class<?> r = method.getReturnType();
            if (r == Optional.class) {
                return Optional.empty();
            }
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class || r == long.class) {
                return 0;
            }
            if (method.getName().equals("toString")) {
                return type.getSimpleName();
            }
            return null;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, h);
    }

    /** Probe gateway scripted per test; records the call order and whether the token was ever read. */
    private static final class ScriptedGateway implements InvocationHandler {

        final List<String> events = new ArrayList<>();
        Result<ServerInfo> version = Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        Result<Permissions> permissions = Result.fail(FetchOutcome.NS_NOT_RESPONDING, DetailCode.TIMEOUT);
        boolean tokenEverRead;
        boolean versionCalledWithToken;
        private TokenSource tokens;
        private final NightscoutGateway proxy = (NightscoutGateway) Proxy.newProxyInstance(
                NightscoutGateway.class.getClassLoader(), new Class<?>[] {NightscoutGateway.class}, this);

        @Override
        public Object invoke(Object p, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "configure":
                    tokens = (TokenSource) args[1];
                    events.add("configure");
                    return null;
                case "version":
                    events.add("version:" + args[0]);
                    versionCalledWithToken = tokens != null && tokens.token().isPresent();
                    return version;
                case "permissions":
                    events.add("permissions");
                    Optional<char[]> t = tokens == null ? Optional.empty() : tokens.token();
                    if (t.isPresent()) {
                        tokenEverRead = true;
                        events.add("token");
                    }
                    return permissions;
                case "toString":
                    return "ScriptedGateway";
                default:
                    throw new AssertionError("unexpected gateway call " + method.getName());
            }
        }
    }
}
