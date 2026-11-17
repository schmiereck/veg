package io.veg.model;

/**
 * Defines whether the payload of a {@link EntityKind#VALUE} entity may be mutated in-place.
 */
public enum ValueMutability {
    /**
     * Default mode: the value entity's payload is immutable once created.
     * Changing an object's attribute value requires disconnecting the old edge and connecting a new value entity.
     */
    IMMUTABLE,

    /**
     * Explicit shared-mutation mode: updating this value entity's payload via {@code updateValue}
     * is permitted and immediately visible through all relations referencing this value entity.
     */
    SHARED_MUTABLE
}
