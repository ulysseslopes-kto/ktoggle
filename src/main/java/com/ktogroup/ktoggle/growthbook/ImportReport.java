package com.ktogroup.ktoggle.growthbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What an import did (or, in a dry run, would do), item by item.
 *
 * @param dryRun true when nothing was written
 */
public record ImportReport(boolean dryRun, List<Item> items, Map<Action, Integer> totals) {

    public enum Action {
        CREATE, UPDATE, UNCHANGED, UNSUPPORTED, FAILED
    }

    /**
     * @param type     project, environment, attribute, savedGroup, feature or sdkConnection
     * @param key      the ktoggle key (GrowthBook ids are kept where possible)
     * @param messages warnings (partially imported items) or the reason it was not imported
     */
    public record Item(String type, String key, Action action, List<String> messages) {
    }

    public static Builder builder(boolean dryRun) {
        return new Builder(dryRun);
    }

    public static final class Builder {

        private final boolean dryRun;
        private final List<Item> items = Collections.synchronizedList(new ArrayList<>());

        private Builder(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public void add(String type, String key, Action action, List<String> messages) {
            items.add(new Item(type, key, action, messages == null ? List.of() : List.copyOf(messages)));
        }

        public void add(String type, String key, Action action, String message) {
            add(type, key, action, message == null ? List.of() : List.of(message));
        }

        public ImportReport build() {
            Map<Action, Integer> totals = new EnumMap<>(Action.class);
            for (Action action : Action.values()) {
                totals.put(action, 0);
            }
            items.forEach(item -> totals.merge(item.action(), 1, Integer::sum));
            return new ImportReport(dryRun, List.copyOf(items), totals);
        }
    }
}
