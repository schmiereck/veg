package io.veg.model;

import java.util.Objects;

/**
 * First-class directed typed relation between two entities.
 *
 * <p>Because an {@code Edge} carries its own {@link EntityId}, it is itself an entity in the graph
 * and can serve as {@code sourceId} or {@code targetId} of further edges (e.g., provenance or confidence).</p>
 */
public record Edge(
        EntityId id,
        EntityId sourceId,
        EntityId predicateId,
        EntityId targetId
) {
    public Edge {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(predicateId, "predicateId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
    }
}
