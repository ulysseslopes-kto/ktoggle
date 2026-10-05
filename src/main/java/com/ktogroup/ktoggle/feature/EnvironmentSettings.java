package com.ktogroup.ktoggle.feature;

import java.util.List;

/** Per-environment state of a feature. An environment without settings is disabled with no rules. */
public record EnvironmentSettings(boolean enabled, List<Rule> rules) {

    public static final EnvironmentSettings DISABLED = new EnvironmentSettings(false, List.of());

    public EnvironmentSettings {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public EnvironmentSettings withEnabled(boolean value) {
        return new EnvironmentSettings(value, rules);
    }
}
