package com.ktogroup.ktoggle.project;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * @param editorRoles Keycloak roles allowed to change this project's features
 * @param editorUsers usernames (or {@code token:<name>} for API tokens) allowed to change this project's features;
 *                    when both lists are empty the project is open to every editor
 */
public record Project(String key, String name, String description, List<String> editorRoles, List<String> editorUsers,
                      Instant createdAt, Instant updatedAt, Long version) {

    public Project {
        editorRoles = editorRoles == null ? List.of() : List.copyOf(editorRoles);
        editorUsers = editorUsers == null ? List.of() : List.copyOf(editorUsers);
    }

    public Project(String key, String name, String description, Instant createdAt, Instant updatedAt, Long version) {
        this(key, name, description, List.of(), List.of(), createdAt, updatedAt, version);
    }

    /** Whether editing is limited to the listed roles and users. */
    public boolean restricted() {
        return !editorRoles.isEmpty() || !editorUsers.isEmpty();
    }

    /** @param roles the caller's realm roles as granted (admins are handled by the caller) */
    public boolean editableBy(String user, Set<String> roles) {
        return !restricted() || editorUsers.contains(user) || editorRoles.stream().anyMatch(roles::contains);
    }
}
