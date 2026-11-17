package io.veg.engine;

/**
 * Thrown when a requested {@link io.veg.model.CommitId} does not exist in the versioned database.
 */
public class CommitNotFoundException extends VegException {

    public CommitNotFoundException(String message) {
        super(message);
    }
}
