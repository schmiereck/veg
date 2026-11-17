package io.veg.engine;

/**
 * Thrown when attempting to create a branch or tag with a name that already exists.
 */
public class DuplicateBranchOrTagException extends VegException {

    public DuplicateBranchOrTagException(String message) {
        super(message);
    }
}
