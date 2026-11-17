package io.veg.engine;

/**
 * Thrown when adding an edge would exceed a predicate's {@code maxOutgoing} or {@code maxIncoming} cardinality.
 */
public class CardinalityViolationException extends SchemaViolationException {

    public CardinalityViolationException(String message) {
        super(message);
    }
}
