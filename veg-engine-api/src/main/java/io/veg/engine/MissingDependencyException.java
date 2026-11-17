package io.veg.engine;

/**
 * Thrown when committing selectively staged changes would leave unresolved dependencies
 * on unstaged schema types, endpoint entities, or dependent edges (V-06).
 */
public class MissingDependencyException extends SchemaViolationException {

    public MissingDependencyException(String message) {
        super(message);
    }
}
