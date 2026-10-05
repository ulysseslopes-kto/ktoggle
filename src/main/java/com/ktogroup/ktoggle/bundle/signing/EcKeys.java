package com.ktogroup.ktoggle.bundle.signing;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** ECDSA P-256 helpers. Keys are accepted as PEM or bare base64 (PKCS#8 private, X.509 public). */
public final class EcKeys {

    private static final String JCA_SIGNATURE = "SHA256withECDSA";

    private EcKeys() {
    }

    public static PrivateKey privateKey(String pemOrBase64) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decode(pemOrBase64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid EC private key", e);
        }
    }

    public static PublicKey publicKey(String pemOrBase64) {
        return publicKey(decode(pemOrBase64));
    }

    public static PublicKey publicKey(byte[] x509) {
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid EC public key", e);
        }
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("EC P-256 not available", e);
        }
    }

    public static String sign(PrivateKey key, byte[] content) {
        try {
            Signature signature = Signature.getInstance(JCA_SIGNATURE);
            signature.initSign(key);
            signature.update(content);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] content, String base64Signature) {
        try {
            Signature signature = Signature.getInstance(JCA_SIGNATURE);
            signature.initVerify(key);
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (SignatureException | InvalidKeyException | IllegalArgumentException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature verification unavailable", e);
        }
    }

    private static byte[] decode(String pemOrBase64) {
        String base64 = pemOrBase64.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
