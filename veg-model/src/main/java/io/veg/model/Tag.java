package io.veg.model;

import java.util.Objects;

/**
 * Immutable named version tag pointing to a specific {@link CommitId} (V-08).
 */
public record Tag(String name, CommitId commitId) {

    public Tag {
        Objects.requireNonNull(name, "tag name must not be null");
        Objects.requireNonNull(commitId, "commitId must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tag name must not be blank");
        }
    }
}
