package com.ktogroup.ktoggle.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature sent with every delivery so receivers can check it came from ktoggle and was not replayed:
 * {@code X-Ktoggle-Signature: sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>} together with
 * {@code X-Ktoggle-Timestamp} (epoch seconds). Receivers should reject old timestamps.
 */
public final class WebhookSignature {

    public static final String SIGNATURE_HEADER = "X-Ktoggle-Signature";
    public static final String TIMESTAMP_HEADER = "X-Ktoggle-Timestamp";
    public static final String EVENT_HEADER = "X-Ktoggle-Event";
    public static final String DELIVERY_HEADER = "X-Ktoggle-Delivery";
    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time check, as a receiver would do it. */
    public static boolean verify(String secret, long timestamp, String body, String signature) {
        return signature != null && MessageDigest.isEqual(sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
