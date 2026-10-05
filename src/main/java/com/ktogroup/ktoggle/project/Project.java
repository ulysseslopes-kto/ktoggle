package com.ktogroup.ktoggle.project;

import java.time.Instant;

public record Project(String key, String name, String description, Instant createdAt, Instant updatedAt, Long version) {
}
