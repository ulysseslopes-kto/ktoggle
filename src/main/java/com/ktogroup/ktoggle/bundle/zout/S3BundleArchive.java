package com.ktogroup.ktoggle.bundle.zout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ktogroup.ktoggle.bundle.Bundle;
import com.ktogroup.ktoggle.bundle.BundleArchive;
import com.ktogroup.ktoggle.bundle.BundleProperties;
import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;

/**
 * Stores {@code <prefix><hash>.json} in a bucket with Object Lock enabled, in COMPLIANCE mode: nobody — not even
 * the AWS root account — can delete or overwrite it before the retention date. The key is the content hash, so
 * re-uploading after a partial failure writes identical bytes.
 */
@Component
@ConditionalOnProperty(name = "ktoggle.bundle.archive.s3.enabled", havingValue = "true")
public class S3BundleArchive implements BundleArchive {

    private final S3Client s3;
    private final ObjectMapper objectMapper;
    private final BundleProperties.S3 config;

    public S3BundleArchive(S3Client s3, ObjectMapper objectMapper, BundleProperties properties) {
        this.s3 = s3;
        this.objectMapper = objectMapper;
        this.config = properties.archive().s3();
        if (config.bucket() == null || config.bucket().isBlank()) {
            throw new IllegalStateException("ktoggle.bundle.archive.s3.bucket is required when the S3 archive is enabled");
        }
    }

    @Override
    public String store(Bundle bundle) {
        String key = config.prefix() + bundle.hash() + ".json";
        Instant retainUntil = bundle.createdAt().plus(Duration.ofDays(config.retentionDays()));
        s3.putObject(request -> request
                        .bucket(config.bucket())
                        .key(key)
                        .contentType("application/json")
                        .objectLockMode(ObjectLockMode.COMPLIANCE)
                        .objectLockRetainUntilDate(retainUntil)
                        .checksumAlgorithm("SHA256"),
                RequestBody.fromString(envelope(bundle)));
        return "s3://%s/%s".formatted(config.bucket(), key);
    }

    private String envelope(Bundle bundle) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("hash", bundle.hash());
        envelope.put("contractVersion", bundle.contractVersion());
        envelope.put("clientKey", bundle.clientKey());
        envelope.put("environmentKey", bundle.environmentKey());
        envelope.put("payloadHash", bundle.payloadHash());
        envelope.put("signatureAlg", bundle.signatureAlg());
        envelope.put("keyId", bundle.keyId());
        envelope.put("signature", bundle.signature());
        envelope.put("createdAt", bundle.createdAt().toString());
        envelope.put("createdBy", bundle.createdBy());
        envelope.put("content", bundle.content());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
