package io.veg.engine;

/**
 * Thrown when reverting a historical commit would violate referential integrity, schema constraints,
 * or overwrite subsequent conflicting modifications.
 */
public class RevertConflictException extends VegException {

    public RevertConflictException(String message) {
        super(message);
    }
}
