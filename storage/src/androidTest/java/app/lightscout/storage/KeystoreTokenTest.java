package app.lightscout.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

import app.lightscout.domain.port.ConnectionStore;

/**
 * ST-06 / IF-12 against the real Android Keystore: TST-05-09 round trip and key parameters, -10 invalidated key,
 * -11 clear connection, -12 host change.
 *
 * <p>Device: RD-2 (API 33) over USB — batched pre-M1 run (CR-04). The locked-screen decrypt over ≥ 8 h is VR-22
 * (RD-1 / RD-2, M1 device session), not this test. Test tokens are fake strings.
 */
@RunWith(AndroidJUnit4.class)
public class KeystoreTokenTest {

    private static final String URL = "https://ns.example.test";
    private static final char[] TOKEN = "fake-token-instrumented".toCharArray();

    private Context context;
    private StorageTestSupport.TestLogger logger;
    private ConnectionStore store;

    @Before
    public void setUp() throws Exception {
        context = StorageTestSupport.context();
        StorageTestSupport.wipe(context);
        new KeystoreTokenCipher().deleteKey();
        logger = new StorageTestSupport.TestLogger();
        store = new StorageFactory(context, new StorageTestSupport.TestClock(), logger).connection();
    }

    @After
    public void tearDown() throws Exception {
        new KeystoreTokenCipher().deleteKey();
        StorageTestSupport.wipe(context);
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    private SharedPreferences connectionPrefs() {
        return context.getSharedPreferences(StorageFactory.CONNECTION_PREFS, Context.MODE_PRIVATE);
    }

    @Test
    public void tst0509_roundTripAndDifferentIvsPerSave() {
        store.save(URL, TOKEN);
        char[] read = store.token().orElseThrow();
        assertArrayEquals(TOKEN, read);
        Arrays.fill(read, '\0');

        String iv1 = connectionPrefs().getString(KeystoreConnectionStore.TOKEN_IV, null);
        String ct1 = connectionPrefs().getString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, null);
        store.save(URL, TOKEN);
        assertNotEquals(iv1, connectionPrefs().getString(KeystoreConnectionStore.TOKEN_IV, null));
        assertNotEquals(ct1, connectionPrefs().getString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, null));
        assertEquals(12, java.util.Base64.getDecoder()
                .decode(connectionPrefs().getString(KeystoreConnectionStore.TOKEN_IV, null)).length);
        assertArrayEquals(TOKEN, store.token().orElseThrow());
    }

    @Test
    public void tst0509_keyIsAesGcm256WithoutUserAuthOrUnlockedDeviceRequirement() throws Exception {
        store.save(URL, TOKEN);
        assertTrue(keyStore().containsAlias("lightscout.token"));
        Key key = keyStore().getKey("lightscout.token", null);
        assertEquals("AES", key.getAlgorithm());
        KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore")
                .getKeySpec((SecretKey) key, KeyInfo.class);
        assertEquals(256, info.getKeySize());
        assertArrayEquals(new String[] {KeyProperties.BLOCK_MODE_GCM}, info.getBlockModes());
        assertArrayEquals(new String[] {KeyProperties.ENCRYPTION_PADDING_NONE}, info.getEncryptionPaddings());
        assertEquals(KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT, info.getPurposes());
        assertFalse(info.isUserAuthenticationRequired());

        KeyGenParameterSpec spec = KeystoreTokenCipher.keySpec();
        assertFalse("no unlocked-device requirement (VR-22)", spec.isUnlockedDeviceRequired());
        assertFalse(spec.isUserAuthenticationRequired());
        assertTrue(spec.isRandomizedEncryptionRequired());
        assertFalse("StrongBox not required", spec.isStrongBoxBacked());
    }

    @Test
    public void tst0510_deletedKeyMeansNoTokenAndNoException() throws Exception {
        store.save(URL, TOKEN);
        keyStore().deleteEntry("lightscout.token");
        assertEquals(Optional.empty(), store.token());
        assertTrue(logger.lines.contains("KEY_INVALIDATED"));
    }

    @Test
    public void tst0510_corruptedCiphertextMeansNoTokenAndNoException() {
        store.save(URL, TOKEN);
        byte[] ct = java.util.Base64.getDecoder()
                .decode(connectionPrefs().getString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, null));
        ct[ct.length - 1] ^= 0x01;
        assertTrue(connectionPrefs().edit().putString(KeystoreConnectionStore.TOKEN_CIPHERTEXT,
                java.util.Base64.getEncoder().encodeToString(ct)).commit());
        assertEquals(Optional.empty(), store.token());
        assertTrue(logger.lines.contains("KEY_INVALIDATED"));
    }

    @Test
    public void tst0511_clearDeletesUrlCiphertextIvAndKeyAlias() throws Exception {
        store.save(URL, TOKEN);
        store.clear();
        assertEquals(Optional.empty(), store.baseUrl());
        assertEquals(Optional.empty(), store.token());
        SharedPreferences prefs = connectionPrefs();
        assertFalse(prefs.contains(KeystoreConnectionStore.BASE_URL));
        assertFalse(prefs.contains(KeystoreConnectionStore.TOKEN_IV));
        assertFalse(prefs.contains(KeystoreConnectionStore.TOKEN_CIPHERTEXT));
        assertFalse(keyStore().containsAlias("lightscout.token"));
    }

    @Test
    public void tst0512_hostChangeReplacesKeyAndCiphertext() throws Exception {
        store.save(URL, TOKEN);
        Key oldKey = keyStore().getKey("lightscout.token", null);
        String oldCt = connectionPrefs().getString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, null);
        String oldIv = connectionPrefs().getString(KeystoreConnectionStore.TOKEN_IV, null);
        store.save("https://other.example.test", TOKEN);
        assertNotEquals(oldCt, connectionPrefs().getString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, null));
        assertArrayEquals(TOKEN, store.token().orElseThrow());

        // the old ciphertext no longer decrypts: the key was replaced
        assertTrue(connectionPrefs().edit().putString(KeystoreConnectionStore.TOKEN_CIPHERTEXT, oldCt)
                .putString(KeystoreConnectionStore.TOKEN_IV, oldIv).commit());
        assertEquals(Optional.empty(), store.token());
        assertNotNull(oldKey);
    }
}
