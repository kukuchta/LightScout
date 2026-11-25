package app.lightscout.feature.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.lifecycle.SavedStateHandle;

import app.lightscout.feature.onboarding.Fakes.FakeConnectionService;
import app.lightscout.feature.onboarding.connection.ConnectionModel;
import app.lightscout.ui.core.ConnectionMode;

import org.junit.Test;

import java.util.Map;

/**
 * The {@link SavedStateHandle} adapter and SR-02 on a real handle: after S-06 input only the URL and the token
 * state flag are in the handle, never the token (CP-14 §9 TST-06 S-06 process death, JVM part).
 */
public class SavedStateHandleStoreTest {

    @Test
    public void readsAndWritesValues() {
        SavedStateHandle handle = new SavedStateHandle();
        SavedStateHandleStore store = new SavedStateHandleStore(handle);
        assertFalse(store.contains("k"));
        assertNull(store.getString("k"));
        assertTrue(store.getBoolean("b", true));
        store.putString("k", "v");
        store.putBoolean("b", false);
        assertTrue(store.contains("k"));
        assertEquals("v", store.getString("k"));
        assertFalse(store.getBoolean("b", true));
    }

    @Test
    public void tokenNeverReachesTheHandle() {
        String token = "subject-0123456789abcdef";
        SavedStateHandle handle = new SavedStateHandle();
        ConnectionModel m = new ConnectionModel(ConnectionMode.ONBOARDING, new FakeConnectionService(),
                new SavedStateHandleStore(handle), Fakes.DIRECT, true);
        m.start();
        m.onUrlEdited("https://ns.example.org");
        m.onTokenTyped(token.toCharArray());
        m.onPrimaryAction();
        for (String key : handle.keys()) {
            Object v = handle.get(key);
            assertFalse(key, String.valueOf(v).contains(token));
        }
        assertEquals(Map.of("s06_url", "https://ns.example.org", "s06_token_state", "TYPED"),
                Map.of("s06_url", handle.get("s06_url"), "s06_token_state", handle.get("s06_token_state")));
        assertEquals(2, handle.keys().size());
    }
}
