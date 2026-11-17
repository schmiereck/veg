package io.veg.model;

import java.util.Optional;

/**
 * Minimal hardcoded bootstrap kernel IDs (range {@code 1..999}) required to avoid infinite meta-type recursion.
 *
 * <p>All domain types, value types, predicates, and constraints defined by users or applications
 * are represented as ordinary graph entities referencing these bootstrap primitives.</p>
 */
public final class BootstrapIds {

    // --- Meta-types (1..9) ---
    public static final EntityId META_TYPE = new EntityId(1L);
    public static final EntityId META_OBJECT_TYPE = new EntityId(2L);
    public static final EntityId META_VALUE_TYPE = new EntityId(3L);
    public static final EntityId META_PREDICATE_TYPE = new EntityId(4L);
    public static final EntityId META_EDGE_TYPE = new EntityId(5L);
    public static final EntityId SYSTEM_IDENTIFIER_VALUE_TYPE = new EntityId(6L);

    // --- Scalar primitive type entities (10..19) ---
    public static final EntityId SCALAR_STRING = new EntityId(10L);
    public static final EntityId SCALAR_INT = new EntityId(11L);
    public static final EntityId SCALAR_DECIMAL = new EntityId(12L);
    public static final EntityId SCALAR_BOOLEAN = new EntityId(13L);
    public static final EntityId SCALAR_BINARY = new EntityId(14L);
    public static final EntityId SCALAR_TIMESTAMP = new EntityId(15L);

    // --- Meta-predicates storing schema definitions inside the graph (20..29) ---
    public static final EntityId HAS_NAME = new EntityId(20L);
    public static final EntityId HAS_SCALAR_TYPE = new EntityId(21L);
    public static final EntityId HAS_DOMAIN = new EntityId(22L);
    public static final EntityId HAS_RANGE = new EntityId(23L);

    // --- Domain/Range wildcards (30..39) ---
    public static final EntityId ANY_ENTITY = new EntityId(30L);
    public static final EntityId ANY_OBJECT = new EntityId(31L);
    public static final EntityId ANY_VALUE = new EntityId(32L);
    public static final EntityId ANY_EDGE = new EntityId(33L);

    private BootstrapIds() {
    }

    public static EntityId forValueType(ValueType valueType) {
        return switch (valueType) {
            case STRING -> SCALAR_STRING;
            case INT -> SCALAR_INT;
            case DECIMAL -> SCALAR_DECIMAL;
            case BOOLEAN -> SCALAR_BOOLEAN;
            case BINARY -> SCALAR_BINARY;
            case TIMESTAMP -> SCALAR_TIMESTAMP;
        };
    }

    public static Optional<ValueType> toValueType(EntityId scalarTypeId) {
        if (SCALAR_STRING.equals(scalarTypeId)) {
            return Optional.of(ValueType.STRING);
        }
        if (SCALAR_INT.equals(scalarTypeId)) {
            return Optional.of(ValueType.INT);
        }
        if (SCALAR_DECIMAL.equals(scalarTypeId)) {
            return Optional.of(ValueType.DECIMAL);
        }
        if (SCALAR_BOOLEAN.equals(scalarTypeId)) {
            return Optional.of(ValueType.BOOLEAN);
        }
        if (SCALAR_BINARY.equals(scalarTypeId)) {
            return Optional.of(ValueType.BINARY);
        }
        if (SCALAR_TIMESTAMP.equals(scalarTypeId)) {
            return Optional.of(ValueType.TIMESTAMP);
        }
        return Optional.empty();
    }

    public static boolean isWildcard(EntityId typeId) {
        return ANY_ENTITY.equals(typeId)
                || ANY_OBJECT.equals(typeId)
                || ANY_VALUE.equals(typeId)
                || ANY_EDGE.equals(typeId);
    }
}
