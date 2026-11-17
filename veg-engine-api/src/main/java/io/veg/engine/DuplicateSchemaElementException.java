package io.veg.engine;

/**
 * Thrown when attempting to register a type or predicate with a name that already exists in the schema.
 */
public class DuplicateSchemaElementException extends SchemaViolationException {

    public DuplicateSchemaElementException(String message) {
        super(message);
    }
}
