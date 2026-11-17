package io.veg.engine;

/**
 * Thrown when a merge resolution or fast-forward operation encounters an unresolvable conflict.
 */
public class MergeConflictException extends VegException {

    public MergeConflictException(String message) {
        super(message);
    }
}
