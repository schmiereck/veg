package io.veg.engine;

/**
 * Thrown when a declarative {@link GraphQueryDsl} string contains invalid or unsupported syntax.
 */
public class QuerySyntaxException extends VegException {

    public QuerySyntaxException(String message) {
        super(message);
    }
}
