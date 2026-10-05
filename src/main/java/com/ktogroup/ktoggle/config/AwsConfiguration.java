package com.ktogroup.ktoggle.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;

/** AWS clients use the default credential chain (IRSA on EKS) and region (AWS_REGION). */
@Configuration
public class AwsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ktoggle.bundle.signing.provider", havingValue = "kms")
    public KmsClient kmsClient() {
        return KmsClient.create();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "ktoggle.bundle.archive.s3.enabled", havingValue = "true")
    public S3Client s3Client() {
        return S3Client.create();
    }
}
