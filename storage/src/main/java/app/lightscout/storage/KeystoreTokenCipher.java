package app.lightscout.storage;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * CMP-25 SecretStore key (ST-06): a non-exportable AES-256-GCM key in the Android Keystore under the alias
 * {@value #ALIAS}, exactly per SR-01 / ADR-056.
 *
 * <ul>
 *   <li>purposes ENCRYPT | DECRYPT; block mode GCM; padding none; 256-bit key; 128-bit tag;</li>
 *   <li>randomized encryption required: the Keystore generates a fresh 12-byte IV per encryption; caller IVs
 *       are never supplied;</li>
 *   <li>AAD = {@value #AAD} (fixed context string incl. the format version, CP-00A R-39);</li>
 *   <li>not bound to user authentication and <b>without</b> {@code setUnlockedDeviceRequired(true)}: the
 *       background tick must decrypt while the screen is locked after the first unlock (VR-22);</li>
 *   <li>StrongBox is not requested (TEE expected, not required).</li>
 * </ul>
 */
final class KeystoreTokenCipher implements TokenCipher {

    /** Keystore alias of the token key (CP-04 §5.2 ST-06). */
    static final String ALIAS = "lightscout.token";

    /** Additional authenticated data with the format version (CP-00A R-39). */
    static final String AAD = "lightscout.token.v1";

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_SIZE_BITS = 256;
    private static final int TAG_LENGTH_BITS = 128;

    @Override
    public SealedToken encrypt(byte[] plaintext) throws GeneralSecurityException {
        Key key = loadKeyStore().getKey(ALIAS, null);
        SecretKey secretKey = key instanceof SecretKey existing ? existing : generateKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        cipher.updateAAD(aad());
        byte[] ciphertext = cipher.doFinal(plaintext);
        return new SealedToken(cipher.getIV(), ciphertext);
    }

    @Override
    public byte[] decrypt(SealedToken sealed) throws GeneralSecurityException {
        Key key = loadKeyStore().getKey(ALIAS, null);
        if (!(key instanceof SecretKey secretKey)) {
            throw new UnrecoverableKeyException("token key missing");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, sealed.iv()));
        cipher.updateAAD(aad());
        return cipher.doFinal(sealed.ciphertext());
    }

    @Override
    public void deleteKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        if (keyStore.containsAlias(ALIAS)) {
            keyStore.deleteEntry(ALIAS);
        }
    }

    /**
     * Builds the key parameters of SR-01. Used by {@link #generateKey()}; package-visible so that the
     * instrumented TST-05-09 can assert the parameters that {@code KeyInfo} does not expose
     * ({@code isUnlockedDeviceRequired}, randomized encryption).
     *
     * @return the key generation parameters
     */
    static KeyGenParameterSpec keySpec() {
        return new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build();
    }

    private static SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(keySpec());
        return generator.generateKey();
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        try {
            keyStore.load(null);
        } catch (IOException e) {
            throw new KeyStoreException("AndroidKeyStore load", e);
        }
        return keyStore;
    }

    private static byte[] aad() {
        return AAD.getBytes(StandardCharsets.UTF_8);
    }
}
