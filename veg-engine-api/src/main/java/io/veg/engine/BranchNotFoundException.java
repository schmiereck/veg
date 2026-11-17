package io.veg.engine;

/**
 * Thrown when referencing a branch name that does not exist in the database.
 */
public class BranchNotFoundException extends VegException {

    public BranchNotFoundException(String message) {
        super(message);
    }
}
