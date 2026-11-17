package io.veg.engine;

/**
 * Thrown when connecting two entities violates the predicate's {@code HAS_DOMAIN} or {@code HAS_RANGE} constraint.
 */
public class DomainRangeViolationException extends SchemaViolationException {

    public DomainRangeViolationException(String message) {
        super(message);
    }
}
