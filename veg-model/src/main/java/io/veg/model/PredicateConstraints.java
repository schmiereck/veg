package io.veg.model;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Cardinality and triple-uniqueness constraints stored as graph data on predicate entities.
 *
 * @param maxOutgoing  optional maximum outgoing edges per source entity for this predicate
 * @param maxIncoming  optional maximum incoming edges per target entity for this predicate
 * @param uniqueTriple when {@code true}, at most one edge with identical {@code (sourceId, predicateId, targetId)} may exist
 */
public record PredicateConstraints(
        OptionalInt maxOutgoing,
        OptionalInt maxIncoming,
        boolean uniqueTriple
) {
    public PredicateConstraints {
        Objects.requireNonNull(maxOutgoing, "maxOutgoing must not be null");
        Objects.requireNonNull(maxIncoming, "maxIncoming must not be null");
        if (maxOutgoing.isPresent() && maxOutgoing.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxOutgoing must be > 0, was: " + maxOutgoing.getAsInt());
        }
        if (maxIncoming.isPresent() && maxIncoming.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxIncoming must be > 0, was: " + maxIncoming.getAsInt());
        }
    }

    public static PredicateConstraints unconstrained() {
        return new PredicateConstraints(OptionalInt.empty(), OptionalInt.empty(), false);
    }

    public static PredicateConstraints uniqueSet() {
        return new PredicateConstraints(OptionalInt.empty(), OptionalInt.empty(), true);
    }

    public static PredicateConstraints manyToOne() {
        return new PredicateConstraints(OptionalInt.of(1), OptionalInt.empty(), true);
    }

    public static PredicateConstraints oneToMany() {
        return new PredicateConstraints(OptionalInt.empty(), OptionalInt.of(1), true);
    }

    public static PredicateConstraints oneToOne() {
        return new PredicateConstraints(OptionalInt.of(1), OptionalInt.of(1), true);
    }
}
