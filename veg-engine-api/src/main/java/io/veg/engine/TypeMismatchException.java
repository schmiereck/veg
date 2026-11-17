package io.veg.engine;

/**
 * Thrown when a scalar payload or entity meta-type does not match the expected schema type.
 */
public class TypeMismatchException extends SchemaViolationException {

    public TypeMismatchException(String message) {
        super(message);
    }
}
