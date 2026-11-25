package app.lightscout.storage;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * JVM stand-in for {@link KeystoreTokenCipher}: a JDK software AES-256-GCM key with a random 12-byte IV per
 * encryption and the same AAD. Counts key generations and deletions; {@link #encryptFailureOnce},
 * {@link #decryptFailure} and {@link #failDecrypt} simulate Keystore failures.
 */
final class FakeTokenCipher implements TokenCipher {

    private final SecureRandom random = new SecureRandom();
    private SecretKey key;
    int keysGenerated;
    int keysDeleted;
    /** Thrown once by the next {@link #encrypt}; null = no failure. */
    GeneralSecurityException encryptFailureOnce;
    /** Thrown by every {@link #decrypt} while set; null = no failure. */
    GeneralSecurityException decryptFailure;
    boolean failDecrypt;
    boolean failDelete;

    boolean hasKey() {
        return key != null;
    }

    SecretKey currentKey() {
        return key;
    }

    @Override
    public SealedToken encrypt(byte[] plaintext) throws GeneralSecurityException {
        if (encryptFailureOnce != null) {
            GeneralSecurityException failure = encryptFailureOnce;
            encryptFailureOnce = null;
            throw failure;
        }
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            key = generator.generateKey();
            keysGenerated++;
        }
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        cipher.updateAAD(KeystoreTokenCipher.AAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new SealedToken(iv, cipher.doFinal(plaintext));
    }

    @Override
    public byte[] decrypt(SealedToken sealed) throws GeneralSecurityException {
        if (decryptFailure != null) {
            throw decryptFailure;
        }
        if (failDecrypt || key == null) {
            throw new UnrecoverableKeyException("simulated missing key");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed.iv()));
        cipher.updateAAD(KeystoreTokenCipher.AAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher.doFinal(sealed.ciphertext());
    }

    @Override
    public void deleteKey() throws GeneralSecurityException {
        if (failDelete) {
            throw new GeneralSecurityException("simulated key store failure");
        }
        if (key != null) {
            key = null;
            keysDeleted++;
        }
    }
}
