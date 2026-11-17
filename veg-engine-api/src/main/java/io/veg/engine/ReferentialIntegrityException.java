package io.veg.engine;

/**
 * Thrown when removing an entity or edge would leave dangling references in active edges.
 */
public class ReferentialIntegrityException extends VegException {

    public ReferentialIntegrityException(String message) {
        super(message);
    }
}
