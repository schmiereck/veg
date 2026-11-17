package io.veg.model;

import java.util.Objects;

/**
 * Machine-readable conflict reported by a three-way merge instead of silent last-writer-wins (H5).
 */
public record MergeConflict(
        MergeConflictType type,
        EntityId subjectId,
        String description
) {
    public MergeConflict {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
