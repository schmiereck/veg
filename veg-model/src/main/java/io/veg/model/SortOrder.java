package io.veg.model;

import java.util.Objects;

/**
 * Ordering criterion on a query result projection key (for example {@code "p.age"} or {@code "o.price"}).
 */
public record SortOrder(String key, SortDirection direction) {

    public SortOrder {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("Sort key must not be blank");
        }
    }
}
