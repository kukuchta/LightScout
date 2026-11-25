package app.lightscout.storage;

import android.content.SharedPreferences;
import android.security.keystore.KeyPermanentlyInvalidatedException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.BadPaddingException;

import app.lightscout.domain.port.ConnectionStore;
import app.lightscout.domain.port.EventCode;
import app.lightscout.domain.port.Logger;

/**
 * IF-12 {@link ConnectionStore} and IF-61 {@code TokenSource}: ENT-06 URL plus the Keystore-encrypted token in
 * the ST-03 {@code connection} preferences (CE).
 *
 * <p>Rules (CP-04 §8.4, CP-00A R-20, R-39):
 * <ul>
 *   <li>IV and ciphertext are stored Base64 (no wrap) in two keys, with {@code token_format = 1}; a new IV is
 *       used on every save;</li>
 *   <li>the plaintext is never kept in a field; {@link #token()} returns a fresh array the caller wipes; the
 *       array passed to {@link #save(String, char[])} is neither wiped nor retained (the caller wipes it);</li>
 *   <li>a save for a different host deletes the old key first, so the token is encrypted under a new key
 *       (07 §10 S4); a key that is unusable for encryption (invalidated / unrecoverable / invalid key) is replaced once (IR-18), so that „Fix connection” works after a
 *       key invalidation;</li>
 *   <li>key or ciphertext unusable (unrecoverable / permanently invalidated key, failed authentication tag,
 *       undecodable data) → {@link #token()} returns {@link Optional#empty()} and logs {@code KEY_INVALIDATED}
 *       (ADR-033 → {@code TOKEN_REJECTED} path); any other Keystore / provider / I/O failure logs
 *       {@code STORAGE_ERROR} and throws {@code StorageException} (R-20, IR-17);</li>
 *   <li>{@link #clear()} deletes URL, IV, ciphertext, format and the key alias.</li>
 * </ul>
 * Methods are synchronized: saves and reads may come from different {@code ioExecutor} threads.
 */
final class KeystoreConnectionStore implements ConnectionStore {

    static final String BASE_URL = "base_url";
    static final String TOKEN_IV = "token_iv";
    static final String TOKEN_CIPHERTEXT = "token_ciphertext";
    static final String TOKEN_FORMAT = "token_format";
    static final int FORMAT = 1;

    private static final String OP_URL = "connection.baseUrl";
    private static final String OP_TOKEN = "connection.token";
    private static final String OP_SAVE = "connection.save";
    private static final String OP_CLEAR = "connection.clear";

    private final SharedPreferences prefs;
    private final TokenCipher cipher;
    private final Logger logger;
    private final StoreGuard guard;

    /**
     * @param prefs  the CE {@code connection} preferences
     * @param cipher the ST-06 key
     * @param logger IF-66 event logger
     * @param guard  failure logging and mapping (R-20)
     */
    KeystoreConnectionStore(SharedPreferences prefs, TokenCipher cipher, Logger logger, StoreGuard guard) {
        this.prefs = prefs;
        this.cipher = cipher;
        this.logger = logger;
        this.guard = guard;
    }

    @Override
    public synchronized Optional<String> baseUrl() {
        try {
            return Optional.ofNullable(prefs.getString(BASE_URL, null));
        } catch (RuntimeException e) {
            throw guard.fail(OP_URL, e);
        }
    }

    @Override
    public synchronized Optional<char[]> token() {
        String iv;
        String ciphertext;
        try {
            iv = prefs.getString(TOKEN_IV, null);
            ciphertext = prefs.getString(TOKEN_CIPHERTEXT, null);
        } catch (RuntimeException e) {
            throw guard.fail(OP_TOKEN, e);
        }
        if (iv == null || ciphertext == null) {
            return Optional.empty();
        }
        TokenCipher.SealedToken sealed;
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            sealed = new TokenCipher.SealedToken(decoder.decode(iv), decoder.decode(ciphertext));
        } catch (IllegalArgumentException e) {
            logger.event(EventCode.KEY_INVALIDATED);
            return Optional.empty();
        }
        byte[] plaintext = null;
        try {
            plaintext = cipher.decrypt(sealed);
            return Optional.of(TokenChars.decode(plaintext));
        } catch (CharacterCodingException e) {
            logger.event(EventCode.KEY_INVALIDATED);
            return Optional.empty();
        } catch (GeneralSecurityException | RuntimeException e) {
            if (keyOrDataUnusable(e)) {
                logger.event(EventCode.KEY_INVALIDATED);
                return Optional.empty();
            }
            throw guard.fail(OP_TOKEN, e);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    @Override
    public synchronized void save(String baseUrl, char[] token) {
        boolean written;
        byte[] plaintext = null;
        try {
            // IR-21: everything that can fail cheaply comes before the destructive key deletion.
            Objects.requireNonNull(baseUrl, "baseUrl");
            plaintext = TokenChars.encode(Objects.requireNonNull(token, "token"));
            String previousUrl = prefs.getString(BASE_URL, null);
            if (previousUrl != null && !sameHost(previousUrl, baseUrl)) {
                cipher.deleteKey();
            }
            TokenCipher.SealedToken sealed = encryptReplacingUnusableKey(plaintext);
            Base64.Encoder encoder = Base64.getEncoder();
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(BASE_URL, baseUrl)
                    .putString(TOKEN_IV, encoder.encodeToString(sealed.iv()))
                    .putString(TOKEN_CIPHERTEXT, encoder.encodeToString(sealed.ciphertext()))
                    .putInt(TOKEN_FORMAT, FORMAT);
            PrefsSchema.stamp(prefs, editor);
            written = StoreGuard.commit(editor);
        } catch (GeneralSecurityException | CharacterCodingException | RuntimeException e) {
            throw guard.fail(OP_SAVE, e);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
        if (!written) {
            throw guard.fail(OP_SAVE, null);
        }
    }

    @Override
    public synchronized void clear() {
        boolean written;
        try {
            written = StoreGuard.commit(prefs.edit()
                    .remove(BASE_URL)
                    .remove(TOKEN_IV)
                    .remove(TOKEN_CIPHERTEXT)
                    .remove(TOKEN_FORMAT));
            cipher.deleteKey();
        } catch (GeneralSecurityException | RuntimeException e) {
            throw guard.fail(OP_CLEAR, e);
        }
        if (!written) {
            throw guard.fail(OP_CLEAR, null);
        }
    }

    private TokenCipher.SealedToken encryptReplacingUnusableKey(byte[] plaintext) throws GeneralSecurityException {
        try {
            return cipher.encrypt(plaintext);
        } catch (GeneralSecurityException firstFailure) {
            if (!keyUnusableForEncryption(firstFailure)) {
                throw firstFailure;
            }
            // ADR-033: an invalidated / unreadable key must not block „Fix connection” forever (IR-18: only for
            // key-unusable failures; a transient Keystore error never destroys a valid key).
            cipher.deleteKey();
            return cipher.encrypt(plaintext);
        }
    }

    /**
     * Decrypt failures that mean „the key or the stored data can no longer be used” (IR-17): the token is treated
     * as missing (ADR-033). Any other Keystore / provider failure is a storage error (R-20).
     */
    static boolean keyOrDataUnusable(Throwable e) {
        return e instanceof UnrecoverableKeyException
                || e instanceof KeyPermanentlyInvalidatedException
                || e instanceof BadPaddingException;   // includes AEADBadTagException (corrupted ciphertext / IV)
    }

    /** Encrypt failures after which the key is replaced (IR-18). */
    static boolean keyUnusableForEncryption(Throwable e) {
        return e instanceof KeyPermanentlyInvalidatedException
                || e instanceof UnrecoverableKeyException
                || e instanceof InvalidKeyException;
    }

    /**
     * Host comparison of 07 §10 S4 (lower-cased host; an unparsable URL counts as a different host, so the key
     * is replaced — the safe side).
     */
    static boolean sameHost(String a, String b) {
        String hostA = host(a);
        return hostA != null && hostA.equals(host(b));
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
