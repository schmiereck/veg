package io.veg.engine;

/**
 * Base runtime exception for all logical errors raised by a VEG graph engine.
 */
public class VegException extends RuntimeException {

    public VegException(String message) {
        super(message);
    }

    public VegException(String message, Throwable cause) {
        super(message, cause);
    }
}
