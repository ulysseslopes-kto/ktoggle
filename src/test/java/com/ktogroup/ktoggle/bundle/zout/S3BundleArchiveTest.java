package com.ktogroup.ktoggle.bundle.zout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleProperties;
import com.ktogroup.ktoggle.ztest.TestBundles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3BundleArchiveTest {

    private final TestBundles testBundles = new TestBundles();
    private final ObjectMapper mapper = testBundles.objectMapper();
    private final S3Client s3 = mock(S3Client.class);

    @Test
    void bundles_are_stored_under_their_hash_with_compliance_object_lock_until_the_retention_date() throws Exception {
        S3BundleArchive archive = archive("audit-bucket", "bundles/", 30);
        Bundle bundle = testBundles.bundle("sdk-a", true);

        String location = archive.store(bundle);

        PutObjectRequest request = capturedRequest();
        assertThat(location).isEqualTo("s3://audit-bucket/bundles/" + bundle.hash() + ".json");
        assertThat(request.bucket()).isEqualTo("audit-bucket");
        assertThat(request.key()).isEqualTo("bundles/" + bundle.hash() + ".json");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(request.objectLockRetainUntilDate()).isEqualTo(bundle.createdAt().plus(Duration.ofDays(30)));
        assertThat(request.checksumAlgorithmAsString()).isEqualTo("SHA256");
    }

    @Test
    void the_stored_object_carries_the_envelope_and_the_canonical_content_to_verify_it_offline() throws Exception {
        S3BundleArchive archive = archive("audit-bucket", "bundles/", 30);
        Bundle bundle = testBundles.bundle("sdk-a", true);

        archive.store(bundle);

        JsonNode stored = mapper.readTree(capturedBody());
        assertThat(stored.path("hash").asText()).isEqualTo(bundle.hash());
        assertThat(stored.path("clientKey").asText()).isEqualTo("sdk-a");
        assertThat(stored.path("environmentKey").asText()).isEqualTo("prod");
        assertThat(stored.path("keyId").asText()).isEqualTo(bundle.keyId());
        assertThat(stored.path("signature").asText()).isEqualTo(bundle.signature());
        assertThat(stored.path("signatureAlg").asText()).isEqualTo(bundle.signatureAlg());
        assertThat(stored.path("payloadHash").asText()).isEqualTo(bundle.payloadHash());
        assertThat(stored.path("createdAt").asText()).isEqualTo(bundle.createdAt().toString());
        assertThat(stored.path("createdBy").asText()).isEqualTo("alice");
        assertThat(stored.path("content").asText()).isEqualTo(bundle.content());
    }

    @Test
    void the_configured_prefix_and_the_default_retention_apply() {
        S3BundleArchive archive = archive("audit-bucket", null, 0);
        Bundle bundle = testBundles.bundle("sdk-a", true);

        archive.store(bundle);

        PutObjectRequest request = capturedRequest();
        assertThat(request.key()).isEqualTo("bundles/" + bundle.hash() + ".json");
        assertThat(request.objectLockRetainUntilDate()).isEqualTo(bundle.createdAt().plus(Duration.ofDays(1825)));
    }

    @Test
    void the_archive_cannot_be_enabled_without_a_bucket() {
        assertThatThrownBy(() -> archive(null, "bundles/", 30)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket is required");
        assertThatThrownBy(() -> archive("  ", "bundles/", 30)).isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    private PutObjectRequest capturedRequest() {
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> consumer = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).putObject(consumer.capture(), any(RequestBody.class));
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        consumer.getValue().accept(builder);
        return builder.build();
    }

    private String capturedBody() throws IOException {
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(any(Consumer.class), body.capture());
        return new String(body.getValue().contentStreamProvider().newStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private S3BundleArchive archive(String bucket, String prefix, int retentionDays) {
        BundleProperties properties = new BundleProperties(null,
                new BundleProperties.Archive(new BundleProperties.S3(true, bucket, prefix, retentionDays)), null);
        return new S3BundleArchive(s3, mapper, properties);
    }
}
