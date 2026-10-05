package com.ktogroup.ktoggle.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.ktogroup.ktoggle.feature.EnvironmentSettings;
import com.ktogroup.ktoggle.feature.Feature;
import com.ktogroup.ktoggle.feature.FeaturePersistencePort;
import com.ktogroup.ktoggle.feature.ForceRule;
import com.ktogroup.ktoggle.feature.Rule;
import com.ktogroup.ktoggle.feature.RuleSchedule;
import com.ktogroup.ktoggle.feature.ValueType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleScheduleWatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    private final FeaturePersistencePort features = mock(FeaturePersistencePort.class);
    private final BundlePublisher publisher = mock(BundlePublisher.class);
    private final RuleScheduleWatcher watcher = new RuleScheduleWatcher(features, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void reports_rules_that_started_or_ended_in_the_window() {
        List<Feature> all = List.of(feature("promo", true,
                rule("fr_start", true, NOW.minusSeconds(5), null),
                rule("fr_end", true, null, NOW.minusSeconds(10)),
                rule("fr_later", true, NOW.plusSeconds(30), null),
                rule("fr_long_ago", true, NOW.minusSeconds(3600), null),
                rule("fr_disabled", false, NOW.minusSeconds(5), null)));

        assertThat(RuleScheduleWatcher.switchedBetween(all, NOW.minusSeconds(60), NOW))
                .containsExactly("promo/prd/fr_start started", "promo/prd/fr_end ended");
    }

    @Test
    void ignores_disabled_environments() {
        List<Feature> all = List.of(feature("promo", false, rule("fr_start", true, NOW.minusSeconds(5), null)));

        assertThat(RuleScheduleWatcher.switchedBetween(all, NOW.minusSeconds(60), NOW)).isEmpty();
    }

    @Test
    void publishes_as_the_scheduler_only_when_a_boundary_was_crossed() {
        when(features.findAllActive()).thenReturn(List.of(feature("promo", true, rule("fr_later", true, NOW.plusSeconds(30), null))));
        watcher.publishDueSchedules();
        verify(publisher, never()).publishAll(any());

        when(features.findAllActive()).thenReturn(List.of(feature("promo", true, rule("fr_now", true, NOW, null))));
        watcher.publishDueSchedules();
        verify(publisher).publishAll(argThat(context -> context.actor().equals("system:scheduler")
                && context.reason().contains("promo/prd/fr_now started")));
    }

    @Test
    void schedule_window_is_start_inclusive_and_end_exclusive() {
        RuleSchedule schedule = new RuleSchedule(NOW, NOW.plusSeconds(60));
        assertThat(schedule.activeAt(NOW.minusMillis(1))).isFalse();
        assertThat(schedule.activeAt(NOW)).isTrue();
        assertThat(schedule.activeAt(NOW.plusSeconds(60))).isFalse();
        assertThat(new RuleSchedule(null, null).isEmpty()).isTrue();
        assertThat(new ForceRule("fr", null, true, null, null, BooleanNode.TRUE, new RuleSchedule(null, null)).schedule())
                .as("an empty schedule is normalized away").isNull();
    }

    private static Rule rule(String id, boolean enabled, Instant startsAt, Instant endsAt) {
        return new ForceRule(id, null, enabled, null, List.of(), BooleanNode.TRUE, new RuleSchedule(startsAt, endsAt));
    }

    private static Feature feature(String key, boolean environmentEnabled, Rule... rules) {
        return new Feature(key, null, ValueType.BOOLEAN, BooleanNode.FALSE, null, null, List.of(), false,
                Map.of("prd", new EnvironmentSettings(environmentEnabled, List.of(rules))), 1, NOW, "test", NOW, "test", 0L);
    }
}
