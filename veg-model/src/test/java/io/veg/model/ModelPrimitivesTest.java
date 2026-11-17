package io.veg.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelPrimitivesTest {

    @Test
    void entityIdRejectsNonPositiveValuesAndDistinguishesBootstrapRange() {
        assertThatThrownBy(() -> new EntityId(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityId(-5)).isInstanceOf(IllegalArgumentException.class);

        EntityId bootstrap = new EntityId(1);
        EntityId boundary = new EntityId(EntityId.BOOTSTRAP_MAX_ID);
        EntityId user = new EntityId(EntityId.USER_MIN_ID);

        assertThat(bootstrap.isBootstrap()).isTrue();
        assertThat(boundary.isBootstrap()).isTrue();
        assertThat(user.isBootstrap()).isFalse();
        assertThat(user.isUuid()).isFalse();
        assertThat(bootstrap.compareTo(user)).isLessThan(0);

        EntityId v7 = EntityId.newUuidV7();
        EntityId v4 = EntityId.randomUuid();
        assertThat(v7.isUuid()).isTrue();
        assertThat(v7.isBootstrap()).isFalse();
        assertThat(v4.isUuid()).isTrue();
        assertThat(EntityId.parse(v7.toString())).isEqualTo(v7);
        assertThat(EntityId.parse(v7.compactString())).isEqualTo(v7);
        assertThat(EntityId.parse("#1025")).isEqualTo(new EntityId(1025L));
        assertThat(user.compareTo(v7)).isLessThan(0);
        assertThat(IdGenerationStrategy.values()).containsExactly(
                IdGenerationStrategy.SEQUENTIAL,
                IdGenerationStrategy.UUID_V7,
                IdGenerationStrategy.UUID_V4
        );
    }

    @Test
    void valueBinaryMakesDefensiveCopiesAndUsesContentEquality() {
        byte[] source = "hello".getBytes(StandardCharsets.UTF_8);
        Value.BinaryValue v1 = Value.of(source);
        Value.BinaryValue v2 = Value.of("hello".getBytes(StandardCharsets.UTF_8));

        source[0] = 'X';
        assertThat(new String(v1.value(), StandardCharsets.UTF_8)).isEqualTo("hello");

        byte[] extracted = v1.value();
        extracted[0] = 'Y';
        assertThat(v1).isEqualTo(v2);
        assertThat(v1.hashCode()).isEqualTo(v2.hashCode());
        assertThat(v1.valueType()).isEqualTo(ValueType.BINARY);
    }

    @Test
    void valueVariantsExposeStrictValueType() {
        assertThat(Value.of("Thomas").valueType()).isEqualTo(ValueType.STRING);
        assertThat(Value.of(42L).valueType()).isEqualTo(ValueType.INT);
        assertThat(Value.of(new BigDecimal("0.95")).valueType()).isEqualTo(ValueType.DECIMAL);
        assertThat(Value.of(true).valueType()).isEqualTo(ValueType.BOOLEAN);
        assertThat(Value.of(Instant.EPOCH).valueType()).isEqualTo(ValueType.TIMESTAMP);
    }

    @Test
    void entityNodeEnforcesPayloadPresenceInvariant() {
        EntityNode validObject = new EntityNode(new EntityId(1000), new EntityId(1001), EntityKind.OBJECT, Optional.empty());
        assertThat(validObject.payload()).isEmpty();

        EntityNode validValue = new EntityNode(new EntityId(1002), new EntityId(1003), EntityKind.VALUE, Optional.of(Value.of("Thomas")));
        assertThat(validValue.payload()).contains(Value.of("Thomas"));

        assertThatThrownBy(() -> new EntityNode(new EntityId(1004), new EntityId(1003), EntityKind.VALUE, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityNode(new EntityId(1005), new EntityId(1001), EntityKind.OBJECT, Optional.of(Value.of(1L))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bootstrapScalarMappingsRoundTripAllValueTypes() {
        for (ValueType type : ValueType.values()) {
            EntityId scalarId = BootstrapIds.forValueType(type);
            assertThat(scalarId.isBootstrap()).isTrue();
            assertThat(BootstrapIds.toValueType(scalarId)).contains(type);
        }
        assertThat(BootstrapIds.toValueType(BootstrapIds.META_TYPE)).isEmpty();
    }

    @Test
    void bootstrapMutabilityMappingsAndPredicateConstraintsValidateBounds() {
        for (ValueMutability mutability : ValueMutability.values()) {
            EntityId markerId = BootstrapIds.forValueMutability(mutability);
            assertThat(markerId.isBootstrap()).isTrue();
            assertThat(BootstrapIds.toValueMutability(markerId)).contains(mutability);
        }
        assertThat(BootstrapIds.toValueMutability(BootstrapIds.META_TYPE)).isEmpty();

        assertThat(PredicateConstraints.unconstrained())
                .isEqualTo(new PredicateConstraints(OptionalInt.empty(), OptionalInt.empty(), false));
        assertThat(PredicateConstraints.oneToOne())
                .isEqualTo(new PredicateConstraints(OptionalInt.of(1), OptionalInt.of(1), true));
        assertThatThrownBy(() -> new PredicateConstraints(OptionalInt.of(0), OptionalInt.empty(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PredicateConstraints(OptionalInt.empty(), OptionalInt.of(-1), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
