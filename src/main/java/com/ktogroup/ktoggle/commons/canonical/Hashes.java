package com.ktogroup.ktoggle.commons.canonical;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** SHA-256 / HMAC-SHA256 helpers. Hashes are always lowercase hex of the UTF-8 bytes. */
public final class Hashes {

    private static final HexFormat HEX = HexFormat.of();

    private Hashes() {
    }

    public static String sha256Hex(String content) {
        return HEX.formatHex(sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Keyed digest used for personal data (e.g. player attributes): a plain SHA-256 of a CPF is trivially
     * reversible by brute force, an HMAC with a server-side secret is not.
     */
    public static String hmacSha256Hex(byte[] key, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
