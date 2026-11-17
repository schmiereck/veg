package io.veg.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Logical representation of an entity node inside a graph snapshot.
 *
 * @param id      stable entity identifier
 * @param typeId  entity ID of the entity's defining type (object type, value type, or meta-type)
 * @param kind    structural entity kind
 * @param payload present if and only if {@code kind == EntityKind.VALUE}
 */
public record EntityNode(
        EntityId id,
        EntityId typeId,
        EntityKind kind,
        Optional<Value> payload
) {
    public EntityNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(typeId, "typeId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (kind == EntityKind.VALUE && payload.isEmpty()) {
            throw new IllegalArgumentException("EntityNode of kind VALUE must carry a payload");
        }
        if (kind != EntityKind.VALUE && payload.isPresent()) {
            throw new IllegalArgumentException("Only EntityNode of kind VALUE may carry a payload, was: " + kind);
        }
    }
}
