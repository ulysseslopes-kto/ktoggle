package com.ktogroup.ktoggle.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DecisionPropertiesTest {

    @Test
    void unset_values_get_safe_defaults() {
        DecisionProperties properties = new DecisionProperties(null, null, 0, 0, null, null, 0, 0);

        assertThat(properties.digestKeyId()).isEqualTo("k1");
        assertThat(properties.queueCapacity()).isEqualTo(50_000);
        assertThat(properties.batchSize()).isEqualTo(500);
        assertThat(properties.maxPastAge()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.maxFutureSkew()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.maxEventsPerRequest()).isEqualTo(1_000);
        assertThat(properties.retentionMonths()).isEqualTo(13);
    }

    @Test
    void blank_key_id_and_negative_numbers_are_replaced_by_defaults() {
        DecisionProperties properties = new DecisionProperties(" ", "secret", -1, -5, null, null, -1, -1);

        assertThat(properties.digestKeyId()).isEqualTo("k1");
        assertThat(properties.digestSecret()).isEqualTo("secret");
        assertThat(properties.queueCapacity()).isEqualTo(50_000);
        assertThat(properties.retentionMonths()).isEqualTo(13);
    }

    @Test
    void configured_values_are_kept() {
        DecisionProperties properties = new DecisionProperties("k2", "s", 10, 5, Duration.ofDays(1), Duration.ofSeconds(30), 20, 3);

        assertThat(properties.digestKeyId()).isEqualTo("k2");
        assertThat(properties.queueCapacity()).isEqualTo(10);
        assertThat(properties.batchSize()).isEqualTo(5);
        assertThat(properties.maxPastAge()).isEqualTo(Duration.ofDays(1));
        assertThat(properties.maxFutureSkew()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.maxEventsPerRequest()).isEqualTo(20);
        assertThat(properties.retentionMonths()).isEqualTo(3);
    }
}
