package io.veg.engine.persistent;

/**
 * Deterministic crash-injection points used to test WAL and checkpoint recovery ({@code T17}).
 */
public enum CrashPoint {
    /** Normal execution without simulated crash. */
    NONE,
    /** Crashes mid-way through writing a WAL frame payload before the remainder and frame trailer are written. */
    MID_WAL_FRAME_WRITE,
    /** Writes a complete WAL tail frame with an intentionally invalid CRC32C checksum and crashes immediately. */
    CORRUPTED_WAL_TAIL_CRC,
    /** Writes and flushes a valid WAL commit frame, then crashes before updating {@code refs.meta}. */
    AFTER_WAL_BEFORE_REFS_UPDATE,
    /** Crashes mid-way through writing a temporary checkpoint file before the atomic rename completes. */
    MID_CHECKPOINT_WRITE
}
