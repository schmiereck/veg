package io.veg.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single result row produced by {@code GraphQuery} or {@code GraphQueryDsl}.
 *
 * @param bindings        matched graph entity IDs keyed by query alias
 * @param projectedValues projected scalar attribute values keyed by {@code "alias.predicate"}
 */
public record QueryRow(
        Map<String, EntityId> bindings,
        Map<String, Value> projectedValues
) {
    public QueryRow {
        bindings = Map.copyOf(Objects.requireNonNull(bindings, "bindings"));
        projectedValues = Map.copyOf(Objects.requireNonNull(projectedValues, "projectedValues"));
    }

    public Optional<EntityId> entity(String alias) {
        return Optional.ofNullable(bindings.get(alias));
    }

    public Optional<Value> value(String key) {
        return Optional.ofNullable(projectedValues.get(key));
    }

    public Optional<String> stringValue(String key) {
        Value v = projectedValues.get(key);
        return v instanceof Value.StringValue(String s) ? Optional.of(s) : Optional.empty();
    }

    public Optional<Long> intValue(String key) {
        Value v = projectedValues.get(key);
        return v instanceof Value.IntValue(long n) ? Optional.of(n) : Optional.empty();
    }

    public Optional<BigDecimal> decimalValue(String key) {
        Value v = projectedValues.get(key);
        return v instanceof Value.DecimalValue(BigDecimal d) ? Optional.of(d) : Optional.empty();
    }

    public Optional<Boolean> booleanValue(String key) {
        Value v = projectedValues.get(key);
        return v instanceof Value.BooleanValue(boolean b) ? Optional.of(b) : Optional.empty();
    }
}
