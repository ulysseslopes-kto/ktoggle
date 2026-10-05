package com.ktogroup.ktoggle.bundle.signing.zout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ktogroup.ktoggle.bundle.signing.EcKeys;
import com.ktogroup.ktoggle.commons.canonical.Hashes;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

class KmsBundleSignerTest {

    private static final String KEY_ARN = "arn:aws:kms:eu-west-1:111122223333:key/abc";

    private final KeyPair kmsKey = EcKeys.generate();
    private final KmsClient kms = mock(KmsClient.class);
    private final KmsBundleSigner signer = new KmsBundleSigner(kms, KEY_ARN);

    @Test
    void sign_asks_kms_to_sign_the_sha256_digest_with_ecdsa_and_the_result_verifies_locally() throws Exception {
        SignRequest[] sent = new SignRequest[1];
        when(kms.sign(any(Consumer.class))).thenAnswer(invocation -> {
            SignRequest.Builder builder = SignRequest.builder();
            invocation.<Consumer<SignRequest.Builder>>getArgument(0).accept(builder);
            sent[0] = builder.build();
            Signature ecdsa = Signature.getInstance("NONEwithECDSA");
            ecdsa.initSign(kmsKey.getPrivate());
            ecdsa.update(sent[0].message().asByteArray());
            return SignResponse.builder().signature(SdkBytes.fromByteArray(ecdsa.sign())).build();
        });
        byte[] content = "canonical bundle".getBytes(StandardCharsets.UTF_8);

        String signature = signer.sign(content);

        assertThat(sent[0].keyId()).isEqualTo(KEY_ARN);
        assertThat(sent[0].messageType()).isEqualTo(MessageType.DIGEST);
        assertThat(sent[0].signingAlgorithm()).isEqualTo(SigningAlgorithmSpec.ECDSA_SHA_256);
        assertThat(sent[0].message().asByteArray()).isEqualTo(Hashes.sha256(content));
        assertThat(EcKeys.verify(kmsKey.getPublic(), content, signature)).isTrue();
        assertThat(Base64.getDecoder().decode(signature)).isNotEmpty();
        assertThat(signer.keyId()).isEqualTo(KEY_ARN);
    }

    @Test
    void public_keys_of_kms_keys_are_fetched_once_and_cached() {
        stubPublicKey();

        var first = signer.find(KEY_ARN);
        var second = signer.find(KEY_ARN);

        assertThat(first).isPresent();
        assertThat(first.get()).isEqualTo(kmsKey.getPublic());
        assertThat(second).isEqualTo(first);
        verify(kms, times(1)).getPublicKey(any(Consumer.class));
    }

    @Test
    void keys_from_an_earlier_rotation_are_resolved_by_their_own_arn() {
        stubPublicKey();

        assertThat(signer.find("arn:aws:kms:eu-west-1:111122223333:key/old")).isPresent();
    }

    @Test
    void the_signers_own_key_id_is_trusted_even_when_it_is_not_an_arn() {
        stubPublicKey();
        KmsBundleSigner alias = new KmsBundleSigner(kms, "alias/ktoggle-bundles");

        assertThat(alias.find("alias/ktoggle-bundles")).isPresent();
    }

    @Test
    void ids_that_are_not_kms_keys_are_never_looked_up() {
        assertThat(signer.find("local-dev")).isEmpty();

        verify(kms, never()).getPublicKey(any(Consumer.class));
    }

    @Test
    void a_kms_failure_means_the_key_is_untrusted_and_is_not_cached() {
        when(kms.getPublicKey(any(Consumer.class))).thenThrow(KmsException.builder().message("denied").build());

        assertThat(signer.find(KEY_ARN)).isEmpty();

        stubPublicKey();
        assertThat(signer.find(KEY_ARN)).isPresent();
    }

    private void stubPublicKey() {
        when(kms.getPublicKey(any(Consumer.class))).thenAnswer(invocation -> {
            GetPublicKeyRequest.Builder builder = GetPublicKeyRequest.builder();
            invocation.<Consumer<GetPublicKeyRequest.Builder>>getArgument(0).accept(builder);
            return GetPublicKeyResponse.builder().keyId(builder.build().keyId())
                    .publicKey(SdkBytes.fromByteArray(kmsKey.getPublic().getEncoded())).build();
        });
    }
}
