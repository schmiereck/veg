package io.veg.engine.persistent;

import io.veg.engine.VegException;

/**
 * Exception raised when a persisted storage artifact (interior WAL frame, checkpoint, cold archive,
 * or {@code refs.meta}) fails magic, format, or {@code CRC32C} checksum verification ({@code P-08}).
 */
public final class StorageCorruptionException extends VegException {

    public StorageCorruptionException(String message) {
        super(message);
    }

    public StorageCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
