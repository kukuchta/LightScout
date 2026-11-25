package app.lightscout.feature.onboarding.connection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.lightscout.domain.port.DetailCode;
import app.lightscout.domain.port.FetchOutcome;
import app.lightscout.domain.port.Result;
import app.lightscout.feature.onboarding.Fakes;
import app.lightscout.feature.onboarding.Fakes.FakeConnectionService;
import app.lightscout.feature.onboarding.Fakes.MapStore;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Helper;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.Primary;
import app.lightscout.feature.onboarding.connection.ConnectionUiState.TokenMode;
import app.lightscout.ui.core.ConnectionMode;
import app.lightscout.usecases.CheckFailure;
import app.lightscout.usecases.CheckResult;
import app.lightscout.usecases.CheckRow;
import app.lightscout.usecases.SaveResult;
import app.lightscout.usecases.StoredConnection;
import app.lightscout.usecases.connection.ConnectionInput.InputError;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests of the S-06 model (CP-14 §9: S-06 URL, token, result mapping, states, GIR-1, process death SR-02,
 * M3-27, edit mode; CP-00A R-51, R-73; Q-44, Q-45, CR-05) with a fake IF-24.
 */
public class ConnectionModelTest {

    private static final String URL = "https://ns.example.org";
    private static final String TOKEN = "subject-0123456789abcdef";

    private FakeConnectionService service;
    private MapStore store;
    private final List<ConnectionModel.Event> events = new ArrayList<>();
    private ConnectionUiState last;

    @Before
    public void setUp() {
        service = new FakeConnectionService();
        store = new MapStore();
    }

    private ConnectionModel model(ConnectionMode mode, boolean online) {
        ConnectionModel m = new ConnectionModel(mode, service, store, Fakes.DIRECT, online);
        m.setListener(new ConnectionModel.Listener() {
            @Override
            public void onState(ConnectionUiState state) {
                last = state;
            }

            @Override
            public void onEvent(ConnectionModel.Event event) {
                events.add(event);
            }
        });
        m.start();
        return m;
    }

    private ConnectionModel onboarding() {
        return model(ConnectionMode.ONBOARDING, true);
    }

    private ConnectionModel filled(String url, String token) {
        ConnectionModel m = onboarding();
        m.onUrlEdited(url);
        m.onTokenTyped(token.toCharArray());
        return m;
    }

    private InputError urlErrorFor(String url) {
        ConnectionModel m = filled(url, TOKEN);
        m.onPrimaryAction();
        return last.urlError();
    }

    // ---------------------------------------------------------------- URL (C-12 check 1, SR-14, SR-28)

    @Test
    public void emptyUrlIsRequiredAndNothingIsSent() {
        ConnectionModel m = onboarding();
        m.onTokenTyped(TOKEN.toCharArray());
        m.onUrlEdited("x");
        m.onUrlEdited("");
        m.onUrlFocusLost();
        assertEquals(InputError.URL_REQUIRED, last.urlError());
        m.onPrimaryAction();
        assertEquals(0, service.count("verify"));
    }

    @Test
    public void schemeLessAddressGetsHttpsAdded() {
        ConnectionModel m = filled("mynightscout.example.org", TOKEN);
        int version = last.urlFieldVersion();
        m.onUrlFocusLost();
        assertNull(last.urlError());
        assertEquals("https://mynightscout.example.org", last.url());
        assertTrue(last.urlFieldVersion() > version);
        m.onPrimaryAction();
        assertEquals("https://mynightscout.example.org", service.verifyUrls.get(0));
    }

    @Test
    public void httpIsRejected() {
        assertEquals(InputError.URL_HTTPS, urlErrorFor("http://x.org"));
        assertEquals(0, service.count("verify"));
    }

    @Test
    public void userInfoQueryFragmentAndMissingHostAreNotWebAddresses() {
        for (String bad : new String[] {"https://user@x.org", "https://x.org/?a=1", "https://x.org/#f", "https://"}) {
            setUp();
            assertEquals(bad, InputError.URL_INVALID, urlErrorFor(bad));
            assertEquals(0, service.count("verify"));
        }
    }

    @Test
    public void pathIsAllowed() {
        assertNull(urlErrorFor("https://x.org/ns"));
        assertEquals(List.of("https://x.org/ns"), service.verifyUrls);
    }

    @Test
    public void editingTheUrlClearsItsError() {
        ConnectionModel m = filled("http://x.org", TOKEN);
        m.onPrimaryAction();
        assertEquals(InputError.URL_HTTPS, last.urlError());
        m.onUrlEdited("https://x.org");
        assertNull(last.urlError());
    }

    // ---------------------------------------------------------------- token (C-12 check 2)

    @Test
    public void emptyTokenIsRequired() {
        ConnectionModel m = onboarding();
        m.onUrlEdited(URL);
        m.onTokenTyped("a".toCharArray());
        m.onTokenTyped(new char[0]);
        assertEquals(TokenMode.EMPTY, last.tokenMode());
        m.onPrimaryAction();   // button disabled in the UI; the model still refuses
        assertEquals(0, service.count("verify"));
    }

    @Test
    public void emptyTokenShowsRequiredErrorOnConnect() {
        ConnectionModel m = filled(URL, "   ");
        m.onPrimaryAction();
        assertEquals(InputError.TOKEN_REQUIRED, last.tokenError());
        assertEquals(0, service.count("verify"));
    }

    @Test
    public void leadingAndTrailingSpacesAreTrimmed() {
        ConnectionModel m = filled(URL, "  " + TOKEN + " ");
        m.onPrimaryAction();
        assertNull(last.tokenError());
        assertEquals(List.of(TOKEN), service.verifyTokens);
    }

    @Test
    public void innerSpaceIsAnError() {
        ConnectionModel m = filled(URL, "abc def");
        m.onPrimaryAction();
        assertEquals(InputError.TOKEN_REQUIRED, last.tokenError());
        assertEquals(0, service.count("verify"));
    }

    @Test
    public void bothInputErrorsAreShownTogether() {
        ConnectionModel m = filled("http://x.org", "a b");
        m.onPrimaryAction();
        assertEquals(InputError.URL_HTTPS, last.urlError());
        assertEquals(InputError.TOKEN_REQUIRED, last.tokenError());
    }

    // ---------------------------------------------------------------- result mapping (R-51, CR-05)

    private ConnectionModel checkedWith(Result<List<CheckResult>> r) {
        ConnectionModel m = filled(URL, TOKEN);
        m.onPrimaryAction();
        service.completeVerify(r);
        return m;
    }

    @Test
    public void allPassedTurnsTheButtonIntoContinue() {
        checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        assertEquals(4, last.results().size());
        assertEquals(Primary.CONTINUE, last.primary());
        assertTrue(last.primaryEnabled());
    }

    @Test
    public void allPassedInEditModeTurnsTheButtonIntoSave() {
        ConnectionModel m = model(ConnectionMode.EDIT, true);
        m.onUrlEdited(URL);
        m.onTokenTyped(TOKEN.toCharArray());
        m.onPrimaryAction();
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));
        assertEquals(Primary.SAVE, last.primary());
    }

    @Test
    public void failingRowIsShownAndButtonStaysConnect() {
        List<CheckResult> rows = List.of(new CheckResult(CheckRow.REACHABLE, true, null, null),
                new CheckResult(CheckRow.VERSION, true, null, "15.0.6"),
                new CheckResult(CheckRow.READ, true, null, null),
                new CheckResult(CheckRow.WRITE, false, CheckFailure.NO_WRITE, null));
        checkedWith(Result.ok(rows));
        assertEquals(rows, last.results());
        assertEquals(Primary.CONNECT, last.primary());
    }

    @Test
    public void failedVerifyResultIsShownAsUnreachable() {
        checkedWith(Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE));
        assertEquals(List.of(new CheckResult(CheckRow.REACHABLE, false, CheckFailure.UNREACHABLE, null)),
                last.results());
        assertEquals(Primary.CONNECT, last.primary());
    }

    @Test
    public void emptyVerifyResultShowsNoRows() {
        checkedWith(Result.ok(List.of()));
        assertTrue(last.results().isEmpty());
        assertEquals(Primary.CONNECT, last.primary());
    }

    // ---------------------------------------------------------------- states (TST-06 S-06 states)

    @Test
    public void emptyFieldsDisableConnectWithHelper() {
        onboarding();
        assertFalse(last.primaryEnabled());
        assertEquals(Helper.EMPTY_FIELDS, last.helper());
        assertEquals(Primary.CONNECT, last.primary());
        assertTrue(last.results().isEmpty());
    }

    @Test
    public void filledFieldsEnableConnect() {
        filled(URL, TOKEN);
        assertTrue(last.primaryEnabled());
        assertEquals(Helper.NONE, last.helper());
    }

    @Test
    public void checkingShowsIndicatorAndDisablesButtonAndFields() {
        ConnectionModel m = filled(URL, TOKEN);
        m.onPrimaryAction();
        assertTrue(last.checking());
        assertFalse(last.primaryEnabled());
        assertFalse(last.fieldsEnabled());
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));
        assertFalse(last.checking());
        assertTrue(last.fieldsEnabled());
    }

    @Test
    public void offlineDisablesConnectWithHelper() {
        ConnectionModel m = model(ConnectionMode.ONBOARDING, false);
        m.onUrlEdited(URL);
        m.onTokenTyped(TOKEN.toCharArray());
        assertFalse(last.primaryEnabled());
        assertEquals(Helper.OFFLINE, last.helper());
        m.onPrimaryAction();
        assertEquals(0, service.count("verify"));
        m.setOnline(true);
        assertTrue(last.primaryEnabled());
        assertEquals(Helper.NONE, last.helper());
    }

    @Test
    public void offlineDisablesContinueWithHelper() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.setOnline(false);
        assertEquals(Primary.CONTINUE, last.primary());
        assertFalse(last.primaryEnabled());
        assertEquals(Helper.OFFLINE, last.helper());
        m.onPrimaryAction();
        assertEquals(0, service.count("save"));
        m.setOnline(true);
        assertTrue(last.primaryEnabled());
        assertEquals(Helper.NONE, last.helper());
    }

    @Test
    public void offlineDisablesSaveWithHelper() {
        service.stored = new StoredConnection(URL, "ns.example.org", true);
        ConnectionModel m = model(ConnectionMode.EDIT, true);
        m.onPrimaryAction();
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));
        m.setOnline(false);
        assertEquals(Primary.SAVE, last.primary());
        assertFalse(last.primaryEnabled());
        assertEquals(Helper.OFFLINE, last.helper());
        m.onPrimaryAction();
        assertEquals(0, service.count("save"));
    }

    @Test
    public void offlineHelperWinsOverEmptyFieldsHelper() {
        model(ConnectionMode.ONBOARDING, false);
        assertEquals(Helper.OFFLINE, last.helper());
    }

    @Test
    public void editingAFieldAfterACheckClearsResults() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onUrlEdited(URL + "/ns");
        assertTrue(last.results().isEmpty());
        assertEquals(Primary.CONNECT, last.primary());

        m.onPrimaryAction();
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));
        m.onTokenTyped((TOKEN + "x").toCharArray());
        assertTrue(last.results().isEmpty());
        assertEquals(Primary.CONNECT, last.primary());
    }

    // ---------------------------------------------------------------- GIR-1

    @Test
    public void doubleConnectSendsOneVerify() {
        ConnectionModel m = filled(URL, TOKEN);
        m.onPrimaryAction();
        m.onPrimaryAction();
        assertEquals(1, service.count("verify"));
    }

    @Test
    public void doubleContinueSendsOneSave() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        m.onPrimaryAction();
        assertEquals(1, service.count("save"));
    }

    // ---------------------------------------------------------------- save (A-S06-2, A-S06-3, Q-45, CR-05)

    @Test
    public void continueStoresThenOpensReadiness() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        assertEquals(List.of(URL), service.saveUrls);
        assertEquals(List.of(TOKEN), service.saveTokens);
        assertTrue(events.isEmpty());
        service.completeSave(Result.ok(new SaveResult(true)));
        assertEquals(List.of(ConnectionModel.Event.OPEN_READINESS), events);
    }

    @Test
    public void continueAgainAfterBackFromReadinessDoesNotSaveTwice() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.ok(new SaveResult(true)));
        assertEquals(Primary.CONTINUE, last.primary());   // kept fields and results (R-38)
        m.onPrimaryAction();
        assertEquals(1, service.count("save"));
        assertEquals(2, events.size());
        assertEquals(ConnectionModel.Event.OPEN_READINESS, events.get(1));
    }

    @Test
    public void refusedSaveShowsNotNightscoutAndNeedsANewCheck() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.fail(FetchOutcome.NOT_NIGHTSCOUT, null));
        assertEquals(List.of(new CheckResult(CheckRow.REACHABLE, false, CheckFailure.NOT_NIGHTSCOUT, null)),
                last.results());
        assertEquals(Primary.CONNECT, last.primary());
        assertTrue(events.isEmpty());
    }

    @Test
    public void storageErrorOnSaveShowsUnreachable() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.fail(FetchOutcome.STORAGE_ERROR, DetailCode.STORAGE));
        assertEquals(CheckFailure.UNREACHABLE, last.results().get(0).failure());
        assertEquals(1, last.results().size());
    }

    @Test
    public void missingStoredTokenOnSaveShowsTokenRejectedOnReadRow() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.fail(FetchOutcome.TOKEN_REJECTED, DetailCode.TOKEN_MISSING));
        assertEquals(3, last.results().size());
        assertEquals("15.0.6", last.results().get(1).serverVersion());
        assertEquals(new CheckResult(CheckRow.READ, false, CheckFailure.TOKEN_REJECTED, null), last.results().get(2));
    }

    // ---------------------------------------------------------------- SR-02 token hygiene

    @Test
    public void arraysPassedToTheServiceAreWipedAfterTheCallback() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.ok(new SaveResult(false)));
        assertEquals(2, service.passedArrays.size());
        for (char[] a : service.passedArrays) {
            for (char c : a) {
                assertEquals('\0', c);
            }
        }
    }

    @Test
    public void clearWipesTheTokenAndDropsLateCallbacks() {
        ConnectionModel m = onboarding();
        m.onUrlEdited(URL);
        char[] typed = TOKEN.toCharArray();
        m.onTokenTyped(typed);
        m.onPrimaryAction();
        m.clear();
        assertArrayEquals(new char[typed.length], typed);
        assertNull(m.tokenForField());
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));   // no listener, no crash
    }

    @Test
    public void tokenIsNeverInSavedState() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.ok(new SaveResult(true)));
        m.onTokenTyped(TOKEN.toCharArray());
        assertFalse(store.containsText(TOKEN));
        assertFalse(store.containsText("subject"));
        assertFalse(last.toString().contains(TOKEN));
        assertEquals(URL, store.getString(ConnectionModel.KEY_URL));
    }

    @Test
    public void typedTokenSurvivesViewRecreationInMemory() {
        ConnectionModel m = filled(URL, TOKEN);
        m.setListener(null);   // view destroyed (rotation, or S-06 → S-05 → S-06 with the graph-scoped VM — M3-27)
        char[] field = m.tokenForField();
        assertEquals(TOKEN, new String(field));
        assertEquals(TokenMode.TYPED, m.state().tokenMode());
        assertEquals(URL, m.state().url());
    }

    // ---------------------------------------------------------------- process death (DS:A-24, M3-28, R-73)

    @Test
    public void afterProcessDeathUrlIsRestoredAndTokenMustBeReentered() {
        filled(URL, TOKEN);
        ConnectionModel restored = onboarding();   // new model, same saved state
        assertEquals(URL, last.url());
        assertEquals(TokenMode.EMPTY, last.tokenMode());
        assertTrue(last.tokenReenter());
        assertNull(restored.tokenForField());
        assertFalse(last.primaryEnabled());

        onboarding();   // a second death before typing keeps the helper
        assertTrue(last.tokenReenter());
    }

    @Test
    public void typingAfterRestoreRemovesTheReenterHelper() {
        filled(URL, TOKEN);
        ConnectionModel restored = onboarding();
        restored.onTokenTyped("t".toCharArray());
        assertFalse(last.tokenReenter());
    }

    @Test
    public void restoredUrlIsNotOverwrittenByTheStoredOne() {
        service.stored = new StoredConnection("https://old.example.org", "old.example.org", true);
        ConnectionModel m = model(ConnectionMode.EDIT, true);
        m.onUrlEdited(URL);
        model(ConnectionMode.EDIT, true);
        assertEquals(URL, last.url());
    }

    @Test
    public void untouchedPlaceholderIsRecreatedAfterProcessDeath() {
        service.stored = new StoredConnection(URL, "ns.example.org", true);
        model(ConnectionMode.EDIT, true);
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        model(ConnectionMode.EDIT, true);
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertFalse(last.tokenReenter());
    }

    @Test
    public void savedOnboardingTokenIsRestoredAsPlaceholder() {
        ConnectionModel m = checkedWith(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        service.completeSave(Result.ok(new SaveResult(true)));
        onboarding();
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertFalse(last.tokenReenter());
    }

    // ---------------------------------------------------------------- edit mode (R-73, F17, A-S06-3/5)

    private ConnectionModel editWithStored() {
        service.stored = new StoredConnection(URL, "ns.example.org", true);
        return model(ConnectionMode.EDIT, true);
    }

    @Test
    public void editModePrefillsUrlAndMaskedTokenWithoutDecrypting() {
        editWithStored();
        assertEquals(URL, last.url());
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertEquals(0, service.count("reveal"));
        assertFalse(last.backGuard());
        assertTrue(last.primaryEnabled());
    }

    @Test
    public void onboardingWithAStoredConnectionPrefillsToo() {
        service.stored = new StoredConnection(URL, "ns.example.org", true);
        onboarding();
        assertEquals(URL, last.url());
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertFalse(last.backGuard());   // no discard guard in onboarding
    }

    @Test
    public void untouchedTokenUsesTheStoredTokenForVerifyAndSave() {
        ConnectionModel m = editWithStored();
        m.onPrimaryAction();
        assertEquals(1, service.verifyTokens.size());
        assertNull(service.verifyTokens.get(0));
        service.completeVerify(Result.ok(Fakes.allPassed("15.0.6")));
        m.onPrimaryAction();
        assertNull(service.saveTokens.get(0));
        service.completeSave(Result.ok(new SaveResult(false)));
        assertEquals(List.of(ConnectionModel.Event.FINISH_SAVED), events);
        assertFalse(last.backGuard());
    }

    @Test
    public void firstKeystrokeReplacesThePlaceholder() {
        ConnectionModel m = editWithStored();
        int version = last.tokenFieldVersion();
        m.onPlaceholderKeystroke("a".toCharArray());
        assertEquals(TokenMode.TYPED, last.tokenMode());
        assertEquals("a", new String(m.tokenForField()));
        assertTrue(last.tokenFieldVersion() > version);
        assertTrue(last.backGuard());
    }

    @Test
    public void deletingThePlaceholderEmptiesTheField() {
        ConnectionModel m = editWithStored();
        m.onPlaceholderKeystroke(new char[0]);
        assertEquals(TokenMode.EMPTY, last.tokenMode());
        assertTrue(last.backGuard());
    }

    @Test
    public void showTokenOnUntouchedFieldRevealsTheStoredToken() {
        ConnectionModel m = editWithStored();
        m.onTokenToggle();
        m.onTokenToggle();   // second tap while decrypting is ignored
        assertEquals(1, service.count("reveal"));
        service.completeReveal();
        assertEquals(TokenMode.STORED_REVEALED, last.tokenMode());
        assertTrue(last.tokenVisible());
        assertEquals("stored-token", new String(m.tokenForField()));
        assertFalse(last.backGuard());
        m.onPrimaryAction();
        assertNull(service.verifyTokens.get(0));   // still the stored token
        m.onTokenToggle();
        assertFalse(last.tokenVisible());
    }

    @Test
    public void onboardingPlaceholderNeverRevealsTheStoredToken() {
        service.stored = new StoredConnection(URL, "ns.example.org", true);   // R-72 READINESS back stack
        ConnectionModel m = onboarding();
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertFalse(last.tokenToggleEnabled());
        m.onTokenToggle();
        assertEquals(0, service.count("reveal"));
        assertEquals(TokenMode.STORED_MASKED, last.tokenMode());
        assertFalse(last.tokenVisible());
        m.onPlaceholderKeystroke("a".toCharArray());   // typed token: toggle works normally
        assertTrue(last.tokenToggleEnabled());
        m.onTokenToggle();
        assertTrue(last.tokenVisible());
        assertEquals(0, service.count("reveal"));
    }

    @Test
    public void editPlaceholderToggleIsEnabled() {
        editWithStored();
        assertTrue(last.tokenToggleEnabled());
    }

    @Test
    public void toggleOnTypedTokenOnlySwitchesVisibility() {
        ConnectionModel m = filled(URL, TOKEN);
        m.onTokenToggle();
        assertTrue(last.tokenVisible());
        m.onTokenToggle();
        assertFalse(last.tokenVisible());
        assertEquals(0, service.count("reveal"));
    }

    @Test
    public void backWithChangesAsksAndKeepEditingKeepsInput() {
        ConnectionModel m = editWithStored();
        m.onUrlEdited(URL + "/ns");
        assertTrue(last.backGuard());
        m.onBackRequested();
        assertTrue(last.discardDialog());
        assertTrue(events.isEmpty());
        m.onDiscardCancelled();
        assertFalse(last.discardDialog());
        assertEquals(URL + "/ns", last.url());
        assertTrue(last.backGuard());
    }

    @Test
    public void discardLeaves() {
        ConnectionModel m = editWithStored();
        m.onUrlEdited(URL + "/ns");
        m.onBackRequested();
        m.onDiscardConfirmed();
        assertEquals(List.of(ConnectionModel.Event.LEAVE), events);
        assertFalse(last.backGuard());
    }

    @Test
    public void backWithoutChangesLeavesWithoutDialog() {
        ConnectionModel m = editWithStored();
        m.onBackRequested();
        assertFalse(last.discardDialog());
        assertEquals(List.of(ConnectionModel.Event.LEAVE), events);
    }

    @Test
    public void onboardingBackNeverAsks() {
        ConnectionModel m = filled(URL, TOKEN);
        assertFalse(last.backGuard());
        m.onBackRequested();
        assertEquals(List.of(ConnectionModel.Event.LEAVE), events);
    }

    @Test
    public void revertingTheUrlRemovesTheGuard() {
        ConnectionModel m = editWithStored();
        m.onUrlEdited(URL + "x");
        assertTrue(last.backGuard());
        m.onUrlEdited(URL);
        assertFalse(last.backGuard());
    }
}
