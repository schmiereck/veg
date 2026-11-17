package io.veg.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents a directed assertion triple {@code (sourceId, predicateId, targetId)} alongside its
 * {@link AssertionOrigin}, keeping explicitly persisted edges cleanly separated from derived views.
 */
public record DerivedAssertion(
        EntityId sourceId,
        EntityId predicateId,
        EntityId targetId,
        AssertionOrigin origin,
        Optional<EntityId> backingEdgeId,
        Optional<EntityId> viaPredicateId
) {
    public DerivedAssertion {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(predicateId, "predicateId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(backingEdgeId, "backingEdgeId");
        Objects.requireNonNull(viaPredicateId, "viaPredicateId");
    }

    public static DerivedAssertion explicit(Edge edge) {
        Objects.requireNonNull(edge, "edge");
        return new DerivedAssertion(
                edge.sourceId(),
                edge.predicateId(),
                edge.targetId(),
                AssertionOrigin.EXPLICIT,
                Optional.of(edge.id()),
                Optional.empty()
        );
    }

    public static DerivedAssertion derived(
            EntityId sourceId,
            EntityId predicateId,
            EntityId targetId,
            AssertionOrigin origin,
            EntityId backingEdgeId,
            EntityId viaPredicateId
    ) {
        if (origin == AssertionOrigin.EXPLICIT) {
            throw new IllegalArgumentException("Use explicit(Edge) for EXPLICIT assertions");
        }
        return new DerivedAssertion(
                sourceId,
                predicateId,
                targetId,
                origin,
                Optional.of(Objects.requireNonNull(backingEdgeId, "backingEdgeId")),
                Optional.of(Objects.requireNonNull(viaPredicateId, "viaPredicateId"))
        );
    }
}
