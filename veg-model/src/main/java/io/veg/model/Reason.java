package io.veg.model;

import java.util.Objects;

/**
 * Business or technical justification recorded with each commit for auditability (V-09).
 */
public record Reason(String text) {

    public Reason {
        Objects.requireNonNull(text, "reason text must not be null");
        if (text.isBlank()) {
            throw new IllegalArgumentException("reason text must not be blank");
        }
    }

    public static Reason of(String text) {
        return new Reason(text);
    }
}
