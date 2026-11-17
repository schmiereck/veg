package io.veg.engine;

/**
 * Thrown when creating a duplicate unique value or duplicate unique {@code (source, predicate, target)} triple.
 */
public class UniquenessViolationException extends SchemaViolationException {

    public UniquenessViolationException(String message) {
        super(message);
    }
}
