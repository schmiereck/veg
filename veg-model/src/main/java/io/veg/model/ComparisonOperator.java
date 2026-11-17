package io.veg.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Typed comparison operators for filtering and deterministic sorting over {@link Value} payloads.
 */
public enum ComparisonOperator {
    EQ,
    NEQ,
    LT,
    LTE,
    GT,
    GTE,
    CONTAINS,
    STARTS_WITH;

    public boolean matches(Value actual, Value expected) {
        Objects.requireNonNull(actual, "actual must not be null");
        Objects.requireNonNull(expected, "expected must not be null");

        return switch (this) {
            case EQ -> valuesEqual(actual, expected);
            case NEQ -> !valuesEqual(actual, expected);
            case LT -> actual.valueType() == expected.valueType()
                    && actual.valueType() != ValueType.BINARY
                    && compareValues(actual, expected) < 0;
            case LTE -> actual.valueType() == expected.valueType()
                    && actual.valueType() != ValueType.BINARY
                    && compareValues(actual, expected) <= 0;
            case GT -> actual.valueType() == expected.valueType()
                    && actual.valueType() != ValueType.BINARY
                    && compareValues(actual, expected) > 0;
            case GTE -> actual.valueType() == expected.valueType()
                    && actual.valueType() != ValueType.BINARY
                    && compareValues(actual, expected) >= 0;
            case CONTAINS -> actual instanceof Value.StringValue(String a)
                    && expected instanceof Value.StringValue(String b)
                    && a.contains(b);
            case STARTS_WITH -> actual instanceof Value.StringValue(String a)
                    && expected instanceof Value.StringValue(String b)
                    && a.startsWith(b);
        };
    }

    private static boolean valuesEqual(Value a, Value b) {
        if (a instanceof Value.DecimalValue(var da) && b instanceof Value.DecimalValue(var db)) {
            return da.compareTo(db) == 0;
        }
        return a.equals(b);
    }

    public static int compareValues(Value a, Value b) {
        Objects.requireNonNull(a, "a must not be null");
        Objects.requireNonNull(b, "b must not be null");
        int typeCmp = Integer.compare(a.valueType().ordinal(), b.valueType().ordinal());
        if (typeCmp != 0) {
            return typeCmp;
        }
        return switch (a) {
            case Value.StringValue(String sa) -> sa.compareTo(((Value.StringValue) b).value());
            case Value.IntValue(long ia) -> Long.compare(ia, ((Value.IntValue) b).value());
            case Value.DecimalValue(var da) -> da.compareTo(((Value.DecimalValue) b).value());
            case Value.BooleanValue(boolean ba) -> Boolean.compare(ba, ((Value.BooleanValue) b).value());
            case Value.TimestampValue(var ta) -> ta.compareTo(((Value.TimestampValue) b).value());
            case Value.BinaryValue va -> Arrays.compareUnsigned(va.value(), ((Value.BinaryValue) b).value());
        };
    }
}
