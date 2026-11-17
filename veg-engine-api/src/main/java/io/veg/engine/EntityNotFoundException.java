package io.veg.engine;

/**
 * Thrown when an entity or edge referenced by an operation does not exist in the current graph view.
 */
public class EntityNotFoundException extends VegException {

    public EntityNotFoundException(String message) {
        super(message);
    }
}
