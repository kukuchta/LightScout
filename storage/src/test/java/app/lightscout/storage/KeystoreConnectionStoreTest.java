package app.lightscout.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.SecretKey;

import app.lightscout.domain.port.StorageException;

/**
 * IF-12 / IF-61 rules (CP-04 §8.4, CP-00A R-20, R-39) with a JDK AES-GCM stand-in for the Keystore key:
 * JVM part of TST-05-09…-12. The Keystore itself is covered by the instrumented {@code KeystoreTokenTest}.
 */
public class KeystoreConnectionStoreTest {

    private static final String URL = "https://ns.example.test";
    private static final char[] TOKEN = "fake-token-0123456789".toCharArray();

    private final FakeSharedPreferences prefs = new FakeSharedPreferences();
    private final FakeTokenCipher cipher = new FakeTokenCipher();
    private final RecordingLogger logger = new RecordingLogger();
    private final KeystoreConnectionStore store =
            new KeystoreConnectionStore(prefs, cipher, logger, new StoreGuard(logger));

    @Test
    public void emptyStoreHasNoUrlAndNoTokenAndLogsNothing() {
        assertEquals(Optional.empty(), store.baseUrl());
        assertEquals(Optional.empty(), store.token());
        assertTrue(logger.lines.isEmpty());
    }

    @Test
    public void saveThenTokenReturnsTheSameCharsAndTheUrl() {
        store.save(URL, TOKEN);
        assertEquals(Optional.of(URL), store.baseUrl());
        assertArrayEquals(TOKEN, store.token().orElseThrow());
    }

    @Test
    public void tokenReturnsAFreshCopyEachTime() {
        store.save(URL, TOKEN);
        char[] first = store.token().orElseThrow();
        java.util.Arrays.fill(first, '\0');
        char[] second = store.token().orElseThrow();
        assertNotSame(first, second);
        assertArrayEquals(TOKEN, second);
    }

    @Test
    public void saveNeitherWipesNorRetainsTheCallersArray() {
        char[] passed = TOKEN.clone();
        store.save(URL, passed);
        assertArrayEquals(TOKEN, passed);
        java.util.Arrays.fill(passed, 'x');
        assertArrayEquals(TOKEN, store.token().orElseThrow());
    }

    @Test
    public void storedFormIsBase64IvAndCiphertextWithFormatOneAndNoPlaintext() {
        store.save(URL, TOKEN);
        byte[] iv = Base64.getDecoder().decode((String) prefs.values.get(KeystoreConnectionStore.TOKEN_IV));
        assertEquals(12, iv.length);
        assertEquals(1, prefs.values.get(KeystoreConnectionStore.TOKEN_FORMAT));
        assertEquals(1, prefs.values.get(PrefsSchema.KEY));
        for (Object value : prefs.values.values()) {
            assertFalse(String.valueOf(value).contains("fake-token"));
        }
        assertEquals(0, prefs.applies);
    }

    @Test
    public void twoSavesProduceDifferentIvsAndCiphertextsUnderTheSameKey() {
        store.save(URL, TOKEN);
        Object iv1 = prefs.values.get(KeystoreConnectionStore.TOKEN_IV);
        Object ct1 = prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT);
        SecretKey key1 = cipher.currentKey();
        store.save(URL + "/", TOKEN);
        assertNotEquals(iv1, prefs.values.get(KeystoreConnectionStore.TOKEN_IV));
        assertNotEquals(ct1, prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT));
        assertEquals("same host keeps the key", key1, cipher.currentKey());
        assertEquals(0, cipher.keysDeleted);
    }

    @Test
    public void saveWithADifferentHostReplacesKeyAndCiphertext() {
        store.save(URL, TOKEN);
        SecretKey oldKey = cipher.currentKey();
        Object oldCt = prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT);
        char[] newToken = "fake-token-other".toCharArray();
        store.save("https://other.example.test", newToken);
        assertEquals(1, cipher.keysDeleted);
        assertEquals(2, cipher.keysGenerated);
        assertNotEquals(oldKey, cipher.currentKey());
        assertNotEquals(oldCt, prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT));
        assertArrayEquals(newToken, store.token().orElseThrow());
        assertEquals(Optional.of("https://other.example.test"), store.baseUrl());
    }

    @Test
    public void hostComparisonIsCaseInsensitiveAndUnparsableUrlsCountAsDifferent() {
        assertTrue(KeystoreConnectionStore.sameHost("https://NS.Example.test/a", "https://ns.example.test"));
        assertFalse(KeystoreConnectionStore.sameHost("https://a.example.test", "https://b.example.test"));
        assertFalse(KeystoreConnectionStore.sameHost("not a url", "not a url"));
    }

    @Test
    public void clearDeletesUrlIvCiphertextFormatAndTheKey() {
        store.save(URL, TOKEN);
        store.clear();
        assertEquals(Optional.empty(), store.baseUrl());
        assertEquals(Optional.empty(), store.token());
        assertNull(prefs.values.get(KeystoreConnectionStore.BASE_URL));
        assertNull(prefs.values.get(KeystoreConnectionStore.TOKEN_IV));
        assertNull(prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT));
        assertNull(prefs.values.get(KeystoreConnectionStore.TOKEN_FORMAT));
        assertFalse(cipher.hasKey());
    }

    @Test
    public void unusableKeyMeansNoTokenAndKeyInvalidatedWithoutException() {
        store.save(URL, TOKEN);
        cipher.failDecrypt = true;
        assertEquals(Optional.empty(), store.token());
        assertEquals(List.of("KEY_INVALIDATED"), logger.lines);
    }

    @Test
    public void deletedKeyMeansNoToken() throws Exception {
        store.save(URL, TOKEN);
        cipher.deleteKey();
        assertEquals(Optional.empty(), store.token());
        assertEquals(List.of("KEY_INVALIDATED"), logger.lines);
    }

    @Test
    public void corruptedCiphertextOrIvMeansNoToken() {
        store.save(URL, TOKEN);
        byte[] ct = Base64.getDecoder().decode((String) prefs.values.get(KeystoreConnectionStore.TOKEN_CIPHERTEXT));
        ct[0] ^= 0x01;
        prefs.values.put(KeystoreConnectionStore.TOKEN_CIPHERTEXT, Base64.getEncoder().encodeToString(ct));
        assertEquals(Optional.empty(), store.token());
        prefs.values.put(KeystoreConnectionStore.TOKEN_IV, "%%% not base64");
        assertEquals(Optional.empty(), store.token());
        assertEquals(List.of("KEY_INVALIDATED", "KEY_INVALIDATED"), logger.lines);
    }

    @Test
    public void aKeyThatCannotEncryptIsReplacedOnceSoFixConnectionWorks() {
        store.save(URL, TOKEN);
        cipher.encryptFailureOnce = new InvalidKeyException("simulated invalid key");
        store.save(URL, TOKEN);
        assertEquals(1, cipher.keysDeleted);
        assertArrayEquals(TOKEN, store.token().orElseThrow());
        assertTrue(logger.lines.isEmpty());

        cipher.encryptFailureOnce = new UnrecoverableKeyException("simulated unrecoverable key");
        store.save(URL, TOKEN);
        assertEquals(2, cipher.keysDeleted);
    }

    @Test
    public void aTransientEncryptFailureNeverDeletesAValidKey() {
        store.save(URL, TOKEN);
        cipher.encryptFailureOnce = new KeyStoreException("simulated transient Keystore failure");
        StorageException e = assertThrows(StorageException.class, () -> store.save(URL, TOKEN));
        assertEquals("connection.save", e.op());
        assertEquals(0, cipher.keysDeleted);
        assertArrayEquals("the stored token still decrypts", TOKEN, store.token().orElseThrow());
        assertEquals(List.of("STORAGE_ERROR:connection.save"), logger.lines);
    }

    @Test
    public void aTransientDecryptFailureIsAStorageErrorNotAMissingToken() {
        store.save(URL, TOKEN);
        cipher.decryptFailure = new KeyStoreException("simulated transient Keystore failure");
        StorageException e = assertThrows(StorageException.class, store::token);
        assertEquals("connection.token", e.op());
        assertEquals(List.of("STORAGE_ERROR:connection.token"), logger.lines);
        cipher.decryptFailure = null;
        assertArrayEquals(TOKEN, store.token().orElseThrow());
    }

    @Test
    public void aFailedAuthenticationTagMeansNoToken() {
        store.save(URL, TOKEN);
        cipher.decryptFailure = new AEADBadTagException("simulated tag mismatch");
        assertEquals(Optional.empty(), store.token());
        assertEquals(List.of("KEY_INVALIDATED"), logger.lines);
    }

    @Test
    public void exceptionClassificationFollowsIr17AndIr18() {
        assertTrue(KeystoreConnectionStore.keyOrDataUnusable(new UnrecoverableKeyException()));
        assertTrue(KeystoreConnectionStore.keyOrDataUnusable(new AEADBadTagException()));
        assertTrue(KeystoreConnectionStore.keyOrDataUnusable(new BadPaddingException()));
        assertFalse(KeystoreConnectionStore.keyOrDataUnusable(new KeyStoreException()));
        assertFalse(KeystoreConnectionStore.keyOrDataUnusable(new InvalidKeyException()));
        assertFalse(KeystoreConnectionStore.keyOrDataUnusable(new java.security.ProviderException()));
        assertTrue(KeystoreConnectionStore.keyUnusableForEncryption(new InvalidKeyException()));
        assertTrue(KeystoreConnectionStore.keyUnusableForEncryption(new UnrecoverableKeyException()));
        assertFalse(KeystoreConnectionStore.keyUnusableForEncryption(new KeyStoreException()));
        assertFalse(KeystoreConnectionStore.keyUnusableForEncryption(new BadPaddingException()));
    }

    @Test
    public void hostChangeWithANullTokenFailsBeforeTheKeyIsDeleted() {
        store.save(URL, TOKEN);
        assertThrows(StorageException.class, () -> store.save("https://other.example.test", null));
        assertEquals(0, cipher.keysDeleted);
        assertArrayEquals(TOKEN, store.token().orElseThrow());
        assertEquals(Optional.of(URL), store.baseUrl());
    }

    @Test
    public void hostChangeWithAnUnencodableTokenFailsBeforeTheKeyIsDeleted() {
        store.save(URL, TOKEN);
        char[] loneSurrogate = {(char) 0xD800};
        assertThrows(StorageException.class, () -> store.save("https://other.example.test", loneSurrogate));
        assertEquals(0, cipher.keysDeleted);
        assertArrayEquals(TOKEN, store.token().orElseThrow());
    }

    @Test
    public void keyStoreFailureOnSaveFailsWithStorageException() {
        store.save(URL, TOKEN);
        cipher.failDelete = true;
        StorageException e = assertThrows(StorageException.class,
                () -> store.save("https://other.example.test", TOKEN));
        assertEquals("connection.save", e.op());
        assertEquals(List.of("STORAGE_ERROR:connection.save"), logger.lines);
    }

    @Test
    public void failedCommitOnSaveFailsWithStorageException() {
        prefs.failCommits = true;
        assertThrows(StorageException.class, () -> store.save(URL, TOKEN));
        assertEquals(List.of("STORAGE_ERROR:connection.save"), logger.lines);
    }

    @Test
    public void logsNeverContainTokenOrUrl() {
        store.save(URL, TOKEN);
        cipher.failDecrypt = true;
        store.token();
        prefs.failCommits = true;
        assertThrows(StorageException.class, () -> store.save(URL, TOKEN));
        for (String line : logger.lines) {
            assertFalse(line.contains("fake-token"));
            assertFalse(line.contains("example"));
        }
    }
}
