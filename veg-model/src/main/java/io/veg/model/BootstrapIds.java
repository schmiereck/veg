package io.veg.model;

import java.util.Optional;

/**
 * Minimal hardcoded bootstrap kernel IDs (range {@code 1..999}) required to avoid infinite meta-type recursion.
 *
 * <p>All domain types, value types, predicates, and constraints defined by users or applications
 * are represented as ordinary graph entities referencing these bootstrap primitives.</p>
 */
public final class BootstrapIds {

    // --- Meta-types and system value types (1..9) ---
    public static final EntityId META_TYPE = new EntityId(1L);
    public static final EntityId META_OBJECT_TYPE = new EntityId(2L);
    public static final EntityId META_VALUE_TYPE = new EntityId(3L);
    public static final EntityId META_PREDICATE_TYPE = new EntityId(4L);
    public static final EntityId META_EDGE_TYPE = new EntityId(5L);
    public static final EntityId SYSTEM_IDENTIFIER_VALUE_TYPE = new EntityId(6L);
    public static final EntityId SYSTEM_INT_VALUE_TYPE = new EntityId(7L);
    public static final EntityId SYSTEM_BOOLEAN_VALUE_TYPE = new EntityId(8L);

    // --- Scalar primitive type entities (10..19) ---
    public static final EntityId SCALAR_STRING = new EntityId(10L);
    public static final EntityId SCALAR_INT = new EntityId(11L);
    public static final EntityId SCALAR_DECIMAL = new EntityId(12L);
    public static final EntityId SCALAR_BOOLEAN = new EntityId(13L);
    public static final EntityId SCALAR_BINARY = new EntityId(14L);
    public static final EntityId SCALAR_TIMESTAMP = new EntityId(15L);

    // --- Meta-predicates storing schema and mutability definitions inside the graph (20..29) ---
    public static final EntityId HAS_NAME = new EntityId(20L);
    public static final EntityId HAS_SCALAR_TYPE = new EntityId(21L);
    public static final EntityId HAS_DOMAIN = new EntityId(22L);
    public static final EntityId HAS_RANGE = new EntityId(23L);
    public static final EntityId HAS_MAX_OUTGOING = new EntityId(24L);
    public static final EntityId HAS_MAX_INCOMING = new EntityId(25L);
    public static final EntityId HAS_UNIQUE_TRIPLE = new EntityId(26L);
    public static final EntityId HAS_UNIQUE_VALUES = new EntityId(27L);
    public static final EntityId HAS_MUTABILITY = new EntityId(28L);

    // --- Domain/Range wildcards (30..39) ---
    public static final EntityId ANY_ENTITY = new EntityId(30L);
    public static final EntityId ANY_OBJECT = new EntityId(31L);
    public static final EntityId ANY_VALUE = new EntityId(32L);
    public static final EntityId ANY_EDGE = new EntityId(33L);

    // --- Mutability marker entities (40..49) ---
    public static final EntityId MUTABILITY_IMMUTABLE = new EntityId(40L);
    public static final EntityId MUTABILITY_SHARED_MUTABLE = new EntityId(41L);

    // --- Stage 5: Access Control & Semantic Meta-Types (50..59) ---
    public static final EntityId META_PRINCIPAL_TYPE = new EntityId(50L);
    public static final EntityId META_ACL_ENTRY_TYPE = new EntityId(51L);
    public static final EntityId META_PROPAGATION_RULE_TYPE = new EntityId(52L);

    // --- Stage 5: Access Control & Semantic Meta-Predicates (60..79) ---
    public static final EntityId MEMBER_OF_GROUP = new EntityId(60L);
    public static final EntityId ACL_PRINCIPAL = new EntityId(61L);
    public static final EntityId ACL_TARGET = new EntityId(62L);
    public static final EntityId ACL_EFFECT = new EntityId(63L);
    public static final EntityId ACL_PERMISSION = new EntityId(64L);
    public static final EntityId PROPAGATION_PREDICATE = new EntityId(65L);
    public static final EntityId PROPAGATION_DIRECTION = new EntityId(66L);
    public static final EntityId PROPAGATION_PERMISSION = new EntityId(67L);
    public static final EntityId HAS_INVERSE_PREDICATE = new EntityId(68L);
    public static final EntityId HAS_SUPER_PREDICATE = new EntityId(69L);

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

    public static EntityId forValueMutability(ValueMutability mutability) {
        return switch (mutability) {
            case IMMUTABLE -> MUTABILITY_IMMUTABLE;
            case SHARED_MUTABLE -> MUTABILITY_SHARED_MUTABLE;
        };
    }

    public static Optional<ValueMutability> toValueMutability(EntityId markerId) {
        if (MUTABILITY_IMMUTABLE.equals(markerId)) {
            return Optional.of(ValueMutability.IMMUTABLE);
        }
        if (MUTABILITY_SHARED_MUTABLE.equals(markerId)) {
            return Optional.of(ValueMutability.SHARED_MUTABLE);
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
