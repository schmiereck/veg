package io.veg.model;

import java.util.Objects;

/**
 * Audit identity of the user or service executing a transaction or revert.
 */
public record Actor(String id, String displayName) {

    public Actor {
        Objects.requireNonNull(id, "actor id must not be null");
        Objects.requireNonNull(displayName, "actor displayName must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("actor id must not be blank");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("actor displayName must not be blank");
        }
    }

    public static Actor user(String id) {
        return new Actor(id, id);
    }

    public static Actor of(String id, String displayName) {
        return new Actor(id, displayName);
    }

    public static Actor system() {
        return new Actor("system", "VEG System");
    }
}
