package io.veg.engine;

/**
 * Thrown when a schema definition or graph mutation violates strict typing, domain/range, or uniqueness rules.
 */
public class SchemaViolationException extends VegException {

    public SchemaViolationException(String message) {
        super(message);
    }
}
