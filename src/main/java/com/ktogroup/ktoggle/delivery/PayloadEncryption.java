package com.ktogroup.ktoggle.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GrowthBook's {@code encryptedFeatures} format: AES-CBC with the connection's base64 key and a random IV, rendered as
 * {@code base64(iv) + "." + base64(ciphertext)}. Readable by the official SDKs ({@code decryptionKey} in JS,
 * {@code encryptionKey} in Java).
 *
 * <p>This keeps rule details out of plain sight in browsers and apps; it is not a secret against someone who extracts
 * the key from the app, which is why bundles (the audit record) are never encrypted.
 */
public final class PayloadEncryption {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private PayloadEncryption() {
    }

    public static String encrypt(String plainJson, String base64Key) {
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to encrypt payload", e);
        }
    }

    /** Inverse of {@link #encrypt}, for tests and diagnostics. */
    public static String decrypt(String encrypted, String base64Key) {
        try {
            String[] parts = encrypted.split("\\.");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"),
                    new IvParameterSpec(Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalStateException("Unable to decrypt payload", e);
        }
    }
}
