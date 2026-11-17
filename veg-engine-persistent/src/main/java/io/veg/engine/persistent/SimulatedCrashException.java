package io.veg.engine.persistent;

import io.veg.engine.VegException;

import java.util.Objects;

/**
 * Exception thrown when a deterministic {@link CrashPoint} triggers during crash-recovery testing ({@code T17}).
 */
public final class SimulatedCrashException extends VegException {

    private final CrashPoint crashPoint;

    public SimulatedCrashException(CrashPoint crashPoint, String message) {
        super(message);
        this.crashPoint = Objects.requireNonNull(crashPoint, "crashPoint must not be null");
    }

    public CrashPoint crashPoint() {
        return crashPoint;
    }
}
