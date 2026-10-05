package com.ktogroup.ktoggle.bundle.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class EcKeysTest {

    private static final byte[] CONTENT = "canonical content".getBytes(StandardCharsets.UTF_8);

    private final KeyPair pair = EcKeys.generate();

    @Test
    void a_signature_verifies_with_the_matching_public_key_only() {
        String signature = EcKeys.sign(pair.getPrivate(), CONTENT);

        assertThat(EcKeys.verify(pair.getPublic(), CONTENT, signature)).isTrue();
        assertThat(EcKeys.verify(EcKeys.generate().getPublic(), CONTENT, signature)).isFalse();
        assertThat(EcKeys.verify(pair.getPublic(), "other".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }

    @Test
    void malformed_signatures_do_not_verify_instead_of_throwing() {
        assertThat(EcKeys.verify(pair.getPublic(), CONTENT, "%%% not base64 %%%")).isFalse();
        assertThat(EcKeys.verify(pair.getPublic(), CONTENT, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}))).isFalse();
    }

    @Test
    void keys_can_be_loaded_from_bare_base64() {
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        String signature = EcKeys.sign(EcKeys.privateKey(privateKey), CONTENT);

        assertThat(EcKeys.verify(EcKeys.publicKey(publicKey), CONTENT, signature)).isTrue();
    }

    @Test
    void keys_can_be_loaded_from_pem_with_line_breaks() {
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        String signature = EcKeys.sign(EcKeys.privateKey(privatePem), CONTENT);

        assertThat(EcKeys.verify(EcKeys.publicKey(publicPem), CONTENT, signature)).isTrue();
        assertThat(EcKeys.publicKey(pair.getPublic().getEncoded())).isEqualTo(pair.getPublic());
    }

    @Test
    void garbage_keys_are_rejected_with_a_clear_error() {
        String garbage = Base64.getEncoder().encodeToString("not a key".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> EcKeys.privateKey(garbage)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid EC private key");
        assertThatThrownBy(() -> EcKeys.publicKey(garbage)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid EC public key");
    }

    @Test
    void generated_keys_are_p256() {
        assertThat(pair.getPublic().getAlgorithm()).isEqualTo("EC");
        assertThat(pair.getPublic().getEncoded()).hasSizeBetween(85, 95);
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
