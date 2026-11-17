package io.veg.engine;

/**
 * Thrown when attempting to mutate an attribute value entity marked as {@code IMMUTABLE} or system-owned.
 */
public class ImmutableValueModificationException extends SchemaViolationException {

    public ImmutableValueModificationException(String message) {
        super(message);
    }
}
