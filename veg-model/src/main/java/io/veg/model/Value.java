package io.veg.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Strictly typed primitive value payload. No implicit cross-type coercion is permitted.
 */
public sealed interface Value permits
        Value.StringValue,
        Value.IntValue,
        Value.DecimalValue,
        Value.BooleanValue,
        Value.BinaryValue,
        Value.TimestampValue {

    ValueType valueType();

    static StringValue of(String value) {
        return new StringValue(value);
    }

    static IntValue of(long value) {
        return new IntValue(value);
    }

    static DecimalValue of(BigDecimal value) {
        return new DecimalValue(value);
    }

    static BooleanValue of(boolean value) {
        return new BooleanValue(value);
    }

    static BinaryValue of(byte[] value) {
        return new BinaryValue(value);
    }

    static TimestampValue of(Instant value) {
        return new TimestampValue(value);
    }

    record StringValue(String value) implements Value {
        public StringValue {
            Objects.requireNonNull(value, "StringValue payload must not be null");
        }

        @Override
        public ValueType valueType() {
            return ValueType.STRING;
        }
    }

    record IntValue(long value) implements Value {
        @Override
        public ValueType valueType() {
            return ValueType.INT;
        }
    }

    record DecimalValue(BigDecimal value) implements Value {
        public DecimalValue {
            Objects.requireNonNull(value, "DecimalValue payload must not be null");
        }

        @Override
        public ValueType valueType() {
            return ValueType.DECIMAL;
        }
    }

    record BooleanValue(boolean value) implements Value {
        @Override
        public ValueType valueType() {
            return ValueType.BOOLEAN;
        }
    }

    record BinaryValue(byte[] value) implements Value {
        public BinaryValue {
            Objects.requireNonNull(value, "BinaryValue payload must not be null");
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public ValueType valueType() {
            return ValueType.BINARY;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BinaryValue other && Arrays.equals(this.value, other.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.value);
        }

        @Override
        public String toString() {
            return "BinaryValue[length=" + value.length + "]";
        }
    }

    record TimestampValue(Instant value) implements Value {
        public TimestampValue {
            Objects.requireNonNull(value, "TimestampValue payload must not be null");
        }

        @Override
        public ValueType valueType() {
            return ValueType.TIMESTAMP;
        }
    }
}
