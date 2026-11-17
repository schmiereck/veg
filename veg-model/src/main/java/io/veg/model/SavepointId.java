package io.veg.model;

import java.util.Objects;

/**
 * Token identifying a savepoint inside an open transaction.
 */
public record SavepointId(String name, long sequence) {

    public SavepointId {
        Objects.requireNonNull(name, "savepoint name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("savepoint name must not be blank");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("savepoint sequence must be >= 0");
        }
    }
}
